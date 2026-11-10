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

    /**
     * Two recordings this far apart in length are not the same recording.
     *
     * The guard that lets the rest be tolerant: an artist list written two ways or a
     * "- Remastered" suffix is forgiven only for a recording of the same length.
     */
    const val DURATION_TOLERANCE_MS = 3_000L

    /**
     * Results asked for per search. Ten was too few for a common title, where the
     * right recording is often below a page of covers and live versions.
     */
    const val SEARCH_LIMIT = 25

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
        s = DASH_SUFFIX.replace(s, " ")
        s = FEATURING.replace(s, " ")
        s = NON_WORD.replace(s, " ")
        return s.trim().replace(WHITESPACE, " ")
    }

    /** A stable scan key for a track known only by metadata. */
    fun keyOf(title: String?, artist: String?, durationMs: Long): String =
        "remote:${normalise(artist)}:${normalise(title)}:${durationMs / 1000}"

    /**
     * The artists credited, one entry each: "A & B", "A, B", "B and A" and
     * "A feat. B" are all `{a, b}`.
     */
    fun artists(raw: String?): Set<String> {
        if (raw.isNullOrBlank()) return emptySet()
        return ARTIST_SEPARATORS.split(raw.lowercase())
            .map { NON_WORD.replace(it, " ").trim().replace(WHITESPACE, " ") }
            .filter { it.isNotEmpty() }
            .toSet()
    }

    /**
     * Whether two metadata triples describe the same recording.
     *
     * Duration is checked only when both sides have one — Music Assistant reports 0
     * for a stream whose length it does not know, and refusing to match on that would
     * rule out live sets and radio-sourced tracks entirely.
     *
     * Artists match when the two credits share one: one library lists the featured
     * artist and the other does not, or orders a duet the other way round. Only with
     * the duration guard behind it, though; with no length to check, the credits
     * must agree in full, as they always had to.
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
        val timed = durationA > 0L && durationB > 0L
        if (timed && abs(durationA - durationB) > DURATION_TOLERANCE_MS) return false
        val a = artists(artistA)
        val b = artists(artistB)
        if (a.isEmpty() || b.isEmpty()) return true
        return if (timed) a.any { it in b } else a == b || normalise(artistA) == normalise(artistB)
    }

    /**
     * The candidate that is [sameTrack] as the given triple, nearest in length when
     * several are: an album version and a radio edit can both pass the tolerance.
     */
    fun <T> closest(
        title: String?,
        artist: String?,
        durationMs: Long,
        candidates: List<T>,
        describe: (T) -> Triple<String?, String?, Long>,
    ): T? = candidates.asSequence()
        .map { it to describe(it) }
        .filter { (_, d) -> sameTrack(title, artist, durationMs, d.first, d.second, d.third) }
        // Unknown lengths sort last and keep their order among themselves.
        .minByOrNull { (_, d) -> if (durationMs > 0 && d.third > 0) abs(durationMs - d.third) else Long.MAX_VALUE }
        ?.first

    /**
     * What to ask a library's text search, best first. Title and artist as given,
     * then the bare title: a server's search misses "Song - Remastered 2011" or an
     * artist written "A & B" when its own tags say otherwise, and [sameTrack] does
     * the checking anyway.
     */
    fun searchQueries(title: String?, artist: String?): List<String> =
        listOf("${title.orEmpty()} ${artist.orEmpty()}".trim(), normalise(title))
            .filter { it.isNotBlank() }
            .distinct()

    /** The first of [searchQueries] that [find] answers. */
    suspend fun <T> search(title: String?, artist: String?, find: suspend (String) -> T?): T? {
        for (query in searchQueries(title, artist)) find(query)?.let { return it }
        return null
    }

    private val BRACKETED = listOf(
        Regex("""\([^)]*(remaster|remastered|deluxe|mono|stereo|edit|version|bonus|live)[^)]*\)"""),
        Regex("""\[[^\]]*(remaster|remastered|deluxe|mono|stereo|edit|version|bonus|live)[^\]]*]"""),
        Regex("""\((feat|ft|featuring)[^)]*\)"""),
        Regex("""\[(feat|ft|featuring)[^\]]*]"""),
    )

    /** A well-known edition note after a dash: "Song - Remastered 2011", "- Single Version". */
    private val DASH_SUFFIX =
        Regex("""\s[-–—]\s.*\b(remaster\w*|deluxe|mono|stereo|edit|version|bonus|live|single)\b.*$""")
    private val ARTIST_SEPARATORS =
        Regex("""\s*(?:[,&+/;]|\b(?:and|x|with|vs|feat|ft|featuring)\b\.?)\s*""")
    private val FEATURING = Regex("""\b(feat|ft|featuring)\b.*$""")
    private val NON_WORD = Regex("""[^\p{L}\p{N}]+""")
    private val WHITESPACE = Regex("""\s+""")
}
