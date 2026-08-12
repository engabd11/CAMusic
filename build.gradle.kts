// AGP stays on the 8.x line. 8.13 is the first that builds against compileSdk 36,
// which is all this needed; AGP 9 is a major with its own migration and buys the app
// nothing a user would ever see.
plugins {
    id("com.android.application") version "8.13.2" apply false
    id("org.jetbrains.kotlin.android") version "2.2.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.2.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.21" apply false
    id("com.android.test") version "8.13.2" apply false
    id("androidx.baselineprofile") version "1.4.1" apply false
    id("com.google.devtools.ksp") version "2.2.21-2.0.5" apply false
}
