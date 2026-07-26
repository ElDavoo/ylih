# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

An Android app (Kotlin, Compose, Room) that records how long each pair of headphones stays
connected, forever and locally, so lifetime hours per *physical pair* can be trusted years later.
`README.md` is the product-level description and is accurate — read it for the two tracking modes,
the accuracy rules and the flavor split before changing anything in those areas.

## Commands

The nix dev shell pins the exact JDK 21, Gradle 9 and Android SDK (platform `android-37.0`,
build-tools `37.0.0`):

```sh
nix develop            # or: direnv allow
```

Every Gradle task is flavor-qualified — there is no plain `assembleDebug`:

```sh
./gradlew assembleClassicDebug          # sideload build
./gradlew assemblePlayDebug             # store build
./gradlew installClassicDebug
./gradlew bundlePlayRelease             # Play Console AAB

# what CI runs, per flavor (matrix over classic/play)
./gradlew lintClassicDebug createClassicDebugUnitTestCoverageReport \
          assembleClassicDebug assembleClassicRelease

# a single test class or method
./gradlew testClassicDebugUnitTest --tests '*SessionRepositoryTest'
./gradlew testClassicDebugUnitTest --tests '*SessionRepositoryTest.a reconcile racing*'

# tests + JaCoCo report, then the same summary CI prints
./gradlew createClassicDebugUnitTestCoverageReport
python3 .github/scripts/coverage-summary.py \
    app/build/reports/coverage/test/classic/debug/report.xml classic
```

Debug builds carry `applicationIdSuffix = ".debug"`, so a debug and a release install coexist.

### Lint is a hard gate

`checkAllWarnings`, `warningsAsErrors` and `abortOnError` are all on, and `checkTestSources`
extends that to `src/test`. There is no baseline file and there should not be one: a baseline
hides a finding instead of deciding about it. So a warning — including the checks lint ships
disabled — fails the build exactly like an error.

That means the only two ways to land a finding are to fix it or to turn the check off in
`app/lint.xml` **with a reason**. The checks currently off are the ones that cannot hold here:
`NewerVersionAvailable`/`GradleDependency` (they hit the network, and the versions are pinned on
purpose), `DuplicateStrings` (77 translations of ~150 strings collide legitimately),
`TypographyQuotes` (it would rewrite translator-supplied text), and `NewApi`/`InlinedApi`
*scoped to `src/test`* only, because Robolectric runs against the compile SDK rather than the
minSdk 23 floor those checks enforce.

One consequence in the app code: a few members are `internal` rather than `private` purely so a
nested class can reach them without a synthetic accessor. They carry a comment saying so.

The Kotlin compiler is held to the same standard — `allWarningsAsErrors` is set, so a deprecation
fails the build rather than scrolling past. The usual way one arrives is a Dependabot bump, and
failing that PR is the intended behaviour. The tests use
`androidx.compose.ui.test.junit4.v2.createComposeRule` for exactly this reason; the v2 rule
dispatches with `StandardTestDispatcher` rather than `UnconfinedTestDispatcher`, which the
Roborazzi listing captures were re-recorded and eyeballed against before the switch landed.

### Coverage

`enableUnitTestCoverage = true` on the debug build type gives AGP a
`create<Variant>DebugUnitTestCoverageReport` task; the report lands in
`app/build/reports/coverage/test/<flavor>/debug/` and CI uploads it with the other reports and
prints the summary table into the job summary.

The one non-obvious bit is in `testOptions.unitTests.all`: Robolectric loads the classes under
test through its own sandbox classloader, so they reach the JaCoCo agent with no code-source
location and are dropped unless `isIncludeNoLocationClasses` is set. Without it the report reads
~2% — only `stats/Stats.kt`, the one package with plain JVM tests — and every Robolectric-covered
class silently reports zero. If coverage ever collapses to a couple of percent again, that
setting is the first thing to check.

## Architecture

### Everything writes through one funnel

`data/SessionRepository.kt` is the only place that mutates session state. Four independent
sources race into it — the manifest Bluetooth receiver, the foreground service, the heartbeat
worker and the UI — so every method takes a `Mutex` and runs inside a Room transaction, and the
operations are idempotent by construction:

- a pair can never hold two open sessions (`openSession` heartbeats an existing one instead);
- closing an already-closed session is a no-op (the SQL carries `AND disconnectedAt IS NULL`);
- an open session older than `STALE_SESSION_MS` is split, not stretched, on the next connect;
- `reconcile` never counts across a boot, and `RECONNECT_GRACE_MS` stops it resurrecting a
  session a just-received disconnect closed.

These invariants are the app's whole reason to exist. `app/src/test/java/it/eldavo/ylih/data/SessionRepositoryTest.kt`
pins them; if you touch this file, that test is the contract.

### Who calls what

`tracking/TrackingController.kt` is the policy layer: it decides whether the foreground service
runs, whether the WorkManager heartbeat is scheduled, and repairs the database against reality.
`syncWithSystem()` is the single "make everything consistent again" entry point, and it is safe to
call as often as you like. It is invoked from `BootReceiver`, `MainActivity.onStart`,
`HeartbeatWorker`, `TrackingService.onCreate` and after a permission result.

- **Bluetooth-only (default)** — `BtConnectionReceiver`, a manifest receiver on ACL connect/
  disconnect. Nothing of the app is resident. A 15-minute `HeartbeatWorker` is scheduled *only*
  while a session is open, to bound the damage of a missed disconnect.
- **Detailed tracking (opt-in)** — `TrackingService`, a foreground service. It exists because
  wired plug events are only delivered to a live process; while up it also runs `PlaybackWatcher`
  and heartbeats every minute.

`data/AppContainer.kt` is a hand-rolled container reached via `(context.applicationContext as
YlihApp).container`. `YlihApp.onCreate` deliberately does no work — it runs on every broadcast-
woken process start. `Clock` is a `fun interface` injected through the container so time-sensitive
logic is testable.

### Data model

`devices` (an identity as Android reports it) → `pairs` (one physical pair, the lifetime unit,
with `generation`) → `sessions`. Retiring a pair freezes its totals; the next connection of the
same device opens generation + 1. This is also how wired headphones work at all, since Android
cannot tell two wired pairs apart.

Two gotchas in `data/Daos.kt`: `observeSummaries()` and `observeSummary(pairId)` are the same
large aggregate query duplicated with a `WHERE` clause — change both. And `PairSummary.closedMs`
counts finished sessions only; live totals are `closedMs + (now - openSince)`.

**Device identity** (`tracking/AudioDevices.kt`) is the other invariant worth care: the two
platform views of one headset report different addresses — `AudioDeviceInfo.getAddress()` redacts
the leading octets while the ACL broadcast gives the full MAC — so keys use the last two octets,
which is all both APIs disclose. Getting this wrong splits one pair's hours across two rows;
`AudioDevicesTest` pins the observed addresses.

### Stats and UI

`stats/Stats.kt` is pure functions over `Span` (start, optional end, optional playing ms),
deliberately decoupled from Room so the maths runs as a plain JVM test. Day bucketing uses
`ZoneId` arithmetic, not 24 h blocks, so DST days bucket correctly.

The UI is Compose with a single `YlihViewModel` (`ui/YlihViewModel.kt`) exposing `StateFlow`s and
a `Channel` of snackbar messages, and a three-tab `YlihNavHost`.

**Every user-visible string is a resource.** `res/values/strings.xml` is the whole vocabulary and
there are 77 `res/values-<lang>/strings.xml` translations beside it; lint runs with
`abortOnError = true` and `MissingTranslation` is an error, so adding an English string without
translating it into all 77 breaks the build. `res/resources.properties` names the unqualified
folder's locale, which `generateLocaleConfig` needs to emit `res/xml/locales_config`. Deliberate exceptions carry `translatable="false"` (`app_name`, `value_none`). House style
is **lowercase throughout** — `action_cancel` carries `tools:ignore="ButtonCase"` and the
Norwegian `welcome_privacy` carries `tools:ignore="Typos"`, because lint would otherwise insist on
"Cancel" and "Internett".

Since lint is a hard gate, `UnusedResources` is one too: a string added before the UI that reads
it fails the build until something uses it.

Two consequences worth knowing: `DeviceKind.displayName()` is `@Composable` because it resolves a
resource, and `ButtonGroupScope` is *not* a composable scope, so labels for it must be hoisted out
with `stringResource` before the `ButtonGroup` call.

### Material 3 Expressive

`ui/theme/Theme.kt` uses `MaterialExpressiveTheme` with `MotionScheme.expressive()`. The app is on
`material3 = "1.5.0-alpha24"`, pinned *ahead of the Compose BOM* in `libs.versions.toml`, because
the Expressive components (`ShortNavigationBar`, `ButtonGroup`, `MediumFlexibleTopAppBar`,
`MaterialShapes`) only exist there; the BOM's 1.4.0 stable has the theme but not the components.
That pin drags compose runtime/foundation/ui to `1.12.0-beta01` for the whole app — the one
pre-release dependency in the build, accepted knowingly.

The colour scheme is a full tonal palette seeded from the launcher blue. It previously set only
`primary`/`secondary`/`tertiary`, which left every container role at Material's default purple;
roles come in sets, so override a set or none of it.

## Build flavors

One `applicationId`, two distributions: `classic` (sideloaded APK) and `play` (Play Console AAB).
Everything variant-specific lives in exactly three places, and nothing else in the codebase knows
which build it is:

- `app/src/{classic,play}/java/it/eldavo/ylih/Distribution.kt` — the capability constants
- `app/src/classic/AndroidManifest.xml` — the manifest overlay adding `specialUse`
- the `productFlavors` block in `app/build.gradle.kts`

`DistributionTest` runs under both flavors and asserts the constants and the *merged manifest*
agree, so they cannot drift. Keep new variant differences inside `Distribution` rather than
adding `BuildConfig.FLAVOR` checks around the codebase.

## Store listing assets

`app/src/test/java/it/eldavo/ylih/listing/` generates the Play Console images with Roborazzi over
Robolectric native graphics — `./gradlew recordRoborazziPlayDebug`, output in
`app/build/outputs/play-listing/`. Roborazzi captures are inert outside a record task, so these
classes cost one composition in the ordinary unit-test run and write nothing.

These are *listing assets*, not golden-image tests: nothing is committed and nothing is compared.
`DemoData.kt` writes through the DAOs rather than `SessionRepository`, deliberately — the
repository's whole job is to refuse backdated history. Sizes come from Robolectric qualifiers
(mdpi means 1dp = 1px).

`StoreScreenshots` is abstract, with one concrete subclass per Play listing language; each sets
its own resource qualifier *and* `Locale.setDefault`, because `ui/Format.kt` runs every date and
duration through `Locale.getDefault()`, which no resource qualifier reaches. Labels are looked up
as resources, never typed in, so a new language costs one subclass.
`docs/play-store.md` explains the rest, including why the screenshots keep dynamic colours on.

## Build-system constraints

These are easy to break and the failures are confusing:

- **AGP 9 compiles Kotlin itself.** The standalone `kotlin-android` plugin is absent on purpose
  and AGP rejects it. The `kotlin` and `ksp` versions in `gradle/libs.versions.toml` must stay
  equal to each other and aligned with the Kotlin Gradle plugin AGP bundles; KSP's release
  cadence is what pins Kotlin below the newest release.
- **`android.disallowKotlinSourceSets=false`** in `gradle.properties` is required because KSP
  still registers Room's generated sources through `kotlin.sourceSets`.
- **The `lint`→`ksp` dependency wiring** at the bottom of `app/build.gradle.kts` is a workaround
  for AGP 9 lint reading generated dirs without declaring a dependency; removing it makes lint
  race KSP and fail on a missing `*_Impl.kt`.
- **Version pins are duplicated in three files** — `gradle/libs.versions.toml`, `flake.nix` and
  `.github/workflows/*.yml` all name build-tools `37.0.0` / platform `37.0`. Change together.

## Room migrations

`exportSchema = true` with schemas committed under `app/schemas/`, and the database is built with
no fallback. Any entity change needs a `version` bump plus a real `Migration`, or existing installs
crash on open — and this app's entire premise is that history is never lost.

## Testing

Unit tests only, run under Robolectric (`@Config(sdk = [...])`); there is no `androidTest` source
set despite the runner being configured. `MainActivityTest` is a startup smoke test that boots the
Application, opens the real Room database and drives `MainActivity` to RESUMED. Repository tests
use an in-memory database with an injected clock (`SessionRepository(db) { clockNow }`), so time
is moved by assignment rather than by sleeping.

## Style

Comments in this codebase explain *why* — a platform quirk, a Play-review constraint, a race that
was actually observed — not what the code does. Match that: if a line needs a comment, the reason
is usually external to the code. Commit messages follow the same habit: a plain-language subject
line and a body explaining the reasoning and the evidence behind it.
