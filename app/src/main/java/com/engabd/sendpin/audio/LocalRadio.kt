package com.engabd.sendpin.audio

import com.engabd.sendpin.library.MusicSource
import com.engabd.sendpin.ma.MaItem

/**
 * Where a radio's next batch of tracks can come from.
 *
 * An interface rather than a `SubsonicClient` so the ladder in [LocalRadio] can be
 * tested without a server — the ordering and the de-duplication are the parts with
 * decisions in them, and neither needs a network to be wrong.
 */
interface RadioSource {
    suspend fun similarToTrack(trackId: String, count: Int): List<MaItem>
    suspend fun similarToAlbum(albumId: String, count: Int): List<MaItem>
    suspend fun topSongs(artistName: String, count: Int): List<MaItem>
    suspend fun byGenre(genre: String, count: Int): List<MaItem>
    suspend fun random(count: Int): List<MaItem>
}

/**
 * The real one, over whatever library is connected.
 *
 * Built from [MusicSource] rather than from a client class, which is the whole point
 * of it. There used to be one of these per backend and a `when (source)` picking
 * between them — so a backend nobody had written one for (Emby, Plex) fell through to
 * the offline picker over downloaded files, and "keep the music going" was silently
 * off on a library with nothing downloaded. The interface already answers all five
 * questions; a source that cannot answer one returns nothing and the ladder moves on.
 *
 * Every call is wrapped: a rung that fails is a rung that's skipped, and the ladder
 * falls through to `random` rather than to silence.
 */
class LibraryRadioSource(private val source: MusicSource) : RadioSource {
    override suspend fun similarToTrack(trackId: String, count: Int) =
        runCatching { source.similarSongs(trackId, count) }.getOrDefault(emptyList())

    override suspend fun similarToAlbum(albumId: String, count: Int) =
        runCatching { source.similarSongs(albumId, count) }.getOrDefault(emptyList())

    override suspend fun topSongs(artistName: String, count: Int) =
        runCatching { source.topSongs(artistName, count) }.getOrDefault(emptyList())

    override suspend fun byGenre(genre: String, count: Int) =
        runCatching { source.songsByGenre(genre, count) }.getOrDefault(emptyList())

    override suspend fun random(count: Int) =
        runCatching { source.randomSongs(count) }.getOrDefault(emptyList())
}

/**
 * Keeps the music going once the queue runs out, on every library this phone plays
 * itself.
 *
 * Music Assistant has had this for a while — `radio_mode` on `play_media`, and
 * "don't stop the music" on the queue — but both are *server* features, so the
 * standalone backend has always simply stopped at the end of the album. That is the
 * backend someone reaches for when MA is down, which is exactly when they least want
 * the room to go quiet.
 *
 * The ladder runs strongest claim first and stops at the first rung that answers:
 * tracks like this one, then tracks from around this record, then the artist's own
 * best-known songs, then the genre, then anything at all. Each is a weaker statement
 * about "more like this" than the one above it, and a server with no last.fm metadata
 * will fall through the first few every time — which is why the bottom rung exists.
 *
 * Holds a rolling history so a radio doesn't circle back on itself within a session.
 * Not persisted: a new session is a new mood.
 */
class LocalRadio(private val historyLimit: Int = 200) {

    private val history = LinkedHashSet<String>()

    /** Note ids as served, oldest dropped once [historyLimit] is passed. */
    fun remember(ids: Collection<String>) {
        history.addAll(ids)
        while (history.size > historyLimit) {
            val oldest = history.first()
            history.remove(oldest)
        }
    }

    fun forget() = history.clear()

    /**
     * The next batch, seeded from [seed] — normally the track that just played.
     *
     * [exclude] is what is already in the queue: suggesting a song that is about to
     * play anyway is the one mistake that makes a radio feel broken rather than
     * merely uninspired.
     *
     * [bonus] is the same extra ranking signal [offline] takes — Harmonic DJ mode's
     * key/tempo compatibility score. It only re-orders *within* whichever rung
     * answers first: a rung further down the ladder is a weaker claim about "more
     * like this" than the one above it, and DJ mode re-ranking should never make a
     * merely key-compatible genre-ladder guess beat a real similarity match. The
     * default contributes nothing, so every existing caller is unaffected.
     */
    suspend fun next(
        source: RadioSource,
        seed: MaItem?,
        count: Int = 10,
        exclude: Set<String> = emptySet(),
        bonus: (MaItem) -> Int = { 0 },
    ): List<MaItem> {
        // Ask for more than needed at every rung: most of what comes back is usually
        // already in the history or the queue, and a second round trip to fill the
        // gap costs more than the larger first one did.
        val want = count * 5
        val rungs: List<suspend () -> List<MaItem>> = buildList {
            seed?.itemId?.takeIf { it.isNotBlank() }?.let { id ->
                add { source.similarToTrack(id, want) }
            }
            // A track's parentId is its *album* id — Navidrome takes song, album or
            // artist ids here, and "more from around this record" is a fair second
            // guess when the track itself has no similarity data.
            seed?.parentId?.takeIf { it.isNotBlank() }?.let { albumId ->
                add { source.similarToAlbum(albumId, want) }
            }
            seed?.subtitle?.substringBefore(",")?.trim()?.takeIf { it.isNotBlank() }?.let { artist ->
                add { source.topSongs(artist, want) }
            }
            seed?.genres?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { genre ->
                add { source.byGenre(genre, want) }
            }
            add { source.random(want) }
        }

        for (rung in rungs) {
            val picked = rung()
                .filter { it.itemId.isNotBlank() }
                .filterNot { it.itemId in exclude || it.itemId in history }
                .distinctBy { it.itemId }
                .sortedByDescending { bonus(it) }
                .take(count)
            if (picked.isNotEmpty()) {
                remember(picked.map { it.itemId })
                return picked
            }
        }
        return emptyList()
    }

    /**
     * Everything the ladder can find near [seed], merged rather than laddered.
     *
     * The opposite arrangement to [next], and deliberately so. That one stops at the
     * first rung that answers, because its job is to make one decision — the
     * strongest available claim about "more like this" — and hand it over. DJ Radio
     * makes its own decision (see [DjSetBuilder]) and what it needs from here is
     * *breadth*: a hundred candidates it can rank on mood and rhythm beats ten it
     * cannot choose between.
     *
     * `random` is only reached when the named rungs come up short, so a library with
     * real similarity data never has its set diluted by arbitrary tracks, and one
     * without still fills a queue.
     *
     * Does **not** [remember] what it returns: this is a pool of candidates, most of
     * which will not be played, and putting them all in the history would exclude
     * them from every later batch for the rest of the session.
     */
    suspend fun pool(
        source: RadioSource,
        seed: MaItem?,
        want: Int,
        exclude: Set<String> = emptySet(),
    ): List<MaItem> {
        val each = (want / 2).coerceAtLeast(20)
        val found = LinkedHashMap<String, MaItem>()

        suspend fun gather(rung: suspend () -> List<MaItem>) {
            rung()
                .filter { it.itemId.isNotBlank() }
                .filterNot { it.itemId in exclude || it.itemId in history }
                .forEach { found.putIfAbsent(it.itemId, it) }
        }

        seed?.itemId?.takeIf { it.isNotBlank() }?.let { gather { source.similarToTrack(it, each) } }
        seed?.parentId?.takeIf { it.isNotBlank() }?.let { gather { source.similarToAlbum(it, each) } }
        seed?.genres?.firstOrNull()?.takeIf { it.isNotBlank() }?.let { gather { source.byGenre(it, each) } }
        seed?.subtitle?.substringBefore(",")?.trim()?.takeIf { it.isNotBlank() }
            ?.let { gather { source.topSongs(it, each) } }
        if (found.size < want) gather { source.random(want) }

        return found.values.toList()
    }

    /**
     * Anything at all, shuffled — the answer when the transport shuffle is on.
     *
     * Deliberately not [next]: that ladder's whole job is to stay *near* the seed,
     * and "shuffle" is the listener saying they don't want that. It asks for a wide
     * page and shuffles it here rather than trusting a server-side random to differ
     * between two calls a minute apart, and it shares [history] with the ladder so a
     * session doesn't circle back on itself either way.
     */
    suspend fun random(
        source: RadioSource,
        count: Int = 10,
        exclude: Set<String> = emptySet(),
        bonus: (MaItem) -> Int = { 0 },
    ): List<MaItem> {
        val pool = source.random(count * 5)
            .filter { it.itemId.isNotBlank() }
            .filterNot { it.itemId in exclude || it.itemId in history }
            .distinctBy { it.itemId }
        if (pool.isEmpty()) return emptyList()
        // Shuffled first, then ranked, so the bonus decides between equals rather
        // than pinning the same key-compatible handful to the front every time.
        val picked = pool.shuffled().sortedByDescending { bonus(it) }.take(count)
        remember(picked.map { it.itemId })
        return picked
    }

    /**
     * The offline answer: what is on the phone, near the seed where that means
     * anything and shuffled where it doesn't.
     *
     * Deliberately not the same code path — with no server there is no similarity to
     * ask about, and pretending otherwise would just be a shuffle with extra steps.
     * Matching on genre and artist is the most that can honestly be done locally.
     *
     * [bonus] is an extra ranking signal a caller can layer on — Harmonic DJ mode's
     * key/tempo compatibility score, for instance — without this method having to
     * know what it means. The default contributes nothing, so every existing caller
     * is unaffected.
     */
    fun offline(
        downloads: List<MaItem>,
        seed: MaItem?,
        count: Int = 10,
        exclude: Set<String> = emptySet(),
        bonus: (MaItem) -> Int = { 0 },
    ): List<MaItem> {
        val pool = downloads.filterNot { it.itemId in exclude || it.itemId in history }
        if (pool.isEmpty()) return emptyList()
        val genre = seed?.genres?.firstOrNull()
        val artist = seed?.subtitle
        val ranked = pool.sortedByDescending { track ->
            var score = 0
            if (genre != null && track.genres.contains(genre)) score += 2
            if (artist != null && track.subtitle == artist) score += 1
            score + bonus(track)
        }
        // Take from the best-matching half, then shuffle, so a genre match leads
        // without the same five songs every time.
        val head = ranked.take(maxOf(count * 3, 12))
        return head.shuffled().take(count).also { remember(it.map { t -> t.itemId }) }
    }
}
