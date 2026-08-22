package com.engabd.sendpin.hue

import kotlin.math.abs

/**
 * Recognising a track that is named only by metadata.
 *
 * A Music Assistant queue playing on a remote speaker tells this phone a title, an
 * artist and a duration — never a file, and never an id that any *other* library
 * would recognise. To analyse that track we have to find the same recording in a
 * library we can actually read, and the only handle is the name.
 *
 * Pure, and separated for the reason all the string handling in this codebase is:
 * normalisation is wrong in ten small ways if it is wrong at all, and every one of
 * them is a track that silently fails to match.
 */
object RemoteTrackIdentity {

    /** Two recordings this far apart in length are not the same recording. */
    const val DURATION_TOLERANCE_MS = 4_000L

    /**
     * Strip everything a tagger might have added and two libraries might disagree
     * about: featured artists, remaster notes, edition markers, punctuation, case.
     *
     * Deliberately aggressive. The cost of over-normalising is matching a different
     * mix of the same song, whose beat grid is nearly right; the cost of
     * under-normalising is no show at all.
     */
    fun normalise(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var s = raw.lowercase()
        for (regex in BRACKETED) s = regex.replace(s, " ")
        s = FEATURING.replace(s, " ")
        s = NON_WORD.replace(s, " ")
        return s.trim().replace(WHITESPACE, " ")
    }

    /** A stable scan key for a track known only by metadata. */
    fun keyOf(title: String?, artist: String?, durationMs: Long): String =
        "remote:${normalise(artist)}:${normalise(title)}:${durationMs / 1000}"

    /**
     * Whether two metadata triples describe the same recording.
     *
     * Duration is checked only when both sides have one — Music Assistant reports 0
     * for a stream whose length it does not know, and refusing to match on that would
     * rule out live sets and radio-sourced tracks entirely.
     */
    fun sameTrack(
        titleA: String?,
        artistA: String?,
        durationA: Long,
        titleB: String?,
        artistB: String?,
        durationB: Long,
    ): Boolean {
        if (normalise(titleA) != normalise(titleB)) return false
        if (normalise(artistA).isNotEmpty() && normalise(artistB).isNotEmpty() &&
            normalise(artistA) != normalise(artistB)
        ) {
            return false
        }
        if (durationA <= 0L || durationB <= 0L) return true
        return abs(durationA - durationB) <= DURATION_TOLERANCE_MS
    }

    private val BRACKETED = listOf(
        Regex("""\([^)]*(remaster|remastered|deluxe|mono|stereo|edit|version|bonus|live)[^)]*\)"""),
        Regex("""\[[^\]]*(remaster|remastered|deluxe|mono|stereo|edit|version|bonus|live)[^\]]*]"""),
        Regex("""\((feat|ft|featuring)[^)]*\)"""),
        Regex("""\[(feat|ft|featuring)[^\]]*]"""),
    )
    private val FEATURING = Regex("""\b(feat|ft|featuring)\b.*$""")
    private val NON_WORD = Regex("""[^\p{L}\p{N}]+""")
    private val WHITESPACE = Regex("""\s+""")
}
