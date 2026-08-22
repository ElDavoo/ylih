# Submitting ylih to Google Play

Everything the Play Console asks for, prepared in advance. The listing text lives under
`fastlane/metadata/android/en-US/`; the images are generated from the app itself.

## 1. Build the artifacts

```sh
./gradlew bundlePlayRelease            # app/build/outputs/bundle/playRelease/*.aab
./gradlew recordRoborazziPlayReleaseTest     # app/build/outputs/play-listing/playReleaseTest/*.png
```

Tagging a release runs both in CI (`.github/workflows/android-release.yml`) and attaches the
images as the `play-listing-assets` artifact, so the images always match the version being
shipped. The AAB must be the **play** flavor: the classic APK declares the `specialUse`
foreground-service type, which is exactly what the store build drops.

Release signing reads `ANDROID_SIGNING_KEYSTORE_PATH`, `ANDROID_SIGNING_STORE_PASSWORD`,
`ANDROID_SIGNING_KEY_ALIAS` and `ANDROID_SIGNING_KEY_PASSWORD`. Without them the build produces an
**unsigned** artifact, which Play rejects just as surely as a debug-signed one — check the signer
before uploading. (It used to fall back to the debug key; F-Droid builds this same source with no
keystore at all, and unsigned is the output that leaves it something clean to sign.)

## 2. The generated images

| File | Size | Play field |
|---|---|---|
| `en-US/01-devices.png` … `en-US/05-devices-dark.png` | 1080×1920 | Phone screenshots, English listing (2–8 required) |
| `it-IT/01-devices.png` … `it-IT/05-devices-dark.png` | 1080×1920 | Phone screenshots, Italian listing |
| `icon-512.png` | 512×512 | App icon (all languages) |
| `feature-graphic-1024x500.png` | 1024×500 | Feature graphic (all languages) |
| `social-preview-1280x640.png` | 1280×640 | Not a Play field — GitHub's social preview, see below |

They are produced on the JVM by `app/src/test/java/it/eldavo/ylih/listing/`, using Roborazzi over
Robolectric's native graphics — there is no emulator anywhere in this project's toolchain, and a
store screenshot is not worth introducing one.

- `DemoData.kt` seeds a plausible year of listening, anchored to the moment of recording, so the
  screenshots never show stale dates. It writes through the DAOs rather than `SessionRepository`,
  because the repository exists precisely to refuse backdated history.
- `StoreGraphics.kt` also writes `social-preview-1280x640.png`, which no store asks for: it is the
  card GitHub unfurls into Google, Slack and Mastodon. GitHub has no API for it — the upload lives
  only in *Settings → General → Social preview* — so the committed copy at
  `docs/img/social-preview.png` is what a human attaches there, and re-recording is only half the
  job until that upload happens.
- `StoreScreenshots.kt` captures the four screens plus a dark-mode shot at 1080×1920 — Play's 9:16
  phone ratio. A Pixel-shaped 1080×2400 is taller than 9:16, which is why the qualifier sets the
  size by hand. The class is abstract with one subclass per listing language, each setting a
  resource qualifier *and* `Locale.setDefault` — the qualifier localises the strings, the JVM
  locale localises the numbers and dates that `ui/Format.kt` formats through
  `Locale.getDefault()`. Adding a language to the store means adding one subclass and one
  `fastlane/metadata/android/<locale>/` directory.
- `StoreGraphics.kt` renders `@mipmap/ic_launcher`'s own layers rather than redrawing the artwork,
  so the geometry solved in `ic_launcher_foreground.xml` cannot drift out of the listing. It draws
  the layers instead of the drawable because `AdaptiveIconDrawable.draw()` applies the platform's
  circular mask, and Play wants a full square it rounds off itself.

The screenshots are captured with the app's ordinary theme, dynamic colours included. On
Robolectric those resolve to the AOSP default palette, which is coherent and representative.
Pinning the hand-written `LightColors`/`DarkColors` in `ui/theme/Theme.kt` was tried and looks
worse: those schemes set only `primary`, `secondary` and `tertiary`, so every container role falls
back to Material 3's default purple under a blue primary. Worth fixing one day as a full tonal
palette; not worth faking in a screenshot.

Nothing generated is committed *for Play*, which takes the images by upload and so has no reason
to carry binaries a Gradle task reproduces exactly. The one exception is
`fastlane/metadata/android/en-US/images/`, which is checked in for F-Droid: F-Droid has no upload
step and reads the images out of the repository or shows none at all. Those are recorded from the
**classic** flavor, because that is the build F-Droid ships and its settings screen differs from
this one. See [`fdroid.md`](fdroid.md).

## 3. Store listing

Listing text lives in `fastlane/metadata/android/<locale>/`, currently 26 locales. Screenshots are
rendered for 29 (`cs-CZ`, `el-GR` and `iw-IL` have images but no listing copy yet — either write
it or drop those three `StoreScreenshots` subclasses before submitting). Add each non-English
listing in Play Console under *Store presence → Main store listing → Manage translations*.

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

The launcher label stays `ylih`; the store title spells the name out in full — "ylih - your life
in headphones", the same phrase the app's own top bar uses, which is where the four-letter word
comes from. It is exactly 30 characters, Play's limit, so there is no room to add to it. Each
translated `title.txt` is that same tagline in its own language rather than a transliteration, and
the per-locale limit is Play's 30, not the 50 that `listing-metadata-check.py` enforces for
F-Droid — check any new title against 30 by hand.

## 4. Data safety form

The honest answers are all the same answer.

- **Does your app collect or share any of the required user data types?** No.
- **Is all of the user data collected by your app encrypted in transit?** Not applicable — no
  data is transmitted. The app declares no `INTERNET` permission.
- **Do you provide a way for users to request that their data is deleted?** Yes: uninstalling
  removes everything, and pairs can be deleted individually in the app. Nothing is stored
  off-device, so there is nothing else to delete.
- **Data types:** none. Device identifiers are *not* collected in Play's sense — the last two
  octets of a headset's address are stored locally and never leave the device.

If the review pushes back, the argument is in the manifest: there is no `INTERNET` permission,
so no data can be transmitted.

## 5. Foreground service declaration

The `play` flavor declares one foreground service type, `connectedDevice`, used only when the
user turns on detailed tracking. Play requires a written justification and often a demo video.

> **What the service does.** It measures how long headphones stay connected, and how much of that
> time audio was actually playing. It runs only while the user has explicitly enabled "Detailed
> tracking" in the app's settings, and it shows a permanent, silent, minimum-priority notification
> the entire time it is running.
>
> **Why a foreground service is required.** Android delivers wired-headphone plug events
> (`ACTION_HEADSET_PLUG`) only to a receiver registered at runtime by a live process; they cannot
> be received by a manifest receiver. Measuring playback likewise requires observing audio state
> continuously. Neither is possible without a running process.
>
> **Why no other type fits.** The service tracks the connection state of an audio output device,
> which is what `connectedDevice` describes.
>
> **Alternatives considered.** Bluetooth headphones are tracked with no service at all, via a
> manifest receiver on the ACL connect/disconnect broadcasts — that is the app's default mode, and
> it is why this service is optional rather than always-on. WorkManager was rejected because plug
> events are instantaneous and a deferred job cannot observe them.

The sideloaded `classic` build additionally declares `specialUse`, for a user who wants wired
headphones tracked but has denied Bluetooth access — Android 14+ ties `connectedDevice` to holding
a Bluetooth permission. That type needs case-by-case approval, so the Play build does without it,
and `SettingsScreen` explains the restriction to the user. Do not add `specialUse` to the store
build without expecting review questions.

## 6. Content rating

Answer "no" throughout: no violence, sexuality, profanity, controlled substances, gambling,
user-generated content, user interaction, location sharing, or digital purchases. The expected
outcome is "Everyone" / PEGI 3.

## 7. App content declarations

- **Ads:** none.
- **In-app purchases:** none.
- **Target audience:** 18+ (nothing about the app is aimed at children).
- **News app:** no.
- **COVID-19 / health:** no.
- **Government app:** no.
- **Financial features:** none. The price field is a number the user types for their own
  cost-per-hour arithmetic; no payment is processed.
- **Data deletion:** the in-app path is Settings and uninstall, per the Data safety section.
- **App access:** all functionality is available without logging in. There are no accounts, so
  there are no credentials to hand review. Say so explicitly rather than leaving it blank — an
  unanswered App access form blocks the submission on its own.
- **Advertising ID:** no. The app declares no `AD_ID` permission and pulls in no ads,
  analytics or attribution SDK; answering "yes" here would fail review against the manifest.
- **Photos and videos / other restricted permissions:** none requested.

One thing to have ready if review reads the manifest: the merged manifest does contain
`ACCESS_NETWORK_STATE`, added by `androidx.work:work-runtime`, not by this app. It reads
connectivity state and cannot transmit anything; `INTERNET` is still absent, so the listing's
"no internet permission" claim holds exactly as written.

## 8. Blockers that cannot be closed from this repository

1. **A public HTTPS URL for the privacy policy.** Play will not accept a submission without one.
   The repository is now public, so `https://github.com/ElDavoo/ylih/blob/main/PRIVACY.md` is a
   usable URL; a GitHub Pages copy reads better if it is worth the setup.
2. **The upload keystore.** It must exist, be registered with Play App Signing, and be present in
   the repository secrets the release workflow reads.
3. **A demo video** for the foreground-service declaration, if review asks for one. It has to show
   a real device, so it cannot be generated here.

## 9. Pre-flight checklist

- [ ] `versionCode` incremented; `changelogs/<versionCode>.txt` exists
- [ ] `./gradlew lintPlayReleaseTest testPlayReleaseTestUnitTest bundlePlayRelease` passes
- [ ] AAB is signed with the upload key, not the debug key
- [ ] Screenshots re-recorded for this version and eyeballed — they contain live demo data, so a
      UI regression shows up as a bad image rather than a failing test
- [ ] Privacy policy URL live
- [ ] Data safety, content rating and foreground-service forms completed from this document
- [ ] Internal testing track first; promote only after installing that exact artifact
