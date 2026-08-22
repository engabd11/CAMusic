package com.engabd.sendpin.capture

/**
 * Telling "that app will not let us listen" apart from "nothing is playing".
 *
 * ## Why this is not optional
 *
 * An app can opt out of `AudioPlaybackCapture` by declaring
 * `android:allowAudioPlaybackCapture="false"`, and YouTube, YouTube Music and most
 * DRM-protected apps do. When they have, the capture succeeds, the `AudioRecord`
 * reads happily, and every sample is **exactly zero**. No exception, no error code,
 * no callback — the single most confusing failure the platform offers.
 *
 * Without something like this the user's only observation is "the lights do nothing",
 * which is indistinguishable from a bug in this app. Saying so plainly is most of the
 * value of the feature working at all.
 *
 * ## Why exact zeros rather than an RMS floor
 *
 * An opted-out app returns bit-exact silence. A genuinely quiet passage does not —
 * dither, noise floor and the tail of the last note all keep the low bits moving. So
 * counting non-zero samples separates the two cleanly, where a level threshold would
 * call a quiet intro "blocked" and a blocked app during a loud passage "fine".
 *
 * Pure, so the whole state machine is testable — which matters here, because
 * everything else in this feature needs a real device and a consent dialog.
 */
object CaptureSilenceWatchdog {

    /** Below this, the stream has not really started. Say nothing yet. */
    const val WARMUP_MS = 1_500L

    /** By this point silence means something. */
    const val VERDICT_MS = 4_000L

    enum class Verdict {
        /** Too early to say. */
        WARMING,

        /** Audio is arriving. */
        AUDIBLE,

        /** Silence, and something *is* playing — so the source app opted out. */
        SILENT_LIKELY_BLOCKED,

        /** Silence, and nothing is playing. Not a fault. */
        SILENT_NOTHING_PLAYING,
    }

    /**
     * [nonZeroSamples] is the running count since capture started; [somethingIsPlaying]
     * is `AudioManager.isMusicActive()`, which is true for a blocked app because the
     * *platform* can hear it perfectly well — it is only this app that cannot.
     */
    fun verdict(elapsedMs: Long, nonZeroSamples: Long, somethingIsPlaying: Boolean): Verdict = when {
        nonZeroSamples > 0 -> Verdict.AUDIBLE
        elapsedMs < WARMUP_MS -> Verdict.WARMING
        elapsedMs < VERDICT_MS -> Verdict.WARMING
        somethingIsPlaying -> Verdict.SILENT_LIKELY_BLOCKED
        else -> Verdict.SILENT_NOTHING_PLAYING
    }
}
