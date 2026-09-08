package com.engabd.sendpin.library

import com.engabd.sendpin.BuildConfig

/**
 * The *application* credentials Qobuz and Tidal ask for on top of the user's own
 * account.
 *
 * Both services want to know which app is calling — Qobuz an app id + secret it
 * signs requests with, Tidal a client id + secret on the device flow — and neither
 * pair belongs to the user. They cannot be committed either: Qobuz issues them per
 * project, and Music Assistant's pair is explicitly not for reuse. So a build
 * carries the pair it was given (`camusic.qobuz.appId` and friends as gradle
 * properties, baked into `BuildConfig` — see app/build.gradle.kts), and when it
 * carries none the connect form asks for one, which is what lets a fork or a
 * self-built APK work with credentials of its own.
 *
 * A config's own options always win over the build's, so a user who pastes a pair
 * is not overruled by whatever the APK shipped with. Spotify is absent from all of
 * this on purpose: librespot authenticates as the user's own Spotify client, so
 * there is no second pair to hold.
 */
object ProviderAppCredentials {

    /** Whether [kind] has to ask the user for the pair, because this build has none. */
    fun asksUser(kind: ServerKind): Boolean = when (kind) {
        ServerKind.QOBUZ -> BuildConfig.QOBUZ_APP_ID.isBlank() || BuildConfig.QOBUZ_APP_SECRET.isBlank()
        ServerKind.TIDAL -> BuildConfig.TIDAL_CLIENT_ID.isBlank() || BuildConfig.TIDAL_CLIENT_SECRET.isBlank()
        else -> false
    }

    /** Qobuz's app id for [config] — the config's own, else the build's. */
    fun qobuzAppId(config: ServerConfig): String =
        config.option(ServerConfig.OPT_QOBUZ_APP_ID).orEmpty().ifBlank { BuildConfig.QOBUZ_APP_ID }

    /** Qobuz's app secret for [config] — the config's own, else the build's. */
    fun qobuzAppSecret(config: ServerConfig): String =
        config.option(ServerConfig.OPT_QOBUZ_APP_SECRET).orEmpty().ifBlank { BuildConfig.QOBUZ_APP_SECRET }

    /** Tidal's client id for [config] — the config's own, else the build's. */
    fun tidalClientId(config: ServerConfig): String =
        config.option(ServerConfig.OPT_TIDAL_CLIENT_ID).orEmpty().ifBlank { BuildConfig.TIDAL_CLIENT_ID }

    /** Tidal's client secret for [config] — the config's own, else the build's. */
    fun tidalClientSecret(config: ServerConfig): String =
        config.option(ServerConfig.OPT_TIDAL_CLIENT_SECRET).orEmpty().ifBlank { BuildConfig.TIDAL_CLIENT_SECRET }
}
