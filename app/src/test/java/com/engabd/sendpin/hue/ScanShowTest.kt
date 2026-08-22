package com.engabd.sendpin.hue

import com.engabd.sendpin.audio.IntensityProfile
import com.engabd.sendpin.audio.ScanFrameSynth
import com.engabd.sendpin.audio.ScanSection
import com.engabd.sendpin.audio.TrackScan
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The scan-driven show, for a Music Assistant queue playing where this phone cannot
 * hear it.
 *
 * The whole feature rests on synthesising something honest out of an offline
 * analysis, so what is pinned here is exactly the honesty: the beats land where the
 * grid says, the stereo field stays absent, and the position never jumps.
 */
class ScanFrameSynthTest {

    /** 120 BPM, eight bars, one section. */
    private fun scanOf(bpm: Float = 120f, beats: Int = 32): TrackScan {
        val period = 60f / bpm
        return TrackScan(
            durationS = beats * period,
            bpm = bpm,
            confidence = 0.9f,
            beats = FloatArray(beats) { it * period },
            accents = FloatArray(beats) { if (it % 4 == 0) 1f else 0.6f },
            downbeat = 0,
            sections = listOf(ScanSection(0f, beats * period, 0.8f)),
            intensity = null,
            melbankRef = FloatArray(16) { 1f - it / 32f },
        )
    }

    @Test
    fun `one beat fires per grid beat and no more`() {
        val scan = scanOf()
        val synth = ScanFrameSynth()
        val step = 0.02f
        var beats = 0
        var t = 0f
        while (t < 4f) {
            if (synth.step(scan, t, step).frame.beat) beats++
            t += step
        }
        // 120 BPM over four seconds is eight beats. Off-by-one at the boundary is
        // acceptable; two per beat or none is not.
        assertTrue(beats in 7..9, "expected ~8 beats in 4s at 120 BPM, got $beats")
    }

    @Test
    fun `the stereo field stays absent`() {
        // Load-bearing rather than an omission: AnalysisFrame.pan documents empty as
        // "do not use this", which is what makes SpatialWaves and GestureTracker
        // degrade correctly instead of reacting to a field invented here.
        val out = ScanFrameSynth().step(scanOf(), 1f, 0.02f)
        assertTrue(out.frame.pan.isEmpty())
        assertTrue(out.frame.melbankRefLive.isEmpty())
    }

    @Test
    fun `the melbank reference is passed through exactly`() {
        val scan = scanOf()
        val out = ScanFrameSynth().step(scan, 1f, 0.02f)
        assertTrue(scan.melbankRef.contentEquals(out.frame.melbankRef))
        assertEquals(scan.bpm, out.frame.tempoBpm)
        assertEquals(1f, out.frame.tAudio)
    }

    @Test
    fun `spectrum off leaves the melbank empty rather than flat`() {
        val out = ScanFrameSynth(ScanFrameSynth.Options(spectrum = false)).step(scanOf(), 1f, 0.02f)
        assertTrue(out.frame.melbank.isEmpty())
    }

    @Test
    fun `energy follows the section curve`() {
        val quiet = scanOf().copy(
            sections = listOf(ScanSection(0f, 60f, 0.2f)),
            intensity = null,
        )
        val loud = scanOf().copy(
            sections = listOf(ScanSection(0f, 60f, 1f)),
            intensity = null,
        )
        // Sampled off-beat in both, so the comparison is the section and not an
        // envelope that happens to be ringing in one of them.
        val a = ScanFrameSynth().step(quiet, 0.35f, 0.02f).frame.energy
        val b = ScanFrameSynth().step(loud, 0.35f, 0.02f).frame.energy
        assertTrue(b > a, "a louder section should light harder: $b !> $a")
    }

    @Test
    fun `an unusable grid is never scheduled against`() {
        // Below MIN_GRID_CONFIDENCE the scanner does not trust its own beats, and a
        // wrong beat is rhythmically, visibly wrong in a way an idle glow is not.
        val bad = scanOf().copy(confidence = 0.1f)
        assertFalse(bad.gridUsable)
        assertFalse(ScanFrameSynth().step(bad, 1f, 0.02f).frame.beat)
    }
}

class PositionSlewTest {

    @Test
    fun `it free-runs between anchors`() {
        val slew = PositionSlew()
        slew.reset(10f)
        repeat(50) { slew.advance(0.02f) }
        assertTrue(abs(slew.positionS - 11f) < 0.05f, "expected ~11s, got ${slew.positionS}")
    }

    @Test
    fun `small drift is absorbed rather than jumped`() {
        val slew = PositionSlew()
        slew.reset(10f)
        slew.anchor(10.12f)
        assertFalse(slew.lastWasSnap)
        // The frame right after the correction must not have moved by the whole of it.
        val before = slew.positionS
        slew.advance(0.02f)
        val moved = slew.positionS - before
        assertTrue(moved < 0.05f, "a 120ms correction landed in one frame: $moved")
    }

    @Test
    fun `a seek snaps`() {
        val slew = PositionSlew()
        slew.reset(10f)
        slew.anchor(45f)
        assertTrue(slew.lastWasSnap)
        assertTrue(abs(slew.positionS - 45f) < 0.001f)
    }
}

class LightSyncFeedPickerTest {

    @Test
    fun `this app's own players win`() {
        assertEquals(
            LightSyncFeed.SENDSPIN_PCM,
            LightSyncFeedPicker.pick(
                sendspinPlayingHere = true, hasSendspinTap = true,
                localPlaying = false, captureRunning = true, scanDriving = true,
            ),
        )
        assertEquals(
            LightSyncFeed.LOCAL_PCM,
            LightSyncFeedPicker.pick(
                sendspinPlayingHere = false, hasSendspinTap = false,
                localPlaying = true, captureRunning = true, scanDriving = true,
            ),
        )
    }

    @Test
    fun `real audio beats a schedule`() {
        assertEquals(
            LightSyncFeed.CAPTURE,
            LightSyncFeedPicker.pick(
                sendspinPlayingHere = false, hasSendspinTap = false,
                localPlaying = false, captureRunning = true, scanDriving = true,
            ),
        )
    }

    @Test
    fun `the scan is the last resort before idle`() {
        assertEquals(
            LightSyncFeed.SCAN_REMOTE,
            LightSyncFeedPicker.pick(
                sendspinPlayingHere = false, hasSendspinTap = false,
                localPlaying = false, captureRunning = false, scanDriving = true,
            ),
        )
        assertEquals(
            LightSyncFeed.LOCAL_PCM,
            LightSyncFeedPicker.pick(
                sendspinPlayingHere = false, hasSendspinTap = false,
                localPlaying = false, captureRunning = false, scanDriving = false,
            ),
        )
    }

    @Test
    fun `sendspin without a tap is not the sendspin feed`() {
        // The engine is rebuilt on reconnect, and for that instant the owner says
        // SENDSPIN while the tap is null. Claiming the feed there would point the show
        // at nothing.
        assertEquals(
            LightSyncFeed.LOCAL_PCM,
            LightSyncFeedPicker.pick(
                sendspinPlayingHere = true, hasSendspinTap = false,
                localPlaying = false, captureRunning = false, scanDriving = false,
            ),
        )
    }
}

class ShowStatusRulesTest {

    private fun status(
        feed: LightSyncFeed,
        fresh: Boolean = true,
        scan: ScanFeedState = ScanFeedState.IDLE,
        blocked: Boolean = false,
    ) = ShowStatusRules.statusFor(
        enabled = true, sessionOpen = true, feed = feed,
        framesFresh = fresh, scanState = scan, captureBlocked = blocked,
    )

    @Test
    fun `an open session with no frames is not reacting to anything`() {
        // The bug this whole path exists to fix: the screen keyed on the DTLS session
        // being open, which has never had anything to do with whether audio arrives.
        assertEquals(ShowStatus.WAITING, status(LightSyncFeed.LOCAL_PCM, fresh = false))
        assertEquals(ShowStatus.LIVE_PCM, status(LightSyncFeed.LOCAL_PCM, fresh = true))
    }

    @Test
    fun `a remote track reports what it is actually doing`() {
        assertEquals(
            ShowStatus.SCAN_DRIVEN,
            status(LightSyncFeed.SCAN_REMOTE, scan = ScanFeedState.DRIVING),
        )
        assertEquals(
            ShowStatus.SCAN_PENDING,
            status(LightSyncFeed.SCAN_REMOTE, scan = ScanFeedState.SCANNING),
        )
        assertEquals(
            ShowStatus.NO_SCAN,
            status(LightSyncFeed.SCAN_REMOTE, scan = ScanFeedState.NO_MATCH),
        )
        assertEquals(
            ShowStatus.NO_SCAN,
            status(LightSyncFeed.SCAN_REMOTE, scan = ScanFeedState.NO_GRID),
        )
    }

    @Test
    fun `a blocked capture says so rather than looking broken`() {
        assertEquals(ShowStatus.CAPTURE_BLOCKED, status(LightSyncFeed.CAPTURE, blocked = true))
        assertEquals(ShowStatus.CAPTURE, status(LightSyncFeed.CAPTURE))
    }

    @Test
    fun `switched off beats everything`() {
        assertEquals(
            ShowStatus.OFF,
            ShowStatusRules.statusFor(
                enabled = false, sessionOpen = true, feed = LightSyncFeed.LOCAL_PCM,
                framesFresh = true, scanState = ScanFeedState.DRIVING, captureBlocked = false,
            ),
        )
    }
}

class RemoteTrackIdentityTest {

    @Test
    fun `edition noise does not stop a match`() {
        assertTrue(
            RemoteTrackIdentity.sameTrack(
                "Solsbury Hill (2002 Remaster)", "Peter Gabriel", 261_000,
                "Solsbury Hill", "Peter Gabriel", 262_000,
            ),
        )
    }

    @Test
    fun `featured artists are stripped from both sides`() {
        assertEquals(
            RemoteTrackIdentity.normalise("Stan (feat. Dido)"),
            RemoteTrackIdentity.normalise("Stan"),
        )
    }

    @Test
    fun `a different recording of the same title does not match`() {
        assertFalse(
            RemoteTrackIdentity.sameTrack(
                "Hallelujah", "Leonard Cohen", 275_000,
                "Hallelujah", "Jeff Buckley", 413_000,
            ),
        )
    }

    @Test
    fun `an unknown duration does not veto a match`() {
        // Music Assistant reports 0 for a stream whose length it does not know, and
        // ruling those out would lose live sets and radio-sourced tracks entirely.
        assertTrue(
            RemoteTrackIdentity.sameTrack(
                "Teardrop", "Massive Attack", 0,
                "Teardrop", "Massive Attack", 330_000,
            ),
        )
    }

    @Test
    fun `the key is stable across cosmetic differences`() {
        assertEquals(
            RemoteTrackIdentity.keyOf("Idioteque", "Radiohead", 309_000),
            RemoteTrackIdentity.keyOf("  IDIOTEQUE  ", "radiohead", 309_400),
        )
    }
}
