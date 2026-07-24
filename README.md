# ylih

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
  "worn around my neck" doesn't count as listening.
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

## Permissions

| Permission | Why |
|---|---|
| `BLUETOOTH_CONNECT` | Required to receive the ACL broadcasts and read a headset's name and class. Without it, Bluetooth tracking cannot work. |
| `POST_NOTIFICATIONS` | Only for the detailed-tracking notification. |
| `RECEIVE_BOOT_COMPLETED` | Close sessions the shutdown never reported, and restart the service. |
| `FOREGROUND_SERVICE_CONNECTED_DEVICE` / `_SPECIAL_USE` | Detailed tracking. `specialUse` is the fallback for a wired-only user who denied Bluetooth, since `connectedDevice` requires a Bluetooth permission on Android 14+. |

There is no internet permission.

## Building

The repo ships a nix dev shell with the exact JDK, Gradle and Android SDK:

```sh
nix develop            # or: direnv allow
./gradlew assembleDebug
./gradlew installDebug
```

Without nix, you need JDK 21, Android SDK platform `android-37.0` and build-tools `37.0.0`; the
Gradle wrapper handles the rest.

```sh
./gradlew lint testDebugUnitTest assembleDebug assembleRelease
```

Note that AGP 9 compiles Kotlin itself — the standalone `kotlin-android` plugin is deliberately
absent, and the Kotlin, Compose-compiler and KSP versions in `gradle/libs.versions.toml` must
stay aligned with the Kotlin Gradle plugin that AGP bundles.

Release builds are signed with the debug key unless `ANDROID_SIGNING_KEYSTORE_PATH`,
`ANDROID_SIGNING_STORE_PASSWORD`, `ANDROID_SIGNING_KEY_ALIAS` and `ANDROID_SIGNING_KEY_PASSWORD`
are set, which is what the release workflow does from repository secrets.

## Data

Room database, three tables: `devices` (an identity as Android reports it), `pairs` (a physical
pair — the lifetime unit, with generations) and `sessions`. Settings → Export writes the whole
thing as readable JSON; Import replaces it. Android backup is enabled too, so the history
survives a new phone.

## License

MIT — see [LICENSE](LICENSE).
