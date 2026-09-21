buildscript {
    dependencies {
        // Lifts the Bouncy Castle AGP brings for APK signing past its advisories. A BOM rather
        // than one module, since bcprov, bcpkix and bcutil must stay the same version.
        classpath(platform(libs.bouncycastle.bom))
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.roborazzi) apply false
}
