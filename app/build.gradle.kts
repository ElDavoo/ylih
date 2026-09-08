@file:OptIn(com.github.takahirom.roborazzi.ExperimentalRoborazziApi::class)

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
        minSdk = 26
        targetSdk = 37
        versionCode = 7
        versionName = "1.4.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // AGP writes the dependency list into the APK's signing block, encrypted with a Google Play
    // key. F-Droid's scanner rejects that — `check apk`: "found extra signing block 'Dependency
    // metadata'" — an opaque, Google-only blob being unauditable. It was 7246 bytes of the v1.1.0
    // APK. The bundle keeps it: Play reads it to warn about vulnerable dependencies, and nobody
    // reproduces the AAB.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = true
    }

    // Same app, two distributions, one application id — a sideloaded and a Play install are one
    // app, not two with split history.
    flavorDimensions += "distribution"
    productFlavors {
        create("classic") {
            dimension = "distribution"
            // Everything the platform allows, distributed as an APK from GitHub Releases.
        }
        create("play") {
            dimension = "distribution"
            // Google Play build. See app/src/play/java/.../Distribution.kt for what's dropped
            // and why; the manifest diff is in app/src/classic/AndroidManifest.xml.
        }
    }

    // Release signing reads env vars so CI can inject a keystore. Without them the release build
    // is left *unsigned* rather than falling back to the debug key: F-Droid builds from source
    // with no keystore of ours and signs the result itself, and a debug key generated fresh per
    // machine is unreproducible and something apksigner would have to strip back off.
    val signingKeystorePath = System.getenv("ANDROID_SIGNING_KEYSTORE_PATH")
    val signingStorePassword = System.getenv("ANDROID_SIGNING_STORE_PASSWORD")
    val signingKeyAlias = System.getenv("ANDROID_SIGNING_KEY_ALIAS")
    val signingKeyPassword = System.getenv("ANDROID_SIGNING_KEY_PASSWORD")

    // All four or none. Three of four set — a typo'd secret name, a variable that never reached
    // the job — used to skip the block silently, publishing an unexplained `*-unsigned.apk`.
    val signingVars = mapOf(
        "ANDROID_SIGNING_KEYSTORE_PATH" to signingKeystorePath,
        "ANDROID_SIGNING_STORE_PASSWORD" to signingStorePassword,
        "ANDROID_SIGNING_KEY_ALIAS" to signingKeyAlias,
        "ANDROID_SIGNING_KEY_PASSWORD" to signingKeyPassword,
    )
    val signingProvided = signingVars.filterValues { !it.isNullOrBlank() }.keys
    require(signingProvided.isEmpty() || signingProvided.size == signingVars.size) {
        "Release signing needs all four ANDROID_SIGNING_* variables or none. " +
            "Missing: ${signingVars.keys - signingProvided}"
    }

    if (signingProvided.size == signingVars.size) {
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
            // Play Console reports an unoptimized upload ("No R8 metadata included"), but
            // shrinking is worth it: Compose and Room are most of the dex, barely reachable from
            // this app.
            //
            // AGP 9.3's optimization block replaces isMinifyEnabled + isShrinkResources and
            // supplies the platform keep rules
            // proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt")) used to —
            // that call is deleted rather than left a no-op. Keep rules now live in
            // src/main/keepRules/*.keep, which AGP 9.3 reads.
            optimization {
                enable = true
            }
            signingConfig = releaseSigningConfig
        }

        // Everything `release` is, plus what a test run needs and a shipped build must not have.
        //
        // Not `release` itself: AGP binds the whole test suite, unit tests included, to
        // testBuildType, and the Compose tests use createAndroidComposeRule<ComponentActivity>,
        // needing the activity ui-test-manifest contributes to the merged manifest. Against
        // `release` that fails 197 unit tests with "Unable to resolve activity", and fixing it
        // there would ship a test activity to users. A build type of its own takes the dependency
        // (releaseTestImplementation below) without the shipped APK seeing it.
        create("releaseTest") {
            // Minification, resource shrinking and signing all come from release, so tests
            // install what ships plus only the test manifest entry.
            initWith(getByName("release"))
            // No library modules today, but a build type with no match fails confusingly later.
            matchingFallbacks += "release"
            // Unit tests live on this build type now, so JaCoCo instruments them here — it's what
            // gives AGP a create<Variant>UnitTestCoverageReport task to hang the report off.
            enableUnitTestCoverage = true
        }
    }

    // Where src/androidTest points, and why releaseTest exists: R8 only runs for release, so
    // this is the only way to run a minified APK's test suite on a device. src/test used to ride
    // along; since android.onlyEnableUnitTestForTheTestedBuildType went off in
    // gradle.properties, unit tests have their own variant per build type and ignore this.
    //
    // -Pylih.testBuildType=debug points the same source set at an unminified build — the
    // comparison is the point: a failure only when minified is R8's doing, one in both is a bug
    // in the app or test. Both came up the day this landed: two linkage errors only when
    // minified, and a missing backup formatVersion broken since 1.0.0. CI runs both; see
    // .github/workflows/android-ci.yml.
    testBuildType = providers.gradleProperty("ylih.testBuildType").getOrElse("releaseTest")

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources {
        // Generates res/xml/locales_config and android:localeConfig from the values-* folders, so
        // the app appears under Settings > System > Languages > App languages. The list derives
        // from the translations — a new values-xx folder is enough. Needs
        // res/resources.properties to name the locale the unqualified values/ folder holds.
        generateLocaleConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true

            all {
                // Gradle's Test task defaults to -Xmx512m; 571 tests in one JVM, almost all
                // Robolectric holding a simulated framework, a parsed resource table, and — in
                // the widget tests — real bitmaps, outgrew it. The failure isn't clean: at 320m
                // it's an OutOfMemoryError naming whatever test was running; a little above, the
                // heap is merely *full* and the JVM spends the run collecting, so one arbitrary
                // test times out — measured at 448m, where the suite still passed 570 of 571 but
                // took 9m14s instead of 2m45s. A CI machine with slightly less headroom produces
                // exactly that from the same 512m: a different test each time, none actually
                // broken. 1g is roughly double the observed peak; the two unit test tasks run
                // sequentially, so it's one gigabyte, not two.
                it.maxHeapSize = "1g"

                // Robolectric loads classes under test through its own sandbox classloader, so
                // they reach the JaCoCo agent with no code-source location and are dropped unless
                // this is set. Without it the report shows ~2% — only stats/Stats.kt, the one
                // package with plain JVM tests — while everything Robolectric touches reads as
                // zero. jdk.internal is excluded since the agent can't instrument it and warns
                // every run.
                //
                // findByType, not configure: only releaseTest sets enableUnitTestCoverage, so
                // it's the only build type whose unit test task AGP gives a JaCoCo extension. The
                // uninstrumented debug suite runs beside it with none, and `configure` throws on
                // those — surfacing as the useless "Could not create task
                // ':app:testClassicDebugUnitTest'".
                it.extensions.findByType(JacocoTaskExtension::class.java)?.apply {
                    isIncludeNoLocationClasses = true
                    excludes = listOf("jdk.internal.*")
                }
            }
        }
    }

    testCoverage {
        // AGP's default predates the JDK 21 the dev shell pins; 0.8.13 is the first release that
        // reads Java 21+ class files without falling over on instrumentation.
        jacocoVersion = libs.versions.jacoco.get()
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }

        // No jniLibs block. It used to keep libdatastore_shared_counter.so's symbols: AGP's
        // stripDebugSymbols strips a .so only if the build machine has an NDK, copying it through
        // untouched otherwise — so the APK's bytes depended on something no source tree recorded.
        // Dropping DataStore for a Room table removed the file and the problem. The one .so still
        // shipped, libandroidx.graphics.path.so, arrives already stripped and is byte-identical
        // either way.
    }

    bundle {
        language {
            // Play would otherwise install only the system language's resources and fetch the
            // rest on demand, and the app's own language setting (AppLocale, what Android 12 and
            // below get instead of a per-app language) can't request a split. Picking a language
            // the system isn't set to is the point, so every translation must be in the install.
            enableSplit = false
        }
    }

    lint {
        // Every check lint has is on, including ones that ship disabled, and a warning fails the
        // build like an error would. Checks that can't hold here are turned off with a reason in
        // app/lint.xml rather than tolerated or hidden behind a baseline.
        checkAllWarnings = true
        warningsAsErrors = true
        abortOnError = true
        // The Roborazzi listing generators and repository tests are real code with real resource
        // and API usage, so they're checked too.
        checkTestSources = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        // Same bargain as lint's warningsAsErrors: a compiler warning is a finding too, and the
        // usual way one lands is a Dependabot bump deprecating something — failing that PR is the
        // point, while the change is still small.
        allWarningsAsErrors.set(true)
        // Material 3 still marks staples like TopAppBar experimental; opting in once here keeps
        // annotation noise out of every screen.
        freeCompilerArgs.add("-opt-in=androidx.compose.material3.ExperimentalMaterial3Api")
    }
}

ksp {
    // Room's generated schema history, committed so migrations can be diffed in review.
    arg("room.schemaLocation", "$projectDir/schemas")
}

roborazzi {
    // Not a golden-image baseline: this is the Play Console upload set, written straight into a
    // directory the release workflow hands to actions/upload-artifact. See docs/play-store.md.
    outputDir.set(layout.buildDirectory.dir("outputs/play-listing").get().asFile)

    // Every record task otherwise shares one output directory. Running the classic and play
    // record tasks in one Gradle invocation then races on it, which Gradle 9 turns into a hard
    // "Cannot access input property 'roborazziImageInput'" failure rather than a warning.
    separateOutputDirs.set(true)
}

// KSP registers its output through kotlin.sourceSets (see android.disallowKotlinSourceSets in
// gradle.properties). AGP 9's lint tasks read every variant's generated directory without
// declaring a dependency on the task that writes it, so `lint` can race `kspReleaseKotlin` and
// fail on a missing DeviceDao_Impl.kt. Make the wiring explicit.
val kspTasks = tasks.matching { it.name.startsWith("ksp") && it.name.endsWith("Kotlin") }
tasks.matching { it.name.startsWith("lint") }.configureEach {
    dependsOn(kspTasks)
}

// Turning off onlyEnableUnitTestForTheTestedBuildType hands *every* build type a unit test
// variant, `release` included — and release must never carry ui-test-manifest, since that would
// ship a test activity to users. Without it, createAndroidComposeRule can't resolve an activity
// and the Compose tests fail wholesale. The variant is unusable by construction, not merely
// unused, so take the task away instead of leaving one that detonates on the first
// `./gradlew check`. debug and releaseTest are the two meant to run.
tasks.matching { it.name.matches(Regex("test(Classic|Play)ReleaseUnitTest")) }.configureEach {
    enabled = false
}

// F-Droid's review bot reads `gradle/verification-metadata.xml` and flagged
// `io.opencensus:opencensus-api` and `opencensus-proto` as trackers. They aren't the app's:
// `com.google.testing.platform:core` declares them — AGP's Unified Test Platform, the host-side
// harness for connectedAndroidTest — and the metadata file checksums every artifact the build
// resolves, tooling included, not just what ships. Neither reaches
// `classicReleaseRuntimeClasspath`, and the published APK references neither.
//
// The flag was cosmetic, but the cheapest fix is to stop resolving them: UTP doesn't load these
// at runtime — the emulator legs of CI run the instrumented suite on both a minified and
// unminified APK, proving it — so excluding them keeps them out of the resolved graph and the
// checksum file entirely.
configurations.configureEach {
    exclude(group = "io.opencensus")
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
    implementation(libs.androidx.compose.material3.adaptive)
    implementation(libs.androidx.compose.material.icons.core)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // The second KSP processor. It reads agent/YlihAppFunctions.kt and writes the concrete
    // service the manifest names, plus the schema XML in assets/; the lint->ksp wiring below
    // already matches every ksp*Kotlin task, so it needs no edit.
    implementation(libs.androidx.appfunctions)
    ksp(libs.androidx.appfunctions.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    // The one place a synchronous Room-backed WorkManager failure can't be produced otherwise:
    // WidgetRolloverWorkerTest mocks the top-level scheduleWidgetRollover to throw, since closing
    // WorkManagerTestInitHelper's own database doesn't propagate the failure to the caller.
    testImplementation(libs.mockk)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.room.runtime)
    testImplementation(libs.kotlinx.coroutines.test)
    // TrackingController schedules and cancels the heartbeat through WorkManager, so testing it
    // needs a WorkManager that initialises without its androidx.startup provider and runs workers
    // on the test thread.
    testImplementation(libs.androidx.work.testing)
    // Composes a GlanceAppWidget to a node tree off-device — the only way anything here exercises
    // the widgets, since RemoteViews are built in our process but rendered in the launcher's.
    testImplementation(libs.androidx.glance.appwidget.testing)

    // Store-listing asset generation (StoreScreenshots). Roborazzi's capture calls are inert
    // unless a record/verify task turns them on, so these ride along doing nothing in normal
    // unit-test runs.
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    // createAndroidComposeRule needs an activity to launch, and ui-test-manifest contributes
    // ComponentActivity to the merged manifest. Scoped to releaseTest — the build type unit tests
    // run on — so it can't reach the shipped release APK.
    "releaseTestImplementation"(libs.androidx.compose.ui.test.manifest)
    // And on debug, which now runs the same suite uninstrumented (gradle.properties). A debug APK
    // is a development convenience, not something shipped, so this is ordinary
    // debugImplementation rather than a build type invented to hold it.
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    // The instrumented suite. Deliberately thin: runs on an emulator against the minified APK,
    // holding only what a Robolectric test can't answer. Room, coroutines and the app's own
    // classes come from the variant under test — AGP minifies this APK with the app's mapping
    // applied, letting these tests call app code by name at all.
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.runner)
    // GrantPermissionRule: launching MainActivity on API 33+ would otherwise race a permission
    // dialog, and a dialog on top is the difference between RESUMED and not.
    androidTestImplementation(libs.androidx.test.rules)
}
