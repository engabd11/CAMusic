// :providers:qobuz - Qobuz (experimental): the client and its MusicSource adapter.
// Everything shared (compileSdk, Java/Kotlin targets, test libraries) comes from the
// root build.gradle.kts; see docs/architecture.md, Modules, for adding a provider.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.engabd.sendpin.providers.qobuz"
}

dependencies {
    api(project(":core"))
}
