import com.android.build.api.artifact.ArtifactTransformationRequest
import com.android.build.api.artifact.SingleArtifact
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.baselineprofile)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
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
        versionCode = 72
        versionName = "0.15.0"

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

        // Streaming-provider *application* credentials. Qobuz and Tidal both want
        // the calling app's own registered pair alongside the user's account, and
        // neither pair can live in this repository: Qobuz's is issued per project
        // and Music Assistant's is explicitly not for reuse. So they arrive as
        // gradle properties — set them in ~/.gradle/gradle.properties (or pass -P
        // on the command line) and every build of that machine carries them:
        //
        //     camusic.qobuz.appId=...        camusic.qobuz.appSecret=...
        //     camusic.tidal.clientId=...     camusic.tidal.clientSecret=...
        //
        // Blank is the normal state for a fork or a CI runner, and it is not a
        // build failure: the connect form asks for the pair itself when the build
        // carries none, so a user with their own credentials can still sign in.
        buildConfigField(
            "String",
            "QOBUZ_APP_ID",
            "\"${providers.gradleProperty("camusic.qobuz.appId").getOrElse("")}\"",
        )
        buildConfigField(
            "String",
            "QOBUZ_APP_SECRET",
            "\"${providers.gradleProperty("camusic.qobuz.appSecret").getOrElse("")}\"",
        )
        buildConfigField(
            "String",
            "TIDAL_CLIENT_ID",
            "\"${providers.gradleProperty("camusic.tidal.clientId").getOrElse("")}\"",
        )
        buildConfigField(
            "String",
            "TIDAL_CLIENT_SECRET",
            "\"${providers.gradleProperty("camusic.tidal.clientSecret").getOrElse("")}\"",
        )
        // Last.fm's app pair, the same arrangement as Qobuz's above: from gradle
        // properties when this machine has them, blank otherwise — and blank only means
        // the Scrobbling page asks for a pair of the user's own.
        //
        //     camusic.lastfm.apiKey=...        camusic.lastfm.apiSecret=...
        buildConfigField(
            "String",
            "LASTFM_API_KEY",
            "\"${providers.gradleProperty("camusic.lastfm.apiKey").getOrElse("")}\"",
        )
        buildConfigField(
            "String",
            "LASTFM_API_SECRET",
            "\"${providers.gradleProperty("camusic.lastfm.apiSecret").getOrElse("")}\"",
        )
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
            // Packaged with the debug key, then re-signed by rotationSign<Variant>Apk
            // (bottom of this file) with the release key and the rotation lineage when
            // ~/.camusic-signing exists — see docs/signing.md. Without it (CI, a fork)
            // the APK stays debug-signed. This is the build that should be judged for smoothness:
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

    // librespot-player (thin) and librespot-lib both ship a root-level log4j2.xml.
    // The log4j backend is excluded above and slf4j routes to logcat, so the file
    // is dead weight - and two copies of it abort mergeJavaResource with "2 files
    // found with path 'log4j2.xml'". Nothing reads it at runtime.
    packaging {
        resources {
            excludes += "log4j2.xml"
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

// Release signing with key rotation (docs/signing.md).
//
// v0.1.0–v0.13.x were signed with one machine's debug key, whose password is the public
// "android". Releases now carry the release key plus a lineage proving the debug key
// handed over to it, so installed copies update in place — and the lineage grants the
// old key no rollback, so an APK signed with the debug key alone can no longer update
// a rotated install. AGP's signingConfig takes one key and no lineage, so this re-signs
// the packaged APK with apksigner instead. The key, its password and the lineage live
// in ~/.camusic-signing (or -Pcamusic.signing.dir), never in the repo; apksigner reads
// the password from its file, so it is never on a command line.
abstract class RotationSignApkTask : DefaultTask() {
    @get:InputFiles abstract val apkIn: DirectoryProperty
    @get:OutputDirectory abstract val apkOut: DirectoryProperty
    @get:Internal abstract val request: Property<ArtifactTransformationRequest<RotationSignApkTask>>
    @get:Input abstract val signingDir: Property<String>
    @get:Input abstract val apksignerJar: Property<String>

    // The key lives outside the build, so Gradle cannot see it change: always re-sign.
    init { outputs.upToDateWhen { false } }

    @TaskAction
    fun sign() {
        val dir = File(signingDir.get())
        val files = listOf("old-debug.keystore", "release.p12", "release.pass", "lineage.bin").map { File(dir, it) }
        val (oldKs, ks, pass, lineage) = files
        val ready = files.all { it.isFile }
        if (!ready) logger.warn("w: $dir is incomplete; the release APK stays signed with the debug key only.")
        request.get().submit(this) { artifact ->
            val input = File(artifact.outputFile)
            val out = apkOut.file(input.name).get().asFile
            if (!ready) return@submit input.copyTo(out, overwrite = true)
            val cmd = listOf(
                File(System.getProperty("java.home"), "bin/java").path, "-jar", apksignerJar.get(), "sign",
                "--ks", oldKs.path, "--ks-pass", "pass:android", "--ks-key-alias", "androiddebugkey",
                "--next-signer", "--ks", ks.path, "--ks-pass", "file:${pass.path}", "--ks-key-alias", "camusic",
                "--lineage", lineage.path, "--rotation-min-sdk-version", "31",
                "--out", out.path, input.path,
            )
            val proc = ProcessBuilder(cmd).redirectErrorStream(true).start()
            val log = proc.inputStream.bufferedReader().readText()
            if (proc.waitFor() != 0) throw GradleException("apksigner failed for ${input.name}:\n$log")
            out
        }
    }
}

androidComponents {
    val signingDir = providers.gradleProperty("camusic.signing.dir")
        .orElse(File(System.getProperty("user.home"), ".camusic-signing").path)
    val apksigner = sdkComponents.sdkDirectory.map {
        it.dir("build-tools/${android.buildToolsVersion}/lib").file("apksigner.jar").asFile.path
    }
    onVariants(selector().withBuildType("release")) { variant ->
        val task = tasks.register<RotationSignApkTask>(
            "rotationSign${variant.name.replaceFirstChar { it.uppercase() }}Apk",
        ) {
            this.signingDir.set(signingDir)
            apksignerJar.set(apksigner)
        }
        val request = variant.artifacts.use(task)
            .wiredWithDirectories(RotationSignApkTask::apkIn, RotationSignApkTask::apkOut)
            .toTransformMany(SingleArtifact.APK)
        task.configure { this.request.set(request) }
    }
}

dependencies {
    // The Oboe native output engine (src/main/cpp/sendspin_output_*) links
    // against this via CMake's find_package(oboe) - see CMakeLists.txt. Ships
    // as a prefab package (buildFeatures.prefab above), not source, since NDK
    // stopped bundling Oboe's sources.
    implementation(libs.oboe)

    // Carries Material3 1.4 — the Expressive release. The motion scheme, the wavy
    // progress indicators and the shape morphing the UI now leans on are all in it.
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)

    // One lifecycle version for every artifact. process and viewmodel-compose sat at
    // 2.9.4 beside runtime 2.10.0; lifecycle's own constraints resolved them all to
    // 2.10.0 anyway, so the 2.9.4 lines only misstated what shipped.
    implementation(libs.androidx.core.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.process)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.activity.compose)
    implementation(libs.navigation.compose)

    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    // M3 Expressive — override BOM to pull material3 1.5.0-alpha26.
    // The BOM 2026.06.01 resolves material3 to 1.4.0 stable where the Expressive
    // APIs are internal. 1.5.0-alpha26 makes them public (MaterialExpressiveTheme,
    // MotionScheme, 8-level Shapes, 30-param Typography). It requires AGP 9.1+
    // (satisfied by AGP 9.2). When 1.5.0 goes stable, remove this override.
    implementation(libs.compose.material3)
    implementation(libs.compose.material3.windowsizeclass)
    implementation(libs.compose.material.icons.extended)

    // Material Components library — provides Theme.Material3.NoActionBar (the XML
    // theme parent) used by themes.xml. The Compose material3 artifact handles the
    // Compose layer; this handles the pre-Compose window so the XML theme's parent
    // matches the Compose MaterialTheme.
    implementation(libs.material.components)

    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)
    // androidx.palette was removed here: zero imports anywhere in the source tree. It
    // was superseded by ui/design/AlbumPalette.kt, a from-scratch CIELAB k-means
    // extractor that does what Palette could not — population-weighted multi-swatch
    // output, and a perceptual achromatic test rather than an HSV saturation gate.
    // Installs the baseline profile at first run. Without it the generated profile is
    // packaged and then ignored, so this is not optional dressing — it is the half that
    // does the work on device.
    implementation(libs.profileinstaller)
    // Names :baselineprofile as the producer of this module's profile. Without it the
    // plugin applies cleanly, `generateBaselineProfile` runs, and nothing is generated
    // — it has no dependency telling it where profiles come from.
    //
    // The committed profile at app/src/release/generated/baselineProfiles/ is only as
    // good as the last run against a device, and a generator added afterwards
    // contributes nothing until someone runs it again. It has drifted once already:
    // the Album, Now Playing, Settings and Startup journeys were written a week after
    // the last regeneration and so were absent from the shipped rules entirely, which
    // is very likely why the first visit to those screens was the rough one.
    //
    //   ./gradlew :app:generateBaselineProfile      # needs a connected device
    //
    // Worth re-running whenever a screen is added or restructured, and worth checking
    // before a release:
    //
    //   grep -c LightSyncScreen app/src/release/generated/baselineProfiles/baseline-prof.txt
    //
    baselineProfile(project(":baselineprofile"))
    implementation(libs.datastore.preferences)

    // The home-screen widget. Glance is Compose for RemoteViews — the alternative is
    // hand-built RemoteViews, which cannot express this layout without a lot of XML
    // and cannot share a line of code with the app's own player.
    //
    // glance-material3 is deliberately *not* here: the widget paints from the app's
    // own Ink/accent tokens like every other surface, and pulling in a second theme
    // system to restate them would be the only thing it was used for.
    implementation(libs.glance.appwidget)
    // The Navidrome/offline player. MediaPlayer could not do gapless reliably
    // (setNextMediaPlayer is OEM-dependent), reported nothing about the format it
    // was decoding, and had no stage to apply ReplayGain in.
    //
    // Was pinned to 1.8.0 because 1.9+ needs compileSdk 36 and AGP 8.7.3 topped out
    // at 35. Both moved in the same change that brought Material3 Expressive in, so
    // the pin is gone.
    //
    // 1.11.1 exactly, and the exactness is the point — **never 1.11.0**.
    //
    // Why move at all: from 1.11.0 media3 adds, by itself, the browse-validation
    // activity that Android 16 and 17 require before a car's Bluetooth stack will
    // browse an app's catalogue over AVRCP. This app targets SDK 36 and did not have
    // it, so that browse was broken in every car regardless of Android Auto.
    //
    // Why not 1.11.0: it regressed the legacy-browser connect path, deadlocking the
    // service's main thread when onGetLibraryRoot returns a future that completes
    // later (androidx/media#3393). CarLibrarySessionCallback no longer does that —
    // the root is immediate now, for this among other reasons — but the shape is one
    // step away from any future edit, and 1.11.1 is the release that fixes it.
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.session)
    // Streams go through the app's OkHttp client, so they get its LAN-only
    // cleartext guard on every redirect hop (see Http.stream).
    implementation(libs.media3.datasource.okhttp)

    // The embedded Spotify client (experimental direct Spotify source). The
    // coordinates are the ones librespot-android proves work on Android: the
    // desktop `sink`/`api`/`dacp` modules, the log4j backend and lmax disruptor
    // are excluded and slf4j routes to Android's logcat. The player module carries
    // the pure-Java jorbis/jlayer decoders, so no NDK decoder is needed; audio
    // leaves through our own SinkOutput into the app's engine (see SpotifySession).
    //
    // The `thin` classifier is load-bearing, not an optimisation: the default
    // artifact SHADES the whole kotlin-stdlib into itself (1.6.5 is built against
    // stdlib 2.4), and those shaded classes win over the real ones on the compile
    // classpath — flagging every enum `entries` use in this codebase as needing an
    // opt-in that never existed. Thin is the shade-free artifact. (The stdlib used
    // to be forced down to 2.2.21 as well, while the compiler was 2.2; the compiler
    // is 2.4.10 now, matching what librespot was built against, so the force went.)
    implementation(variantOf(libs.librespot.player) { classifier("thin") }) {
        exclude(group = "xyz.gianlu.librespot", module = "librespot-sink")
        exclude(group = "com.lmax", module = "disruptor")
        exclude(group = "org.apache.logging.log4j")
    }
    // The binding has to match slf4j-api's major version. librespot brings slf4j-api
    // 2.0.16, which finds bindings through ServiceLoader and ignores the 1.7-style
    // StaticLoggerBinder the old 1.7.30 artifact provides, so librespot logged to
    // slf4j's no-op logger and nothing reached logcat.
    implementation(libs.slf4j.android)
    constraints {
        // librespot-lib 1.6.5 pins protobuf-java 3.25.2, which is affected by
        // CVE-2024-7254 (unbounded recursion parsing nested groups → stack overflow).
        // Fixed from 3.25.5; raised to the newest 3.25.x, same API.
        implementation(libs.protobuf.java) {
            because("CVE-2024-7254 in the 3.25.2 librespot pins")
        }
    }

    // Room: offline download index and local media cache. One catalog version for
    // the artifacts and the androidx.room Gradle plugin — the plugin was 2.8.4 while
    // every Room artifact was 2.7.1.
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // D-pad focus affordance (scale/glow on the focused item) for the "tv" flavor's
    // screens. Stock Compose Foundation's LazyRow/LazyColumn/LazyVerticalGrid (used
    // throughout the phone UI already, e.g. LibraryScreen.kt) already handle D-pad
    // scroll/focus traversal - what they don't give components built for touch (see
    // ui/design/SendspinDesign.kt) is the focused-item visual feedback TV UX expects,
    // which is what this library's Card/Button ship tuned out of the box. Only the
    // *components* are used from it - theming stays SendspinTheme's, via
    // tv/design/TvDesign.kt's wrappers, so TV screens still read as this app.
    "tvImplementation"(libs.tv.material)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

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
    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit)
    testImplementation(libs.room.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)

    // Instrumented tests: ./gradlew :app:connectedDebugAndroidTest (needs a device).
    // Deliberately thin — these exist to pin two specific regressions that cost a
    // release each, not to become a second test suite. See app/src/androidTest.
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core.ktx)
    androidTestImplementation(libs.androidx.test.runner)
    // Room's MigrationTestHelper, for LocalMediaDatabaseMigrationTest. It needs a
    // real SQLite, so it belongs here rather than beside the `room-testing` already
    // in testImplementation — there is no Robolectric in this project.
    androidTestImplementation(libs.room.testing)
}
