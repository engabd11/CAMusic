package com.engabd.sendpin.data

/**
 * How the album and artist pages, and the library around them, are dressed.
 *
 * Every option here is an *addition*. The pages as they shipped are the defaults —
 * [DetailStyle.CLASSIC], the four shelves that already existed switched on, the rest
 * off — so an install that never opens the settings page sees nothing change. All of
 * it is painted from the cover's own palette, the same colour source the rest of the
 * app uses; none of it brings a colour of its own.
 */

/** The hero at the top of an album or artist page. */
enum class DetailStyle(val key: String, val label: String) {
    /** The centred cover (or round portrait) and title — the pages as they shipped. */
    CLASSIC("classic", "Classic"),

    /**
     * Album: the sleeve with its record sliding out, label printed in the cover's
     * colours, and editorial type. Artist: a full-width duotone banner.
     */
    GALLERY("gallery", "Gallery");

    companion object {
        fun byKey(key: String?): DetailStyle = entries.firstOrNull { it.key == key } ?: CLASSIC
    }
}

/**
 * One optional section of an album or artist page.
 *
 * [default] is true exactly for the shelves the pages already had, so switching this
 * system in changes nothing until someone asks it to.
 */
enum class PageShelf(
    val key: String,
    val page: Page,
    val default: Boolean,
    val title: String,
    val gist: String,
) {
    ALBUM_ABOUT(
        "album_about", Page.ALBUM, true, "About the record",
        "The notes, and the facts: released, genre, discs, length, the formats it is stored in",
    ),
    ALBUM_RELATED(
        "album_related", Page.ALBUM, true, "More to play next",
        "The artist's other records, or ones filed near this one",
    ),
    ALBUM_COLOURS(
        "album_colours", Page.ALBUM, false, "Colours of the sleeve",
        "The palette the page is painted in, taken from the cover, as swatches",
    ),
    ALBUM_LISTENING(
        "album_listening", Page.ALBUM, false, "Your listening",
        "How often you have played it here, when you last did, and your favourite track on it",
    ),

    ARTIST_LATEST(
        "artist_latest", Page.ARTIST, false, "Latest release",
        "Their newest record in your library, given the stage",
    ),
    ARTIST_ABOUT(
        "artist_about", Page.ARTIST, true, "About",
        "The biography, when your library has one",
    ),
    ARTIST_TOP(
        "artist_top", Page.ARTIST, true, "Top tracks",
        "The songs to start with",
    ),
    ARTIST_TIMELINE(
        "artist_timeline", Page.ARTIST, false, "Through the years",
        "The discography laid out along a timeline, oldest to newest",
    ),
    ARTIST_LISTENING(
        "artist_listening", Page.ARTIST, false, "Your listening",
        "Your plays of this artist, since when, and the song of theirs you play most",
    ),
    ARTIST_SIMILAR(
        "artist_similar", Page.ARTIST, true, "Similar artists",
        "Who else in your library to try",
    );

    enum class Page { ALBUM, ARTIST }

    /** The DataStore key name. Prefixed so it can never collide with another setting. */
    val prefName: String get() = "shelf_$key"
}

/** How an artist page lists the albums. */
enum class DiscographyLayout(val key: String, val label: String) {
    LIST("list", "List"),
    GRID("grid", "Covers");

    companion object {
        fun byKey(key: String?): DiscographyLayout = entries.firstOrNull { it.key == key } ?: LIST
    }
}

/** How album covers are drawn in the library's grids and shelves. */
enum class TileStyle(val key: String, val label: String) {
    /** A cover, a hairline and two labels — as it shipped. */
    CLASSIC("classic", "Classic"),

    /** Rounder, with a glow under each cover in that cover's own colour. */
    GALLERY("gallery", "Gallery");

    companion object {
        fun byKey(key: String?): TileStyle = entries.firstOrNull { it.key == key } ?: CLASSIC
    }
}
