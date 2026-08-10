import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
    id("androidx.baselineprofile")
}

android {
    namespace = "com.engabd.sendpin"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.engabd.sendpin"
        minSdk = 31
        targetSdk = 36
        versionCode = 33
        versionName = "0.8.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Signed with the debug key so a minified build is installable without a
            // release keystore. This is the build that should be judged for smoothness:
            // a debug build carries Compose composition tracing, skips R8 entirely, and
            // runs debuggable, which suppresses most of ART's optimisation. Testing
            // scroll performance on one measures the build, not the app.
            signingConfig = signingConfigs.getByName("debug")
            // Installs a throwaway copy alongside the real app, with its own empty
            // data directory:
            //
            //   ./gradlew :app:installRelease -PsideBySide
            //
            // The only way to test a genuine first-run path — onboarding, an empty
            // DataStore, no baseline profile yet — is a fresh install, and doing that
            // on the real package destroys the user's server, credentials and
            // downloads. This gets the same clean state without touching them.
            if (project.findProperty("sideBySide") != null) {
                applicationIdSuffix = ".freshtest"
                versionNameSuffix = "-freshtest"
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin { compilerOptions { jvmTarget = JvmTarget.JVM_17 } }

    // buildConfig: the About screen reads the version from BuildConfig rather than
    // carrying a hand-typed copy that goes stale between releases.
    buildFeatures { compose = true; buildConfig = true }

    // The native AAudio-I24 / libFLAC pipeline (src/main/cpp) is kept for the
    // future bit-perfect phase that bypasses the Android mixer, but is NOT built:
    // nothing in Kotlin loads it, so switching the NDK on would add a toolchain
    // download to every CI run and an unused .so to every APK. Hi-res today goes
    // through MediaCodec + AudioTrack — see SendspinAudioEngine.bitPerfect.
    // Re-enable the externalNativeBuild { cmake { … } } block here when the
    // native output path actually has a caller.
}

// Compose stability/skippability reports, off by default because writing them costs
// build time on every compile:
//
//   ./gradlew :app:compileReleaseKotlin -PcomposeMetrics
//   app/build/compose_reports/app_release-composables.txt
//
// The library rows are the reason this is here. A list item that reports as
// `restartable skippable` re-uses its composition while scrolling; one that doesn't
// is rebuilt from scratch every time anything on the screen changes, which is what
// made scrolling feel unsettled. Check here before assuming a stability fix took.
composeCompiler {
    if (project.findProperty("composeMetrics") != null) {
        reportsDestination = layout.buildDirectory.dir("compose_reports")
        metricsDestination = layout.buildDirectory.dir("compose_metrics")
    }
}

dependencies {
    // Carries Material3 1.4 — the Expressive release. The motion scheme, the wavy
    // progress indicators and the shape morphing the UI now leans on are all in it.
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)

    // core 1.17+ and lifecycle 2.10+ are built against compileSdk 37 and demand AGP 9.1,
    // so these two stop here while the app stays on AGP 8.x / compileSdk 36. Nothing
    // else in the tree is held back by it — the Compose BOM below, and with it
    // Material3 Expressive, resolves fine at 36.
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-process:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.navigation:navigation-compose:2.9.8")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Material Components library — provides Theme.Material3.NoActionBar (the XML
    // theme parent) used by themes.xml. The Compose material3 artifact handles the
    // Compose layer; this handles the pre-Compose window so the XML theme's parent
    // matches the Compose MaterialTheme.
    implementation("com.google.android.material:material:1.12.0")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.palette:palette-ktx:1.0.0")
    // Installs the baseline profile at first run. Without it the generated profile is
    // packaged and then ignored, so this is not optional dressing — it is the half that
    // does the work on device.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    // Names :baselineprofile as the producer of this module's profile. Without it the
    // plugin applies cleanly, `generateBaselineProfile` runs, and nothing is generated
    // — it has no dependency telling it where profiles come from.
    baselineProfile(project(":baselineprofile"))
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("androidx.media:media:1.7.0")
    // The Navidrome/offline player. MediaPlayer could not do gapless reliably
    // (setNextMediaPlayer is OEM-dependent), reported nothing about the format it
    // was decoding, and had no stage to apply ReplayGain in.
    //
    // Was pinned to 1.8.0 because 1.9+ needs compileSdk 36 and AGP 8.7.3 topped out
    // at 35. Both moved in the same change that brought Material3 Expressive in, so
    // the pin is gone.
    implementation("androidx.media3:media3-exoplayer:1.10.1")
    implementation("androidx.media3:media3-session:1.10.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // JVM unit tests (pure protocol/clock logic): ./gradlew :app:testDebugUnitTest
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}
