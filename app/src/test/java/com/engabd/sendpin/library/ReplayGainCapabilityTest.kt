package com.engabd.sendpin.library

import com.engabd.sendpin.emby.EmbyClient
import com.engabd.sendpin.jellyfin.JellyfinClient
import com.engabd.sendpin.plex.PlexClient
import com.engabd.sendpin.subsonic.SubsonicClient
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which libraries claim loudness levelling of their own, and which honestly don't.
 *
 * [Capability.REPLAY_GAIN] is what decides whether the Now Playing sheet offers the
 * control at all, so a source that declares it without a measurement behind it puts a
 * switch on screen that can only ever do nothing — the exact failure [Capability]'s
 * own doc exists to prevent. These are the claims, pinned.
 */
class ReplayGainCapabilityTest {

    private fun subsonic(vararg extensions: String) = SubsonicSource(
        SubsonicClient("http://nas.local:4533", "u", "p"),
        ServerKind.NAVIDROME,
        extensions.toSet(),
    )

    /**
     * The `replayGain` object rides in on the same OpenSubsonic response the format
     * fields do, so the gate is the same one: a plain Subsonic server that advertises
     * no extensions has no measurement to offer.
     */
    @Test
    fun `Subsonic declares ReplayGain only where OpenSubsonic does`() {
        assertTrue(subsonic("formatRestrictions").has(Capability.REPLAY_GAIN))
        assertTrue(subsonic("songLyrics").has(Capability.REPLAY_GAIN))
        assertFalse(subsonic().has(Capability.REPLAY_GAIN), "a plain Subsonic server sends no gain")
    }

    /** Jellyfin scans loudness itself and publishes the correction per item. */
    @Test
    fun `Jellyfin declares ReplayGain`() {
        val source = JellyfinSource(JellyfinClient("http://nas.local:8096", token = "t", userId = "u"))
        assertTrue(source.has(Capability.REPLAY_GAIN))
    }

    /**
     * Emby shares Jellyfin's DTOs and not its loudness scan — normalization is still
     * an open feature request there — so the shared shape stops exactly here.
     */
    @Test
    fun `Emby does not, despite looking like Jellyfin`() {
        assertFalse(EmbySource(EmbyClient("http://nas.local:8096")).has(Capability.REPLAY_GAIN))
    }

    /**
     * Plex does analyse loudness, but keeps the result inside its own sonic-analysis
     * store for Plexamp rather than on any metadata a client can read.
     */
    @Test
    fun `Plex does not either`() {
        assertFalse(PlexSource(PlexClient("http://nas.local:32400")).has(Capability.REPLAY_GAIN))
    }
}
