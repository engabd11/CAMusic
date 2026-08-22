package com.engabd.sendpin.audio

/**
 * What to do with the audio still buffered when `stream/end` arrives.
 *
 * ## The problem this exists to solve
 *
 * Music Assistant sends `stream/end` for three completely different events, and the
 * message is byte-identical for all of them:
 *
 * - the listener paused;
 * - a track finished and the next one is about to start;
 * - a TTS announcement finished.
 *
 * The engine's answer has been to throw the tail away and mute what was already
 * decoded ([SendspinDataSource.discardQueued] and its long note). That is exactly
 * right for a pause — the server has streamed up to ~30 s ahead, and playing it out
 * made pausing take over 20 s to fall silent — and exactly wrong for the other two,
 * because the tail *is* the end of the thing you were listening to. An announcement
 * is short enough that the discarded read-ahead is a meaningful fraction of it, which
 * is why roughly two in five of them lost their last words while music never
 * noticeably did.
 *
 * ## The discriminator
 *
 * The protocol cannot tell them apart, so this reasons from what the app knows
 * *around* the message, in descending order of confidence:
 *
 * 1. **A pause this app asked for.** Certain: every path that pauses MA goes through
 *    one call in `Playback`, which stamps the time.
 * 2. **An MA `player_updated` event saying this player is not playing.** Fast and
 *    reliable, and it covers a pause from another controller — the MA web UI, the
 *    Home Assistant app — which this app is not otherwise told about.
 * 3. **Nothing at all.** Treated as music, which is the conservative side: a
 *    misjudged announcement is truncated a little late rather than a little early,
 *    while a misjudged pause plays music the listener asked to stop.
 *
 * Pure and Android-free so the whole table can be exercised on the JVM — the same
 * reasoning [SendspinPlaybackSupport] is split out for.
 */
enum class TailAction {
    /** Throw the buffered tail away and go silent now. What a pause needs. */
    DISCARD,

    /** Let what is already buffered play out, up to [TailPlan.capMs]. */
    DRAIN,
}

/**
 * [capMs] bounds a [TailAction.DRAIN]: however long the tail turns out to be, the
 * output is cut after this. It is a safety net, not the mechanism — the drain
 * normally ends when the queue runs dry, which is sooner.
 */
data class TailPlan(val action: TailAction, val capMs: Long)

object StreamEndPolicy {

    /**
     * How long after a locally-issued pause a `stream/end` still counts as that pause.
     *
     * The round-trip is a LAN WebSocket hop each way plus whatever MA takes to stop
     * the stream. Generous, because the cost of being wrong in this direction is only
     * that a track boundary inside the window gets a pause's treatment — which is the
     * behaviour that shipped for the last several releases.
     */
    const val LOCAL_PAUSE_WINDOW_MS = 1_500L

    /**
     * How long a drain stays cancellable after it starts.
     *
     * A remote pause arrives as an MA event rather than as anything ordered against
     * the audio socket, so it can land just after `stream/end`. Waiting this long
     * *before* deciding would cost real audio when the answer is DRAIN, so the drain
     * starts immediately and this is how long it can still be converted.
     */
    const val REMOTE_PAUSE_GRACE_MS = 250L

    /**
     * A track boundary's tail.
     *
     * Short. The next `stream/start` usually lands within a few tens of milliseconds
     * and supersedes this anyway; the cap only matters when nothing follows, and there
     * a long tail is indistinguishable from the pause case.
     */
    const val MUSIC_CAP_MS = 400L

    /**
     * An announcement's tail.
     *
     * Generous, because no pause semantics apply to it: MA does not offer a pause
     * button for a TTS clip, so a drain here cannot be a mis-read pause. Long enough
     * to cover the read-ahead of any announcement anyone actually makes.
     */
    const val ANNOUNCEMENT_CAP_MS = 5_000L

    /**
     * What to do with this `stream/end`.
     *
     * [localPauseAtMs] and [remotePausedAtMs] are elapsed-realtime stamps of the last
     * pause seen from each source, or null for "never".
     */
    fun decide(
        nowMs: Long,
        localPauseAtMs: Long?,
        remotePausedAtMs: Long?,
        kind: StreamClassifier.Kind,
    ): TailPlan {
        val locallyPaused = localPauseAtMs != null && nowMs - localPauseAtMs in 0..LOCAL_PAUSE_WINDOW_MS
        val remotelyPaused = remotePausedAtMs != null && nowMs - remotePausedAtMs in 0..LOCAL_PAUSE_WINDOW_MS
        if (locallyPaused || remotelyPaused) return TailPlan(TailAction.DISCARD, 0L)
        val cap = if (kind == StreamClassifier.Kind.ANNOUNCEMENT) ANNOUNCEMENT_CAP_MS else MUSIC_CAP_MS
        return TailPlan(TailAction.DRAIN, cap)
    }

    /**
     * Re-decide once the grace window has closed.
     *
     * [pausedSinceEnd] is whether a pause landed *after* the `stream/end` that started
     * this drain — an earlier one has already been accounted for by [decide].
     */
    fun revise(plan: TailPlan, pausedSinceEnd: Boolean): TailAction =
        if (pausedSinceEnd) TailAction.DISCARD else plan.action
}

/**
 * What kind of thing a `stream/start` is beginning.
 *
 * Music Assistant plays an announcement by pausing whatever queue is running and
 * streaming the clip, so "audio arrived while this player's queue is not playing" is
 * what an announcement looks like from here. It is a heuristic and it fails safe:
 * [Kind.UNKNOWN] is treated as [Kind.MUSIC] everywhere, which is the behaviour that
 * shipped before any of this existed.
 */
object StreamClassifier {

    enum class Kind { MUSIC, ANNOUNCEMENT, UNKNOWN }

    /**
     * How stale a player-state reading may be and still be trusted.
     *
     * Past this the socket has probably been quiet for a reason — a reconnect, a
     * backgrounded process — and a stale "paused" would classify ordinary music as an
     * announcement, which is the direction that costs real audio.
     */
    const val MAX_STATE_AGE_MS = 4_000L

    /**
     * [queueState] is Music Assistant's own `MaPlayer.state` for *this* phone
     * (`playing` / `paused` / `idle`), or null if none has been seen.
     * [msSincePreviousStreamEnd] is how long ago the previous stream ended, or null if
     * none has.
     */
    fun classify(
        queueState: String?,
        queueStateAgeMs: Long,
        msSincePreviousStreamEnd: Long?,
        endLingerMs: Long = SendspinPlaybackSupport.END_LINGER_MS,
    ): Kind {
        // A stream that starts moments after one ended is the next track, whatever
        // the queue state says — MA has not necessarily republished it yet.
        if (msSincePreviousStreamEnd != null && msSincePreviousStreamEnd <= endLingerMs) return Kind.MUSIC
        if (queueState == null || queueStateAgeMs > MAX_STATE_AGE_MS) return Kind.UNKNOWN
        return if (queueState.equals("playing", ignoreCase = true)) Kind.MUSIC else Kind.ANNOUNCEMENT
    }
}
