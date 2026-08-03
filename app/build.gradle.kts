plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.engabd.sendpin"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.engabd.sendpin"
        minSdk = 31
        targetSdk = 35
        versionCode = 26
        versionName = "0.4.3"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }

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
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.5")

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
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.media:media:1.7.0")
    // The Navidrome/offline player. MediaPlayer could not do gapless reliably
    // (setNextMediaPlayer is OEM-dependent), reported nothing about the format it
    // was decoding, and had no stage to apply ReplayGain in.
    //
    // Pinned to 1.8.0 rather than the current 1.10.x: from 1.9 media3 requires
    // compileSdk 36, and AGP 8.7.3 tops out at 35. Moving to it means an AGP and
    // compileSdk bump, which belongs in its own change rather than riding along
    // with an audio rewrite.
    implementation("androidx.media3:media3-exoplayer:1.8.0")
    implementation("androidx.media3:media3-session:1.8.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // JVM unit tests (pure protocol/clock logic): ./gradlew :app:testDebugUnitTest
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}
