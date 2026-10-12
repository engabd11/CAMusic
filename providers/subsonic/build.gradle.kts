// :providers:subsonic - Navidrome and every Subsonic-compatible server: the client and its MusicSource adapter.
// Everything shared (compileSdk, Java/Kotlin targets, test libraries) comes from the
// root build.gradle.kts; see docs/architecture.md, Modules, for adding a provider.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.engabd.sendpin.providers.subsonic"
}

dependencies {
    api(project(":core"))
}
