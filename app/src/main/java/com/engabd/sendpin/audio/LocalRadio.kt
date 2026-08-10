package com.engabd.sendpin.audio

import com.engabd.sendpin.ma.MaItem
import com.engabd.sendpin.subsonic.SubsonicClient

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

/** The real one. Every call is wrapped: a rung that fails is a rung that's skipped. */
class SubsonicRadioSource(private val client: SubsonicClient) : RadioSource {
    override suspend fun similarToTrack(trackId: String, count: Int) =
        runCatching { client.getSimilarSongs(trackId, count) }.getOrDefault(emptyList())

    override suspend fun similarToAlbum(albumId: String, count: Int) =
        runCatching { client.getSimilarSongs(albumId, count) }.getOrDefault(emptyList())

    override suspend fun topSongs(artistName: String, count: Int) =
        runCatching { client.getTopSongs(artistName, count) }.getOrDefault(emptyList())

    override suspend fun byGenre(genre: String, count: Int) =
        runCatching { client.songsByGenre(genre, count) }.getOrDefault(emptyList())

    override suspend fun random(count: Int) =
        runCatching { client.randomSongs(count) }.getOrDefault(emptyList())
}

/**
 * Keeps the music going once the queue runs out, on the Navidrome path.
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
     */
    suspend fun next(
        source: RadioSource,
        seed: MaItem?,
        count: Int = 10,
        exclude: Set<String> = emptySet(),
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
                .take(count)
            if (picked.isNotEmpty()) {
                remember(picked.map { it.itemId })
                return picked
            }
        }
        return emptyList()
    }

    /**
     * The offline answer: what is on the phone, near the seed where that means
     * anything and shuffled where it doesn't.
     *
     * Deliberately not the same code path — with no server there is no similarity to
     * ask about, and pretending otherwise would just be a shuffle with extra steps.
     * Matching on genre and artist is the most that can honestly be done locally.
     */
    fun offline(downloads: List<MaItem>, seed: MaItem?, count: Int = 10, exclude: Set<String> = emptySet()): List<MaItem> {
        val pool = downloads.filterNot { it.itemId in exclude || it.itemId in history }
        if (pool.isEmpty()) return emptyList()
        val genre = seed?.genres?.firstOrNull()
        val artist = seed?.subtitle
        val ranked = pool.sortedByDescending { track ->
            var score = 0
            if (genre != null && track.genres.contains(genre)) score += 2
            if (artist != null && track.subtitle == artist) score += 1
            score
        }
        // Take from the best-matching half, then shuffle, so a genre match leads
        // without the same five songs every time.
        val head = ranked.take(maxOf(count * 3, 12))
        return head.shuffled().take(count).also { remember(it.map { t -> t.itemId }) }
    }
}
