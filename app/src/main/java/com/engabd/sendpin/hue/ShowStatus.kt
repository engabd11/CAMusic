package com.engabd.sendpin.hue

/**
 * What the light show is actually doing, for the one line of text that says so.
 *
 * The Light Sync screen used to derive that line from [DirectLightSync.active], which
 * means "the DTLS entertainment session is open" and nothing more. It has never had
 * any relationship to whether analysis frames were arriving — so a Music Assistant
 * queue playing on a *remote* speaker, where this phone decodes no audio whatsoever,
 * reported "Reacting to the beat" while the lamps sat on the idle pattern. That gap
 * between what the app claimed and what the room did is the bug; this is the fix.
 */
enum class ShowStatus {
    /** Light sync is switched off. */
    OFF,

    /** On, connected, and nothing is playing. */
    WAITING,

    /** Following real audio, frame by frame. The full show. */
    LIVE_PCM,

    /**
     * Following the track's offline analysis, because the audio is somewhere this
     * phone cannot hear. Beats and structure, not spectrum.
     */
    SCAN_DRIVEN,

    /** The remote track is being analysed; the show is idle until it lands. */
    SCAN_PENDING,

    /** Playing somewhere this phone cannot hear, and there is no analysis to use. */
    NO_SCAN,

    /** Following another app's audio through capture. */
    CAPTURE,

    /** Capturing, but the source app forbids it — see the capture watchdog. */
    CAPTURE_BLOCKED,
}

/** What [ScanFrameSource] has managed to find for the current remote track. */
enum class ScanFeedState {
    /** Not applicable — nothing is playing remotely. */
    IDLE,

    /** A remote track is playing but nothing in any library matches it. */
    NO_MATCH,

    /** Matched, and an analysis has been requested. */
    SCANNING,

    /** Matched and analysed, but the beat grid was not good enough to schedule against. */
    NO_GRID,

    /** Frames are being synthesised right now. */
    DRIVING,
}

object ShowStatusRules {

    /**
     * [framesFresh] is the render loop's own freshness test — the same one that
     * decides between the real show and the idle pattern. Threading the *same* value
     * into the status is what stops the text and the lamps disagreeing.
     */
    fun statusFor(
        enabled: Boolean,
        sessionOpen: Boolean,
        feed: LightSyncFeed,
        framesFresh: Boolean,
        scanState: ScanFeedState,
        captureBlocked: Boolean,
    ): ShowStatus {
        if (!enabled || !sessionOpen) return ShowStatus.OFF
        return when (feed) {
            LightSyncFeed.LOCAL_PCM, LightSyncFeed.SENDSPIN_PCM ->
                if (framesFresh) ShowStatus.LIVE_PCM else ShowStatus.WAITING

            LightSyncFeed.CAPTURE -> when {
                captureBlocked -> ShowStatus.CAPTURE_BLOCKED
                framesFresh -> ShowStatus.CAPTURE
                else -> ShowStatus.WAITING
            }

            LightSyncFeed.SCAN_REMOTE -> when (scanState) {
                ScanFeedState.DRIVING -> if (framesFresh) ShowStatus.SCAN_DRIVEN else ShowStatus.WAITING
                ScanFeedState.SCANNING -> ShowStatus.SCAN_PENDING
                ScanFeedState.NO_MATCH, ScanFeedState.NO_GRID -> ShowStatus.NO_SCAN
                ScanFeedState.IDLE -> ShowStatus.WAITING
            }
        }
    }
}
