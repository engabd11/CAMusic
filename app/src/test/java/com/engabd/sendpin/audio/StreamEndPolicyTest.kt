package com.engabd.sendpin.audio

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The pause-versus-ending discriminator.
 *
 * Worth pinning because the two failure directions are not symmetric and the code
 * deliberately leans one way: a misjudged announcement loses its last words, a
 * misjudged pause plays music the listener asked to stop. Every default here points
 * at the second being the one to avoid.
 */
class StreamEndPolicyTest {

    private val now = 1_000_000L

    @Test
    fun `a pause this app asked for discards the tail immediately`() {
        val plan = StreamEndPolicy.decide(
            nowMs = now,
            localPauseAtMs = now - 200,
            remotePausedAtMs = null,
            kind = StreamClassifier.Kind.MUSIC,
        )
        assertEquals(TailAction.DISCARD, plan.action)
        assertEquals(0L, plan.capMs)
    }

    @Test
    fun `a pause from another controller discards too`() {
        val plan = StreamEndPolicy.decide(
            nowMs = now,
            localPauseAtMs = null,
            remotePausedAtMs = now - 100,
            kind = StreamClassifier.Kind.ANNOUNCEMENT,
        )
        assertEquals(TailAction.DISCARD, plan.action)
    }

    @Test
    fun `a pause from long ago is not this stream end`() {
        // Otherwise one pause early in a session would make every later track
        // boundary behave like a pause for the rest of it.
        val plan = StreamEndPolicy.decide(
            nowMs = now,
            localPauseAtMs = now - StreamEndPolicy.LOCAL_PAUSE_WINDOW_MS - 1,
            remotePausedAtMs = null,
            kind = StreamClassifier.Kind.MUSIC,
        )
        assertEquals(TailAction.DRAIN, plan.action)
    }

    @Test
    fun `an announcement drains generously and music does not`() {
        val speech = StreamEndPolicy.decide(now, null, null, StreamClassifier.Kind.ANNOUNCEMENT)
        val music = StreamEndPolicy.decide(now, null, null, StreamClassifier.Kind.MUSIC)
        assertEquals(TailAction.DRAIN, speech.action)
        assertEquals(StreamEndPolicy.ANNOUNCEMENT_CAP_MS, speech.capMs)
        assertEquals(TailAction.DRAIN, music.action)
        assertEquals(StreamEndPolicy.MUSIC_CAP_MS, music.capMs)
    }

    @Test
    fun `an unclassified stream is treated as music`() {
        val plan = StreamEndPolicy.decide(now, null, null, StreamClassifier.Kind.UNKNOWN)
        assertEquals(StreamEndPolicy.MUSIC_CAP_MS, plan.capMs)
    }

    @Test
    fun `a pause landing inside the grace window converts the drain`() {
        val plan = TailPlan(TailAction.DRAIN, StreamEndPolicy.ANNOUNCEMENT_CAP_MS)
        assertEquals(TailAction.DISCARD, StreamEndPolicy.revise(plan, pausedSinceEnd = true))
        assertEquals(TailAction.DRAIN, StreamEndPolicy.revise(plan, pausedSinceEnd = false))
    }
}

class StreamClassifierTest {

    @Test
    fun `audio while the queue is not playing is an announcement`() {
        assertEquals(
            StreamClassifier.Kind.ANNOUNCEMENT,
            StreamClassifier.classify("paused", queueStateAgeMs = 50, msSincePreviousStreamEnd = null),
        )
        assertEquals(
            StreamClassifier.Kind.ANNOUNCEMENT,
            StreamClassifier.classify("idle", queueStateAgeMs = 50, msSincePreviousStreamEnd = null),
        )
    }

    @Test
    fun `audio while the queue is playing is music`() {
        assertEquals(
            StreamClassifier.Kind.MUSIC,
            StreamClassifier.classify("playing", queueStateAgeMs = 50, msSincePreviousStreamEnd = null),
        )
    }

    @Test
    fun `a stream that follows straight on from another is the next track`() {
        // MA has not necessarily republished the player state by then, so the state
        // must not be allowed to call a gapless track change an announcement.
        assertEquals(
            StreamClassifier.Kind.MUSIC,
            StreamClassifier.classify("paused", queueStateAgeMs = 50, msSincePreviousStreamEnd = 30),
        )
    }

    @Test
    fun `a stale reading is not trusted`() {
        assertEquals(
            StreamClassifier.Kind.UNKNOWN,
            StreamClassifier.classify(
                "paused",
                queueStateAgeMs = StreamClassifier.MAX_STATE_AGE_MS + 1,
                msSincePreviousStreamEnd = null,
            ),
        )
        assertEquals(
            StreamClassifier.Kind.UNKNOWN,
            StreamClassifier.classify(null, queueStateAgeMs = 0, msSincePreviousStreamEnd = null),
        )
    }
}
