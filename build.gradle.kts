// AGP 9.3 — upgraded to unlock M3 Expressive APIs (MotionScheme, MaterialExpressiveTheme,
// 8-level Shapes, 30-param Typography). It requires Gradle 9.4.1+ and supports
// compileSdk 37. Uses built-in Kotlin (no org.jetbrains.kotlin.android plugin needed).
plugins {
    // Versions live in gradle/libs.versions.toml.
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.baselineprofile) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
}
// What every Android library module (:core and the :providers:*) shares with :app, in
// one place, so a new module's own build file only says what is particular to it. These
// match :app's own settings; change them together.
subprojects {
    pluginManager.withPlugin("com.android.library") {
        extensions.configure<com.android.build.api.dsl.LibraryExtension> {
            compileSdk = 37
            defaultConfig {
                minSdk = 31
            }
            compileOptions {
                sourceCompatibility = JavaVersion.VERSION_17
                targetCompatibility = JavaVersion.VERSION_17
            }
        }
        tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile>().configureEach {
            compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
        val libs = rootProject.extensions.getByType<VersionCatalogsExtension>().named("libs")
        dependencies {
            "testImplementation"(libs.findLibrary("kotlin-test-junit").get())
            "testImplementation"(libs.findLibrary("junit").get())
            "testImplementation"(libs.findLibrary("kotlinx-coroutines-test").get())
            "testImplementation"(libs.findLibrary("okhttp-mockwebserver").get())
        }
    }
}
