package com.engabd.sendpin.service

import com.engabd.sendpin.ma.MaPlayer
import com.engabd.sendpin.ma.MaQueue
import com.engabd.sendpin.ma.isPlaying
import com.engabd.sendpin.ma.seekableDurationMs
import com.engabd.sendpin.ui.viewmodel.PlayerPositionTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/**
 * The Music Assistant playhead: where the selected player is, right now, for every
 * bar in the app — the screen, the notification, Android Auto, the car.
 *
 * One instance, owned by [MaNowPlaying], because there is one selected player and
 * two bars showing different positions for it was a bug the app actually had. The
 * model is the official Music Assistant app's (`PlayerPositionTracker` +
 * `LocalPlayerController` there), on top of this app's capture-time
 * [PlayerPositionTracker]:
 *
 * - **Server readings anchor, the projection ticks.** A `queue_updated` event, a
 *   `queue_time_updated` event and the 5 s poll all feed the same [tracker]; it
 *   decides what is news, what is stale and what is a stalled server clock.
 * - **Only this phone's own player gets an optimistic hold.** A seek or a skip on the
 *   self player holds the bar at the target (or zero) until the *first chunk of the
 *   new stream* arrives — [Playback.streamChunkSeq], the official app's
 *   `Buffering → Synchronized` edge. A remote speaker gets no hold: the command is
 *   sent, and the server's next reading is the answer (the scrubber's own two-second
 *   latch covers the round trip visually).
 * - **The hold cannot wedge.** It releases on the edge, immediately when the command
 *   throws, and after [WATCHDOG_MS] regardless — a backstop for a stream that never
 *   comes, not a timing the design relies on. The official app has no watchdog; a
 *   bar stuck on a rejected seek is the worse failure.
 *
 * Keyed by the **player** id, not the queue id. Music Assistant flaps a player's
 * `synced_to` around transport changes, and a tracker keyed on the queue re-keyed
 * to an entry with no anchor — a bar at 0:00 — every time it did.
 */
class MaPlayhead(
    private val scope: CoroutineScope,
    private val nowMs: () -> Long = { System.currentTimeMillis() },
    private val tracker: PlayerPositionTracker = PlayerPositionTracker(nowMs),
) {
    /** The selected player, as the owner resolves it on every reading. */
    data class Target(
        /** The player id — the tracker key. */
        val key: String,
        /** The queue its numbers come from: the group leader's, for a member. */
        val queueId: String,
        /** This phone is the player, so its stream edge can confirm a hold. */
        val isSelf: Boolean,
    )

    // ── Server readings ──────────────────────────────────────────────────────

    /** The 5 s poll: the player and its queue as last read. */
    fun onPoll(t: Target, player: MaPlayer?, queue: MaQueue?) =
        anchor(t, player, queue, isPlaying = player?.isPlaying)

    /**
     * A `queue_updated` / `queue_added` / `queue_items_updated` event: the queue
     * object as the server holds it now, ahead of the next poll.
     */
    fun onQueueUpdated(t: Target, queue: MaQueue, player: MaPlayer?) {
        if (queue.queueId != t.queueId) return
        anchor(t, player, queue, isPlaying = queue.isPlaying)
    }

    /**
     * A `queue_time_updated` event: the queue's elapsed seconds, sent on a jump.
     *
     * No stamp comes with it. The server computes the value at the moment it emits
     * the event, so arrival is its capture time to within a hop — and stamping it so
     * lets the tracker's stalled-clock rule see it, which matters at every track
     * start (see [notCountingYet]).
     */
    fun onQueueTime(t: Target, queueId: String, elapsedMs: Long) {
        if (queueId != t.queueId) return
        if (notCountingYet(elapsedMs)) return
        tracker.setAnchor(t.key, elapsedMs, capturedAtMs = nowMs())
    }

    /**
     * Music Assistant's first second after a stream (re)start, when it has not
     * started counting.
     *
     * The server's elapsed for a Sendspin player is `None` until a full second of
     * the new stream has been committed; until then the queue reports the bare
     * seek offset — 0 after a skip, the target after a seek — with a fresh stamp,
     * and the "jump" event it emits for the change carries that same number about
     * a second after this phone's stream edge. Anchoring it dragged a bar that had
     * rightly been ticking for a second straight back to where it started, on every
     * skip and every seek. So for [RELEASE_GRACE_MS] after a hold lifts, a reading
     * that merely repeats the held value is the server not counting yet; anything
     * else is news.
     */
    private fun notCountingYet(elapsedMs: Long): Boolean {
        val at = releasedAtMs
        return at != 0L && nowMs() - at < RELEASE_GRACE_MS && elapsedMs == releasedValueMs
    }

    /** A `player_updated` event: play/pause, without a position. */
    fun onPlayerUpdated(t: Target, player: MaPlayer) {
        if (player.playerId != t.key) return
        tracker.setPlaying(t.key, player.isPlaying)
    }

    private fun anchor(t: Target, player: MaPlayer?, queue: MaQueue?, isPlaying: Boolean?) {
        val fromQueue = queue?.elapsedMs
        val elapsed = fromQueue ?: player?.nowPlaying?.elapsedMs ?: return
        if (notCountingYet(elapsed) && !tracker.isFrozen(t.key)) return
        // The server's own capture time, only when the reading came with one — the
        // player object's `elapsed_time` carries no stamp, and null means "anchor at
        // arrival", which is right for it.
        val capturedAtMs = if (fromQueue != null) {
            queue.elapsedTimeLastUpdated?.let { (it * 1000).toLong() }
        } else null
        tracker.setAnchor(
            queueId = t.key,
            elapsedMs = elapsed,
            capturedAtMs = capturedAtMs,
            isPlaying = isPlaying,
            durationMs = seekableDurationMs(queue, player).takeIf { it > 0 },
            speed = queue?.playbackSpeed,
            itemId = queue?.currentQueueItemId,
        )
    }

    // ── The self player's stream edge ────────────────────────────────────────

    @Volatile private var lastChunkSeq = 0L

    /** [Playback.streamChunkSeq] moved: the first chunk of a (re)started stream arrived. */
    fun onStreamChunk(seq: Long) {
        lastChunkSeq = seq
        val h = hold ?: return
        if (seq > h.armedAtSeq) release(h.key, "edge")
    }

    // ── Optimistic holds ─────────────────────────────────────────────────────

    private class Hold(val key: String, val valueMs: Long, val armedAtSeq: Long, val watchdog: Job)

    @Volatile private var hold: Hold? = null

    /** When the last hold lifted, and the value it was holding — see [notCountingYet]. */
    @Volatile private var releasedAtMs = 0L
    @Volatile private var releasedValueMs = -1L

    /**
     * Run [command] — a seek to [targetMs] — holding the bar there when [t] is this
     * phone. Rethrows whatever the command throws, after releasing the hold.
     */
    suspend fun <T> holdForSeek(t: Target, targetMs: Long, durationMs: Long?, command: suspend () -> T): T {
        if (!t.isSelf) return command()
        arm(t.key, targetMs) { tracker.setOptimisticSeek(t.key, targetMs, durationMs) }
        return runHeld(t.key, command)
    }

    /**
     * Run [command] — next, previous, a queue jump, a play — holding the bar at zero
     * when [t] is this phone.
     */
    suspend fun <T> holdForTrackChange(t: Target, command: suspend () -> T): T {
        if (!t.isSelf) return command()
        arm(t.key, 0L) { tracker.setOptimisticTrackChange(t.key) }
        return runHeld(t.key, command)
    }

    /**
     * Arm a seek hold without running the command — for a seek that is already on its
     * way through another route (the media session's, see
     * [Playback.onSelfSeekRequested]).
     */
    fun armSeek(t: Target, targetMs: Long, durationMs: Long?) {
        if (!t.isSelf) return
        arm(t.key, targetMs) { tracker.setOptimisticSeek(t.key, targetMs, durationMs) }
    }

    /** As [armSeek], for a skip. */
    fun armTrackChange(t: Target) {
        if (!t.isSelf) return
        arm(t.key, 0L) { tracker.setOptimisticTrackChange(t.key) }
    }

    private fun arm(key: String, valueMs: Long, freeze: () -> Unit) {
        hold?.watchdog?.cancel()
        releasedAtMs = 0L
        freeze()
        hold = Hold(
            key = key,
            valueMs = valueMs,
            armedAtSeq = lastChunkSeq,
            watchdog = scope.launch {
                delay(WATCHDOG_MS)
                release(key, "watchdog")
            },
        )
    }

    private suspend fun <T> runHeld(key: String, command: suspend () -> T): T =
        try {
            command()
        } catch (e: Exception) {
            // Nothing moved, so stop pretending it did; the next reading is the truth.
            release(key, "throw")
            throw e
        }

    /** [why] names the releasing condition — edge, watchdog, throw — for the reader, not the code. */
    @Suppress("UNUSED_PARAMETER")
    private fun release(key: String, why: String) {
        val h = hold ?: return
        if (h.key != key) return
        h.watchdog.cancel()
        hold = null
        releasedAtMs = nowMs()
        releasedValueMs = h.valueMs
        tracker.confirmPlaying(key)
    }

    // ── Outputs ──────────────────────────────────────────────────────────────

    fun observe(key: String): Flow<Long> = tracker.observe(key)
    fun effectiveMs(key: String): Long? = tracker.effectiveMs(key)
    fun isAtEnd(key: String): Boolean = tracker.isAtEnd(key)
    fun isHeld(key: String): Boolean = tracker.isFrozen(key)

    fun forget(key: String) = tracker.remove(key)

    fun clear() {
        hold?.watchdog?.cancel()
        hold = null
        tracker.clear()
    }

    companion object {
        /**
         * How long a hold may wait for the stream edge before it lets go anyway. Long
         * enough that it never fires on a working seek (the edge lands within ~1.5 s),
         * short enough that a seek the server refused, or a stream that never came,
         * costs seconds of a still bar rather than a stuck one.
         */
        const val WATCHDOG_MS = 8_000L

        /**
         * How long after a hold lifts a reading repeating the held value is taken as
         * the server not counting yet — see [notCountingYet]. Two seconds: the
         * server starts counting one second in, and its jump event for the change
         * arrives within the next.
         */
        const val RELEASE_GRACE_MS = 2_000L
    }
}
