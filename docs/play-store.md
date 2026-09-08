# Submitting ylih to Google Play

Everything Play Console asks for, prepared in advance: listing text lives under
`fastlane/metadata/android/en-US/`, images generated from the app.

## 1. Build the artifacts

```sh
./gradlew bundlePlayRelease            # app/build/outputs/bundle/playRelease/*.aab
./gradlew recordRoborazziPlayReleaseTest     # app/build/outputs/play-listing/playReleaseTest/*.png
```

A tag runs both in CI (`.github/workflows/android-release.yml`) and attaches the images as
`play-listing-assets` for that version. The AAB must be **play** — the classic APK declares
`specialUse`, dropped by the store build.

Release signing reads `ANDROID_SIGNING_KEYSTORE_PATH`, `ANDROID_SIGNING_STORE_PASSWORD`,
`ANDROID_SIGNING_KEY_ALIAS` and `ANDROID_SIGNING_KEY_PASSWORD`. Without them the build is
**unsigned**, rejected like debug-signed — check the signer before uploading. (It used to fall
back to the debug key; F-Droid builds with no keystore, so unsigned keeps it signable.)

## 2. The generated images

| File | Size | Play field |
|---|---|---|
| `en-US/01-devices.png` … `en-US/05-devices-dark.png` | 1080×1920 | Phone screenshots, English listing (2–8 required) |
| `it-IT/01-devices.png` … `it-IT/05-devices-dark.png` | 1080×1920 | Phone screenshots, Italian listing |
| `icon-512.png` | 512×512 | App icon (all languages) |
| `feature-graphic-1024x500.png` | 1024×500 | Feature graphic (all languages) |
| `social-preview-1280x640.png` | 1280×640 | Not a Play field — GitHub's social preview, see below |

Produced on the JVM by `app/src/test/java/it/eldavo/ylih/listing/`, via Roborazzi over
Robolectric's native graphics — no emulator needed.

- `DemoData.kt` seeds a plausible year of listening anchored to the recording moment, avoiding
  stale-looking dates. It writes through the DAOs, not `SessionRepository`, which refuses
  backdated history.
- `StoreGraphics.kt` also writes `social-preview-1280x640.png` — not a Play field, but the card
  GitHub unfurls into Google, Slack and Mastodon. GitHub has no API for it, only *Settings →
  General → Social preview*, so `docs/img/social-preview.jpg` must be uploaded there by hand after
  re-recording. Committed as JPEG, not the record task's PNG, because GitHub's uploader showed the
  PNG blank despite it being valid and opaque — convert rather than redraw:

  ```sh
  magick app/build/outputs/play-listing/classicReleaseTest/social-preview-1280x640.png \
      -alpha remove -alpha off -quality 92 -sampling-factor 4:4:4 docs/img/social-preview.jpg
  ```
- `StoreScreenshots.kt` captures the four screens plus a dark-mode shot at 1080×1920, Play's 9:16
  phone ratio. A Pixel-shaped 1080×2400 is taller, so the qualifier sets the size by hand. It's
  abstract with one subclass per listing language, each setting a resource qualifier (for strings)
  *and* `Locale.setDefault` (for what `ui/Format.kt` formats via `Locale.getDefault()`). A new
  language needs one subclass plus a `fastlane/metadata/android/<locale>/` directory.
- `StoreGraphics.kt` renders `@mipmap/ic_launcher`'s own layers, not the artwork, so
  `ic_launcher_foreground.xml`'s geometry can't drift from the listing:
  `AdaptiveIconDrawable.draw()` applies the platform's circular mask to the drawable, while Play
  wants a full square it rounds off itself.

Screenshots use the app's ordinary theme, dynamic colours included; on Robolectric these resolve
to the AOSP default palette — closer to a real phone on stock wallpaper than pinning
`LightColors`/`DarkColors` from `ui/theme/Theme.kt` would be.

Nothing generated is committed for Play — it takes images by upload, so there's no reason to
carry binaries a Gradle task reproduces. The exception is
`fastlane/metadata/android/en-US/images/`, checked in for F-Droid: no upload step there, so it
shows nothing without a repository image. Recorded from the **classic** flavor, which F-Droid
ships and whose settings screen differs from this one — see [`fdroid.md`](fdroid.md).

## 3. Store listing

Listing text lives in `fastlane/metadata/android/<locale>/`, 26 locales; screenshots render for
29 (`cs-CZ`, `el-GR`, `iw-IL` have images but no listing copy — write it, or drop those three
`StoreScreenshots` subclasses before submitting). Add each non-English listing in Play Console
under *Store presence → Main store listing → Manage translations*.

| Field | Value |
|---|---|
| App name | `<locale>/title.txt` (30 / 28 chars, limit 30) |
| Short description | `<locale>/short_description.txt` (78 / 77 chars, limit 80) |
| Full description | `<locale>/full_description.txt` (2717 / 2824 chars, limit 4000) |
| Release notes | `<locale>/changelogs/1.txt`, named for `versionCode` (limit 500) |
| Category | Tools |
| Tags | Suggested: utilities, personal |
| Contact email | dpfuturehacker@gmail.com |
| Website | https://github.com/ElDavoo/ylih |
| Privacy policy | `PRIVACY.md`, served over HTTPS — see "Blockers" |

The launcher label stays `ylih`; the store title spells it out as "ylih - your life in
headphones" — the app's top-bar phrase and the source of the four-letter name — at exactly 30
characters, Play's limit, with no room to spare. Each translated `title.txt` is the same tagline
in its own language, not a transliteration. Play's 30-character limit is stricter than the 50
`listing-metadata-check.py` enforces for F-Droid — check new titles against 30 by hand.

## 4. Data safety form

- **Does your app collect or share any of the required user data types?** No.
- **Is all of the user data collected by your app encrypted in transit?** N/A — nothing is
  transmitted; the app declares no `INTERNET` permission.
- **Do you provide a way for users to request that their data is deleted?** Yes: uninstalling
  removes everything, and pairs can be deleted individually. Nothing is stored off-device.
- **Data types:** none. Device identifiers aren't collected in Play's sense — a headset address's
  last two octets stay local and never leave the device.

If review pushes back, the manifest is the argument: no `INTERNET` permission, so nothing can be
transmitted.

## 5. Foreground service declaration

The `play` flavor declares one foreground service type, `connectedDevice`, used only with detailed
tracking on. Play requires a written justification and often a demo video.

> **What the service does.** Measures how long headphones stay connected and how much of that time
> audio played. Runs only with "Detailed tracking" enabled in settings, showing a permanent,
> silent, minimum-priority notification throughout.
>
> **Why a foreground service is required.** Android delivers wired-headphone plug events
> (`ACTION_HEADSET_PLUG`) only to a runtime-registered receiver in a live process, not a manifest
> one; measuring playback also needs continuous audio-state observation — neither works without a
> running process.
>
> **Why no other type fits.** The service tracks an audio output device's connection state, which
> is what `connectedDevice` describes.
>
> **Alternatives considered.** Bluetooth headphones need no service — a manifest receiver on the
> ACL connect/disconnect broadcasts handles them, the app's default and why this service is
> optional. WorkManager was rejected: plug events are instantaneous and a deferred job can't
> observe them.

The sideloaded `classic` build also declares `specialUse`, for a user tracking wired headphones
with Bluetooth access denied — Android 14+ ties `connectedDevice` to a Bluetooth permission. That
type needs case-by-case approval, so the Play build omits it and `SettingsScreen` explains the
restriction; adding `specialUse` to the store build would invite review questions.

## 6. Content rating

Answer "no" throughout: no violence, sexuality, profanity, controlled substances, gambling,
user-generated content, user interaction, location sharing, or digital purchases. Expected
outcome: "Everyone" / PEGI 3.

## 7. App content declarations

- **Ads:** none.
- **In-app purchases:** none.
- **Target audience:** 18+ (nothing about the app is aimed at children).
- **News app:** no.
- **COVID-19 / health:** no.
- **Government app:** no.
- **Financial features:** none — the price field is a number the user types for their own
  cost-per-hour arithmetic; no payment is processed.
- **Data deletion:** the in-app path is Settings and uninstall, per Data safety.
- **App access:** everything works without logging in — no accounts, no credentials to hand
  review. Say so explicitly: an unanswered App access form blocks submission on its own.
- **Advertising ID:** no. No `AD_ID` permission and no ads, analytics or attribution SDK;
  answering "yes" would fail review against the manifest.
- **Photos and videos / other restricted permissions:** none requested.

The manifest does contain `ACCESS_NETWORK_STATE`, added by `androidx.work:work-runtime`, not this
app; it reads connectivity state and can't transmit anything. `INTERNET` is still absent, so "no
internet permission" holds.

## 8. Blockers that cannot be closed from this repository

1. **A public HTTPS URL for the privacy policy.** Play requires one; the repository is public, so
   `https://github.com/ElDavoo/ylih/blob/main/PRIVACY.md` works, though a GitHub Pages copy would
   read better if worth the setup.
2. **The upload keystore.** Must exist, be registered with Play App Signing, and sit in the
   repository secrets the release workflow reads.
3. **A demo video** for the foreground-service declaration, if review asks — must show a real
   device, so it can't be generated here.

## 9. Pre-flight checklist

- [ ] `versionCode` incremented; `changelogs/<versionCode>.txt` exists
- [ ] `./gradlew lintPlayReleaseTest testPlayReleaseTestUnitTest bundlePlayRelease` passes
- [ ] AAB is signed with the upload key, not the debug key
- [ ] Screenshots re-recorded for this version and eyeballed — they contain live demo data, so a
      UI regression shows up as a bad image rather than a failing test
- [ ] Privacy policy URL live
- [ ] Data safety, content rating and foreground-service forms completed from this document
- [ ] Internal testing track first; promote only after installing that exact artifact
