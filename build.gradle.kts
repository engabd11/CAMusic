// AGP 9.2 — upgraded to unlock M3 Expressive APIs (MotionScheme, MaterialExpressiveTheme,
// 8-level Shapes, 30-param Typography). AGP 9.2 requires Gradle 9.4.1+ and supports
// compileSdk 37. Uses built-in Kotlin (no org.jetbrains.kotlin.android plugin needed).
plugins {
    id("com.android.application") version "9.2.0" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21" apply false
    id("com.android.test") version "9.2.0" apply false
    id("androidx.baselineprofile") version "1.4.1" apply false
    id("com.google.devtools.ksp") version "2.2.21-2.0.5" apply false
    id("androidx.room") version "2.7.1" apply false
}