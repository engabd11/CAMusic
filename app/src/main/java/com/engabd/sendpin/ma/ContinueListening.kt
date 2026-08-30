package com.engabd.sendpin.ma

/**
 * How the front page's two "what have I been listening to" shelves divide the answer.
 *
 * Music Assistant has two commands here and they answer different halves of one
 * question. `music/in_progress_items` is the strong one — items with a saved position,
 * genuinely unfinished — but it only covers the two media types MA keeps a position
 * for, podcasts and audiobooks. So "Continue listening" was a shelf that, on a library
 * of music, either never appeared or never once mentioned a song.
 * `music/recently_played_items` is the other half: everything, in the order it was
 * last played, with no notion of finished or not.
 *
 * Naively that is one shelf of podcasts and one shelf of everything, listing the same
 * podcast episode in both. So the recently-played list is split by *what kind of thing*
 * it is instead:
 *
 *  - a **song** is a thing you were listening to, and goes to "Continue listening"
 *    behind the genuinely-unfinished items;
 *  - an **album, artist, playlist or station** is a place you were listening *in*,
 *    which is a real second question and keeps "Recently played" worth having.
 *
 * Pure and separate from [LibraryViewModel] for the reason the split exists at all: it
 * is a property of the *pair* of shelves — a song promoted to one is a song the other
 * must not repeat — and a rule about two lists enforced inside one of them is a rule
 * the other can silently disagree with.
 */
internal object ContinueListening {

    /** The two shelves, after the split. */
    data class Split(
        val continueListening: List<MaItem>,
        val recentlyPlayed: List<MaItem>,
    )

    /**
     * Media types that count as "a song" for the purposes above.
     *
     * A podcast episode and an audiobook chapter are single playable items too, and
     * they are deliberately *not* here: those are exactly what `in_progress_items`
     * already reports, with a position attached, and taking them out of "Recently
     * played" as well would promote a finished episode to the top of the page as
     * though it were unfinished.
     */
    private val SONG_TYPES = setOf("track")

    fun split(inProgress: List<MaItem>, recentlyPlayed: List<MaItem>): Split {
        val songs = recentlyPlayed.filter { it.mediaType in SONG_TYPES }
        return Split(
            // Unfinished first. A half-listened chapter is a stronger offer than a
            // song that merely played most recently, and it is the one thing on the
            // page that has nowhere else to appear.
            continueListening = distinct(inProgress + songs),
            recentlyPlayed = recentlyPlayed.filterNot { it.mediaType in SONG_TYPES },
        )
    }

    /**
     * One item per (provider, type, id), first occurrence winning.
     *
     * The same de-duplication [MaRepository.distinctItems] does within one command's
     * results, applied where two commands' results are concatenated: an episode can be
     * both in progress and the last thing played, and a carousel listing it twice looks
     * like a bug because it is one.
     */
    private fun distinct(items: List<MaItem>): List<MaItem> =
        items.distinctBy { "${it.provider}|${it.mediaType}|${it.itemId}" }
}
