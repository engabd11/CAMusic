// :core - what every part of the app, and every library provider module, builds on:
// the shared models (MaItem and friends), the HTTP client, the MusicSource API and
// server configuration, and the remote-playback contracts. It depends on no other
// module. Files keep their original packages, so moving them here changed no imports.
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    // Not for any UI: the models here are passed to composables, and Compose only infers
    // a class is stable when the Compose compiler built it. Without the plugin every
    // MaItem would count as unstable in :app and skip less.
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.engabd.sendpin.core"
}

dependencies {
    // api, not implementation: the runtime below has no version of its own, so every
    // module that sees it through :core needs the BOM that names one.
    api(platform(libs.compose.bom))
    api(libs.compose.runtime)
    api(libs.okhttp)
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.android)
}
