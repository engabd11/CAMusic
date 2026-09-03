package com.engabd.sendpin.hue

/**
 * Where the light show's frames are coming from right now.
 *
 * Three of these are real audio and one is not, and the difference matters to the
 * user rather than only to the code — the show that follows a beat grid is a
 * different, lesser thing than the show that follows a spectrum, and the UI says so.
 * See [ShowStatus].
 */
enum class LightSyncFeed {
    /** This phone's own local player. PCM through the ExoPlayer tap. */
    LOCAL_PCM,

    /** A Music Assistant queue streaming *to this phone*. Also PCM through a tap. */
    SENDSPIN_PCM,

    /**
     * A Music Assistant queue playing on a *remote* speaker, or a library — MPD —
     * playing itself with this phone only as its remote.
     *
     * No audio is decoded here at all, so the frames are synthesised from the track's
     * offline analysis — see [com.engabd.sendpin.audio.ScanFrameSynth],
     * [com.engabd.sendpin.hue.ScanFrameSource] and
     * [com.engabd.sendpin.hue.MpdScanFrameSource].
     */
    SCAN_REMOTE,

    /** Another app's playback, captured through MediaProjection. */
    CAPTURE,
}

/**
 * Which feed should be driving the show.
 *
 * Pure, and one function rather than an expression inlined at the site that builds
 * [ActiveLightSyncSource], because the precedence used to be derived in two places
 * that derived it *differently* — which is exactly how a paused MA player ended up
 * driving one and not the other.
 */
object LightSyncFeedPicker {

    /**
     * Precedence, and the reasoning for it:
     *
     * 1. **This app's own players first.** Their PCM is exact and free — the tap is
     *    already in the render chain — and if either is playing, that *is* what the
     *    room is listening to.
     * 2. **Capture next.** Real audio, but post-mix, unbounded in what it might pick
     *    up, and it excludes this app's own uid anyway, so it can never compete with
     *    the two above.
     * 3. **The scan last.** Not audio at all: a schedule. Right only when nothing
     *    audible is reaching this phone.
     * 4. **Otherwise the local tap**, which is receiving nothing and produces the
     *    idle show — the honest answer to "nothing is playing".
     */
    fun pick(
        sendspinPlayingHere: Boolean,
        hasSendspinTap: Boolean,
        localPlaying: Boolean,
        captureRunning: Boolean,
        scanDriving: Boolean,
        /**
         * How the user wants the "Listen to this phone" feed to behave:
         * "auto" — internal tap when this app is playing, capture otherwise;
         * "internal" — always use the internal tap (ignore capture);
         * "projection" — always use MediaProjection capture.
         */
        phoneAudioFeed: String = "auto",
    ): LightSyncFeed {
        return when (phoneAudioFeed) {
            "internal" -> when {
                sendspinPlayingHere && hasSendspinTap -> LightSyncFeed.SENDSPIN_PCM
                localPlaying -> LightSyncFeed.LOCAL_PCM
                scanDriving -> LightSyncFeed.SCAN_REMOTE
                else -> LightSyncFeed.LOCAL_PCM
            }
            "projection" -> when {
                captureRunning -> LightSyncFeed.CAPTURE
                scanDriving -> LightSyncFeed.SCAN_REMOTE
                else -> LightSyncFeed.LOCAL_PCM
            }
            else -> when { // "auto" and anything unexpected
                sendspinPlayingHere && hasSendspinTap -> LightSyncFeed.SENDSPIN_PCM
                localPlaying -> LightSyncFeed.LOCAL_PCM
                captureRunning -> LightSyncFeed.CAPTURE
                scanDriving -> LightSyncFeed.SCAN_REMOTE
                else -> LightSyncFeed.LOCAL_PCM
            }
        }
    }
}
