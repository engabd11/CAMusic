package com.engabd.sendpin.library

/**
 * The per-provider settings each streaming account actually has — as its own client
 * or engine exposes them, not a generic "stream quality" that the source then
 * ignores. Persisted on the [ServerConfig] as options; read by the source when it
 * is built (`MusicSources.create`) and shown by the provider's settings page.
 *
 * Every choice here maps to a real knob:
 *  - Spotify → librespot's `PlayerConfiguration` and `Session.Builder`.
 *  - Qobuz → the `format_id` chain `QobuzClient.streamUrl` walks.
 *  - Tidal → the `audioquality` chain `TidalClient.streamUrl` walks.
 */
object ProviderSettings {

    // ── Spotify (librespot) ─────────────────────────────────────────────────

    /** Preferred audio quality: `normal` (96 kbps), `high` (160), `very_high` (320). */
    const val OPT_SPOTIFY_QUALITY = "spotifyQuality"

    /** Volume normalisation (librespot's `enableNormalisation`). */
    const val OPT_SPOTIFY_NORMALISE = "spotifyNormalise"

    /** Keep playing similar music when the queue ends (`autoplayEnabled`). */
    const val OPT_SPOTIFY_AUTOPLAY = "spotifyAutoplay"

    /** Crossfade between tracks, in seconds; 0 is off (`crossfadeDuration`). */
    const val OPT_SPOTIFY_CROSSFADE_S = "spotifyCrossfadeSeconds"

    /** Fetch the next track ahead of time (`preloadEnabled`). */
    const val OPT_SPOTIFY_PRELOAD = "spotifyPreload"

    /** What this phone is called in Spotify Connect (`Session.Builder.setDeviceName`). */
    const val OPT_SPOTIFY_DEVICE_NAME = "spotifyDeviceName"

    enum class SpotifyQuality(val key: String, val label: String, val detail: String) {
        NORMAL("normal", "Normal", "96 kbps Ogg Vorbis"),
        HIGH("high", "High", "160 kbps Ogg Vorbis"),
        VERY_HIGH("very_high", "Very high", "320 kbps Ogg Vorbis — Premium");

        companion object {
            fun from(key: String?): SpotifyQuality = entries.firstOrNull { it.key == key } ?: VERY_HIGH
        }
    }

    /** Everything the librespot session and player are built from, in one value. */
    data class SpotifyPrefs(
        val quality: SpotifyQuality = SpotifyQuality.VERY_HIGH,
        val normalise: Boolean = false,
        val autoplay: Boolean = false,
        val crossfadeSeconds: Int = 0,
        val preload: Boolean = true,
        val deviceName: String = DEFAULT_SPOTIFY_DEVICE_NAME,
    ) {
        companion object {
            fun from(config: ServerConfig) = SpotifyPrefs(
                quality = SpotifyQuality.from(config.option(OPT_SPOTIFY_QUALITY)),
                normalise = config.option(OPT_SPOTIFY_NORMALISE) == "true",
                autoplay = config.option(OPT_SPOTIFY_AUTOPLAY) == "true",
                crossfadeSeconds = config.option(OPT_SPOTIFY_CROSSFADE_S)?.toIntOrNull()?.coerceIn(0, 12) ?: 0,
                preload = config.option(OPT_SPOTIFY_PRELOAD) != "false",
                deviceName = config.option(OPT_SPOTIFY_DEVICE_NAME)?.trim().orEmpty().ifBlank { DEFAULT_SPOTIFY_DEVICE_NAME },
            )
        }
    }

    const val DEFAULT_SPOTIFY_DEVICE_NAME = "CAMusic"

    // ── Qobuz ───────────────────────────────────────────────────────────────

    /** The highest `format_id` to ask for; see [QobuzQuality]. */
    const val OPT_QOBUZ_MAX_FORMAT = "qobuzMaxFormat"

    /**
     * Qobuz's own tiers, as `format_id`s. Higher id, higher quality — the chain the
     * client walks is these in descending order, capped at the chosen one.
     */
    enum class QobuzQuality(val formatId: Int, val label: String, val detail: String, val hiRes: Boolean) {
        MP3(5, "MP3", "320 kbps", hiRes = false),
        CD(6, "CD", "FLAC 16-bit · 44.1 kHz", hiRes = false),
        HI_RES_96(7, "Hi-Res", "FLAC 24-bit · up to 96 kHz", hiRes = true),
        HI_RES_192(27, "Hi-Res+", "FLAC 24-bit · up to 192 kHz", hiRes = true);

        companion object {
            fun from(formatId: Int?): QobuzQuality = entries.firstOrNull { it.formatId == formatId } ?: HI_RES_192
            fun from(config: ServerConfig): QobuzQuality = from(config.option(OPT_QOBUZ_MAX_FORMAT)?.toIntOrNull())
        }
    }

    // ── Tidal ───────────────────────────────────────────────────────────────

    /** The highest `audioquality` to ask for; see [TidalQuality]. */
    const val OPT_TIDAL_QUALITY = "tidalQuality"

    /** Tidal's own tiers, named as its app names them. Declared lowest first. */
    enum class TidalQuality(val wire: String, val label: String, val detail: String) {
        LOW("LOW", "Low", "AAC 96 kbps"),
        HIGH("HIGH", "High", "AAC 320 kbps"),
        LOSSLESS("LOSSLESS", "HiFi", "FLAC 16-bit · 44.1 kHz"),
        HI_RES("HI_RES", "Max", "FLAC up to 24-bit · 192 kHz");

        companion object {
            fun from(wire: String?): TidalQuality = entries.firstOrNull { it.wire == wire } ?: HI_RES
            fun from(config: ServerConfig): TidalQuality = from(config.option(OPT_TIDAL_QUALITY))

            /** The chain to walk for a chosen ceiling: the ceiling and everything under it, best first. */
            fun chainFor(max: TidalQuality): List<String> =
                entries.filter { it.ordinal <= max.ordinal }.sortedByDescending { it.ordinal }.map { it.wire }
        }
    }
}

/**
 * What a streaming account says about itself once signed in — the settings page's
 * account card. Every field optional: providers answer what they answer.
 */
data class ProviderAccount(
    /** Display name, or the login email when that is all there is. */
    val name: String? = null,
    /** "Premium", "Studio", "HiFi Plus" — the tier as the provider names it. */
    val plan: String? = null,
    val country: String? = null,
    /** The highest quality this plan can stream, in the provider's own words, when known. */
    val maxQuality: String? = null,
    /** Qobuz: whether the plan includes hi-res streaming at all. Null when unknown. */
    val hiResAllowed: Boolean? = null,
)
