package com.engabd.sendpin.audio

/**
 * The policy behind "Exclusive output": what it forces on, what it turns off,
 * and whether a given decoded stream can actually reach the DAC through a
 * given route without this app doing anything to it.
 *
 * A pure object with no Android dependency, on purpose — [LocalPlayer] and the
 * Settings UI both have to agree on what this mode means, and the only way to
 * guarantee that is for both to read it from one place rather than each
 * holding its own copy of the rules. Shaped like [FormatNegotiator] for the
 * same reason that one is a plain object: the policy is what is worth testing,
 * not the platform calls around it.
 *
 * **The honest ceiling.** Android gives an ordinary app no exclusive or DIRECT
 * output flag, and a real USB Audio Class driver over `UsbManager` — the only
 * way to actually own the hardware — is out of scope here. What this mode
 * does is remove every stage *this app* puts between the decoder and the DAC,
 * and ask the platform to carry the source's own rate and depth. It cannot
 * reach past AudioFlinger, Android's own mixer — see [mixerRateCaveat]. See
 * [ANDROID_CEILING_NOTE] for the exact wording the UI shows.
 */
object ExclusiveOutput {

    /** One thing this app normally does to a decoded stream that this mode turns off. */
    data class Disabled(val title: String, val reason: String)

    /**
     * What exclusive mode disables, as data rather than a string written out
     * separately in the toggle's own `info` and in every screen that then has
     * to explain why its own control has gone inert (the EQ panel, the
     * sound-mode rows) — one list, rendered wherever the reason is needed.
     */
    val disables: List<Disabled> = listOf(
        Disabled(
            "Equaliser",
            "No processor of this app's runs on the float output path this mode forces.",
        ),
        Disabled(
            "Light Sync audio analysis",
            "The tap is a processor too, so there is nothing left to feed it from.",
        ),
        Disabled(
            "Vinyl / Lo-fi sound modes",
            "Same chain as the equaliser, same reason.",
        ),
        Disabled(
            "ReplayGain",
            "Levelling is a volume multiply applied to the signal before the DAC sees " +
                "it, so it goes with everything else. Albums play at their mastered " +
                "level, loud ones included.",
        ),
        Disabled(
            "Fades and speed-adaptive volume",
            "Both ride on the same gain ReplayGain does. Track transitions are gapless " +
                "here whatever the smooth-transitions setting says.",
        ),
        Disabled(
            "Output resampling",
            "The fixed output-rate setting is a processor too; every file goes out at " +
                "its own rate instead.",
        ),
    )

    /**
     * What this mode explicitly does *not* take away, because the list above
     * reads as though it might.
     *
     * The volume slider on Now Playing is unaffected: on a local session it
     * already drives the phone's own media volume rather than any gain inside
     * the player (see `NowPlayingViewModel.setVolume`), which is the one place
     * volume can live without touching the samples on their way out.
     */
    const val VOLUME_NOTE = "The volume slider still works — on this phone's own library " +
        "it drives the device's media volume, not a gain inside the player, so it was " +
        "never part of the signal to begin with."

    /**
     * The limit stated plainly, for the toggle's own `info` text and anywhere
     * else this mode gets explained. See the class doc.
     */
    const val ANDROID_CEILING_NOTE = "Android gives apps no exclusive or DIRECT output mode, " +
        "and a full USB Audio Class driver is out of scope for this app. This removes every " +
        "stage CAMusic adds and asks the platform for the source's own rate and depth — it " +
        "cannot bypass AudioFlinger, Android's own mixer."

    /**
     * Could a route carry a stream at [sampleRateHz] / [bitDepth] through to the
     * DAC without this app resampling or requantising it first?
     *
     * Takes the route's capability as plain [routeSampleRates] / [routeEncodings]
     * lists — the same fields [DeviceCapabilities.Route] exposes — rather than
     * the `Route` itself, so this object stays free of any Android type and is
     * trivial to check from a plain unit test.
     *
     * An empty list means the platform did not report that capability
     * ([DeviceCapabilities.Route] documents the same convention for the same
     * reason), which is not evidence either way — so on its own it does not
     * rule the rate or depth out. This only answers false when something the
     * platform *did* report actually contradicts the stream.
     */
    fun canCarryUntouched(
        sampleRateHz: Int,
        bitDepth: Int,
        routeSampleRates: List<Int>,
        routeEncodings: List<Int>,
    ): Boolean {
        if (routeSampleRates.isNotEmpty() && sampleRateHz !in routeSampleRates) return false
        if (routeEncodings.isNotEmpty() && routeEncodings.none { it in encodingsFor(bitDepth) }) return false
        return true
    }

    /**
     * The honest caveat for when Android's own mixer is running at a different
     * rate to the stream — null when the two agree or either is unknown.
     *
     * This is the one stage exclusive mode cannot remove: AudioFlinger mixes
     * every stream at one rate, and a track that does not match it is
     * resampled there, invisibly, whatever this app asked the platform for.
     * See the class doc.
     */
    fun mixerRateCaveat(streamRateHz: Int, mixerRateHz: Int): String? {
        if (streamRateHz <= 0 || mixerRateHz <= 0 || streamRateHz == mixerRateHz) return null
        return "Android's own mixer is running at ${StreamQuality.khz(mixerRateHz)} kHz against " +
            "a ${StreamQuality.khz(streamRateHz)} kHz stream, so it is being resampled there — " +
            "past AudioFlinger, which this app cannot reach."
    }

    /**
     * The `android.media.AudioFormat.ENCODING_PCM_*` values [canCarryUntouched]
     * needs to recognise, reproduced as plain ints so this object carries no
     * Android import. Stable public API constants — part of `AudioFormat`'s
     * documented contract, so they cannot change under an app; verified against
     * the platform's own `AudioFormat.class`. [DeviceCapabilities] holds the
     * same values behind its own `android.media.AudioFormat` import.
     */
    private fun encodingsFor(bitDepth: Int): Set<Int> = when (bitDepth) {
        8 -> setOf(ENCODING_PCM_8BIT)
        16 -> setOf(ENCODING_PCM_16BIT)
        24 -> setOf(ENCODING_PCM_24BIT_PACKED)
        32 -> setOf(ENCODING_PCM_32BIT, ENCODING_PCM_FLOAT)
        else -> emptySet()
    }

    private const val ENCODING_PCM_16BIT = 2
    private const val ENCODING_PCM_8BIT = 3
    private const val ENCODING_PCM_FLOAT = 4
    private const val ENCODING_PCM_24BIT_PACKED = 21
    private const val ENCODING_PCM_32BIT = 22
}
