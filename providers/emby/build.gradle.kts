// :providers:emby - Emby (its client is Jellyfin's with Emby's auth and quirks): the client and its MusicSource adapter.
// Everything shared (compileSdk, Java/Kotlin targets, test libraries) comes from the
// root build.gradle.kts; see docs/architecture.md, Modules, for adding a provider.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.engabd.sendpin.providers.emby"
}

dependencies {
    api(project(":core"))
    api(project(":providers:jellyfin"))
}
