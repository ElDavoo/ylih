@file:OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)

import com.android.build.gradle.internal.tasks.L8DexDesugarLibTask
import org.gradle.testing.jacoco.plugins.JacocoTaskExtension
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    // No kotlin-android plugin: AGP 9 compiles Kotlin itself and rejects it.
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.roborazzi)
}

android {
    namespace = "it.eldavo.ylih"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "it.eldavo.ylih"
        minSdk = 23
        targetSdk = 37
        versionCode = 3
        versionName = "1.1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // AGP writes the dependency list into the APK's signing block, compressed and encrypted with
    // a Google Play signing key. F-Droid's scanner rejects that outright — `check apk` fails with
    // "found extra signing block 'Dependency metadata'" — and it is hard to argue with: an opaque
    // blob only Google can read is exactly what a build nobody can audit looks like. It was 7246
    // bytes of the v1.1.0 APK.
    //
    // The bundle keeps it, because that is where it does the job it exists for: Play reads it to
    // warn about vulnerable dependencies, and the AAB is not something anyone reproduces.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = true
    }

    // Same app, two distributions. The application id is deliberately identical so a sideloaded
    // install and a Play install are the same app rather than two copies with split history.
    flavorDimensions += "distribution"
    productFlavors {
        create("classic") {
            dimension = "distribution"
            // Everything the platform allows, distributed as an APK from GitHub Releases.
        }
        create("play") {
            dimension = "distribution"
            // Google Play build. See app/src/play/java/.../Distribution.kt for what is dropped
            // and why; the manifest difference lives in app/src/classic/AndroidManifest.xml.
        }
    }

    // Release signing is driven by env vars so CI can inject a keystore. Without them the
    // release build is left *unsigned* rather than falling back to the debug key: F-Droid
    // builds from source on a machine that has no keystore of ours and signs the result
    // itself, and a debug key is both generated fresh per machine — which makes the APK
    // unreproducible — and something apksigner would then have to strip back off.
    val signingKeystorePath = System.getenv("ANDROID_SIGNING_KEYSTORE_PATH")
    val signingStorePassword = System.getenv("ANDROID_SIGNING_STORE_PASSWORD")
    val signingKeyAlias = System.getenv("ANDROID_SIGNING_KEY_ALIAS")
    val signingKeyPassword = System.getenv("ANDROID_SIGNING_KEY_PASSWORD")

    if (!signingKeystorePath.isNullOrBlank() &&
        !signingStorePassword.isNullOrBlank() &&
        !signingKeyAlias.isNullOrBlank() &&
        !signingKeyPassword.isNullOrBlank()
    ) {
        signingConfigs {
            create("release") {
                storeFile = file(signingKeystorePath)
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }

    val releaseSigningConfig = signingConfigs.findByName("release")

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }

        release {
            // Play Console reports an unoptimized upload — "No R8 metadata included" — and the
            // shrinking is worth having on its own: Compose and Room are most of the dex and
            // almost none of either is reachable from this app.
            //
            // AGP 9.3's optimization block replaces isMinifyEnabled + isShrinkResources and
            // carries the platform keep rules that
            // proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt")) used to
            // supply, so that call is deleted rather than left as a no-op. Keep rules now live
            // in src/main/keepRules/*.keep, which is the source set AGP 9.3 reads them from.
            optimization {
                enable = true
            }
            signingConfig = releaseSigningConfig
        }

        // Everything `release` is, plus the one thing a test run needs and a shipped build must
        // not have. R8 is only worth testing if the tests run against what R8 produced, and
        // `testBuildType` is the only way to point androidTest at a minified build.
        //
        // Why not point it straight at `release`: AGP binds the *whole* test suite to
        // testBuildType, unit tests included, and the Compose tests use
        // createAndroidComposeRule<ComponentActivity> — which needs the activity that
        // ui-test-manifest contributes to the merged manifest. Against `release` that fails 197
        // unit tests with "Unable to resolve activity", and the only way to satisfy it there
        // would be shipping a test activity to users. A build type of its own takes the
        // dependency (see releaseTestImplementation below) without the shipped APK seeing it.
        create("releaseTest") {
            // Minification, resource shrinking and signing all come from release, so what the
            // tests install differs from what ships only by the test manifest entry.
            initWith(getByName("release"))
            // Nothing here has library modules today, but a build type with no match in one is
            // the kind of thing that fails confusingly later.
            matchingFallbacks += "release"
            // The unit tests live on this build type now, so this is where JaCoCo has to
            // instrument them — it is what gives AGP a create<Variant>UnitTestCoverageReport
            // task to hang the coverage report off.
            enableUnitTestCoverage = true
        }
    }

    // Where src/androidTest points, and the whole reason releaseTest exists: R8 only runs for a
    // release build, so this is the only way to install a minified APK on a device and run the
    // suite against it. src/test used to be dragged along with it; since
    // android.onlyEnableUnitTestForTheTestedBuildType went off in gradle.properties the unit
    // tests have their own variant per build type and no longer care what this says.
    //
    // -Pylih.testBuildType=debug aims the same androidTest source set at an unminified build.
    // Nothing in the suite is written for one or the other — the point is the comparison. Run
    // both and a failure that only happens minified is R8's doing, while one that happens in
    // both is a bug in the app or the test. Both halves of that came up the day this landed: two
    // linkage errors that only existed minified, and a missing backup formatVersion that had
    // been broken in every build since 1.0.0. CI runs both; see .github/workflows/android-ci.yml.
    testBuildType = providers.gradleProperty("ylih.testBuildType").getOrElse("releaseTest")

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        // Generates res/xml/locales_config and android:localeConfig from the values-* folders,
        // which is what makes the app appear under Settings > System > Languages > App languages.
        // The locale list is derived from the translations, so a new values-xx folder is enough.
        // Needs res/resources.properties to name the locale the unqualified values/ folder holds.
        generateLocaleConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // java.time (ZoneId etc., used throughout stats/Stats.kt) is only native from API 26;
        // desugaring backports it to the API 23 floor instead of rewriting that arithmetic.
        isCoreLibraryDesugaringEnabled = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true

            all {
                // Robolectric loads the classes under test through its own sandbox classloader,
                // so they arrive at the JaCoCo agent with no code-source location and are dropped
                // unless this is set. Without it the report shows ~2% — only stats/Stats.kt, the
                // one package with plain JVM tests — while everything Robolectric touches
                // silently reads as zero. jdk.internal is excluded because the agent cannot
                // instrument it and warns on every run.
                //
                // findByType, not configure: only releaseTest sets enableUnitTestCoverage, so it
                // is the only build type whose unit test task AGP gives a JaCoCo extension, and
                // since the debug suite runs uninstrumented beside it this block now sees tasks
                // that have none. `configure` throws on those, which surfaces as the useless
                // "Could not create task ':app:testClassicDebugUnitTest'".
                it.extensions.findByType(JacocoTaskExtension::class.java)?.apply {
                    isIncludeNoLocationClasses = true
                    excludes = listOf("jdk.internal.*")
                }
            }
        }
    }

    testCoverage {
        // AGP's default is older than the JDK 21 the dev shell pins; 0.8.13 is the first release
        // that reads Java 21+ class files without falling over on instrumentation.
        jacocoVersion = libs.versions.jacoco.get()
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }

        // No jniLibs block. It used to keep libdatastore_shared_counter.so's symbols, because
        // AGP's stripDebugSymbols strips a .so only if the build machine has an NDK and copies it
        // through untouched otherwise — so the APK's bytes depended on something no source tree
        // records. Dropping DataStore for a Room table removed the file and the problem with it.
        // The one .so still shipped, libandroidx.graphics.path.so, arrives already stripped and
        // is byte-identical either way.
    }

    bundle {
        language {
            // Play would otherwise install only the resources for the system language and fetch
            // the rest on demand — and the app's own language setting (see AppLocale, which is
            // what Android 12 and below get instead of a per-app language) has no way to ask for
            // a split. Picking a language the system is not set to is the entire point, so every
            // translation has to be in the install.
            enableSplit = false
        }
    }

    lint {
        // Nothing is allowed to accumulate: every check lint has is on, including the ones that
        // ship disabled, and a warning fails the build exactly like an error does. The handful of
        // checks that genuinely cannot hold here are turned off with a reason in app/lint.xml
        // rather than tolerated as warnings or hidden behind a baseline.
        checkAllWarnings = true
        warningsAsErrors = true
        abortOnError = true
        // The Roborazzi listing generators and the repository tests are real code with real
        // resource and API usage, so they get checked too.
        checkTestSources = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        // Same bargain as lint's warningsAsErrors, on the other half of the build: a compiler
        // warning is a finding too, and the usual way one lands is a Dependabot bump deprecating
        // something. Failing that PR is the point — it says so while the change is still small.
        allWarningsAsErrors.set(true)
        // Material 3 still marks staples like TopAppBar experimental; opting in once here
        // keeps the annotation noise out of every screen.
        freeCompilerArgs.add("-opt-in=androidx.compose.material3.ExperimentalMaterial3Api")
    }
}

ksp {
    // Room's generated schema history, committed so migrations can be diffed in review.
    arg("room.schemaLocation", "$projectDir/schemas")
}

roborazzi {
    // Not a golden-image baseline: this is the Play Console upload set, written straight into a
    // directory the release workflow can hand to actions/upload-artifact. See docs/play-store.md.
    outputDir.set(layout.buildDirectory.dir("outputs/play-listing").get().asFile)

    // Every record task otherwise shares one output directory. Running the classic and play
    // record tasks in a single Gradle invocation then races on it, which Gradle 9 turns into a
    // hard "Cannot access input property 'roborazziImageInput'" failure rather than a warning.
    separateOutputDirs.set(true)
}

// KSP registers its output through kotlin.sourceSets (see android.disallowKotlinSourceSets in
// gradle.properties). AGP 9's lint tasks then read every variant's generated directory without
// declaring a dependency on the task that writes it, so `lint` can race `kspReleaseKotlin` and
// fail on a missing DeviceDao_Impl.kt. Make the wiring explicit.
val kspTasks = tasks.matching { it.name.startsWith("ksp") && it.name.endsWith("Kotlin") }
tasks.matching { it.name.startsWith("lint") }.configureEach {
    dependsOn(kspTasks)
}

// Turning off onlyEnableUnitTestForTheTestedBuildType hands *every* build type a unit test
// variant, `release` included — and release is precisely the one that must never carry
// ui-test-manifest, because that would ship a test activity to users. Without it
// createAndroidComposeRule cannot resolve an activity and the Compose tests fail as a body.
// The variant is unusable by construction rather than merely unused, so take the task away
// instead of leaving one that looks runnable and detonates the first time anyone types
// `./gradlew check`. debug and releaseTest are the two that are meant to run.
tasks.matching { it.name.matches(Regex("test(Classic|Play)ReleaseUnitTest")) }.configureEach {
    enabled = false
}

// The desugared library (`j$.**`, what backports java.time to the minSdk 23 floor) is dexed by
// its own L8 run per variant, and AGP tells L8 to leave names alone only when that variant is
// unminified. releaseTest and the androidTest APK beside it are both minified, so each L8 run
// shrank and renamed the library independently and the two APKs ended up defining 472 of the
// same class names as different classes. Instrumentation loads both dexes into one classloader,
// so the app's own code then resolves j$.util.stream.a to whichever copy came first:
//
//   java.lang.VerifyError: Verifier rejected class j$.util.concurrent.ThreadLocalRandom:
//     'this' argument 'Uninitialized Reference: j$.util.stream.w0'
//     not instance of 'Reference: j$.util.stream.a'
//
// Pinning both runs to the unshrunk, unrenamed library makes the two copies identical, which is
// what every unminified build has always relied on. Matched by task name so it reaches only the
// build type the tests install: l8DexDesugarLibClassicRelease and its play twin keep AGP's
// default, and the shipped APKs are untouched.
tasks.withType<L8DexDesugarLibTask>().configureEach {
    if (name.contains("ReleaseTest")) {
        keepRulesConfigurations.addAll("-dontshrink", "-dontobfuscate", "-dontoptimize")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    coreLibraryDesugaring(libs.android.desugar.jdk.libs)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.room.runtime)
    testImplementation(libs.kotlinx.coroutines.test)
    // TrackingController schedules and cancels the heartbeat through WorkManager, so testing it
    // needs a WorkManager that initialises without its androidx.startup provider and runs its
    // workers on the test thread.
    testImplementation(libs.androidx.work.testing)
    // Composes a GlanceAppWidget to a node tree off-device, which is the only way anything here
    // exercises the widgets: RemoteViews are built in our process but rendered in the launcher's.
    testImplementation(libs.androidx.glance.appwidget.testing)

    // Store-listing asset generation (StoreScreenshots). Roborazzi's capture calls are inert
    // unless a record/verify task turns them on, so these ride along in the normal unit-test run
    // without doing anything.
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    // createAndroidComposeRule needs an activity to launch, and ui-test-manifest is what
    // contributes ComponentActivity to the merged manifest. Scoped to releaseTest — the build
    // type the unit tests run on — precisely so it cannot reach the shipped release APK.
    "releaseTestImplementation"(libs.androidx.compose.ui.test.manifest)
    // And on debug, which now runs the same suite uninstrumented (see gradle.properties). A
    // debug APK is a development convenience rather than something anyone is shipped, so this
    // one is an ordinary debugImplementation rather than a build type invented to hold it.
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // The instrumented suite. Deliberately thin: it runs on an emulator against the minified
    // APK, so it holds only what a Robolectric test cannot answer. Room, coroutines and the app's
    // own classes come from the variant under test — AGP minifies this APK with the app's mapping
    // applied, which is what lets these tests call app code by name at all.
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    // GrantPermissionRule: launching MainActivity on API 33+ would otherwise race a permission
    // dialog, and a dialog on top is the difference between RESUMED and not.
    androidTestImplementation(libs.androidx.test.rules)
}
