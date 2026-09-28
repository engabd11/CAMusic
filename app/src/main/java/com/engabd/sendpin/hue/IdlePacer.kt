package com.engabd.sendpin.hue

/**
 * How hard the render loop should work, given how long the room has been idle.
 *
 * Light Sync streams at 60 Hz, which is right for music and matches Hue's own 50–60 Hz
 * guidance. It is not right for a paused player: the idle show is a slow drift whose
 * waves take tens of seconds to cross the room, and sixty frames a second of it, with
 * the low-latency Wi-Fi lock held, is most of what the feature cost with the screen
 * off and nothing playing.
 *
 * So once the room has been idle for [DEEP_AFTER_NANOS] the loop drops to
 * [DEEP_FRAME_NANOS] (10 Hz) and gives up the low-latency Wi-Fi lock; the first
 * frame of music puts both back. The minute of grace covers a skip, a seek or a
 * pause to answer the door without the room ever noticing.
 *
 * Deliberately *not* touched: the CPU wake lock. A loop that stalls under doze lets
 * the bridge time the session out, and a bridge-side close is treated as the Hue app
 * taking the area — never retried. Light Sync would then be off after a long pause
 * instead of dimmer, which is the opposite of the trade this is making.
 */
internal class IdlePacer(private val activeFrameNanos: Long) {

    private var idleSince = -1L

    /** True while the loop should run slowly and hold no low-latency lock. */
    var deep = false
        private set

    /**
     * Report one frame. [idle] is the render loop's own verdict (nothing playing, or
     * no fresh analysis); [now] is `System.nanoTime()`.
     *
     * @return true when [deep] changed on this frame, so the caller can take or
     *   release its Wi-Fi lock exactly once per transition.
     */
    fun onFrame(idle: Boolean, now: Long): Boolean {
        val wasDeep = deep
        if (!idle) {
            idleSince = -1L
            deep = false
        } else {
            if (idleSince < 0L) idleSince = now
            deep = now - idleSince >= DEEP_AFTER_NANOS
        }
        return deep != wasDeep
    }

    /** The period to schedule the next frame at. */
    val frameNanos: Long get() = if (deep) DEEP_FRAME_NANOS else activeFrameNanos

    /**
     * The largest step the engine may integrate. The active clamp stops a stall from
     * jumping the animation; at 10 Hz every step is 100 ms by design, so the clamp
     * has to allow it or the idle show would run at a fraction of its speed.
     */
    fun maxStepS(activeMaxStepS: Float): Float =
        if (deep) maxOf(activeMaxStepS, DEEP_FRAME_NANOS * 1.5f / 1e9f) else activeMaxStepS

    companion object {
        const val DEEP_AFTER_NANOS = 60_000_000_000L
        const val DEEP_FRAME_NANOS = 100_000_000L
    }
}
