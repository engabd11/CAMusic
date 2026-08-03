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
        versionCode = 22
        versionName = "0.3.7"
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

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("androidx.palette:palette-ktx:1.0.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.media:media:1.7.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // JVM unit tests (pure protocol/clock logic): ./gradlew :app:testDebugUnitTest
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}
