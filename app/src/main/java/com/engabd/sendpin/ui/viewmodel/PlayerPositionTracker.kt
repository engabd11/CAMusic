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
 * 3. **Cap how far a capture may be forward-projected.** MA freezes
 *    `elapsed_time_last_updated` while paused, so an event arriving after a long pause
 *    carries a capture stale by the whole pause. Beyond the cap, anchor the value as-is
 *    instead of projecting it.
 * 4. **Re-anchor the timestamp to "now" on the paused→play boundary**, so a stale
 *    capture cannot jump the position on resume.
 *
 * Time is the wall clock (`System.currentTimeMillis`), matching the domain of
 * `elapsed_time_last_updated` — MA's server reports it as a Unix epoch in seconds,
 * and the caller converts to local ms. A monotonic clock would avoid NTP jumps, but
 * the cap on forward projection limits the damage from a clock correction to at most
 * [MAX_PROJECTION_MS] of inflated progress — the same bound that handles paused-stale
 * captures. The wall clock is also the only clock the server timestamp can be
 * meaningfully compared to.
 */
class PlayerPositionTracker(
    /** Monotonic milliseconds. Injectable so the projection is testable off-device. */
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {

    /** Why an anchor is refusing server updates. */
    enum class FreezeReason { SEEK, TRACK_CHANGE }

    /**
     * Max wall-clock gap we forward-project a server-captured elapsed time across.
     *
     * During live playback `(now - capturedAt)` is just WS latency (well under a second
     * on LAN), so projecting it yields the true current position. But the MA server
     * *freezes* `elapsed_time_last_updated` while paused, so a `QUEUE_UPDATED` that
     * lands right after a long pause/background carries a capture stale by the whole
     * pause duration — projecting that forward jumps the position ahead by the paused
     * seconds (and can shoot past the track end, e.g. 4:04 on a 3:31 track). Beyond
     * this cap the capture is treated as stale and the elapsed value is anchored as-is.
     */

    /** One queue's playhead. */
    data class Anchor(
        val elapsedMs: Long,
        /** The wall-clock instant [elapsedMs] was captured at — *server* capture time, not "now". */
        val capturedAtMs: Long,
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
         * Interpolation runs only while playing and not frozen. The projection is
         * capped at [PlayerPositionTracker.MAX_PROJECTION_MS] of wall-clock delta: a
         * capture stale by a long pause would otherwise shoot the bar past the track
         * end.
         */
        fun effectiveAt(now: Long): Long {
            if (!isPlaying || freezeReason != null) return clamp(elapsedMs)
            val projectedDelta = ((now - capturedAtMs).coerceIn(0L, MAX_PROJECTION_MS) * speed).toLong()
            return clamp(elapsedMs + projectedDelta)
        }

        /** True once the projection has run out the track — the caller should re-poll. */
        fun isAtEnd(now: Long): Boolean = durationMs > 0 && effectiveAt(now) >= durationMs

        private fun clamp(v: Long): Long =
            if (durationMs > 0) v.coerceIn(0L, durationMs) else v.coerceAtLeast(0L)
    }

    private val anchors = MutableStateFlow<Map<String, Anchor>>(emptyMap())

    /**
     * A reading from the server.
     *
     * [capturedAtMs] is the server's own capture timestamp (`elapsed_time_last_updated`
     * converted to the local monotonic clock), NOT "now". This is what makes
     * re-anchoring idempotent: every reading re-derives the same continuous function of
     * `(elapsed, capturedAt, now)`, so a repeated or sparse reading lands on the value
     * already displayed. There is nothing to jump, and therefore nothing to filter.
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
        capturedAtMs: Long,
        isPlaying: Boolean? = null,
        durationMs: Long? = null,
        speed: Float? = null,
    ) {
        anchors.update { existing ->
            val current = existing[queueId]
            if (current?.freezeReason != null) return@update existing
            existing + (
                queueId to Anchor(
                    elapsedMs = elapsedMs,
                    capturedAtMs = capturedAtMs,
                    isPlaying = isPlaying ?: current?.isPlaying ?: false,
                    durationMs = durationMs ?: current?.durationMs ?: 0L,
                    speed = speed ?: current?.speed ?: 1f,
                    freezeReason = null,
                )
            )
        }
    }

    /**
     * A play/pause transition. Snapshots the projected position as the new anchor so
     * neither the bar nor the media session sees a jump: pausing freezes it where it
     * was, resuming carries on from there.
     *
     * On the paused→play boundary the capture timestamp is re-anchored to "now", so a
     * stale capture (MA freezes `elapsed_time_last_updated` while paused) cannot jump
     * the position forward by the pause duration on resume.
     */
    fun setPlaying(queueId: String, isPlaying: Boolean) {
        anchors.update { existing ->
            val current = existing[queueId] ?: return@update existing
            if (current.isPlaying == isPlaying) return@update existing
            existing + (
                queueId to current.copy(
                    elapsedMs = current.effectiveAt(nowMs()),
                    capturedAtMs = nowMs(),
                    isPlaying = isPlaying,
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
         * Max wall-clock gap we forward-project a server-captured elapsed time across.
         *
         * During live playback `(now - capturedAt)` is just WS latency (well under a
         * second on LAN). But MA freezes `elapsed_time_last_updated` while paused, so
         * an event arriving after a long pause carries a capture stale by the whole
         * pause duration — projecting that forward jumps the bar ahead by the paused
         * seconds. Beyond this cap the capture is treated as stale and the elapsed
         * value is anchored as-is.
         *
         * 5 seconds: generous enough for any real WS latency on a LAN, tight enough
         * that a pause-then-event can't shoot the bar past the track end.
         */
        const val MAX_PROJECTION_MS = 5_000L
    }
}