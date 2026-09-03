# Submitting ylih to F-Droid

F-Droid does not accept an upload. It builds the app itself, from a tagged commit of this
repository, on a machine that has none of our keys, and then signs the result with its own.
Everything below follows from that: the recipe has to name a commit that exists, the build has to
produce an unsigned APK, and the listing text and images have to be *in the repository*, because
that is the only place F-Droid looks.

The recipe itself is `metadata/it.eldavo.ylih.yml`, kept here so a version bump is one change
rather than two disconnected ones. Nothing in this repository reads it; it is copied into a fork
of [fdroiddata](https://gitlab.com/fdroid/fdroiddata) and submitted as a merge request there.

## 1. Why this app qualifies

The [inclusion policy](https://f-droid.org/docs/Inclusion_Policy/) asks four things, and the
answers happen to be the shortest part of this document:

| Requirement | ylih |
|---|---|
| FLOSS licence, public source | MIT, `LICENSE`, GitHub |
| No proprietary dependencies | no Play Services, no Firebase, no analytics; every artifact on the shipped classpath is Apache-2.0 but for two, both FLOSS — see below |
| Builds with a FLOSS toolchain | Gradle, AGP, KSP and the Android SDK; OpenJDK, never Oracle's |
| No embedded API keys, no downloaded executables | there is no `INTERNET` permission, so neither is possible |

The dependency answer is worth stating from the resolved graph rather than from
`libs.versions.toml`, because the declared list and the shipped list are not the same thing.
`./gradlew :app:dependencies --configuration classicReleaseRuntimeClasspath` resolves to ~230
artifacts, all from `google()` and `mavenCentral()` — both on F-Droid's allowlist of trusted Maven
repositories — and every one of them declares Apache-2.0 except two:

- **`com.google.guava:listenablefuture:1.0`** declares no licence at all. Its POM has no
  `<licenses>` block, which is a metadata gap rather than a licensing one: the jar holds exactly
  one class, `com.google.common.util.concurrent.ListenableFuture`, split out of Guava so that
  `androidx.concurrent` can depend on the interface without dragging in Guava. Apache-2.0, as
  Guava is.
- **`androidx.datastore:datastore-preferences-external-protobuf`** declares **BSD-3-Clause**. It is
  protobuf-javalite, repackaged by AndroidX under `androidx.datastore.preferences.protobuf`, and it
  arrives because DataStore serialises preferences as protobuf. BSD-3-Clause is DFSG-free,
  OSI-approved and GPL-compatible.

The build-time-only graph is the same story: Room's KSP processor pulls in AutoValue, Error Prone,
Guava, JavaPoet, commons-codec and `sqlite-jdbc`, and every cached POM among them declares
Apache-2.0.

Prebuilt Android SDK binaries are the one non-free thing anywhere near this build, and the
inclusion policy names them explicitly — "the Android SDK, Flutter SDK and Hermes have permission
to use official prebuilt binaries until Debian provides alternative solutions". Nothing else in
the toolchain needs that dispensation. The policy's one named forbidden tool, Oracle's JDK, is not
used anywhere: the nix shell pins OpenJDK 21, CI uses Temurin, and the buildserver uses Debian's
`default-jdk-headless`.

`material3` is pinned to a `1.5.0-alpha` and that drags Compose to a `1.12.0-beta` (see
`gradle/libs.versions.toml` for why). Those are pre-release *libraries*, not a pre-release
toolchain — AGP, Kotlin, KSP and Gradle are all stable releases — so the policy's line about
pre-release toolchains does not bite. It is still the build's most fragile pin.

No [anti-feature](https://f-droid.org/docs/Anti-Features/) applies. `Tracking` in particular is
about tracking *the user*, reported off-device; ylih records headphone connections into a local
Room database and has no way to send them anywhere.

The `classic` flavor is the one F-Droid ships. `play` exists only to satisfy Play review — it
drops the `specialUse` foreground-service type and the battery-optimisation shortcut, which are
restrictions F-Droid has no reason to inherit. See the flavor table in `README.md`.

## 2. What the buildserver does with this repo

Worth knowing before changing the build, because these are the parts that fail confusingly:

- **The Gradle wrapper is deleted.** `fdroidserver` removes `gradlew`, `gradlew.bat` and
  `.gradle/`, then runs its own `gradlew-fdroid`. That script reads
  `gradle/wrapper/gradle-wrapper.properties` — which it leaves in place — and downloads exactly
  the version named there, verified against the
  [gradle-transparency-log](https://gitlab.com/fdroid/gradle-transparency-log). Bumping the
  wrapper to a Gradle release that log has not yet recorded is what breaks the build; every 9.x
  release so far has appeared there within days.
- **The SDK is old, and AGP is expected to fix that itself.** The buildserver image preinstalls
  build-tools up to 33 and platforms up to `android-33`, so `compileSdk = 37` /
  `buildToolsVersion = "37.0.0"` are far past what is there. The recipe still names no SDK
  version, because `buildserver/provision-android-sdk` pre-writes the licence hashes into
  `$ANDROID_HOME/licenses/` and then does exactly this:

  ```sh
  # allow gradle to install newer build-tools and platforms
  mkdir -p $ANDROID_HOME/{build-tools,platforms}
  chgrp vagrant $ANDROID_HOME/{build-tools,platforms}
  chmod g+w $ANDROID_HOME/{build-tools,platforms}
  ```

  AGP's own SDK download is the intended mechanism and this repository does not disable it
  (`android.builder.sdkDownload` is unset in `gradle.properties`). Adding a
  `sudo: sdkmanager 'platforms;android-37.0' …` line would be worse than redundant: fdroidserver
  runs the `sudo` field as `sudo bash -e -u -o pipefail -c …`, which is not a login shell, so
  `/etc/profile.d/bsenv.sh` is never sourced and `ANDROID_HOME` is unset — F-Droid's `sdkmanager`
  would fall back to `/opt/android-sdk` and abort. The place for an SDK package the build cannot
  get for itself is `prebuild:`, which runs as `vagrant` with the environment set up.
- **The output APK is found by convention, not by an `output:` glob.** `gradle: [classic]` makes
  fdroidserver run `assembleClassicRelease`, and it then searches
  `build/outputs/apk/{release,}` *plus* the flavor directory it finds by case-insensitive match —
  `app/build/outputs/apk/classic/release/`, where `app-classic-release-unsigned.apk` actually
  lands. Adding an `output:` field would switch the whole build to the `raw` method; there is no
  reason to.
- **JDK 21 is what the build gets.** The image is Debian trixie with `default-jdk-headless` and
  `update-java-alternatives --set` on the highest installed, and AGP 9 needs 17 or newer.
- **The build must be unsigned.** `assembleClassicRelease` produces
  `app-classic-release-unsigned.apk` unless the four `ANDROID_SIGNING_*` environment variables are
  set, and F-Droid sets none of them. The release build type used to fall back to the debug key;
  it no longer does, because a debug keystore is generated fresh per machine and would make the
  APK both unreproducible and something `apksigner` has to strip a signature back off.
- **The scanner reads the whole tree**, not just `app/`. Three of its checks are worth knowing
  because a future commit could trip them: it allowlists `gradle-wrapper.jar` by name and that is
  the only tracked binary here (the listing PNGs are images, which it exempts explicitly); it
  fails a `package.json`/`Cargo.toml`/`pubspec.yaml` that has no lockfile beside it, and this
  repository has none of the three; and it rejects any `maven { url = … }` outside its allowlist,
  which is why `settings.gradle.kts` should keep naming only `google()`, `mavenCentral()` and
  `gradlePluginPortal()`.

## 3. Listing text and images

F-Droid reads `fastlane/metadata/android/<locale>/` from this repository — the same directory
the Play listing uses, so there is one copy of the text and it is translated 26 ways.

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
silently rather than rejecting, so an over-long Finnish summary is a bug nobody would ever notice
by using the app.

Two details specific to F-Droid:

- A changelog only appears if F-Droid has built that versionCode. `changelogs/1.txt` therefore
  never shows up at all — the recipe's `Builds:` list starts at versionCode 2, for the reason in
  section 6 — while still being what Play serves as versionCode 1's release notes. The file stays
  because the two stores read the same directory, not because F-Droid will ever use it.
- `en-US` is the fallback for every locale that is missing a file, so it is the one that has to
  be complete.

### The images are committed, and that is a deliberate reversal

`docs/play-store.md` used to say nothing generated is committed, because Play takes the images by
upload and a Gradle task reproduces them exactly. F-Droid has no upload: an image that is not in
the repository does not exist. So `fastlane/metadata/android/en-US/images/` is checked in — 628 KB
for the icon, the feature graphic and five phone screenshots.

They are regenerated from the **classic** flavor, since that is the build F-Droid ships and its
settings screen differs from the Play one:

```sh
./gradlew recordRoborazziClassicReleaseTest
cp app/build/outputs/play-listing/classicReleaseTest/en-US/*.png \
   fastlane/metadata/android/en-US/images/phoneScreenshots/
cp app/build/outputs/play-listing/classicReleaseTest/icon-512.png \
   fastlane/metadata/android/en-US/images/icon.png
cp app/build/outputs/play-listing/classicReleaseTest/feature-graphic-1024x500.png \
   fastlane/metadata/android/en-US/images/featureGraphic.png
```

Only English is committed. Screenshots exist for 29 languages and committing all of them would be
about 18 MB of near-identical PNGs in a repository whose entire history F-Droid clones; F-Droid
falls back to `en-US` images the same way it falls back to `en-US` text. Add another locale's
`images/` only if someone asks for it.

## 4. Releasing a version

1. Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
2. Add `fastlane/metadata/android/<locale>/changelogs/<versionCode>.txt` — at minimum `en-US`.
   The `translations` skill covers `strings.xml`, not these; translate them by hand.
3. Re-record the listing images if the UI changed (section 3).
4. Commit, then tag: `git tag -a v<versionName> -m 'v<versionName>' && git push --tags`.
   The tag name must match `UpdateCheckMode: Tags ^v[0-9.]+$` in the recipe, and `versionName`
   must be readable from `app/build.gradle.kts` — F-Droid extracts it with a regex and cannot
   evaluate anything computed.
5. `.github/workflows/android-release.yml` builds the APK and the AAB and publishes the GitHub
   release.
6. Update `Builds:`, `CurrentVersion:` and `CurrentVersionCode:` in
   `metadata/it.eldavo.ylih.yml`, and open the fdroiddata merge request (section 5). The build
   entry's `commit:` is the tag's **full 40-character hash**, not the tag name —
   `git rev-parse v<versionName>^{}` prints it.

`AutoUpdateMode: Version` means F-Droid picks up subsequent tags on its own and files the new
build entry itself, so step 6 is only manual for the first release and whenever the recipe itself
has to change. `fdroid checkupdates` resolves the tag it matched to that same full hash, so the
generated entry has the shape a reviewer asks for without anyone maintaining it.

## 5. The fdroiddata merge request

fdroiddata is on GitLab, so `gh` is no help here and the dev shell carries `glab` instead. It
needs authenticating once, interactively — a browser flow, or a token from
[personal access tokens](https://gitlab.com/-/user_settings/personal_access_tokens) with the
`api` and `write_repository` scopes, the first to open the merge request and the second to push
the branch over https:

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
each metadata file a merge request touches and fails on any diff. So a comment is not a thing the
file can hold: it is a red pipeline. The copy here used to carry a header explaining itself, and
the CI check that was meant to catch this compared the *parsed* YAML, where a comment does not
exist — it passed for as long as the comments were there and the merge request pipeline was what
found them. It now demands the file come back byte-identical.

What those comments said, since a recipe of nine fields is otherwise opaque:

- **No `Summary:` or `Description:`** — F-Droid reads both from `fastlane/metadata/android/`, so
  there is one copy of the listing text and it is translated. See the quirk above.
- **`Categories:`** — `Multimedia` and `Time Tracker`, both from fdroiddata's own
  `config/categories.yml`, which is the whole of the valid list.
- **`AutoName: ylih`** — what `fdroid checkupdates` derives from `android:label` in the merged
  manifest. fdroiddata's `checkupdates` job runs it and fails on any diff, so the recipe has to
  already say it; absent, that job fails with this exact line as the diff. It is not the display
  name — F-Droid shows the fastlane `title.txt`, translated 26 ways.
- **`Binaries:`** — the reproducible-build URL, §6. `%v` is the versionName, and the release
  workflow names the asset after the tag, which is `v` + versionName.
- **`AllowedAPKSigningKeys:`** — the signing certificate's SHA-256, §6 again. An APK signed with
  anything else is refused rather than published, which is the point of the field.
- **No `WebSite:`**, which the recipe carried until a reviewer asked for it to go. It pointed at
  the same GitHub project as `SourceCode:`, and F-Droid renders both, so the app page would have
  offered the identical link twice under two labels. The app has no site of its own; the field
  comes back the day it has one and not before.
- **`commit:` is the tag's full 40-character hash, not the tag name.** The same review asked for
  that, and the reason is that a tag is mutable — `git tag -f` moves it, and this repository has
  moved one, when the fdroiddata pipeline found two problems in v1.1.2 after it was pushed. What
  F-Droid built and what a reviewer read would then be different trees under one name, with
  nothing in the recipe recording that it had happened. A hash cannot move. The tag still matters
  either side of this field: `UpdateCheckMode` matches on it and `Binaries:` is addressed by it.
- **`Builds:` starts at 1.1.3**, not at the first release, and there is nobody to keep the earlier
  ones for: F-Droid published none of them. 1.0.0 could not have verified anywhere, because the
  pin that made a build agree across machines with and without an NDK landed after it. 1.1.2
  removed the need for that pin entirely — the one file it protected was DataStore's prebuilt
  `.so`, and the settings now live in the app's own database — so from there on reproducibility
  rests on nothing but the source. 1.1.3 is where the list begins simply because it landed while
  the merge request was still open, and a list of one is a smaller thing for a reviewer to check
  than a list of two that says the same thing twice.
- **No `sudo:` and no `output:`** — both in §2.

One `fdroid lint` quirk to know about: it checks the `Summary:` field with
`.*[a-z0-9][.!?]( |$)` ("Punctuation should be avoided") and against an 80-character limit. The
recipe deliberately sets no `Summary:` or `Description:` — they come from the fastlane files — so
neither check ever fires. Do not "fix" that by copying the text into the YAML; it would fork the
English listing away from the other 25 languages.

**That silence is a blind spot, not a convenience**, and it hid two things until a reviewer would
have found them: 24 of the 26 `short_description.txt` files ended in a full stop, and two were
exactly 80 characters, which Play allows and the inclusion guide ("less than 80 characters, no
trailing dot") does not. `.github/scripts/listing-metadata-check.py` now enforces both against
the fastlane files themselves, which is the only place that can see them.

It enforces the guide's wording rather than lint's regex, and the gap between the two is worth
knowing: `[a-z0-9][.!?]( |$)` also matches *mid-string* punctuation, so the second sentence in
`Track how many hours each pair of headphones lasts. Offline, forever, private` would trip it
too. Nothing enforces that today — the regex needs a `Summary:` to read, and it only matches
Latin letters and digits, so most of these translations could never trip it anyway. Rewriting 26
translations to drop an internal full stop is a content decision, not a lint fix, so it is left
alone deliberately.

## 6. Reproducible builds

[Reproducible builds](https://f-droid.org/docs/Reproducible_Builds/) let F-Droid publish *our*
signed APK after verifying it matches one it built itself, which means an F-Droid user and a
GitHub-release user can update from either source instead of having to uninstall to switch. It is
not needed for the first submission — F-Droid signs with its own key either way — but v1.0.0 is
signed and published, so the recipe points at it and asks for verification from the start.

### What the build already guarantees

Determinism is the prerequisite, and it holds. Two clean `assembleClassicRelease` builds of the
same commit produce a byte-identical APK:

```sh
./gradlew clean assembleClassicRelease && sha256sum app/build/outputs/apk/classic/release/*.apk
./gradlew clean assembleClassicRelease && sha256sum app/build/outputs/apk/classic/release/*.apk
```

Every zip entry carries one fixed timestamp rather than the build clock. Dropping the debug-key
fallback is what made this possible at all — that key is generated per machine, so no two
machines could ever have agreed.

R8 runs on the release build (see the `optimization` block in `app/build.gradle.kts`) and does
not threaten this: it renames deterministically from the input program, and the R8 that runs is
the one AGP bundles, which the version catalogue pins. It would only become a hazard if someone
supplied `-obfuscationdictionary` with a generated file, or let the AGP version float. The check
above was re-run across the change and both builds still agree.

Three things do vary and are worth knowing before a verification failure sends you hunting:

- **Native libraries, which this app has despite writing no native code.** The APK ships eight
  `.so` files pulled in by dependencies: `libandroidx.graphics.path.so` and
  `libdatastore_shared_counter.so`, one of each per ABI. AGP's `stripDebugSymbols` strips them
  with the NDK's `strip` — *if the build machine has an NDK it can use*, and silently copies them
  through unstripped if it does not. `libandroidx.graphics.path.so` arrives already stripped and
  is identical either way; `libdatastore_shared_counter.so` does not.

  The gap is measured, not estimated: unzip the published v1.0.0 APK, which the release runner
  built, and `libdatastore_shared_counter.so` is 7784 / 5916 / 6124 / 7336 bytes across
  arm64-v8a, armeabi-v7a, x86 and x86_64. Build the same tag in the nix dev shell, which has no
  NDK, and it is 10360 / 8432 / 7976 / 9424 — the sizes the AAR ships, copied through untouched.
  8.8 KB over the four ABIs. Two builds on one machine agree, which is why this went unnoticed,
  and why CI does not catch it either: both sides of the `fdroid build` comparison in section 7
  are GitHub runners, so both are stripped and both match.

  `app/build.gradle.kts` therefore keeps that library's symbols
  (`packaging { jniLibs { keepDebugSymbols += "**/libdatastore_shared_counter.so" } }`), which
  picks the one answer every machine can give — the unstripped file — for ~10 KB. **The fix is
  not in v1.0.0**, whose published asset is the stripped variant, so that tag could only verify
  on a machine whose NDK situation happened to match the release runner's. **v1.1.0 is the first
  tag that carries it**, and that is why the recipe's `Builds:` list starts there rather than at
  the first release: F-Droid never published v1.0.0, so there is nobody to keep it for, and
  leaving it out means every version F-Droid ever serves is one that reproduces anywhere.

- AGP embeds `META-INF/version-control-info.textproto`, holding the git revision. Its
  `local_root_path` is normalised to `$PROJECT_DIR`, so it is not machine-specific, but it does
  mean **two builds of different commits never match** even when the sources are identical. F-Droid
  builds the commit the recipe names and so gets the same value the release workflow did.
- The JDK. F-Droid's buildserver compiles with Debian's `default-jdk-headless`; the release
  workflow uses Temurin 21 and the nix shell OpenJDK 21. Almost everything here is Kotlin, whose
  compiler is pinned by the wrapper, so the exposure is small — but this is the axis that cannot be
  tested from this repository, and it is the usual reason a first verification attempt fails.

### How it was enabled

1. The signing key was created and the `ANDROID_SIGNING_*` secrets set, so the release asset is
   `ylih-v1.0.0-classic.apk` rather than `…-classic-unsigned.apk`. See "Release signing" in
   `README.md` for the `keytool` invocation.
2. Tagging v1.0.0 ran the `Signing certificate fingerprint` step in `android-release.yml`, which
   prints the certificate SHA-256 in exactly the format `AllowedAPKSigningKeys` wants — lowercase
   hex, no colons — so it was read out of the job log rather than off the machine holding the key.
3. `Binaries:` and `AllowedAPKSigningKeys:` in `metadata/it.eldavo.ylih.yml` now carry that URL and
   that fingerprint. Both are app-level fields rather than per-build ones, so a version bump does
   not touch them: `%v` expands to the versionName, and the fingerprint only changes if the signing
   key does — at which point F-Droid refuses the new APK until this line is updated too, which is
   the protection the field exists for.

F-Droid then builds from source, downloads the release asset, copies the signature across with
`apksigcopier` and compares. The source build being unsigned is not a problem for this — it is what
the tooling expects.

One consequence to accept knowingly: this makes the GitHub-release APK and the F-Droid APK
interchangeable, but neither is interchangeable with the Play build, which Play App Signing signs
with Google's key. That is already true today and is not something the recipe can fix.

## 7. Running F-Droid's checks in CI

Everything above describes a feedback loop measured in days: the recipe is a copy of a file that
lives somewhere else, nothing in the ordinary build reads it, and getting it wrong surfaces as a
merge request review. `.github/workflows/fdroid.yml` closes that loop by running fdroidserver's
own tools rather than an approximation of them. Three jobs:

- **recipe** — `fdroid readmeta`, `fdroid lint`, and a check that `fdroid rewritemeta` changes
  nothing at all, plus `.github/scripts/fdroid-recipe-check.py` for the part lint cannot
  know: whether the recipe still describes this repository (tags that exist, changelogs that
  exist, flavors that exist, a `CurrentVersion` matching the newest build entry).
- **scanner** — `fdroid scanner`, the source scan F-Droid runs before it will build anything:
  tracked binaries, dependency files with no lockfile, maven repositories off its allowlist.
- **build** — `fdroid build`, which checks out the tag, deletes the Gradle wrapper, strips
  `signingConfigs` out of `build.gradle.kts`, builds through `gradlew-fdroid`, and — because
  `Binaries:` is set — downloads the published APK and compares. Then `fdroid verify` runs a
  comparison standalone, against a *different* APK: it fetches
  `f-droid.org/repo/<package>_<versionCode>.apk`, falling back to `/archive`, rather than the URL
  `Binaries:` names. Until F-Droid publishes ylih there is nothing at either address, and
  fdroidserver reports that 404 as "NOT verified" — which reads as "the release stopped
  reproducing" and is not that. The step tells the two apart and treats only the 404 as a skip, so
  the reproducibility check that runs today is the `Binaries:` one inside `fdroid build`.

  **`fdroid build` exits 0 whether or not it built anything.** A failed build is a log line
  reading `1 build failed`, and the process still returns success — so the step has to assert the
  outcome itself, which it does by grepping the log and requiring an APK in `unsigned/`. It did
  not, and read green through a build that had failed on dependency verification while the
  merge request pipeline was failing on the same commit.

Both `scanner` and `build` clone the commit the recipe names, so on a pull request they say nothing
about the change under review. That is why the triggers are path-filtered and why there is a
weekly run: the interesting failures here come from things outside this repository moving.

Two setup details are load-bearing, both handled by `.github/scripts/fdroid-workdir.sh`:

- fdroidserver only runs from a directory shaped like fdroiddata, and it must be a **git**
  repository — `fdroid build` reads `SOURCE_DATE_EPOCH` off the commit that last touched
  `metadata/<appid>.yml`, and with no git repository that returns `None` and the build dies
  inside `os.environ` with `str expected, not NoneType`.
- **`gradlew-fdroid` has to come from its own repository**, not from the fdroidserver release.
  F-Droid deletes our wrapper and builds with this instead, resolving the Gradle version from
  `distributionUrl` against a transparency log of known checksums. It was split out of
  fdroidserver, and the copy still bundled in the 2.4.5 release is the old bash one, whose
  hardcoded table stops at Gradle 8.14.2 — it refuses to build this app with `No hash for gradle
  version 9.7.0! Exiting...`. The standalone version knows 9.7.0, and cloning it is what the
  buildserver itself does (`buildserver/provision-gradle`). A Gradle wrapper bump that lands
  before the transparency log has the new version would fail F-Droid the same way, which is why
  `gradle-wrapper.properties` is one of the paths that triggers this workflow.

The jobs need no secrets and the build one needs no preinstalled platform 37 or build-tools
37.0.0 — it deliberately omits them so that AGP's own SDK download, which §2 explains the recipe
depends on, is exercised rather than assumed. A step afterwards fails the job if they did not
appear.

## 8. Pre-flight checklist

- [ ] `versionCode` bumped; `en-US/changelogs/<versionCode>.txt` written and under 500 chars
- [ ] `python3 .github/scripts/listing-metadata-check.py fastlane/metadata/android <versionCode>`
- [ ] Listing images re-recorded from `recordRoborazziClassicReleaseTest` and eyeballed — they contain
      live demo data, so a UI regression shows up as a bad image rather than a failing test
- [ ] `./gradlew lintClassicReleaseTest testClassicReleaseTestUnitTest assembleClassicRelease` passes
- [ ] The release APK really is `app-classic-release-unsigned.apk` when no keystore is configured
- [ ] Tag pushed, matching `^v[0-9.]+$` and equal to `versionName`
- [ ] `metadata/it.eldavo.ylih.yml` updated with the new build entry, `commit:` carrying the
      tag's full hash from `git rev-parse v<versionName>^{}` rather than the tag name
- [ ] The `F-Droid` workflow green on the release commit — it runs readmeta, lint, rewritemeta,
      scanner, `fdroid build` and `fdroid verify`, so a green run is the fork's `fdroid lint`
      and build already answered (section 7)
- [ ] `fdroid lint it.eldavo.ylih` clean in the fdroiddata fork
