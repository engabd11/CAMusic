package com.engabd.sendpin.data

import com.engabd.sendpin.data.AppSettings.Companion.BACKEND_MA
import com.engabd.sendpin.data.AppSettings.Companion.BACKEND_SUBSONIC
import com.engabd.sendpin.data.AppSettings.Companion.MODE_DIRECT
import com.engabd.sendpin.data.AppSettings.Companion.MODE_HA
import com.engabd.sendpin.data.AppSettings.Companion.lightSyncModeFor
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The library decides the Light Sync transport, because the library decides
 * where the audio actually comes out.
 *
 * Navidrome always plays through this phone's own ExoPlayer, which is exactly
 * what the direct bridge path taps. Music Assistant plays to whatever speaker is
 * targeted — including this phone over Sendspin, which uses a raw AudioTrack the
 * tap cannot see — so Home Assistant, following the MA player entity, is the one
 * transport that covers every MA case.
 */
class LightSyncModeCouplingTest {

    @Test
    fun `navidrome implies the direct bridge`() {
        assertEquals(MODE_DIRECT, lightSyncModeFor(BACKEND_SUBSONIC))
    }

    @Test
    fun `music assistant implies home assistant`() {
        assertEquals(MODE_HA, lightSyncModeFor(BACKEND_MA))
    }

    @Test
    fun `an unknown backend falls back to home assistant`() {
        // The safer default: HA does nothing without a configured server, while
        // direct mode would try to open a bridge session for a library whose
        // audio may never reach the local player.
        assertEquals(MODE_HA, lightSyncModeFor(""))
        assertEquals(MODE_HA, lightSyncModeFor("something-new"))
    }

    @Test
    fun `the mapping is stable under repetition`() {
        // The coordinator compares against the current mode before writing, so a
        // mapping that wobbled would write on every emission and thrash the
        // stream teardown.
        repeat(5) {
            assertEquals(MODE_DIRECT, lightSyncModeFor(BACKEND_SUBSONIC))
            assertEquals(MODE_HA, lightSyncModeFor(BACKEND_MA))
        }
    }
}
