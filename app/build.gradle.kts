import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.plugin.compose")
    id("androidx.baselineprofile")
    id("com.google.devtools.ksp")
    id("androidx.room")
}

android {
    namespace = "com.engabd.sendpin"
    compileSdk = 37

    // NDK r27 is the minimum for the oboe prefab package (1.9.3) and CMake 3.22
    // that this build uses. The prefab's libc++_shared.so must match the app's.
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.engabd.sendpin"
        minSdk = 31
        targetSdk = 36
        versionCode = 56
        versionName = "0.11.1"

        // app/src/androidTest had no runner because it had no tests. The two below
        // are the ones Phase 0 found by hand, and neither can run on the JVM: both
        // need a real Context — one for SharedPreferences and Settings.Secure, the
        // other for a real media3 MediaSession.
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // The Oboe native output engine (src/main/cpp) - see SendspinNativeOutput.kt.
        // Oboe itself comes from the com.google.oboe:oboe prefab package (below),
        // not NDK-bundled sources - NDK stopped shipping those at
        // sources/third_party/oboe as of at least r27.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
        externalNativeBuild {
            cmake {
                cppFlags += "-std=c++17"
                // Required by the oboe prefab package - its own libc++_shared.so
                // must match the app's, or the linker sees duplicate/conflicting
                // C++ runtime symbols at load time.
                arguments += "-DANDROID_STL=c++_shared"
                // 16 KB page sizes. Every 64-bit Android device shipping from 2025 uses
                // them, Play requires support for anything targeting Android 15+, and
                // without this the loader falls back to a compatibility mode and says so
                // in a dialog on first launch.
                //
                // Only *this* library needed it. Every prebuilt dependency in the APK —
                // oboe, datastore, androidx.graphics.path, and the STL - already ships
                // 16 KB-aligned; the one built here did not, because NDK r27 aligns to
                // 4 KB unless asked. (r28 makes it the default and this becomes a no-op.)
                arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON"
            }
        }
    }

    // Two installable apps from one source tree. "mobile" is everything that exists
    // today — its own AndroidManifest.xml is app/src/main's, unchanged. "tv" adds an
    // app/src/tv source set (its own manifest overlay, its own Activity/Compose
    // screens) while compiling the exact same app/src/main business logic — every
    // ViewModel, the whole audio-tap/Hue-sync pipeline, all of it. See
    // SendpinApp.kt's Platform.isTelevision guard for the few phone-only features
    // (driving mode, USB DAC toasts) that opt out at runtime instead of being split
    // into a separate source set.
    flavorDimensions += "platform"
    productFlavors {
        create("mobile") {
            dimension = "platform"
            // Lets Studio/AGP pick a buildable variant without prompting - this is
            // the flavor every existing release has shipped as.
            isDefault = true
        }
        create("tv") {
            dimension = "platform"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            // Drops resources R8 proved unreachable. Only meaningful alongside
            // isMinifyEnabled, which is why it was never worth turning on separately —
            // and worth something now that the app carries Glance layouts, three
            // notification channels and a Material Components dependency it uses one
            // XML theme from.
            isShrinkResources = true
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
    // prefab: how CMake finds the oboe:: target from the com.google.oboe:oboe
    // Maven dependency below - see CMakeLists.txt's find_package(oboe).
    buildFeatures { compose = true; buildConfig = true; prefab = true }

    room {
        schemaDirectory("$projectDir/schemas")
    }

    // The Oboe native output engine (src/main/cpp/sendspin_output_*) - GC-immune
    // real-time PCM output for the experimental SendspinExoEngine path (see
    // docs/exoplayer-upgrade-plan.md). Adds an NDK/CMake toolchain download to a
    // clean build and a .so per ABI to the APK - the cost the previous, unwired
    // prototype here was deliberately avoiding - but this one has a caller
    // ([OboeRenderer]).
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

// The offline-analysis harness (`ScanHarnessTest`) reads a directory of decoded
// audio that only exists on a developer's machine, so it is switched on by a
// system property and skips itself otherwise. Gradle forks the test JVM, which
// does not inherit the daemon's `-D` flags, so the two the harness reads have to
// be forwarded explicitly — without this the property is simply absent in the
// test and the harness silently skips however it is invoked.
//
//   ./gradlew :app:testDebugUnitTest --tests '*ScanHarnessTest*'
//       -Dcamusic.audio.dir=... -Dcamusic.harness.out=...
//
// See tools/analysis-harness/README.md.
tasks.withType<Test>().configureEach {
    for (key in listOf("camusic.audio.dir", "camusic.harness.out", "camusic.harness.limit")) {
        System.getProperty(key)?.let { systemProperty(key, it) }
    }
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
    // The Oboe native output engine (src/main/cpp/sendspin_output_*) links
    // against this via CMake's find_package(oboe) - see CMakeLists.txt. Ships
    // as a prefab package (buildFeatures.prefab above), not source, since NDK
    // stopped bundling Oboe's sources.
    implementation("com.google.oboe:oboe:1.10.0")

    // Carries Material3 1.4 — the Expressive release. The motion scheme, the wavy
    // progress indicators and the shape morphing the UI now leans on are all in it.
    val composeBom = platform("androidx.compose:compose-bom:2026.06.01")
    implementation(composeBom)

    // With AGP 9.2, these can move to latest. Previously pinned because
    // core 1.17+ and lifecycle 2.10+ are built against compileSdk 37
    // and demanded AGP 9.1+.
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-process:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.navigation:navigation-compose:2.9.8")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    // M3 Expressive — override BOM to pull material3 1.5.0-alpha26.
    // The BOM 2026.06.01 resolves material3 to 1.4.0 stable where the Expressive
    // APIs are internal. 1.5.0-alpha26 makes them public (MaterialExpressiveTheme,
    // MotionScheme, 8-level Shapes, 30-param Typography). It requires AGP 9.1+
    // (satisfied by AGP 9.2). When 1.5.0 goes stable, remove this override.
    implementation("androidx.compose.material3:material3:1.5.0-alpha26")
    implementation("androidx.compose.material3:material3-window-size-class:1.5.0-alpha26")
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
    // androidx.palette was removed here: zero imports anywhere in the source tree. It
    // was superseded by ui/design/AlbumPalette.kt, a from-scratch CIELAB k-means
    // extractor that does what Palette could not — population-weighted multi-swatch
    // output, and a perceptual achromatic test rather than an HSV saturation gate.
    // Installs the baseline profile at first run. Without it the generated profile is
    // packaged and then ignored, so this is not optional dressing — it is the half that
    // does the work on device.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    // Names :baselineprofile as the producer of this module's profile. Without it the
    // plugin applies cleanly, `generateBaselineProfile` runs, and nothing is generated
    // — it has no dependency telling it where profiles come from.
    baselineProfile(project(":baselineprofile"))
    implementation("androidx.datastore:datastore-preferences:1.2.1")

    // The home-screen widget. Glance is Compose for RemoteViews — the alternative is
    // hand-built RemoteViews, which cannot express this layout without a lot of XML
    // and cannot share a line of code with the app's own player.
    //
    // glance-material3 is deliberately *not* here: the widget paints from the app's
    // own Ink/accent tokens like every other surface, and pulling in a second theme
    // system to restate them would be the only thing it was used for.
    implementation("androidx.glance:glance-appwidget:1.1.1")
    // The Navidrome/offline player. MediaPlayer could not do gapless reliably
    // (setNextMediaPlayer is OEM-dependent), reported nothing about the format it
    // was decoding, and had no stage to apply ReplayGain in.
    //
    // Was pinned to 1.8.0 because 1.9+ needs compileSdk 36 and AGP 8.7.3 topped out
    // at 35. Both moved in the same change that brought Material3 Expressive in, so
    // the pin is gone.
    implementation("androidx.media3:media3-exoplayer:1.10.1")
    implementation("androidx.media3:media3-session:1.10.1")

    // Room: offline download index and local media cache.
    val roomVersion = "2.7.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // D-pad focus affordance (scale/glow on the focused item) for the "tv" flavor's
    // screens. Stock Compose Foundation's LazyRow/LazyColumn/LazyVerticalGrid (used
    // throughout the phone UI already, e.g. LibraryScreen.kt) already handle D-pad
    // scroll/focus traversal - what they don't give components built for touch (see
    // ui/design/SendspinDesign.kt) is the focused-item visual feedback TV UX expects,
    // which is what this library's Card/Button ship tuned out of the box. Only the
    // *components* are used from it - theming stays SendspinTheme's, via
    // tv/design/TvDesign.kt's wrappers, so TV screens still read as this app.
    "tvImplementation"("androidx.tv:tv-material:1.1.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // JVM unit tests (pure protocol/clock logic): ./gradlew :app:testDebugUnitTest
    // kotlin-test-junit, not the multiplatform "kotlin-test" facade: that facade
    // publishes Gradle module metadata with separate jvm/js/metadata variants, and
    // variant selection needs the "org.jetbrains.kotlin.platform.type" attribute that
    // the org.jetbrains.kotlin.android plugin used to set on every configuration. AGP
    // 9's built-in Kotlin support means this module no longer applies that plugin, so
    // nothing sets the attribute and Gradle fell back to the metadata-only variant —
    // real classes, no kotlin.test.Test — breaking every JVM test file with
    // "Unresolved reference 'Test'". kotlin-test-junit is a plain single-target JVM
    // artifact (no variant ambiguity) and is the artifact Kotlin recommends for
    // non-multiplatform JUnit 4 projects anyway.
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:2.2.21")
    testImplementation("junit:junit:4.13.2")
    testImplementation("androidx.room:room-testing:$roomVersion")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")

    // Instrumented tests: ./gradlew :app:connectedDebugAndroidTest (needs a device).
    // Deliberately thin — these exist to pin two specific regressions that cost a
    // release each, not to become a second test suite. See app/src/androidTest.
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:core-ktx:1.6.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
}
