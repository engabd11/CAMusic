package com.engabd.sendpin.library

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The per-provider settings: every stored value maps to the knob the source reads,
 * and a missing or garbled value lands on the provider's best tier rather than
 * breaking the source.
 */
class ProviderSettingsTest {

    private fun cfg(kind: ServerKind, vararg opts: Pair<String, String>) =
        ServerConfig(kind = kind, options = mapOf(*opts))

    @Test
    fun `spotify prefs read every knob and default sensibly`() {
        val p = ProviderSettings.SpotifyPrefs.from(cfg(ServerKind.SPOTIFY))
        assertEquals(ProviderSettings.SpotifyQuality.VERY_HIGH, p.quality)
        assertFalse(p.normalise)
        assertFalse(p.autoplay)
        assertEquals(0, p.crossfadeSeconds)
        assertTrue(p.preload)
        assertEquals("CAMusic", p.deviceName)

        val q = ProviderSettings.SpotifyPrefs.from(
            cfg(
                ServerKind.SPOTIFY,
                ProviderSettings.OPT_SPOTIFY_QUALITY to "high",
                ProviderSettings.OPT_SPOTIFY_NORMALISE to "true",
                ProviderSettings.OPT_SPOTIFY_AUTOPLAY to "true",
                ProviderSettings.OPT_SPOTIFY_CROSSFADE_S to "40",
                ProviderSettings.OPT_SPOTIFY_PRELOAD to "false",
                ProviderSettings.OPT_SPOTIFY_DEVICE_NAME to "  Kitchen phone ",
            ),
        )
        assertEquals(ProviderSettings.SpotifyQuality.HIGH, q.quality)
        assertTrue(q.normalise)
        assertTrue(q.autoplay)
        assertEquals(12, q.crossfadeSeconds, "crossfade is clamped to librespot's useful range")
        assertFalse(q.preload)
        assertEquals("Kitchen phone", q.deviceName)
    }

    @Test
    fun `an unknown spotify quality falls back to very high`() {
        val p = ProviderSettings.SpotifyPrefs.from(cfg(ServerKind.SPOTIFY, ProviderSettings.OPT_SPOTIFY_QUALITY to "ultra"))
        assertEquals(ProviderSettings.SpotifyQuality.VERY_HIGH, p.quality)
    }

    @Test
    fun `qobuz quality maps to the format id the client caps at`() {
        assertEquals(27, ProviderSettings.QobuzQuality.from(cfg(ServerKind.QOBUZ)).formatId)
        assertEquals(6, ProviderSettings.QobuzQuality.from(cfg(ServerKind.QOBUZ, ProviderSettings.OPT_QOBUZ_MAX_FORMAT to "6")).formatId)
        assertEquals(27, ProviderSettings.QobuzQuality.from(cfg(ServerKind.QOBUZ, ProviderSettings.OPT_QOBUZ_MAX_FORMAT to "99")).formatId)
        // The tiers are ordered by quality, and only the two FLAC-24 ones are hi-res.
        assertEquals(listOf(5, 6, 7, 27), ProviderSettings.QobuzQuality.entries.map { it.formatId })
        assertEquals(listOf(false, false, true, true), ProviderSettings.QobuzQuality.entries.map { it.hiRes })
    }

    @Test
    fun `tidal quality chain walks from the chosen ceiling down`() {
        assertEquals(listOf("HI_RES", "LOSSLESS", "HIGH", "LOW"), ProviderSettings.TidalQuality.chainFor(ProviderSettings.TidalQuality.HI_RES))
        assertEquals(listOf("LOSSLESS", "HIGH", "LOW"), ProviderSettings.TidalQuality.chainFor(ProviderSettings.TidalQuality.LOSSLESS))
        assertEquals(listOf("LOW"), ProviderSettings.TidalQuality.chainFor(ProviderSettings.TidalQuality.LOW))
        assertEquals(ProviderSettings.TidalQuality.HI_RES, ProviderSettings.TidalQuality.from(cfg(ServerKind.TIDAL)))
        assertEquals(ProviderSettings.TidalQuality.HIGH, ProviderSettings.TidalQuality.from(cfg(ServerKind.TIDAL, ProviderSettings.OPT_TIDAL_QUALITY to "HIGH")))
    }
}
