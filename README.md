# ylih — your life in headphones

[<img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png" alt="Get it on F-Droid" height="70">](https://f-droid.org/packages/it.eldavo.ylih/)
[<img src="docs/img/badge-obtainium.png" alt="Obtain it on Obtainium" height="70">](https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/ElDavoo/ylih)

<p>
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/01-devices.png" alt="List of headphones with lifetime hours" width="24%">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/02-stats.png" alt="Stats: bar chart of the last 30 days" width="24%">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/03-pair-detail.png" alt="One pair's detail: sessions and cost per hour" width="24%">
  <img src="fastlane/metadata/android/en-US/images/phoneScreenshots/05-devices-dark.png" alt="Same list, dark mode" width="24%">
</p>

Headphones die. Before they do, ylih answers the one question that matters:
**how many hours did you get out of them?**

Every connect and disconnect gets a timestamp, automatically, forever, on your phone only.

## What you get

- **Lifetime hours for each pair.** Retire a dead pair and its total freezes; the next pair of the
  same model starts at zero.
- **Every session, kept for good** — start, end, duration.
- **Listening time, not just connected time** (optional), so headphones worn around your neck
  don't count as listening.
- **Cost per hour**, if you tell it what you paid. It goes down every time you wear them.
- **Hours per charge**, if your headphones report battery — and how that's changed since new. A
  charge cycle is 100% of battery used, however many part-charges it took. Pairs that don't
  report battery to Android have no such section.
- **The last thirty days** as a chart, with every day listed underneath.
- **Home-screen widgets** — lifetime hours, today's total, or the thirty-day chart.

## Two ways to track

Most people never need to change this.

|  | Bluetooth only (default) | Detailed tracking |
|---|---|---|
| Bluetooth headphones | tracked | tracked |
| Wired / USB headphones | not tracked | tracked |
| Listening vs connected time | not measured | measured |
| Notification | **none** | one, silent and hidden away |
| Battery cost | none — nothing runs | small, but not nothing |

By default nothing of the app runs — a connection is two timestamps Android reports on its own.
Wired headphones are the exception: Android only reports those to an app already awake, so
detailed tracking keeps something running, and stays off unless you enable it in Settings.

## Your data stays yours

The app has **no internet permission** — nothing uploaded, no accounts, no analytics, no ads.

Your history lives in a database on the phone. Settings → Export writes it out as a readable file;
Import puts it back. Android's own backup carries years of history to a new phone — the system
copies the file, not the app sending it. Uninstalling offers to keep the data.

**Assistants, only if you ask.** Android 17 lets an app offer read-only questions to an on-device
assistant. ylih offers two — lifetime hours per pair, and today / 7-day / 30-day totals — shipped
switched off: until you enable Settings → assistant access, the system doesn't list them. Neither
can change or delete anything, and disabling the switch removes them from the system too, not just
the app.

The full policy is in [PRIVACY.md](PRIVACY.md).

## Which download?

Both are the same app, built from this repository:

- **[F-Droid](https://f-droid.org/packages/it.eldavo.ylih/)** — built and verified by F-Droid.
- **[Obtainium](https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/ElDavoo/ylih)** or the
  [Releases page](https://github.com/ElDavoo/ylih/releases) — updates straight from here.
- **Google Play** — one difference: tracking *wired* headphones there also requires Bluetooth
  access, since Play's rules make it unavoidable. Everything else is identical.

The app speaks **77 languages** and follows your system language.

## Why you can trust the numbers

Long-term totals are worthless if a crash or reboot adds twelve hours to them:

- Time your phone spent switched off is never counted.
- A session interrupted by a reboot or a crash closes at the last moment it was known connected,
  never guessed forward.
- The same connection is never counted twice.

Tests pin these rules and fail the build if any stop holding.

## For developers

Kotlin, Jetpack Compose, Room and Glance widgets, built with Gradle. A nix dev shell pins the
exact JDK, Gradle and Android SDK:

```sh
nix develop                        # or: direnv allow
./gradlew assembleClassicDebug     # sideload build
./gradlew installClassicDebug
```

Without nix: JDK 21, Android SDK platform `android-37.0` and build-tools `37.0.0` — the Gradle
wrapper does the rest. Two flavors exist, `classic` (F-Droid and GitHub) and `play`, so every task
is flavor-qualified; there is no plain `assembleDebug`.

```sh
# what CI runs, per flavor
./gradlew lintClassicReleaseTest testClassicReleaseTestUnitTest \
          assembleClassicDebug assembleClassicRelease
```

Lint and the Kotlin compiler run with warnings as errors and no baseline, so a warning fails the
build. The test suite is mostly Robolectric and gated on coverage in CI.

- [CLAUDE.md](CLAUDE.md) — the architecture in depth: the write funnel and its invariants, device
  identity, the widgets, and the build-system constraints.
- [docs/play-store.md](docs/play-store.md) — the Play release runbook.
- [docs/fdroid.md](docs/fdroid.md) — the F-Droid runbook; the build recipe is at
  [metadata/it.eldavo.ylih.yml](metadata/it.eldavo.ylih.yml).

Store screenshots, the icon and the feature graphic are generated, not drawn — they render the
real UI on the JVM, so they cannot drift from the shipped app:

```sh
./gradlew recordRoborazziClassicReleaseTest
```

## License

MIT — see [LICENSE](LICENSE).
