# Submitting ylih to F-Droid

F-Droid builds the app itself from a tagged commit, on a machine with none of our keys, and signs
it with its own key. So the recipe must name an existing commit, the build must produce an
unsigned APK, and the listing text and images must live in the repository — the only place F-Droid
looks.

The recipe is `metadata/it.eldavo.ylih.yml`, kept here so a version bump is one change, not two.
Nothing here reads it; it's copied into a fork of
[fdroiddata](https://gitlab.com/fdroid/fdroiddata) and submitted as a merge request there.

## 1. Why this app qualifies

The [inclusion policy](https://f-droid.org/docs/Inclusion_Policy/) asks four things:

| Requirement | ylih |
|---|---|
| FLOSS licence, public source | MIT, `LICENSE`, GitHub |
| No proprietary dependencies | no Play Services, Firebase or analytics; every shipped artifact is Apache-2.0 except two, both FLOSS — see below |
| Builds with a FLOSS toolchain | Gradle, AGP, KSP and the Android SDK; OpenJDK, never Oracle's |
| No embedded API keys, no downloaded executables | no `INTERNET` permission, so neither is possible |

The dependency answer comes from the resolved graph, not `libs.versions.toml` — declared and
shipped lists differ. `./gradlew :app:dependencies --configuration classicReleaseRuntimeClasspath`
resolves ~230 artifacts, all from `google()` and `mavenCentral()` (both on F-Droid's Maven
allowlist), all Apache-2.0 except two:

- **`com.google.guava:listenablefuture:1.0`** declares no licence — a metadata gap, not a
  licensing one. The jar holds one class, `com.google.common.util.concurrent.ListenableFuture`,
  split out of Guava so `androidx.concurrent` can depend on the interface without pulling Guava
  in. Apache-2.0, like Guava.
- **`androidx.datastore:datastore-preferences-external-protobuf`** declares **BSD-3-Clause**:
  protobuf-javalite, repackaged by AndroidX under `androidx.datastore.preferences.protobuf`,
  pulled in because DataStore serialises preferences as protobuf. BSD-3-Clause is DFSG-free,
  OSI-approved and GPL-compatible.

The build-time-only graph is the same: Room's KSP processor pulls in AutoValue, Error Prone,
Guava, JavaPoet, commons-codec and `sqlite-jdbc`, and every cached POM declares Apache-2.0.

The one non-free thing near this build is prebuilt Android SDK binaries, explicitly allowed by the
inclusion policy — "the Android SDK, Flutter SDK and Hermes have permission to use official
prebuilt binaries until Debian provides alternative solutions" — a dispensation nothing else
needs. The policy's one forbidden tool, Oracle's JDK, goes unused: nix pins OpenJDK 21, CI uses
Temurin, and the buildserver uses Debian's `default-jdk-headless`.

`material3` pins to `1.5.0-alpha`, dragging Compose to `1.12.0-beta` (see
`gradle/libs.versions.toml` for why) — pre-release *libraries*, not a pre-release toolchain, since
AGP, Kotlin, KSP and Gradle stay stable. So the policy's toolchain rule doesn't apply, though this
remains the build's most fragile pin.

No [anti-feature](https://f-droid.org/docs/Anti-Features/) applies: `Tracking` means tracking
*the user*, reported off-device, and ylih records headphone connections into a local Room database
with no way to send them anywhere.

F-Droid ships `classic`. `play` exists only for Play review — dropping the `specialUse`
foreground-service type and the battery-optimisation shortcut, restrictions F-Droid doesn't need.
See the flavor table in `README.md`.

## 2. What the buildserver does with this repo

Parts of the build that fail confusingly if changed unknowingly:

- **The Gradle wrapper is deleted.** `fdroidserver` removes `gradlew`, `gradlew.bat` and
  `.gradle/`, then runs its own `gradlew-fdroid`. That script reads
  `gradle/wrapper/gradle-wrapper.properties` (left in place) and downloads the version named
  there, verified against the
  [gradle-transparency-log](https://gitlab.com/fdroid/gradle-transparency-log). Bumping to a
  Gradle release the log hasn't recorded yet breaks the build; every 9.x release so far has
  appeared there within days.
- **The SDK is old; AGP fixes it.** The buildserver image preinstalls build-tools up to 33 and
  platforms up to `android-33`, short of `compileSdk = 37` / `buildToolsVersion = "37.0.0"`. The
  recipe names no SDK version because `buildserver/provision-android-sdk` pre-writes the licence
  hashes into `$ANDROID_HOME/licenses/` and then:

  ```sh
  # allow gradle to install newer build-tools and platforms
  mkdir -p $ANDROID_HOME/{build-tools,platforms}
  chgrp vagrant $ANDROID_HOME/{build-tools,platforms}
  chmod g+w $ANDROID_HOME/{build-tools,platforms}
  ```

  AGP's SDK download is the intended mechanism and stays enabled (`android.builder.sdkDownload`
  unset in `gradle.properties`). A `sudo: sdkmanager 'platforms;android-37.0' …` line would be
  worse than redundant: fdroidserver runs `sudo` as `sudo bash -e -u -o pipefail -c …`, not a
  login shell, so `/etc/profile.d/bsenv.sh` never sources, `ANDROID_HOME` is unset, and
  `sdkmanager` falls back to `/opt/android-sdk` and aborts. An SDK package the build can't fetch
  itself belongs in `prebuild:`, which runs as `vagrant` with the environment set up.
- **The output APK is found by convention, not an `output:` glob.** `gradle: [classic]` makes
  fdroidserver run `assembleClassicRelease`, then search `build/outputs/apk/{release,}` plus the
  flavor directory found by case-insensitive match — `app/build/outputs/apk/classic/release/`,
  where `app-classic-release-unsigned.apk` lands. An `output:` field would switch to the `raw`
  method for no reason.
- **JDK 21.** The image is Debian trixie with `default-jdk-headless`, `update-java-alternatives
  --set` on the highest installed; AGP 9 needs 17+.
- **The build must be unsigned.** `assembleClassicRelease` produces
  `app-classic-release-unsigned.apk` unless the four `ANDROID_SIGNING_*` env vars are set;
  F-Droid sets none. The release build type used to fall back to the debug key, but a debug
  keystore is generated fresh per machine, which would make the APK unreproducible and force
  `apksigner` to strip the signature back off — so it no longer falls back.
- **The scanner reads the whole tree**, not just `app/`. Three checks a future commit could trip:
  it allowlists `gradle-wrapper.jar` by name, the only tracked binary (listing PNGs are exempted as
  images); it fails a `package.json`/`Cargo.toml`/`pubspec.yaml` with no lockfile beside it — this
  repo has none of the three; and it rejects any `maven { url = … }` outside its allowlist, so
  `settings.gradle.kts` should keep naming only `google()`, `mavenCentral()` and
  `gradlePluginPortal()`.

## 3. Listing text and images

F-Droid reads `fastlane/metadata/android/<locale>/` from this repository, the same directory the
Play listing uses — one copy of the text, translated 26 ways.

```
fastlane/metadata/android/<locale>/
  title.txt                       max 50 chars
  short_description.txt           max 80 chars
  full_description.txt            max 4000 chars
  changelogs/<versionCode>.txt    max 500 chars, named for the versionCode exactly
  images/icon.png
  images/featureGraphic.png
  images/phoneScreenshots/*.png
```

`.github/scripts/listing-metadata-check.py` enforces the limits in CI. Both stores truncate
silently rather than reject, so an over-long Finnish summary is a bug nobody notices by using the
app.

Two details specific to F-Droid:

- A changelog appears only if F-Droid built that versionCode: `changelogs/1.txt` never shows,
  since the recipe's `Builds:` list starts at versionCode 2 (section 6), though Play still serves
  it as versionCode 1's release notes. The file stays because the two stores share a directory,
  not because F-Droid uses it.
- `en-US` is the fallback for every locale missing a file, so it has to be complete.

### The images are committed, and that is a deliberate reversal

Play takes images by upload and a Gradle task reproduces them exactly, so nothing generated is
committed there. F-Droid has no upload — an image not in the repository doesn't exist — so
`fastlane/metadata/android/en-US/images/` is checked in: 628 KB for the icon, feature graphic and
five phone screenshots.

Regenerated from the **classic** flavor, the build F-Droid ships, whose settings screen differs
from Play's:

```sh
./gradlew recordRoborazziClassicReleaseTest
cp app/build/outputs/play-listing/classicReleaseTest/en-US/*.png \
   fastlane/metadata/android/en-US/images/phoneScreenshots/
cp app/build/outputs/play-listing/classicReleaseTest/icon-512.png \
   fastlane/metadata/android/en-US/images/icon.png
cp app/build/outputs/play-listing/classicReleaseTest/feature-graphic-1024x500.png \
   fastlane/metadata/android/en-US/images/featureGraphic.png
```

Only English is committed. Screenshots exist for 29 languages; committing all would add ~18 MB of
near-identical PNGs to a repository whose whole history F-Droid clones. F-Droid falls back to
`en-US` images the same way it falls back to `en-US` text; add another locale's `images/` only if
asked.

## 4. Releasing a version

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Add `fastlane/metadata/android/<locale>/changelogs/<versionCode>.txt` — at minimum `en-US`.
   The `translations` skill covers `strings.xml`, not these; translate them by hand.
3. Re-record the listing images if the UI changed (section 3).
4. Commit, then tag: `git tag -a v<versionName> -m 'v<versionName>' && git push --tags`.
   The tag must match `UpdateCheckMode: Tags ^v[0-9.]+$` in the recipe, and `versionName` must be
   readable from `app/build.gradle.kts` — F-Droid extracts it with a regex and can't evaluate
   anything computed.
5. `.github/workflows/android-release.yml` builds the APK and the AAB and publishes the GitHub
   release.
6. Update `Builds:`, `CurrentVersion:` and `CurrentVersionCode:` in
   `metadata/it.eldavo.ylih.yml`, and open the fdroiddata merge request (section 5). The build
   entry's `commit:` is the tag's **full 40-character hash**, not the tag name —
   `git rev-parse v<versionName>^{}` prints it.

`AutoUpdateMode: Version` means F-Droid picks up subsequent tags itself and files the build entry,
so step 6 is manual only for the first release and when the recipe changes. `fdroid checkupdates`
resolves the matched tag to that same full hash, giving the generated entry the shape a reviewer
wants with no one maintaining it.

## 5. The fdroiddata merge request

fdroiddata is on GitLab, so `gh` is no help; the dev shell carries `glab`. It needs one-time
interactive authentication — a browser flow, or a token from
[personal access tokens](https://gitlab.com/-/user_settings/personal_access_tokens) with `api`
and `write_repository` scopes (the first opens the MR, the second pushes over https):

```sh
glab auth login --hostname gitlab.com
```

```sh
glab repo fork fdroid/fdroiddata --clone --remote && cd fdroiddata
cp <ylih>/metadata/it.eldavo.ylih.yml metadata/
fdroid readmeta && fdroid lint it.eldavo.ylih
fdroid rewritemeta it.eldavo.ylih          # canonical field order and formatting
fdroid build it.eldavo.ylih                # optional, and slow; --server for the real VM
git checkout -b it.eldavo.ylih && git commit -am 'New app: ylih' && git push -u origin HEAD
glab mr create --repo fdroid/fdroiddata --target-branch master --title 'New app: ylih'
```

Expect 24–48 hours from merge to the app appearing in the repository.

### The recipe carries no comments

`fdroid rewritemeta` deletes every comment in a recipe, and fdroiddata's pipeline runs it over
each metadata file an MR touches, failing on any diff — so a comment here is a red pipeline. The
copy used to carry an explanatory header; the CI check meant to catch this compared *parsed* YAML,
where comments don't exist, so it passed until the MR pipeline found them, and now demands the
file come back byte-identical.

What those comments said, since the nine-field recipe is otherwise opaque:

- **No `Summary:` or `Description:`** — F-Droid reads both from `fastlane/metadata/android/`, one
  copy of the listing text, translated. See the quirk above.
- **`Categories:`** — `Multimedia` and `Time Tracker`, both from fdroiddata's own
  `config/categories.yml`, the whole valid list.
- **`AutoName: ylih`** — what `fdroid checkupdates` derives from `android:label` in the merged
  manifest. fdroiddata's `checkupdates` job runs it and fails on any diff, so the recipe must
  already say it. Not the display name — F-Droid shows the fastlane `title.txt`, translated 26
  ways.
- **`Binaries:`** — the reproducible-build URL, §6. `%v` is the versionName; the release workflow
  names the asset after the tag, `v` + versionName.
- **`AllowedAPKSigningKeys:`** — the signing certificate's SHA-256, §6 again. An APK signed with
  anything else is refused, not published.
- **No `WebSite:`** — the recipe carried it until a reviewer asked for it to go: it pointed at the
  same GitHub project as `SourceCode:`, and F-Droid renders both, so the app page offered one link
  under two labels. The app has no site of its own; the field returns the day it does.
- **`commit:` is the tag's full 40-character hash, not the tag name.** Same review, same reason: a
  tag is mutable — `git tag -f` moves it, and this repository moved one when the fdroiddata
  pipeline found two problems in v1.1.2 after pushing. F-Droid's build and a reviewer's read would
  then be different trees under one name, with nothing recording it; a hash can't move. The tag
  still matters elsewhere: `UpdateCheckMode` matches on it and `Binaries:` is addressed by it.
- **`Builds:` starts at 1.1.3**, not the first release — F-Droid published none of the earlier
  ones. 1.0.0 could not have verified anywhere, since the pin making a build agree across machines
  with and without an NDK landed after it. 1.1.2 removed the need for that pin entirely — the one
  file it protected was DataStore's prebuilt `.so`, and settings now live in the app's own
  database — so reproducibility rests on the source alone from there. 1.1.3 begins the list simply
  because it landed while the MR was open: a list of one is smaller for a reviewer to check than a
  list of two saying the same thing twice.
- **No `sudo:` and no `output:`** — both in §2.

One `fdroid lint` quirk: it checks `Summary:` against `.*[a-z0-9][.!?]( |$)` ("Punctuation should
be avoided") and an 80-character limit — but the recipe sets neither `Summary:` nor `Description:`
(they come from the fastlane files), so neither check fires. Don't "fix" that by copying text into
the YAML; it would fork the English listing from the other 25 languages.

**That silence is a blind spot, not a convenience.** It hid two real problems until a reviewer
found them: 24 of 26 `short_description.txt` files ended in a full stop, and two were exactly 80
characters — which Play allows but the inclusion guide ("less than 80 characters, no trailing
dot") doesn't. `.github/scripts/listing-metadata-check.py` now enforces both against the fastlane
files, the only place that can see them, matching the guide's wording rather than lint's regex.
The regex is actually broader — it also matches mid-string punctuation, so the second sentence in
`Track how many hours each pair of headphones lasts. Offline, forever, private` would trip it too —
but nothing enforces that, since the regex needs a `Summary:` to read and matches only Latin
letters and digits, so most translations couldn't trip it anyway. Dropping that internal full stop
across 26 translations is a content decision, not a lint fix, and stays as is.

## 6. Reproducible builds

[Reproducible builds](https://f-droid.org/docs/Reproducible_Builds/) let F-Droid publish *our*
signed APK after verifying it matches one it built itself, so an F-Droid user and a GitHub-release
user can update from either source without uninstalling. Not needed for the first submission — F-Droid
signs with its own key regardless — but v1.0.0 is already signed and published, so the recipe
points at it and asks for verification from the start.

### What the build already guarantees

Determinism is the prerequisite, and it holds: two clean `assembleClassicRelease` builds of the
same commit produce a byte-identical APK:

```sh
./gradlew clean assembleClassicRelease && sha256sum app/build/outputs/apk/classic/release/*.apk
./gradlew clean assembleClassicRelease && sha256sum app/build/outputs/apk/classic/release/*.apk
```

Every zip entry carries one fixed timestamp rather than the build clock. Dropping the debug-key
fallback (section 2) made this possible.

R8 runs on the release build (`optimization` block in `app/build.gradle.kts`) without threatening
this: it renames deterministically from the input program, and the R8 that runs is the one AGP
bundles, pinned by the version catalogue. It would become a hazard only if someone supplied
`-obfuscationdictionary` with a generated file, or let the AGP version float. The check above was
re-run across the change and both builds still agree.

Three things vary, and knowing them saves hunting after a verification failure:

- **Native libraries, despite this app writing no native code.** The APK ships eight `.so` files
  pulled in by dependencies: `libandroidx.graphics.path.so` and `libdatastore_shared_counter.so`,
  one per ABI. AGP's `stripDebugSymbols` strips them with the NDK's `strip` if the build machine
  has an NDK it can use, else copies them through unstripped. `libandroidx.graphics.path.so`
  arrives already stripped, identical either way; `libdatastore_shared_counter.so` does not.

  Measured: the published v1.0.0 APK (release runner) has `libdatastore_shared_counter.so` at
  7784 / 5916 / 6124 / 7336 bytes across arm64-v8a, armeabi-v7a, x86 and x86_64. Building the same
  tag in the nix dev shell, which has no NDK, gives 10360 / 8432 / 7976 / 9424 — the AAR's
  untouched shipped sizes, 8.8 KB more over the four ABIs. Two builds on one machine agree, which
  is why this went unnoticed and why CI doesn't catch it: both sides of the `fdroid build`
  comparison in section 7 are GitHub runners, both stripped, both matching.

  `app/build.gradle.kts` therefore keeps that library's symbols
  (`packaging { jniLibs { keepDebugSymbols += "**/libdatastore_shared_counter.so" } }`) — the one
  answer every machine can give, for ~10 KB. **The fix isn't in v1.0.0**: its published asset is
  the stripped variant, so that tag could verify only on a machine whose NDK situation matched the
  release runner's. **v1.1.0 is the first tag that carries it**, which is why the recipe's
  `Builds:` list starts there rather than at the first release — F-Droid never published v1.0.0,
  so there's nobody to keep it for, and leaving it out means every version F-Droid serves
  reproduces anywhere.

- AGP embeds `META-INF/version-control-info.textproto`, holding the git revision. Its
  `local_root_path` normalises to `$PROJECT_DIR`, so it's not machine-specific, but **two builds
  of different commits never match** even with identical sources. F-Droid builds the named commit,
  so it gets the same value the release workflow did.
- The JDK. F-Droid's buildserver compiles with Debian's `default-jdk-headless`; the release
  workflow uses Temurin 21 and the nix shell OpenJDK 21. Almost everything here is Kotlin, whose
  compiler is wrapper-pinned, so exposure is small — but this axis can't be tested from this
  repository, and is the usual reason a first verification attempt fails.

### How it was enabled

1. The signing key was created and `ANDROID_SIGNING_*` secrets set, so the release asset is
   `ylih-v1.0.0-classic.apk`, not `…-classic-unsigned.apk`. See "Release signing" in `README.md`
   for the `keytool` invocation.
2. Tagging v1.0.0 ran the `Signing certificate fingerprint` step in `android-release.yml`, which
   prints the certificate SHA-256 in the format `AllowedAPKSigningKeys` wants — lowercase hex, no
   colons — read off the job log, not the machine holding the key.
3. `Binaries:` and `AllowedAPKSigningKeys:` in `metadata/it.eldavo.ylih.yml` now carry that URL and
   fingerprint. Both are app-level fields, not per-build, so a version bump doesn't touch them:
   `%v` expands to the versionName, and the fingerprint changes only if the signing key does — at
   which point F-Droid refuses the new APK until this line updates too.

F-Droid then builds from source, downloads the release asset, copies the signature across with
`apksigcopier` and compares. The source build being unsigned isn't a problem — it's what the
tooling expects.

One consequence: the GitHub-release APK and the F-Droid APK become interchangeable, but neither is
interchangeable with the Play build, which Play App Signing signs with Google's key — already true
today, and not something the recipe can fix.

## 7. Running F-Droid's checks in CI

Everything above is a feedback loop measured in days: the recipe copies a file living elsewhere,
nothing in the ordinary build reads it, and mistakes surface as an MR review.
`.github/workflows/fdroid.yml` closes that loop by running fdroidserver's own tools rather than an
approximation. Three jobs:

- **recipe** — `fdroid readmeta`, `fdroid lint`, a check that `fdroid rewritemeta` changes
  nothing, plus `.github/scripts/fdroid-recipe-check.py` for what lint can't know: whether the
  recipe still describes this repository — tags, changelogs and flavors that exist,
  `CurrentVersion` matching the newest build entry.
- **scanner** — `fdroid scanner`, the source scan F-Droid runs before building anything: tracked
  binaries, dependency files with no lockfile, maven repositories off its allowlist.
- **build** — `fdroid build`, which checks out the tag, deletes the Gradle wrapper, strips
  `signingConfigs` from `build.gradle.kts`, builds through `gradlew-fdroid`, and — since
  `Binaries:` is set — downloads the published APK and compares. `fdroid verify` then runs a
  separate comparison against a *different* APK: it fetches
  `f-droid.org/repo/<package>_<versionCode>.apk`, falling back to `/archive`, rather than the
  `Binaries:` URL. Until F-Droid publishes ylih neither address has anything, and fdroidserver
  reports that 404 as "NOT verified" — which reads, wrongly, as "the release stopped
  reproducing." The step tells the two apart and treats only the 404 as a skip, so today's
  reproducibility check is the `Binaries:` one inside `fdroid build`.

  **`fdroid build` exits 0 whether or not it built anything.** A failed build is just a log line
  reading `1 build failed`; the process still returns success. So the step asserts the outcome
  itself, grepping the log and requiring an APK in `unsigned/`. It once didn't, and read green
  through a build that failed dependency verification while the MR pipeline failed on the same
  commit.

Both `scanner` and `build` clone the commit the recipe names, so on a PR they say nothing about the
change under review — hence the path-filtered triggers and the weekly run: interesting failures
here come from things outside this repository moving.

Two setup details, both handled by `.github/scripts/fdroid-workdir.sh`:

- fdroidserver only runs from a directory shaped like fdroiddata, and it must be a **git**
  repository — `fdroid build` reads `SOURCE_DATE_EPOCH` off the commit that last touched
  `metadata/<appid>.yml`; with no git repository that returns `None` and the build dies inside
  `os.environ` with `str expected, not NoneType`.
- **`gradlew-fdroid` must come from its own repository**, not the fdroidserver release. F-Droid
  deletes our wrapper and builds with this, resolving the Gradle version from `distributionUrl`
  against a transparency log of known checksums. It was split out of fdroidserver; the 2.4.5
  release still bundles the old bash version, whose hardcoded table stops at Gradle 8.14.2 and
  refuses this app with `No hash for gradle version 9.7.0! Exiting...`. The standalone version
  knows 9.7.0, and cloning it is what the buildserver itself does (`buildserver/provision-gradle`).
  A wrapper bump landing before the transparency log catches up would fail F-Droid the same way,
  so `gradle-wrapper.properties` is one of this workflow's trigger paths.

The jobs need no secrets, and the build one needs no preinstalled platform 37 or build-tools
37.0.0 — omitted so AGP's own SDK download (§2) is exercised, not assumed. A step afterward fails
the job if they didn't appear.

## 8. Pre-flight checklist

- [ ] `versionCode` bumped; `en-US/changelogs/<versionCode>.txt` written and under 500 chars
- [ ] `python3 .github/scripts/listing-metadata-check.py fastlane/metadata/android <versionCode>`
- [ ] Listing images re-recorded from `recordRoborazziClassicReleaseTest` and eyeballed — they contain
      live demo data, so a UI regression shows up as a bad image rather than a failing test
- [ ] `./gradlew lintClassicReleaseTest testClassicReleaseTestUnitTest assembleClassicRelease` passes
- [ ] The release APK is `app-classic-release-unsigned.apk` when no keystore is configured
- [ ] Tag pushed, matching `^v[0-9.]+$` and equal to `versionName`
- [ ] `metadata/it.eldavo.ylih.yml` updated with the new build entry, `commit:` carrying the
      tag's full hash from `git rev-parse v<versionName>^{}` rather than the tag name
- [ ] The `F-Droid` workflow green on the release commit — it runs readmeta, lint, rewritemeta,
      scanner, `fdroid build` and `fdroid verify`, so a green run is the fork's `fdroid lint`
      and build already answered (section 7)
- [ ] `fdroid lint it.eldavo.ylih` clean in the fdroiddata fork
