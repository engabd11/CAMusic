package com.engabd.sendpin.audio

import com.engabd.sendpin.util.runCatchingCancellable
import com.engabd.sendpin.library.MusicSource
import com.engabd.sendpin.ma.MaItem
import kotlin.random.Random

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
    suspend fun byGenre(genre: String, count: Int, offset: Int = 0): List<MaItem>
    suspend fun random(count: Int): List<MaItem>

    /**
     * Tracks that *sound* like [seed], from scans rather than from server metadata.
     * Asked only when [similarToTrack] has nothing, which on Plex, MPD, foobar2000
     * and local files is every time. Nothing by default.
     */
    suspend fun soundsLike(seed: MaItem, count: Int): List<MaItem> = emptyList()
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
class LibraryRadioSource(
    private val source: MusicSource,
    /** This phone's scan index, for [soundsLike]; null where there is none to ask. */
    private val sonic: LocalSonicIndex? = null,
) : RadioSource {
    override suspend fun similarToTrack(trackId: String, count: Int) =
        runCatchingCancellable { source.similarSongs(trackId, count) }.getOrDefault(emptyList())

    override suspend fun similarToAlbum(albumId: String, count: Int) =
        runCatchingCancellable { source.similarSongs(albumId, count) }.getOrDefault(emptyList())

    override suspend fun topSongs(artistName: String, count: Int) =
        runCatchingCancellable { source.topSongs(artistName, count) }.getOrDefault(emptyList())

    override suspend fun byGenre(genre: String, count: Int, offset: Int) =
        runCatchingCancellable { source.songsByGenre(genre, count, offset) }.getOrDefault(emptyList())

    override suspend fun random(count: Int) =
        runCatchingCancellable { source.randomSongs(count) }.getOrDefault(emptyList())

    // The index spans every local library; only this one's tracks can be queued here.
    // Not waited for while it is first built: a top-up has the server's answers anyway.
    override suspend fun soundsLike(seed: MaItem, count: Int) = runCatchingCancellable {
        sonic?.similarTo(seed, count, wait = false).orEmpty()
            .filter { it.provider == source.providerId }
            .map { MaItem(it.itemId, it.provider, it.name, it.uri, "track", it.artist, it.image, null) }
    }.getOrDefault(emptyList())
}

/**
 * DJ Radio's candidates on Music Assistant: the server's own similar tracks for the
 * seed, plus its sonic text search for the set's mood where the server has one.
 *
 * Nothing else. A random page here would be ranked as an equal of a real match —
 * neither carries genres from MA — so a seed MA knows nothing about yields an empty
 * pool, and the queue's own "Don't stop the music" carries the set instead.
 */
class MaRadioSource(
    private val repo: com.engabd.sendpin.ma.MaRepository,
    private val mood: DjMood? = null,
) : RadioSource {
    override suspend fun similarToTrack(trackId: String, count: Int) = emptyList<MaItem>()
    override suspend fun similarToAlbum(albumId: String, count: Int) = emptyList<MaItem>()
    override suspend fun topSongs(artistName: String, count: Int) = emptyList<MaItem>()
    override suspend fun byGenre(genre: String, count: Int, offset: Int) = emptyList<MaItem>()
    override suspend fun random(count: Int) = emptyList<MaItem>()

    override suspend fun soundsLike(seed: MaItem, count: Int): List<MaItem> {
        val similar = runCatchingCancellable { repo.similarTracks(seed.itemId, seed.provider, count) }
            .getOrDefault(emptyList())
        // Older servers, and servers without the sonic provider, refuse the command.
        val brief = mood?.takeIf { !it.open }?.let { m ->
            runCatchingCancellable { repo.sonicTextSearch(m.title, count) }.getOrDefault(emptyList())
        }.orEmpty()
        return (similar + brief)
            .filter { it.uri != null }
            .map { MaItem(it.itemId, it.provider, it.name, it.uri, "track", it.artist, it.image, null) }
    }
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
 * Remembers what it served and what was played through [memory], which the library
 * refills from disk before each batch, so a radio doesn't circle back on itself —
 * this session or after a restart.
 */
class LocalRadio(
    historyLimit: Int = RadioMemory.HISTORY_LIMIT,
    private val random: Random = Random.Default,
) {

    /**
     * What has been heard lately and what the listener thinks of it. Empty until the
     * caller loads one from disk; see [RadioMemory].
     */
    var memory: RadioMemory = RadioMemory(limit = historyLimit)

    /** Note ids as served, so the next batches leave them alone. */
    fun remember(items: Collection<MaItem>) = memory.served(items.map(memory::key))

    fun forget() = memory.forget()

    /** Which of the seed's genres the next genre page asks for. */
    private var genreTurn = 0

    /**
     * One page of the seed's genre — a different tag each time, from a random offset.
     *
     * Asked the same way twice, a server hands back the same first page of the same
     * first genre, which was every genre-led batch this radio ever played. A page
     * past the end of a small genre comes back empty, so that one is asked again
     * from the top.
     */
    private suspend fun genrePage(source: RadioSource, seed: MaItem?, count: Int): List<MaItem> {
        val genres = seed?.genres?.filter { it.isNotBlank() }.orEmpty()
        if (genres.isEmpty()) return emptyList()
        val genre = genres[genreTurn++ % genres.size]
        val offset = random.nextInt(GENRE_MAX_OFFSET + 1)
        val page = source.byGenre(genre, count, offset)
        return if (page.isEmpty() && offset > 0) source.byGenre(genre, count, 0) else page
    }

    /** The bonus first, then what the listener's history says, as the tie-break. */
    private fun ranking(bonus: (MaItem) -> Int) =
        compareByDescending<MaItem> { bonus(it) }.thenByDescending { memory.adjust(it) }

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
            if (seed?.genres.orEmpty().any { it.isNotBlank() }) add { genrePage(source, seed, want) }
            add { source.random(want) }
        }

        val heard = ArrayList<MaItem>()
        for (rung in rungs) {
            val answered = rung()
                .filter { it.itemId.isNotBlank() && it.itemId !in exclude }
                .distinctBy { it.itemId }
            val picked = answered.filterNot { memory.isBlocked(it) }
                .sortedWith(ranking(bonus))
                .take(count)
            if (picked.isNotEmpty()) {
                remember(picked)
                return picked
            }
            heard += answered
        }
        // Every rung answered only with what was heard lately: a library smaller than
        // the no-repeat window. The longest-ago heard of it, rather than silence.
        val picked = memory.admit(heard.distinctBy { it.itemId }, count)
            .sortedWith(ranking(bonus))
            .take(count)
        remember(picked)
        return picked
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
     * [count] is the batch the pool is for: on a library too small to spare the
     * no-repeat window, that many are let back in (see [RadioMemory.admit]).
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
        count: Int = 10,
    ): List<MaItem> {
        val each = (want / 2).coerceAtLeast(20)
        val found = LinkedHashMap<String, MaItem>()

        suspend fun gather(rung: suspend () -> List<MaItem>) {
            rung()
                .filter { it.itemId.isNotBlank() && it.itemId !in exclude }
                .forEach { found.putIfAbsent(it.itemId, it) }
        }

        var similar = 0
        seed?.itemId?.takeIf { it.isNotBlank() }
            ?.let { gather { source.similarToTrack(it, each).also { r -> similar = r.size } } }
        // No similarity on the server (Plex, MPD, foobar2000, local files): what the
        // scans on this phone say sounds alike instead.
        if (seed != null && similar == 0) gather { source.soundsLike(seed, each) }
        seed?.parentId?.takeIf { it.isNotBlank() }?.let { gather { source.similarToAlbum(it, each) } }
        gather { genrePage(source, seed, each) }
        // One artist's best-known songs are seasoning, not the pool: uncapped they
        // were a quarter of it, and the set came back to that artist all evening.
        seed?.subtitle?.substringBefore(",")?.trim()?.takeIf { it.isNotBlank() }
            ?.let { gather { source.topSongs(it, each).take(topSongsCap(want)) } }
        if (found.values.count { !memory.isBlocked(it) } < want) gather { source.random(want) }

        return memory.admit(found.values.toList(), count)
    }

    /**
     * Anything at all, shuffled — the answer when the transport shuffle is on.
     *
     * Deliberately not [next]: that ladder's whole job is to stay *near* the seed,
     * and "shuffle" is the listener saying they don't want that. It asks for a wide
     * page and shuffles it here rather than trusting a server-side random to differ
     * between two calls a minute apart, and it shares [memory] with the ladder so a
     * session doesn't circle back on itself either way.
     */
    suspend fun random(
        source: RadioSource,
        count: Int = 10,
        exclude: Set<String> = emptySet(),
        bonus: (MaItem) -> Int = { 0 },
    ): List<MaItem> {
        val page = source.random(count * 5)
            .filter { it.itemId.isNotBlank() && it.itemId !in exclude }
            .distinctBy { it.itemId }
        val pool = memory.admit(page, 1)
        if (pool.isEmpty()) return emptyList()
        // Shuffled first, then ranked, so the bonus decides between equals rather
        // than pinning the same key-compatible handful to the front every time.
        val picked = pool.shuffled(random).sortedWith(ranking(bonus)).take(count)
        remember(picked)
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
        val pool = memory.admit(downloads.filterNot { it.itemId in exclude }, 1)
        if (pool.isEmpty()) return emptyList()
        val genre = seed?.genres?.firstOrNull()
        val artist = seed?.subtitle
        val ranked = pool.sortedWith(
            compareByDescending<MaItem> { track ->
                var score = 0
                if (genre != null && track.genres.contains(genre)) score += 2
                if (artist != null && track.subtitle == artist) score += 1
                score + bonus(track)
            }.thenByDescending { memory.adjust(it) },
        )
        // Take from the best-matching half, then shuffle, so a genre match leads
        // without the same five songs every time.
        val head = ranked.take(maxOf(count * 3, 12))
        return head.shuffled(random).take(count).also { remember(it) }
    }

    companion object {
        /** How far into a genre a page may start. A smaller genre falls back to 0. */
        const val GENRE_MAX_OFFSET = 200

        /** The most of a pool one artist's top songs may be. */
        const val TOP_SONGS_SHARE = 0.15f

        fun topSongsCap(want: Int): Int = (want * TOP_SONGS_SHARE).toInt().coerceAtLeast(3)

        /**
         * The radio's seed for [track]: the library item it was queued from when one
         * was [recorded], else one rebuilt from what the track carries itself.
         *
         * Only the library's own screen records items; a queue started from an album,
         * artist or playlist page, the car or Now Playing has none, and with no seed
         * every similar, genre and artist rung was skipped and the radio was a
         * shuffle. The rebuilt one has no album id, so the album rung still sits out.
         */
        fun seedOf(track: LocalTrack, recorded: MaItem?): MaItem = recorded ?: MaItem(
            itemId = track.scrobbleId ?: track.id,
            provider = track.scrobbleProvider.orEmpty(),
            name = track.title,
            uri = null,
            mediaType = "track",
            subtitle = track.artist,
            image = track.artUrl,
            duration = (track.durationMs / 1000).toInt(),
            genres = listOfNotNull(track.genre?.takeIf { it.isNotBlank() }),
            album = track.album,
        )
    }
}
