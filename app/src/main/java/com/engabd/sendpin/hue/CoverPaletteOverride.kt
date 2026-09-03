package com.engabd.sendpin.hue

/**
 * A user-chosen replacement for the album-art palette the light-sync engine
 * would otherwise extract from the cover.
 *
 * Stored by a stable key (album name + artist when available, otherwise the art
 * url, otherwise the track id) so the same override survives per-track artwork
 * URL churn and backend handovers. See [keysFor], and
 * [com.engabd.sendpin.data.AppSettings.setCoverPaletteOverrideForKeys] for why a
 * save is written under every key rather than the best one.
 *
 * ## Shape
 *
 * The same shape as an extracted [AlbumColours]: a list of colours, and a parallel
 * list of weights saying how much of the room each one should hold. That is
 * deliberate — an override that could only express "n colours, evenly" would be a
 * strictly worse thing than what it replaces, and "the blue is right but there is
 * far too much of it" is the most common correction anyone actually wants to make.
 *
 * [weights] may be empty, which means even, and is what every override saved before
 * weights existed decodes to.
 */
data class CoverPaletteOverride(
    /** ARGB ints, as returned by `android.graphics.Color` / Compose `Color.toArgb()`. */
    val colors: List<Int>,
    /** How this override was created. Saved for future UI hints; does not affect rendering. */
    val mode: Mode = Mode.OVERRIDE,
    /**
     * Relative share of the room per colour, parallel to [colors].
     *
     * Not required to sum to anything — [normalisedWeights] does that — so the
     * editor can hold raw slider values without renormalising on every drag.
     * Empty, or any length that does not match [colors], means even.
     */
    val weights: List<Float> = emptyList(),
) {
    enum class Mode {
        /** User picked every colour by hand. */
        OVERRIDE,

        /** User locked the currently extracted cover palette in place. */
        LOCKED,

        /** User mixed the extracted palette with one of their own. */
        MIXED,
    }

    /**
     * Whether this override carries real percentages, as opposed to even spacing.
     *
     * The distinction reaches the engine: even spacing is expressed to it as *no*
     * weights at all, which selects its own even-interpolation path rather than a
     * weighted sample that happens to have equal weights. See `applyAlbumArt`.
     */
    val hasWeights: Boolean
        get() = weights.size == colors.size && colors.isNotEmpty() && weights.sum() > 0f

    /**
     * [weights] as a distribution summing to 1, or even shares when there are none
     * usable. Always the same length as [colors].
     */
    fun normalisedWeights(): List<Float> {
        val n = colors.size
        if (n == 0) return emptyList()
        val even = 1f / n
        if (weights.size != n) return List(n) { even }
        // A negative slider value cannot mean anything, and one colour at zero is a
        // legitimate "none of this" rather than a reason to throw the set away.
        val clamped = weights.map { it.coerceAtLeast(0f) }
        val total = clamped.sum()
        if (total <= 0f) return List(n) { even }
        return clamped.map { it / total }
    }

    companion object {
        /** Empty helper used when an id has no override. */
        val EMPTY = CoverPaletteOverride(emptyList())

        /** Fewest colours a palette can be edited down to and still be a palette. */
        const val MIN_COLOURS = 2

        /**
         * Most colours the editor offers.
         *
         * The extractors produce four (v2) or five (v1); this leaves room to add one
         * or two without the sheet becoming a list nobody can balance by hand.
         */
        const val MAX_COLOURS = 6

        /**
         * The keys an override for this album could be filed under, best first.
         *
         * The editor saves under **all** of these and the engine tries each in turn,
         * because what it knows about a playing track is not always what the editor
         * knew. Album-and-artist leads because it survives per-track artwork URL
         * churn and a backend handover. The artwork URL is what the Music Assistant
         * feed falls back to: it carries no `LocalTrack`, so there is no album name
         * to read. The track id is the last resort, and pins the override to that one
         * song.
         */
        fun keysFor(
            album: String?,
            artist: String?,
            coverUrl: String?,
            trackId: String?,
        ): List<String> = listOfNotNull(
            album?.takeIf { it.isNotBlank() }?.let { "$it|${artist.orEmpty()}" },
            coverUrl?.takeIf { it.isNotBlank() },
            trackId?.takeIf { it.isNotBlank() },
        )
    }
}

/** Convert ARGB ints to the 0..1 RGB triples the engine expects. */
internal fun List<Int>.toRgbList(): List<Rgb> = map { argb ->
    val r = ((argb shr 16) and 0xFF) / 255f
    val g = ((argb shr 8) and 0xFF) / 255f
    val b = (argb and 0xFF) / 255f
    Triple(r, g, b)
}

/**
 * Wrap the override colours in the same shape [extractAlbumColours] produces.
 *
 * The weights are the user's where they set any, and even shares otherwise — which
 * is what every override did before percentages existed, so an old one decodes to
 * exactly the palette it always rendered.
 */
internal fun CoverPaletteOverride.toAlbumColours(): AlbumColours =
    AlbumColours(colors.toRgbList(), normalisedWeights())
