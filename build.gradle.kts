// AGP 9.3 — upgraded to unlock M3 Expressive APIs (MotionScheme, MaterialExpressiveTheme,
// 8-level Shapes, 30-param Typography). It requires Gradle 9.4.1+ and supports
// compileSdk 37. Uses built-in Kotlin (no org.jetbrains.kotlin.android plugin needed).
plugins {
    // Versions live in gradle/libs.versions.toml.
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.android.test) apply false
    alias(libs.plugins.baselineprofile) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.room) apply false
}