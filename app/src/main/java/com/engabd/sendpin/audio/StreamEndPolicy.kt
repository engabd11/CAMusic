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
 * decoded ([SendspinNativeEngine.flush] and its long note). That is exactly
 * right for a pause — the server has streamed up to ~30 s ahead, and playing it out
 * made pausing take over 20 s to fall silent — and exactly wrong for the other two,
 * because the tail *is* the end of the thing you were listening to. An announcement
 * is short enough that the discarded read-ahead is a meaningful fraction of it, which
 * is why roughly two in five of them lost their last words while music never
 * noticeably did.
 *
 * ## Why the first attempt at this made it worse
 *
 * Nothing here worked, because none of it ran. The `player_updated` collector that
 * feeds every field below read `state` from the event, and a serialised MA player
 * carries `playback_state` — so no reading was ever taken, no stream was ever
 * classified as anything, and every `stream/end` fell through to the default. What
 * *did* change was that a `stream/end` now started a drain coroutine, and a drain
 * outlives the stream that started it: MA begins an announcement a few hundred
 * milliseconds after ending the music, inside the music tail's own 400 ms cap, so the
 * music's drain reached the announcement and silenced it a moment after its
 * pre-announcement tone. Fixing the key is what makes the rest of this file real; the
 * stream id on every tail operation is what makes the drain safe either way.
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
 *    Home Assistant app — which this app is not otherwise told about. It does *not*
 *    count against an announcement: MA pauses the queue to make room for the clip,
 *    so the very event that identifies an announcement used to condemn its tail.
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
 * normally ends when the player reports it has played everything, which is sooner.
 *
 * [kind] rides along because the drain has to make the same judgement [decide] just
 * made, about a pause that lands a moment later: for an announcement a remote "not
 * playing" is the announcement's own queue pause and must not cut it short.
 */
data class TailPlan(
    val action: TailAction,
    val capMs: Long,
    val kind: StreamClassifier.Kind = StreamClassifier.Kind.UNKNOWN,
)

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
        // A remote "not playing" is how Music Assistant *announces*: it pauses the
        // queue to make room for the clip. Reading that as a pause meant the
        // announcement's tail was thrown away precisely because it was an
        // announcement — one event both classified the stream and condemned it. There
        // is nothing to lose by ignoring it here, for the same reason
        // [ANNOUNCEMENT_CAP_MS] can be generous: MA offers no pause button for a TTS
        // clip, so a drain here cannot be a mis-read pause. A *local* pause still
        // wins, because that one is the listener rather than the server.
        val remotelyPaused = kind != StreamClassifier.Kind.ANNOUNCEMENT &&
            remotePausedAtMs != null && nowMs - remotePausedAtMs in 0..LOCAL_PAUSE_WINDOW_MS
        if (locallyPaused || remotelyPaused) return TailPlan(TailAction.DISCARD, 0L, kind)
        val cap = if (kind == StreamClassifier.Kind.ANNOUNCEMENT) ANNOUNCEMENT_CAP_MS else MUSIC_CAP_MS
        return TailPlan(TailAction.DRAIN, cap, kind)
    }

    /**
     * Re-decide once the grace window has closed.
     *
     * [pausedSinceEnd] is whether a pause landed *after* the `stream/end` that started
     * this drain — an earlier one has already been accounted for by [decide].
     */
    fun revise(plan: TailPlan, pausedSinceEnd: Boolean): TailAction =
        if (pausedSinceEnd) TailAction.DISCARD else plan.action

    /** What a running drain should do, re-evaluated on every poll. */
    enum class DrainStep {
        /** Keep waiting: there is still audio to be heard and nothing has overtaken it. */
        WAIT,

        /** The player has played everything it was given. Cut, so a pause after this is instant. */
        PLAYED_OUT,

        /** [capMs] is up and the player never finished. Cut: the safety net. */
        EXPIRED,

        /** A pause landed inside the grace window. Cut, so it still feels immediate. */
        PAUSED,

        /**
         * A newer stream is playing. Stop **without** cutting.
         *
         * The one outcome that is not a cut, and the reason this is a function rather
         * than an inline condition. A tail outlives its stream, so a drain can still
         * be counting when the next `stream/start` lands — and Music Assistant starts
         * an announcement a few hundred milliseconds after ending the music, which is
         * inside the music tail's own cap. Cutting there silences the announcement
         * instead of the music, a moment after its pre-announcement tone.
         */
        SUPERSEDED,
    }

    /**
     * [hasPlayedOut] is the engine's own end-of-playback for the stream this drain
     * belongs to — not "the frame queue is empty", which is true up to a second before
     * the audio has actually been heard, since the rest of it is in the decoder and
     * the audio track by then.
     *
     * The two pause flags are kept apart for the reason [decide] keeps them apart: a
     * remote one is how Music Assistant announces, and cutting an announcement on it
     * is cutting it because of what it is.
     */
    fun drainStep(
        nowMs: Long,
        endedAt: Long,
        plan: TailPlan,
        isCurrentStream: Boolean,
        hasPlayedOut: Boolean,
        localPausedSinceEnd: Boolean,
        remotePausedSinceEnd: Boolean,
    ): DrainStep {
        val paused = localPausedSinceEnd ||
            (remotePausedSinceEnd && plan.kind != StreamClassifier.Kind.ANNOUNCEMENT)
        return when {
            !isCurrentStream -> DrainStep.SUPERSEDED
            hasPlayedOut -> DrainStep.PLAYED_OUT
            paused && nowMs - endedAt <= REMOTE_PAUSE_GRACE_MS &&
                revise(plan, pausedSinceEnd = true) == TailAction.DISCARD -> DrainStep.PAUSED
            nowMs - endedAt >= plan.capMs -> DrainStep.EXPIRED
            else -> DrainStep.WAIT
        }
    }
}

/**
 * What kind of thing a `stream/start` is beginning.
 *
 * Music Assistant answers this itself, on any build that sends
 * `announcement_in_progress`, and that answer is taken over everything below it.
 *
 * The fallback is the inference, for servers that do not: MA plays an announcement by
 * pausing whatever queue is running and streaming the clip, so "audio arrived while
 * this player's queue is not playing" is what an announcement looks like from here.
 * It is a heuristic and it fails safe — [Kind.UNKNOWN] is treated as [Kind.MUSIC]
 * everywhere, which is the behaviour that shipped before any of this existed — but it
 * is also weak in exactly the case it exists for, which is why the flag is read first:
 * the announcement's `stream/start` follows the music's `stream/end` immediately, and
 * so does the next track's.
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
     * [announcementInProgress] is Music Assistant's own `announcement_in_progress` for
     * this player, or null on a build that does not send it.
     * [queueState] is MA's `playback_state` for *this* phone (`playing` / `paused` /
     * `idle`), or null if none has been seen. [msSincePreviousStreamEnd] is how long
     * ago the previous stream ended, or null if none has.
     */
    fun classify(
        announcementInProgress: Boolean?,
        announcementFlagAgeMs: Long,
        queueState: String?,
        queueStateAgeMs: Long,
        msSincePreviousStreamEnd: Long?,
        endLingerMs: Long = SendspinPlaybackSupport.END_LINGER_MS,
    ): Kind {
        // Being told beats every inference below it, and the inference is wrong in
        // exactly the case that matters most — see the track-boundary rule. Only
        // `true` is acted on: MA sets the flag before it plays and clears it after, so
        // a fresh `true` is definitive, while a `false` may simply not have caught up.
        if (announcementInProgress == true && announcementFlagAgeMs <= MAX_STATE_AGE_MS) {
            return Kind.ANNOUNCEMENT
        }
        // A stream that starts moments after one ended is usually the next track,
        // whatever the queue state says — MA has not necessarily republished it yet.
        //
        // "Not necessarily" is the whole of the rule, so it only holds while the
        // reading really is older than the boundary. An announcement looks identical
        // from here — MA ends the music stream and starts the clip immediately after —
        // and applying this unconditionally called every announcement the next track,
        // which is how the classifier came to answer MUSIC for all of them.
        val sinceBoundary = msSincePreviousStreamEnd
        if (sinceBoundary != null && sinceBoundary <= endLingerMs &&
            (queueState == null || queueStateAgeMs >= sinceBoundary)
        ) {
            return Kind.MUSIC
        }
        if (queueState == null || queueStateAgeMs > MAX_STATE_AGE_MS) return Kind.UNKNOWN
        return if (queueState.equals("playing", ignoreCase = true)) Kind.MUSIC else Kind.ANNOUNCEMENT
    }
}
