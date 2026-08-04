plugins {
    id("com.android.test")
    id("org.jetbrains.kotlin.android")
    id("androidx.baselineprofile")
}

android {
    namespace = "com.engabd.sendpin.baselineprofile"
    compileSdk = 36

    defaultConfig {
        // Baseline profile generation needs a device that can be root-shelled or a
        // userdebug emulator; API 28 is the floor the tooling supports.
        minSdk = 31
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin { compilerOptions { jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17 } }

    targetProjectPath = ":app"
}

// Generation runs on a real device or emulator, never in CI here:
//
//   ./gradlew :app:generateBaselineProfile
//
// It drives the app through the journeys in LibraryScrollProfile and writes the
// result to app/src/release/generated/baselineProfile/. Re-run it after any UI change
// large enough to move which classes load on startup, or the profile will describe
// code paths the app no longer takes.
baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation("androidx.test.ext:junit:1.3.0")
    implementation("androidx.test.espresso:espresso-core:3.6.1")
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")
    implementation("androidx.benchmark:benchmark-macro-junit4:1.4.1")
}
