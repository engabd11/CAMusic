// :providers:foobar2000 - foobar2000 through the Beefweb plugin (experimental): the client and its MusicSource adapter.
// Everything shared (compileSdk, Java/Kotlin targets, test libraries) comes from the
// root build.gradle.kts; see docs/architecture.md, Modules, for adding a provider.
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.engabd.sendpin.providers.foobar2000"
}

dependencies {
    api(project(":core"))
}
