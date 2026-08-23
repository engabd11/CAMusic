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
            kind = StreamClassifier.Kind.MUSIC,
        )
        assertEquals(TailAction.DISCARD, plan.action)
    }

    @Test
    fun `an announcement is not paused by the pause that made room for it`() {
        // Music Assistant pauses the queue to play a clip, so the event that says
        // "not playing" is what identifies an announcement in the first place. Letting
        // it also count as a pause discarded the announcement's tail *because* it was
        // an announcement, which is the whole of "TTS still cuts off".
        val plan = StreamEndPolicy.decide(
            nowMs = now,
            localPauseAtMs = null,
            remotePausedAtMs = now - 100,
            kind = StreamClassifier.Kind.ANNOUNCEMENT,
        )
        assertEquals(TailAction.DRAIN, plan.action)
        assertEquals(StreamEndPolicy.ANNOUNCEMENT_CAP_MS, plan.capMs)
    }

    @Test
    fun `a local pause still stops an announcement`() {
        // The listener, not the server. Nothing about the clip makes that ignorable.
        val plan = StreamEndPolicy.decide(
            nowMs = now,
            localPauseAtMs = now - 100,
            remotePausedAtMs = null,
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

/**
 * What a running drain does on each poll.
 *
 * The table exists because the drain runs on a coroutine that outlives the stream it
 * belongs to, and the consequences of each outcome are audible rather than cosmetic.
 */
class DrainStepTest {

    private val endedAt = 1_000_000L
    private val plan = TailPlan(
        TailAction.DRAIN,
        StreamEndPolicy.ANNOUNCEMENT_CAP_MS,
        StreamClassifier.Kind.ANNOUNCEMENT,
    )

    private fun step(
        atMs: Long = endedAt + 50,
        plan: TailPlan = this.plan,
        isCurrentStream: Boolean = true,
        hasPlayedOut: Boolean = false,
        localPausedSinceEnd: Boolean = false,
        remotePausedSinceEnd: Boolean = false,
    ) = StreamEndPolicy.drainStep(
        nowMs = atMs,
        endedAt = endedAt,
        plan = plan,
        isCurrentStream = isCurrentStream,
        hasPlayedOut = hasPlayedOut,
        localPausedSinceEnd = localPausedSinceEnd,
        remotePausedSinceEnd = remotePausedSinceEnd,
    )

    private val musicPlan = TailPlan(
        TailAction.DRAIN,
        StreamEndPolicy.MUSIC_CAP_MS,
        StreamClassifier.Kind.MUSIC,
    )

    @Test
    fun `a tail with audio still in the sink waits`() {
        assertEquals(StreamEndPolicy.DrainStep.WAIT, step())
    }

    @Test
    fun `the cut waits for the player, not for an empty queue`() {
        // The whole point: an empty frame queue means the bytes reached ExoPlayer, and
        // up to a second of it is still undecoded or unplayed at that moment.
        assertEquals(StreamEndPolicy.DrainStep.PLAYED_OUT, step(hasPlayedOut = true))
    }

    @Test
    fun `a drain that has been overtaken does not cut`() {
        // MA starts an announcement a few hundred ms after ending the music, which is
        // inside the music tail's own 400ms cap. Cutting there silenced the
        // announcement a moment after its pre-announcement tone.
        assertEquals(
            StreamEndPolicy.DrainStep.SUPERSEDED,
            step(
                atMs = endedAt + StreamEndPolicy.MUSIC_CAP_MS + 1,
                plan = musicPlan,
                isCurrentStream = false,
            ),
        )
        // Even with every other reason to cut present.
        assertEquals(
            StreamEndPolicy.DrainStep.SUPERSEDED,
            step(isCurrentStream = false, hasPlayedOut = true, localPausedSinceEnd = true),
        )
    }

    @Test
    fun `a pause inside the grace window cuts`() {
        assertEquals(
            StreamEndPolicy.DrainStep.PAUSED,
            step(
                atMs = endedAt + StreamEndPolicy.REMOTE_PAUSE_GRACE_MS,
                plan = musicPlan,
                remotePausedSinceEnd = true,
            ),
        )
    }

    @Test
    fun `a pause after the grace window has closed does not`() {
        assertEquals(
            StreamEndPolicy.DrainStep.WAIT,
            step(
                atMs = endedAt + StreamEndPolicy.REMOTE_PAUSE_GRACE_MS + 1,
                plan = musicPlan,
                remotePausedSinceEnd = true,
            ),
        )
    }

    @Test
    fun `an announcement is not cut by MA pausing the queue behind it`() {
        // The same judgement `decide` makes at `stream/end`, made again here because
        // MA republishes the paused queue while the clip is still playing out.
        assertEquals(StreamEndPolicy.DrainStep.WAIT, step(remotePausedSinceEnd = true))
        // The listener pressing pause is still the listener.
        assertEquals(StreamEndPolicy.DrainStep.PAUSED, step(localPausedSinceEnd = true))
    }

    @Test
    fun `the cap is the safety net`() {
        assertEquals(
            StreamEndPolicy.DrainStep.EXPIRED,
            step(atMs = endedAt + StreamEndPolicy.ANNOUNCEMENT_CAP_MS),
        )
        assertEquals(
            StreamEndPolicy.DrainStep.WAIT,
            step(atMs = endedAt + StreamEndPolicy.ANNOUNCEMENT_CAP_MS - 1),
        )
    }
}

class StreamClassifierTest {

    private fun classify(
        announcing: Boolean? = null,
        announcingAgeMs: Long = 0,
        queueState: String? = null,
        queueStateAgeMs: Long = 50,
        msSincePreviousStreamEnd: Long? = null,
    ) = StreamClassifier.classify(
        announcementInProgress = announcing,
        announcementFlagAgeMs = announcingAgeMs,
        queueState = queueState,
        queueStateAgeMs = queueStateAgeMs,
        msSincePreviousStreamEnd = msSincePreviousStreamEnd,
    )

    @Test
    fun `Music Assistant saying so beats every inference`() {
        // Including the track-boundary rule, which otherwise reads an announcement as
        // the next track: MA ends the music stream and starts the clip immediately.
        assertEquals(
            StreamClassifier.Kind.ANNOUNCEMENT,
            classify(announcing = true, queueState = "playing", msSincePreviousStreamEnd = 30),
        )
    }

    @Test
    fun `a stale announcement flag is not trusted`() {
        assertEquals(
            StreamClassifier.Kind.MUSIC,
            classify(
                announcing = true,
                announcingAgeMs = StreamClassifier.MAX_STATE_AGE_MS + 1,
                queueState = "playing",
            ),
        )
    }

    @Test
    fun `a cleared flag falls back rather than deciding`() {
        // MA sets the flag before it plays and clears it after, so `false` may simply
        // not have caught up. Only `true` is definitive.
        assertEquals(
            StreamClassifier.Kind.ANNOUNCEMENT,
            classify(announcing = false, queueState = "paused"),
        )
    }

    @Test
    fun `audio while the queue is not playing is an announcement`() {
        assertEquals(StreamClassifier.Kind.ANNOUNCEMENT, classify(queueState = "paused"))
        assertEquals(StreamClassifier.Kind.ANNOUNCEMENT, classify(queueState = "idle"))
    }

    @Test
    fun `audio while the queue is playing is music`() {
        assertEquals(StreamClassifier.Kind.MUSIC, classify(queueState = "playing"))
    }

    @Test
    fun `a stream that follows straight on from an unexplained end is the next track`() {
        // MA has not necessarily republished the player state by then, so the state
        // must not be allowed to call a gapless track change an announcement.
        assertEquals(
            StreamClassifier.Kind.MUSIC,
            classify(queueState = "paused", queueStateAgeMs = 500, msSincePreviousStreamEnd = 30),
        )
    }

    @Test
    fun `a reading taken since the previous stream ended explains it`() {
        // "Not necessarily republished" is the whole of that rule, and it stops
        // holding the moment a fresher reading exists. Without this the boundary rule
        // swallowed every announcement, since MA always starts one within the linger.
        assertEquals(
            StreamClassifier.Kind.ANNOUNCEMENT,
            classify(queueState = "paused", queueStateAgeMs = 20, msSincePreviousStreamEnd = 100),
        )
        assertEquals(
            StreamClassifier.Kind.MUSIC,
            classify(queueState = "playing", queueStateAgeMs = 20, msSincePreviousStreamEnd = 100),
        )
    }

    @Test
    fun `a stale reading is not trusted`() {
        assertEquals(
            StreamClassifier.Kind.UNKNOWN,
            classify(queueState = "paused", queueStateAgeMs = StreamClassifier.MAX_STATE_AGE_MS + 1),
        )
        assertEquals(StreamClassifier.Kind.UNKNOWN, classify(queueState = null))
    }
}
