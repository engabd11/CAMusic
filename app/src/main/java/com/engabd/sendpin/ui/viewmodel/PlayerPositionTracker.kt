package com.engabd.sendpin.ui.viewmodel

import android.os.SystemClock
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
 * Ported from the official Music Assistant app's `PlayerPositionTracker`. The shape
 * matters more than the code: an *anchor* per queue (a server-reported elapsed time
 * plus the local instant it was captured) that the UI forward-projects between
 * updates, rather than a set of loose scalars re-derived on every poll.
 *
 * Three properties are what make seeking and skipping solid, and all three were
 * missing from the scalar engine this replaces:
 *
 *  - **Optimistic freeze.** A seek or a track change drops an anchor and *stops
 *    accepting server updates for it* until [confirmPlaying]. Music Assistant keeps
 *    reporting the old position for a beat after a skip, and the previous engine
 *    dutifully rendered it — the bar jumped backwards, then forwards again.
 *  - **A staleness gate.** Anchors are only replaced by fresher readings; see
 *    [MaQueue.elapsedTimeLastUpdated][com.engabd.sendpin.ma.MaQueue] and the caller's
 *    check. Out-of-order poll responses can no longer pin the bar to a finished track.
 *  - **A play/pause snapshot.** [setPlaying] rewrites the anchor to where the
 *    playhead actually is, so time spent paused doesn't fold into the next forward
 *    step.
 *
 * Time is measured with [SystemClock.elapsedRealtime] rather than the wall clock, so
 * an NTP correction mid-track can't jump the bar.
 *
 * There is no background tick: [observe] is cold and only runs while something is
 * collecting *and* the queue is playing. [effectiveMs] is an O(1) map read.
 */
class PlayerPositionTracker(
    /** Monotonic milliseconds. Injectable so the projection is testable off-device. */
    private val nowMs: () -> Long = { SystemClock.elapsedRealtime() },
) {

    /** Why an anchor is refusing server updates. */
    enum class FreezeReason { SEEK, TRACK_CHANGE }

    /** One queue's playhead. */
    data class Anchor(
        val elapsedMs: Long,
        /** The monotonic instant [elapsedMs] was captured at. */
        val atRealtimeMs: Long,
        val isPlaying: Boolean,
        val durationMs: Long,
        /**
         * Media-milliseconds per wall-millisecond (1.0 = normal). The server reports
         * elapsed in media-time, so a variable-speed queue (audiobooks, podcasts)
         * needs the projected delta scaled to match or the bar drifts and then snaps
         * back on every anchor.
         */
        val speed: Float = 1f,
        val freezeReason: FreezeReason? = null,
    ) {
        /** Anchor plus speed-scaled time since it was taken, capped at the duration. */
        fun effectiveAt(now: Long): Long {
            if (!isPlaying || freezeReason != null) return clamp(elapsedMs)
            return clamp(elapsedMs + ((now - atRealtimeMs) * speed).toLong())
        }

        /** True once the projection has run out the track — the caller should re-poll. */
        fun isAtEnd(now: Long): Boolean = durationMs > 0 && effectiveAt(now) >= durationMs

        private fun clamp(v: Long): Long =
            if (durationMs > 0) v.coerceIn(0L, durationMs) else v.coerceAtLeast(0L)
    }

    private val anchors = MutableStateFlow<Map<String, Anchor>>(emptyMap())

    /**
     * A reading from the server. [isPlaying], [durationMs] and [speed] keep their
     * current values when null, so an event that only carries a new elapsed time
     * doesn't wipe what a fuller poll established.
     *
     * Ignored entirely while the anchor is frozen — that is the point of the freeze.
     */
    fun setAnchor(
        queueId: String,
        elapsedMs: Long,
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
                    atRealtimeMs = nowMs(),
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
     */
    fun setPlaying(queueId: String, isPlaying: Boolean) {
        anchors.update { existing ->
            val current = existing[queueId] ?: return@update existing
            if (current.isPlaying == isPlaying) return@update existing
            existing + (
                queueId to current.copy(
                    elapsedMs = current.effectiveAt(nowMs()),
                    atRealtimeMs = nowMs(),
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
                    atRealtimeMs = nowMs(),
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
                    atRealtimeMs = nowMs(),
                    isPlaying = true,
                    freezeReason = null,
                )
            )
        }
    }

    /** Current projected position, or null if this queue has no anchor yet. */
    fun effectiveMs(queueId: String): Long? = anchors.value[queueId]?.effectiveAt(nowMs())

    /** True while a seek or track change is waiting on [confirmPlaying]. */
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
    }
}
