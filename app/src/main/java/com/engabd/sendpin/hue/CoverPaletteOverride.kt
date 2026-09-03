package com.engabd.sendpin.hue

/**
 * A user-chosen replacement for the album-art palette the light-sync engine
 * would otherwise extract from the cover.
 *
 * Stored by a stable key (album id when available, otherwise album name +
 * artist, otherwise the art url) so the same override survives per-track
 * artwork URL churn and backend handovers.
 */
data class CoverPaletteOverride(
    /** ARGB ints, as returned by `android.graphics.Color` / Compose `Color.toArgb()`. */
    val colors: List<Int>,
    /** How this override was created. Saved for future UI hints; does not affect rendering. */
    val mode: Mode = Mode.OVERRIDE,
) {
    enum class Mode {
        /** User picked every colour by hand. */
        OVERRIDE,
        /** User locked the currently extracted cover palette in place. */
        LOCKED,
        /** User mixed the extracted palette with one of their own. */
        MIXED,
    }

    companion object {
        /** Empty helper used when an id has no override. */
        val EMPTY = CoverPaletteOverride(emptyList())

        /**
         * The keys an override for this album could be filed under, best first.
         *
         * The editor saves under the first of these; the engine tries each in
         * turn, because what it knows about a playing track is not always what
         * the editor knew. Album-and-artist leads because it survives per-track
         * artwork URL churn and a backend handover. The artwork URL is what the
         * Music Assistant feed falls back to: it carries no `LocalTrack`, so
         * there is no album name to read. The track id is the last resort, and
         * pins the override to that one song.
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

/** Wrap the override colours in the same shape [applyAlbumArt] already produces. */
internal fun CoverPaletteOverride.toAlbumColours(): AlbumColours {
    val rgbs = colors.toRgbList()
    // No population weights are available for hand-picked colours, so we use
    // even weights. This matches the v1 "album_art" path; if the user has the
    // v2 scheme selected the engine still gets a valid palette and samples it.
    return AlbumColours(rgbs, rgbs.map { 1f / rgbs.size.coerceAtLeast(1) })
}
