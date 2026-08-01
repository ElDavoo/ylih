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
        minSdk = 23
        targetSdk = 37
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
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
            // JaCoCo-instruments the unit tests, which is what gives AGP a
            // create<Variant>UnitTestCoverageReport task to hang the coverage report off.
            enableUnitTestCoverage = true
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
    }

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
                it.extensions.configure(JacocoTaskExtension::class) {
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

        jniLibs {
            // This app writes no native code, but DataStore brings a .so per ABI. AGP's
            // stripDebugSymbols runs the NDK's `strip` over it *if the build machine has an NDK*
            // and silently copies it through unstripped if not, so the APK depends on something
            // no source tree records: the release runner has an NDK, F-Droid's buildserver and
            // this dev shell do not, and the file differs by ~2.5 KB per ABI between them.
            // Reproducibility is the point (docs/fdroid.md §6), so pick the answer every machine
            // can give — keep the symbols — at a cost of ~10 KB. The other library the app ships,
            // libandroidx.graphics.path.so, arrives already stripped and is unaffected either way.
            keepDebugSymbols += "**/libdatastore_shared_counter.so"
        }
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

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
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

    // Store-listing asset generation (StoreScreenshots). Roborazzi's capture calls are inert
    // unless a record/verify task turns them on, so these ride along in the normal unit-test run
    // without doing anything.
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.roborazzi)
    testImplementation(libs.roborazzi.compose)
    testImplementation(libs.roborazzi.junit.rule)
    // createAndroidComposeRule needs an activity to launch, and ui-test-manifest is what
    // contributes ComponentActivity to the debug manifest.
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
