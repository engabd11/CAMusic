package com.engabd.sendpin.ui.viewmodel

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive

/**
 * Where each queue's playhead is, right now.
 *
 * Rewritten on the model of `massdroid_native`'s `PlayerRepositoryImpl.updatePosition()` /
 * `interpolatedPosition()`, which has none of the jump problems the old design accumulated
 * guards for and is markedly simpler. One decision does the work:
 *
 * > **Anchor to the server's capture time, never to "now".**
 *
 * The displayed position is a continuous function of `(elapsed, capturedAt, now)` while
 * playing, and frozen at `elapsed` otherwise. Re-anchoring is **idempotent**: every
 * reading re-derives the same value, so sparse or repeated readings cannot move the bar.
 * There is nothing to jump, and therefore nothing to filter.
 *
 * Four rules, in full:
 *
 * 1. Anchor `(elapsed, capturedAt)`; never substitute "now" for `capturedAt`.
 * 2. Interpolate **only while playing**; frozen at `elapsed` otherwise. Never advance
 *    across paused time.
 * 3. **A capture too old to project from is anchored as-is**, at arrival time. MA
 *    freezes `elapsed_time_last_updated` while paused, so an event landing after a
 *    long pause carries a capture stale by the whole pause; projecting that would jump
 *    the bar by the paused seconds. Past [MAX_PROJECTION_MS] the reading is taken at
 *    face value and interpolation continues from *now* — the bar keeps moving, which
 *    is the whole point of interpolating.
 * 4. **Re-anchor to "now" across a play/pause transition**, so a capture frozen over
 *    the pause cannot jump the position on resume.
 *
 * Rules 3 and 4 both re-base the anchor, and re-basing on every poll would be
 * non-idempotent all over again — a restated reading would drag the bar back to
 * wherever it was last re-based. So a reading carrying a capture stamp this queue is
 * already anchored on is discarded as *no news*: the anchor already running is the
 * better answer, and leaving it alone is what stops sparse polls moving the bar. That
 * one test stands in for the whole of the old guard machinery, and unlike those guards
 * it asks a question with an answer — "has the server recomputed since we last
 * looked?" — rather than guessing at intent.
 *
 * Time is the wall clock (`System.currentTimeMillis`), the only clock MA's
 * `elapsed_time_last_updated` — a Unix epoch in seconds — can be compared to. The two
 * clocks are not the same clock, and the asymmetry matters: a server clock reading
 * *ahead* of ours makes a capture look like it came from the future, which rule 3
 * already catches by anchoring at arrival time. A server clock reading *behind* ours
 * makes every capture look uniformly stale by the skew — well inside
 * [MAX_PROJECTION_MS] — so the skew was projected forward on every single anchor, and
 * the bar sat a second or two ahead of the music from 0:00 onwards.
 *
 * [skew] closes that without the clock-offset estimator this class deliberately does
 * not want. Real staleness is never negative, so across many readings the *smallest*
 * observed `(now - capturedAt)` is very nearly the pure offset between the clocks:
 * a rolling minimum estimates it for free out of readings that were arriving anyway,
 * with no extra round trips and nothing to keep in sync.
 */
class PlayerPositionTracker(
    /** Wall-clock milliseconds. Injectable so the projection is testable off-device. */
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {

    /** Why an anchor is refusing server updates. */
    enum class FreezeReason { SEEK, TRACK_CHANGE }

    /** One queue's playhead. */
    data class Anchor(
        val elapsedMs: Long,
        /**
         * The wall-clock instant interpolation runs from.
         *
         * The server's own capture time whenever that is close enough to project from,
         * so re-deriving from the same reading lands on the same value; arrival time
         * when it is not (see [PlayerPositionTracker.MAX_PROJECTION_MS]).
         */
        val capturedAtMs: Long,
        /**
         * The raw reading this anchor was built from: `elapsed_time` and
         * `elapsed_time_last_updated`, or null when the server sent no stamp.
         *
         * Only ever compared, never projected from — [elapsedMs] and [capturedAtMs]
         * drift away from these the moment a pause is folded in. Together they are how
         * [PlayerPositionTracker.setAnchor] tells a fresh reading from the server
         * restating itself.
         *
         * The *pair*, deliberately, not the stamp alone. A repeated stamp usually does
         * mean the server has not recomputed, but "usually" is not good enough here: if
         * MA ever reflects an outside seek in `elapsed_time` without moving the stamp,
         * a stamp-only test would swallow it, and on a remote speaker nothing else
         * would ever notice. Requiring both to repeat still kills the sawtooth — that
         * case repeats both — while leaving every real change a way through.
         *
         * Null [serverStampMs] means "can't tell", which counts as news: a server that
         * omits the field must not read as one that has stopped updating.
         */
        val serverElapsedMs: Long?,
        val serverStampMs: Long?,
        val isPlaying: Boolean,
        val durationMs: Long,
        /**
         * Media-milliseconds per wall-millisecond (1.0 = normal). The server reports
         * elapsed in media-time, so a variable-speed queue (audiobooks, podcasts) needs
         * the projected delta scaled to match or the bar drifts and then snaps back on
         * every anchor.
         */
        val speed: Float = 1f,
        val freezeReason: FreezeReason? = null,
    ) {
        /**
         * Anchor plus speed-scaled time since it was taken, capped at the duration.
         *
         * Interpolation runs only while playing and not frozen, and is deliberately
         * unbounded in time. [capturedAtMs] is already guaranteed recent by
         * [PlayerPositionTracker.setAnchor], so there is nothing left here to cap —
         * and capping *here* would stall the bar at `elapsed + cap` whenever the server
         * has not recomputed lately, which for a remote speaker is most polls.
         */
        fun effectiveAt(now: Long): Long {
            if (!isPlaying || freezeReason != null) return clamp(elapsedMs)
            return clamp(elapsedMs + ((now - capturedAtMs).coerceAtLeast(0L) * speed).toLong())
        }

        /** True once the projection has run out the track — the caller should re-poll. */
        fun isAtEnd(now: Long): Boolean = durationMs > 0 && effectiveAt(now) >= durationMs

        private fun clamp(v: Long): Long =
            if (durationMs > 0) v.coerceIn(0L, durationMs) else v.coerceAtLeast(0L)
    }

    private val anchors = MutableStateFlow<Map<String, Anchor>>(emptyMap())

    /**
     * How far behind this phone's wall clock the server's appears to run, in ms.
     *
     * A leaky rolling minimum of `(now - capturedAt)` over live readings. The minimum
     * because network latency and server staleness only ever *add* to that difference,
     * so the smallest value seen is the closest thing to the clock offset alone. Leaky
     * — it is allowed to drift back up by [SKEW_DECAY_MS] per reading — because a
     * minimum that could only fall would be permanently poisoned by one unusually
     * prompt reading, and would never notice an NTP correction on either machine.
     *
     * Zero until a reading has been seen, so the very first anchor behaves exactly as
     * it did before this existed.
     */
    @Volatile private var skewMs: Long = 0L

    /** True once [skewMs] means something. */
    @Volatile private var skewSeen = false

    /**
     * The correction actually applied, in ms — zero unless the clocks are far enough
     * apart to be worth correcting.
     *
     * The deadband is not a tolerance, it is the point of the thing. Two clocks within
     * a couple of hundred milliseconds produce a bar that is right; what they also
     * produce is exact idempotence, because the anchor is then the server's own stamp
     * untouched and any reading describing the same moment re-derives the same answer.
     * A correction that is always on would trade that property away to fix an error
     * nobody can see. Below [SKEW_DEADBAND_MS] this returns zero and the class behaves
     * exactly as it did before the estimator existed; above it, the correction is worth
     * more than the property.
     */
    val skew: Long get() = if (skewSeen && skewMs >= SKEW_DEADBAND_MS) skewMs else 0L

    /** The raw estimate, before the deadband. Diagnostics and tests. */
    val rawSkew: Long get() = if (skewSeen) skewMs else 0L

    /**
     * Fold one reading's apparent lag into [skewMs].
     *
     * Only readings that arrive while playing are used. A capture frozen across a pause
     * looks arbitrarily stale, and feeding those in would only ever push the estimate
     * up — never down, since this tracks a minimum — so they are simply not evidence.
     */
    private fun observeLag(lagMs: Long, playing: Boolean) {
        if (!playing || lagMs < 0 || lagMs > MAX_PROJECTION_MS) return
        skewMs = if (!skewSeen) lagMs else minOf(lagMs, skewMs + SKEW_DECAY_MS)
        skewSeen = true
    }

    /**
     * A reading from the server.
     *
     * [capturedAtMs] is the server's own capture timestamp (`elapsed_time_last_updated`
     * as local wall-clock ms), NOT "now", and null when the server sent none. Anchoring
     * on it is what makes re-anchoring idempotent: the displayed value is a continuous
     * function of `(elapsed, capturedAt, now)`, so a repeated or sparse reading lands on
     * the value already displayed.
     *
     * Three things happen, in an order that matters:
     *
     * 1. A play/pause transition is applied first and on its own terms — snapshot the
     *    projected position, re-base to now. MA freezes its capture stamp while paused,
     *    so applying the reading first would jump the bar forward by the paused seconds
     *    on resume.
     * 2. A reading whose capture stamp this queue is already anchored on is *no news*,
     *    and the running anchor is kept. Without this, steps 1 and 3 would re-base on
     *    every poll and the bar would sawtooth.
     * 3. Otherwise the reading is applied — anchored to its own capture time when that
     *    is recent enough to project from, and to now when it is not.
     *
     * [isPlaying], [durationMs] and [speed] keep their current values when null, so an
     * event that only carries a new elapsed time doesn't wipe what a fuller poll
     * established.
     *
     * Ignored entirely while the anchor is frozen — that is the point of the freeze.
     */
    fun setAnchor(
        queueId: String,
        elapsedMs: Long,
        capturedAtMs: Long?,
        isPlaying: Boolean? = null,
        durationMs: Long? = null,
        speed: Float? = null,
    ) {
        anchors.update { existing ->
            val current = existing[queueId]
            if (current?.freezeReason != null) return@update existing
            val now = nowMs()
            val playing = isPlaying ?: current?.isPlaying ?: false
            val duration = durationMs ?: current?.durationMs ?: 0L
            val rate = speed ?: current?.speed ?: 1f

            // 1. Play/pause first: hold the bar where it had got to, and start the clock
            //    again from here rather than from a capture taken before the pause.
            val running = if (current != null && current.isPlaying != playing) {
                current.copy(
                    elapsedMs = current.effectiveAt(now),
                    capturedAtMs = now,
                    isPlaying = playing,
                )
            } else {
                current
            }

            // 2. The server restating itself is not news. Let the projection carry on.
            if (running != null && capturedAtMs != null &&
                capturedAtMs == running.serverStampMs && elapsedMs == running.serverElapsedMs
            ) {
                val kept = running.copy(durationMs = duration, speed = rate)
                return@update if (kept == current) existing else existing + (queueId to kept)
            }

            // 3. News. Project from the server's own capture time while it is close
            //    enough to be worth having; otherwise take the reading at arrival time,
            //    which also covers a server clock reading ahead of ours.
            //
            //    The capture time is corrected for clock skew before it is used as an
            //    anchor. Without that, a server clock running a second behind this one
            //    made every capture look a second stale, and the projection added that
            //    second to the position on every anchor, forever — a bar that was ahead
            //    of the music even at the very start of a track. See [skew].
            if (capturedAtMs != null) observeLag(now - capturedAtMs, playing)
            val anchorAt = capturedAtMs
                ?.let { it + skew }
                // Never anchor in the future: the correction is an estimate, and one
                // that overshoots would run the bar backwards.
                ?.coerceAtMost(now)
                ?.takeIf { now - it in 0..MAX_PROJECTION_MS }
                ?: now
            existing + (
                queueId to Anchor(
                    elapsedMs = elapsedMs,
                    capturedAtMs = anchorAt,
                    serverElapsedMs = elapsedMs,
                    serverStampMs = capturedAtMs,
                    isPlaying = playing,
                    durationMs = duration,
                    speed = rate,
                    freezeReason = null,
                )
            )
        }
    }

    /**
     * The user dropped the scrubber at [elapsedMs]. Held until [confirmPlaying] —
     * server echoes of the *old* position must not drag the bar back.
     */
    fun setOptimisticSeek(queueId: String, elapsedMs: Long, durationMs: Long? = null) =
        freeze(queueId, elapsedMs, durationMs, FreezeReason.SEEK)

    /**
     * A next/previous/queue-jump. Position goes to zero and stays there until audio
     * is actually flowing, rather than briefly showing the outgoing track's time.
     */
    fun setOptimisticTrackChange(queueId: String, durationMs: Long? = null) =
        freeze(queueId, 0L, durationMs, FreezeReason.TRACK_CHANGE)

    private fun freeze(queueId: String, elapsedMs: Long, durationMs: Long?, reason: FreezeReason) {
        anchors.update { existing ->
            val current = existing[queueId]
            existing + (
                queueId to Anchor(
                    elapsedMs = elapsedMs,
                    capturedAtMs = nowMs(),
                    // Ours, not the server's, so whatever it says next counts as news
                    // and can confirm or replace this.
                    serverElapsedMs = null,
                    serverStampMs = null,
                    isPlaying = current?.isPlaying ?: false,
                    durationMs = durationMs ?: current?.durationMs ?: 0L,
                    speed = current?.speed ?: 1f,
                    freezeReason = reason,
                )
            )
        }
    }

    /** Audio is confirmed flowing — release the freeze and start ticking again. */
    fun confirmPlaying(queueId: String) {
        anchors.update { existing ->
            val current = existing[queueId] ?: return@update existing
            if (current.freezeReason == null) return@update existing
            existing + (
                queueId to current.copy(
                    elapsedMs = current.effectiveAt(nowMs()),
                    capturedAtMs = nowMs(),
                    isPlaying = true,
                    freezeReason = null,
                )
            )
        }
    }

    /** Current projected position, or null if this queue has no anchor yet. */
    fun effectiveMs(queueId: String): Long? = anchors.value[queueId]?.effectiveAt(nowMs())

    /** True while a seek or track change is waiting for [confirmPlaying]. */
    fun isFrozen(queueId: String): Boolean = anchors.value[queueId]?.freezeReason != null

    /** True once the projection has run past the end of the track. */
    fun isAtEnd(queueId: String): Boolean = anchors.value[queueId]?.isAtEnd(nowMs()) == true

    /**
     * Smoothly-ticking position for [queueId]. Emits immediately, re-emits whenever
     * the anchor changes (seek, skip, pause, a server nudge), and ticks every
     * [TICK_MS] while playing. Stops ticking when paused or frozen and waits for the
     * next anchor instead.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun observe(queueId: String): Flow<Long> = anchors
        .map { it[queueId] }
        .distinctUntilChanged()
        .flatMapLatest { anchor ->
            if (anchor == null) {
                flowOf(0L)
            } else {
                flow {
                    while (currentCoroutineContext().isActive) {
                        emit(anchor.effectiveAt(nowMs()))
                        if (!anchor.isPlaying || anchor.freezeReason != null) break
                        delay(TICK_MS)
                    }
                }
            }
        }

    /** Forget a queue (it went away, or the target changed). */
    fun remove(queueId: String) = anchors.update { it - queueId }

    /** Forget everything — disconnect, or a server switch. */
    fun clear() = anchors.update { emptyMap() }

    companion object {
        /**
         * How often the interpolated playhead is re-emitted.
         *
         * 250 ms, matching `LocalPlayer.POSITION_TICK_MS` and the Sendspin path. This
         * was 500, which is fine for a progress bar and visibly coarse for synced
         * lyrics: half a second is most of a sung line, so the highlight on a remote
         * speaker could sit a whole phrase behind the voice. The value is interpolated
         * locally between server polls, so the extra ticks cost arithmetic, not
         * traffic.
         */
        const val TICK_MS = 250L

        /**
         * How old a server capture may be and still be projected forward from.
         *
         * During live playback `(now - capturedAt)` is just WS latency (well under a
         * second on LAN), and projecting it yields the true current position. But MA
         * freezes `elapsed_time_last_updated` while paused, so an event landing after a
         * long pause carries a capture stale by the whole pause — projecting that
         * jumps the bar ahead by the paused seconds, and can shoot past the track end
         * (measured: 4:04 on a 3:31 track). Past this the reading is anchored at
         * arrival time instead, and interpolation carries on from there.
         *
         * This bounds the *anchor*, not the projection, and the distinction is the
         * whole point. Capping the projection would stall the bar at `elapsed + cap`
         * whenever the server has not recomputed lately — which for a remote speaker
         * is most polls, as [com.engabd.sendpin.hue.PositionSlew] records — turning
         * smooth interpolation back into the stepping it exists to remove.
         *
         * 5 seconds: generous enough for any real WS latency or modest clock skew,
         * tight enough that a pause-then-event is caught.
         */
        const val MAX_PROJECTION_MS = 5_000L

        /**
         * How fast the [skew] estimate is allowed to drift back up, per reading.
         *
         * A pure minimum can only ever fall, so a single unusually prompt reading would
         * pin the estimate low for the rest of the session and no clock correction on
         * either machine would ever be noticed. Letting it rise by 20 ms a reading means
         * a genuine step change is tracked out within a few seconds of polling, while
         * ordinary jitter — which is far larger than 20 ms and lands above the minimum
         * anyway — does not move it at all.
         */
        const val SKEW_DECAY_MS = 20L

        /**
         * How far apart the clocks have to look before the skew correction is applied.
         *
         * A quarter of a second. Below that the bar is already right to within a frame
         * or two of the seek bar's own resolution, and leaving the anchor alone keeps
         * the idempotence the rest of this class is built on. The bug this exists for
         * was one to two *seconds*, which is four to eight times this.
         */
        const val SKEW_DEADBAND_MS = 250L
    }
}