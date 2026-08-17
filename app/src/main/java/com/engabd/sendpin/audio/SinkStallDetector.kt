package com.engabd.sendpin.audio

/**
 * Decides when an audio sink that is accepting nothing has stopped being back pressure
 * and started being a fault.
 *
 * [OboeAudioSink] hands decoded PCM to a native ring. A ring that is briefly full is
 * normal — that is what `handleBuffer` returning `false` is *for*, and ExoPlayer
 * re-presents the same buffer a moment later. A ring that is full and never drains is
 * something else: it means nothing downstream is consuming, so the stream is silent, and
 * because the sink keeps politely answering "not yet" it stays silent indefinitely while
 * reporting a playing player. That is the failure mode PR #59 documents, and the
 * distinction between the two is only ever "how many times in a row".
 *
 * Split out of the sink so it can be tested without a native library, an ExoPlayer or a
 * device: the thresholds are the whole of the logic, and they are worth being exact
 * about.
 */
internal class SinkStallDetector(
    /**
     * Consecutive total refusals before saying so in the log.
     *
     * Well past what back pressure explains: ExoPlayer re-presents on its own cadence,
     * so a ring that is merely full clears within a handful of attempts.
     */
    private val refusalsBeforeWarning: Int = 50,
    /**
     * Consecutive total refusals before the sink is declared broken.
     *
     * ExoPlayer re-presents roughly every 10 ms while blocked, so this is on the order
     * of a couple of seconds of a stream that is producing no sound at all. Deliberately
     * well short of ExoPlayer's own ten-second "stuck with no progress" timeout: that
     * one fires with a generic message about the renderer, from which nobody has ever
     * guessed "the native audio ring is not draining".
     */
    private val refusalsBeforeFailure: Int = 250,
) {
    /** What this buffer's result means about the sink as a whole. */
    enum class Verdict {
        /** Normal. Either the buffer went in, or some of it did. */
        OK,

        /** Long enough with nothing accepted to be worth a line in the log. Once. */
        WARN,

        /** Nothing is draining this sink. Reported once, then it stays quiet. */
        STALLED,
    }

    private var consecutiveRefusals = 0
    private var warned = false
    private var stalled = false

    /** How many buffers in a row have been refused outright. */
    val refusals: Int get() = consecutiveRefusals

    /**
     * Record one `handleBuffer` result.
     *
     * A *partial* acceptance counts as progress, not as a refusal: the ring took some
     * of it, which it can only do if something is consuming the other end.
     */
    fun onBuffer(accepted: Int, offered: Int): Verdict {
        if (offered <= 0 || accepted > 0) {
            consecutiveRefusals = 0
            return Verdict.OK
        }
        consecutiveRefusals++
        if (consecutiveRefusals >= refusalsBeforeFailure && !stalled) {
            stalled = true
            return Verdict.STALLED
        }
        if (consecutiveRefusals >= refusalsBeforeWarning && !warned) {
            warned = true
            return Verdict.WARN
        }
        return Verdict.OK
    }

    /**
     * Start again — a new stream, or a flush.
     *
     * The one-shot flags reset with the count, because "the sink stalled" is a fact
     * about a stream rather than about the sink's whole lifetime: the next stream
     * deserves to be judged on its own, and to have its own line in the log if it fails
     * the same way.
     */
    fun reset() {
        consecutiveRefusals = 0
        warned = false
        stalled = false
    }
}
