pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

@Suppress("UnstableApiUsage")
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "sendpin"
include(":app")
// The foundation every library provider builds on: shared models, the HTTP client, the
// MusicSource API and the remote-playback contracts. See docs/architecture.md, Modules.
include(":core")
// One module per library provider: its client and its MusicSource adapter, on :core.
include(":providers:subsonic")
include(":providers:jellyfin")
include(":providers:emby")
include(":providers:plex")
include(":providers:qobuz")
include(":providers:tidal")
include(":providers:mpd")
include(":providers:foobar2000")
// Generates the startup/scroll baseline profile. Never built by a normal assemble —
// see baselineprofile/build.gradle.kts for how to run it.
include(":baselineprofile")
