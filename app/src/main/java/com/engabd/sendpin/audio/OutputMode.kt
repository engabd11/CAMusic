package com.engabd.sendpin.audio

/**
 * How much of this app stays between the decoder and the DAC.
 *
 * ## Why this exists
 *
 * There were three independent switches — "High-resolution output", "Exclusive
 * output" and "Bit-perfect AAudio output" — and they read as three names for the
 * same idea, which is exactly the complaint that produced this file. They were never
 * independent:
 *
 *  - Exclusive forces the float path on regardless of the high-resolution switch
 *    (`setEnableAudioFloatOutput(bitPerfect || exclusive)` in [LocalPlayer]), so
 *    turning high-resolution *off* under it changed nothing.
 *  - AAudio does nothing at all without exclusive
 *    (`aaudioBitperfect && exclusive`), so two of its four combinations were dead.
 *
 * So they are one dial with four positions, and this is that dial. Each rung is
 * everything the rung before it does, plus one more stage removed. Expressing it as
 * an ordered type means the invalid combinations cannot be written down, rather than
 * being guarded case by case in the UI as they were before.
 *
 * ## Why the names changed
 *
 * "Bit-perfect" was the *key* behind the switch labelled "High-resolution output"
 * and the *label* on a different switch, which is how the three came to sound alike.
 * The rungs are named for what each one removes now, because that is the only thing
 * that distinguishes them:
 *
 *  - [STANDARD] removes nothing.
 *  - [HIGH_RESOLUTION] removes the 16-bit truncation on the way to the sink.
 *  - [PURE] removes every stage this app adds.
 *  - [DIRECT] removes media3 and the normal mixer path as well.
 *
 * "Exclusive" is deliberately not one of them: Android gives an ordinary app no
 * exclusive mode, [ExclusiveOutput] says so at length, and naming a rung after a
 * thing the platform does not offer was half of why these were confusing.
 */
enum class OutputMode(
    val title: String,
    /** One line, for the segmented control's caption. */
    val summary: String,
) {
    STANDARD(
        "Standard",
        "Everything on: equaliser, Light Sync, sound modes and ReplayGain.",
    ),

    HIGH_RESOLUTION(
        "High resolution",
        "Carries more than 16 bits to the output, where the decoder produced them.",
    ),

    PURE(
        "Pure",
        "Nothing of this app's between the decoder and the DAC.",
    ),

    DIRECT(
        "Direct to DAC",
        "Bypasses media3 with AAudio, straight to a USB DAC.",
    );

    /** Whether media3's float output path is used. [PURE] and above force it. */
    val floatPath: Boolean get() = this >= HIGH_RESOLUTION

    /** Whether every processor this app adds is taken out of the chain. */
    val exclusive: Boolean get() = this >= PURE

    /** Whether playback leaves media3 entirely for the AAudio path. */
    val aaudio: Boolean get() = this == DIRECT

    /**
     * Whether this rung stops the equaliser, the Light Sync tap and the sound modes.
     *
     * [HIGH_RESOLUTION] is the surprising one and the reason this is a function
     * rather than a constant per rung: it does not *ask* for the processors to be
     * removed, but on a stream where the float path actually engages media3 runs
     * none of them anyway. Which is why the Signal path panel, not this, is what
     * tells a user whether it is happening right now.
     */
    val alwaysBypassesProcessors: Boolean get() = this >= PURE

    companion object {
        /**
         * Read the rung back out of the three stored booleans.
         *
         * Tolerant of combinations the old three-switch UI could store and the
         * ladder cannot — an AAudio flag with no exclusive flag under it was
         * reachable and did nothing, and resolves here to whatever the flags
         * beneath it actually achieved.
         */
        fun of(floatPath: Boolean, exclusive: Boolean, aaudio: Boolean): OutputMode = when {
            exclusive && aaudio -> DIRECT
            exclusive -> PURE
            floatPath -> HIGH_RESOLUTION
            else -> STANDARD
        }

        /** The rungs on offer, shallowest first. [advanced] adds [PURE] and [DIRECT]. */
        fun offered(advanced: Boolean, current: OutputMode): List<OutputMode> {
            val base = if (advanced) entries else listOf(STANDARD, HIGH_RESOLUTION)
            // Never hide the rung the user is standing on. Someone who set Pure while
            // advanced was on, then turned advanced off, must still be able to see
            // where they are and step back down.
            return if (current in base) base.toList() else entries.take(current.ordinal + 1)
        }
    }
}
