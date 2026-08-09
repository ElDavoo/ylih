# ylih - your life in headphones

An Android app that records how long your headphones stay connected, so that months later you
can answer the only question that matters about a pair of headphones: **how many hours did they
last before they died?**

Every connection is stored forever, locally. Nothing leaves the phone.

## What it tracks

- **Lifetime hours per physical pair.** When a pair dies, retire it — its total freezes and the
  next pair of the same headphones starts at zero, as generation 2. This also handles wired
  headphones, which Android cannot tell apart from each other at all.
- **Every session**, kept permanently: connected at, disconnected at, and why it ended.
- **Playback time** (optional): how much of the connected time was actually playing audio, so
  "worn around my neck" doesn't count as listening. A switch in Settings makes every figure in the
  app count that instead of connected time; sessions recorded before detailed tracking was on never
  measured playback, so they are left out rather than counted as zero.
- **Cost per hour**, if you enter what you paid.

## The two tracking modes

A connection is just two timestamps, so the default mode needs nothing running at all.

| | Bluetooth only (default) | Detailed tracking |
|---|---|---|
| Wired / USB headphones | not tracked | tracked |
| Playback vs connected time | not measured | measured |
| Persistent notification | **none** | one, silent and minimum priority |
| Battery cost | none — nothing runs | a foreground service |

Bluetooth works with a manifest `BroadcastReceiver` on `ACL_CONNECTED`/`ACL_DISCONNECTED`, which
are on Android's implicit-broadcast exception list and so still reach a manifest receiver on
modern releases. Wired plug events (`ACTION_HEADSET_PLUG`) can only be registered at runtime, so
observing them at all requires a live process — that, and nothing else, is why the notification
in detailed mode exists.

Turn detailed tracking on in Settings. Bluetooth is tracked either way.

## Keeping the numbers honest

Long-term totals are worthless if a crash or a reboot quietly adds twelve hours. The rules:

- Every write goes through one funnel (`SessionRepository`) that is idempotent: a pair can never
  hold two open sessions, and closing a closed session does nothing.
- While a session is open it is heartbeated — every minute by the service, every 15 minutes by a
  WorkManager job in Bluetooth-only mode (scheduled only while something is connected).
- A session left open by process death or a reboot is closed at its **last heartbeat**, and never
  later than the moment the phone booted. Time the phone spent switched off is never counted. If
  the headphones are still connected after the reboot, a fresh session starts instead of the old
  one being stretched across the gap.
- A reconcile that races a just-received disconnect will not resurrect the session it closed.

These rules are what `app/src/test/java/it/eldavo/ylih/data/SessionRepositoryTest.kt` tests.

## Two build flavors

One codebase, two distributions with the same `applicationId`:

| | `classic` | `play` |
|---|---|---|
| Distribution | APK from GitHub Releases | Google Play (AAB) |
| `specialUse` foreground-service type | declared | **not declared** |
| Detailed tracking without Bluetooth permission | works | unavailable — the toggle explains why |
| Battery-optimisation shortcut | button into system settings | text instructions only |

The one that actually costs functionality is `specialUse`. Play reviews that service type case by
case and expects a justification for why no other type fits, so the store build declares only
`connectedDevice` — and Android 14+ ties `connectedDevice` to holding a Bluetooth permission.
Someone on the Play build who wants *only* wired headphones tracked must therefore still grant
Bluetooth access. The classic build keeps `specialUse` and has no such condition.

Dropping the battery shortcut is conservative rather than required: the app only ever opens the
system settings screen and never requests `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`.

The differences live in exactly three places — `app/src/{classic,play}/java/it/eldavo/ylih/Distribution.kt`,
`app/src/classic/AndroidManifest.xml`, and the `productFlavors` block — so nothing else in the
codebase has to know which build it is.

```sh
./gradlew assembleClassicDebug        # or assemblePlayDebug
./gradlew assembleClassicRelease      # sideload APK
./gradlew bundlePlayRelease           # Play Console AAB
```

## Permissions

| Permission | Why |
|---|---|
| `BLUETOOTH_CONNECT` | Required to receive the ACL broadcasts and read a headset's name and class. Without it, Bluetooth tracking cannot work. |
| `POST_NOTIFICATIONS` | Only for the detailed-tracking notification. |
| `RECEIVE_BOOT_COMPLETED` | Close sessions the shutdown never reported, and restart the service. |
| `FOREGROUND_SERVICE_CONNECTED_DEVICE` / `_SPECIAL_USE` | Detailed tracking. `specialUse` is the fallback for a wired-only user who denied Bluetooth, since `connectedDevice` requires a Bluetooth permission on Android 14+. |

There is no internet permission. That is not the same as the data being unable to leave the
phone: `allowBackup` is on and the backup rules include the database, so Android's own backup
will copy it to the user's cloud backup where that is enabled — the system holds the network
access, not the app. It is deliberate, because years of history are worth keeping across a new
phone, and the store listing says so rather than claiming the app is incapable of it.

## Building

The repo ships a nix dev shell with the exact JDK, Gradle and Android SDK:

```sh
nix develop                       # or: direnv allow
./gradlew assembleClassicDebug
./gradlew installClassicDebug
```

Without nix, you need JDK 21, Android SDK platform `android-37.0` and build-tools `37.0.0`; the
Gradle wrapper handles the rest.

```sh
# what CI runs, per flavor
./gradlew lintClassicReleaseTest testClassicReleaseTestUnitTest assembleClassicDebug assembleClassicRelease
```

Note that AGP 9 compiles Kotlin itself — the standalone `kotlin-android` plugin is deliberately
absent, and the Kotlin, Compose-compiler and KSP versions in `gradle/libs.versions.toml` must
stay aligned with the Kotlin Gradle plugin that AGP bundles.

### Release signing

Release builds are left **unsigned** unless `ANDROID_SIGNING_KEYSTORE_PATH`,
`ANDROID_SIGNING_STORE_PASSWORD`, `ANDROID_SIGNING_KEY_ALIAS` and `ANDROID_SIGNING_KEY_PASSWORD`
are set, which is what the release workflow does from repository secrets. Unsigned rather than
debug-signed because F-Droid builds this from source on a machine that has no key of ours and
signs the result itself, and a per-machine debug key would make that build unreproducible.

The key itself, made once and kept off this machine's repository directory:

```sh
keytool -genkeypair -keystore ylih-release.keystore -storetype pkcs12 \
        -alias ylih -keyalg RSA -keysize 4096 -validity 10000 \
        -dname "CN=Davide Palma, O=ylih, C=IT"
```

PKCS12 holds one password rather than two, so the store and key passwords are the same string —
both env vars still have to be set, because the build treats a blank one as "no signing config".
10000 days is to 2053; Play rejects a key expiring before October 2033. Then, once:

```sh
gh secret set ANDROID_SIGNING_KEYSTORE_BASE64 < <(base64 -w0 ylih-release.keystore)
gh secret set ANDROID_SIGNING_KEY_ALIAS --body ylih
gh secret set ANDROID_SIGNING_STORE_PASSWORD
gh secret set ANDROID_SIGNING_KEY_PASSWORD
```

Back the keystore up somewhere that survives this disk. With reproducible builds enabled F-Droid
publishes our signed APK rather than one of its own, so losing the key means every user has to
uninstall before they can update again.

## Design and languages

The UI is Material 3 Expressive — `MaterialExpressiveTheme` with the expressive motion scheme, the
emphasized type scale on the figures that matter, and Expressive's own components
(`ShortNavigationBar`, `ButtonGroup`, a flexible top app bar that collapses as you scroll). That
needs `material3` 1.5.0-alpha, pinned ahead of the Compose BOM, which puts Compose itself on
`1.12.0-beta01`. Dynamic colour is used from Android 12; below it the app falls back to its own
tonal palette, seeded from the blue the launcher icon sits on.

All copy is lowercase, on purpose.

Every user-visible string is a resource, and the app ships **77 languages** — one
`res/values-<lang>/strings.xml` each, with `res/xml/locales_config` generated from those folders
so the app appears under *Settings → System → Languages → App languages*. Lint treats a missing
translation as an error, so an untranslated string fails the build rather than shipping. Adding a
language means one `res/values-<lang>/strings.xml`, and — if it should also get a Play listing —
one `StoreScreenshots` subclass and one `fastlane/metadata/android/<locale>/` directory.

## Store listings

The store assets are generated, not drawn by hand:

```sh
./gradlew recordRoborazziPlayReleaseTest       # app/build/outputs/play-listing/playReleaseTest/
./gradlew recordRoborazziClassicReleaseTest    # the same, for the flavor F-Droid ships
```

That renders the five phone screenshots, the 512×512 icon and the 1024×500 feature graphic on the
JVM through Roborazzi and Robolectric's native graphics — there is no emulator in this project's
toolchain and a screenshot is not a good reason to add one. The screenshots run the real Compose
UI against a seeded database, and the icon is rendered from `@mipmap/ic_launcher` itself, so
neither can drift away from the shipped app. Tagging a release produces the same images in CI as
the `play-listing-assets` artifact.

Screenshots are captured once per Play listing language (29 of them, one directory each), so every
listing translation gets images in its own language.

Listing text lives in `fastlane/metadata/android/<locale>/`, shared by both stores, and
`.github/scripts/listing-metadata-check.py` holds it to the character limits in CI — both stores
truncate an over-long summary silently rather than rejecting it.

- [`docs/play-store.md`](docs/play-store.md) — the Play runbook: prepared answers for the Data
  safety form, the content rating questionnaire and the `connectedDevice` foreground-service
  declaration.
- [`docs/fdroid.md`](docs/fdroid.md) — the F-Droid runbook. F-Droid builds from a tag on this
  repository rather than accepting an upload, so the listing images for `en-US` are committed
  under `fastlane/metadata/android/en-US/images/` and the build recipe lives at
  [`metadata/it.eldavo.ylih.yml`](metadata/it.eldavo.ylih.yml), ready to copy into an fdroiddata
  merge request.

The privacy policy is [`PRIVACY.md`](PRIVACY.md).

## Data

Room database, three tables: `devices` (an identity as Android reports it), `pairs` (a physical
pair — the lifetime unit, with generations) and `sessions`. Settings → Export writes the whole
thing as readable JSON; Import replaces it. Android backup is enabled too, so the history
survives a new phone, and `hasFragileUserData` offers "keep app data" in the uninstall dialog so
it survives a reinstall on the same one.

## License

MIT — see [LICENSE](LICENSE).
