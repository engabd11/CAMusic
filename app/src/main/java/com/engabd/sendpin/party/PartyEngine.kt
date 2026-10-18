package com.engabd.sendpin.party

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** What is playing, as the party needs it. Nothing here names a server, a path or a stream. */
data class PartyNowPlaying(
    /** Changes when the song changes; used to reset skip votes. */
    val key: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val positionMs: Long,
    val playing: Boolean,
    /** Backend-private handle for the cover, passed back to [PartyBackend.artwork]. */
    val artRef: String?,
)

/** One song waiting after the current one. [key] is how the backend recognises it in its queue. */
data class PartyUpNext(val key: String, val title: String, val artist: String, val artRef: String?)

/** One search hit. [ref] is backend-private and never leaves the phone. */
data class PartyCandidate(
    val ref: String,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val artRef: String?,
)

/**
 * The phone's side of a party: the player and the libraries. Implemented over the real
 * app in `AppPartyBackend`; faked in tests.
 */
interface PartyBackend {
    fun nowPlaying(): PartyNowPlaying?

    /** The songs after the current one, in play order. */
    suspend fun upNext(limit: Int): List<PartyUpNext>

    /** Search every library. */
    suspend fun search(query: String, limit: Int): List<PartyCandidate>

    /**
     * Queue [ref] [position] songs after the current one (0 = next). Returns the key the
     * song will have in [upNext], or null when it could not be queued.
     */
    suspend fun enqueue(ref: String, position: Int): String?

    /** A small JPEG of the cover, or null. Size-capped by the caller. */
    suspend fun artwork(artRef: String): ByteArray?

    fun skip()
}

data class PartySettings(
    val approveFirst: Boolean = false,
    val skipMode: SkipMode = SkipMode.OFF,
)

data class PartyGuest(val id: String, val name: String, val joinedAt: Long, val lastSeen: Long)

data class PartyRequest(
    val id: String,
    val guestId: String,
    val guestName: String,
    val title: String,
    val artist: String,
    internal val ref: String,
)

/** A song a guest added that is still waiting to play. */
data class PartyEntry(val key: String, val guestId: String, val guestName: String)

/** What the host's Party screen shows. */
data class PartyHostState(
    val guests: List<PartyGuest> = emptyList(),
    val requests: List<PartyRequest> = emptyList(),
    val skipVotes: Int = 0,
    val skipNeeded: Int? = null,
    val settings: PartySettings = PartySettings(),
)

class PartyResponse(val status: Int, val contentType: String, val body: ByteArray) {
    companion object {
        fun json(status: Int, obj: JsonElement) =
            PartyResponse(status, "application/json; charset=utf-8", obj.toString().toByteArray(Charsets.UTF_8))

        fun error(status: Int, message: String) = json(status, buildJsonObject { put("error", message) })
    }
}

/**
 * The party's rules, independent of HTTP and of Android: who may do what, how often,
 * and in what order songs play. [PartyServer] is a thin skin over [handle].
 */
class PartyEngine(
    val token: String,
    private val backend: PartyBackend,
    initial: PartySettings = PartySettings(),
    private val maxGuests: Int = 12,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val queueLock = Mutex()

    private val guests = LinkedHashMap<String, PartyGuest>()
    private val requests = ArrayList<PartyRequest>()
    private var entries: List<PartyEntry> = emptyList()
    private val votes = HashSet<String>()
    private var voteKey: String? = null
    private var settings = initial

    private val addLimit = RateLimiter(ADDS_PER_MINUTE, MINUTE, clock)
    private val searchLimit = RateLimiter(SEARCHES_PER_MINUTE, MINUTE, clock)
    private val generalLimit = RateLimiter(REQUESTS_PER_MINUTE, MINUTE, clock)
    private val artLimit = RateLimiter(ART_PER_MINUTE, MINUTE, clock)
    private val joinLimit = RateLimiter(JOINS_PER_MINUTE, MINUTE, clock)

    /** Public opaque ids for backend refs, so guests only ever see random strings. */
    private val ids = OpaqueIds(MAX_IDS)
    private val artCache = object : LinkedHashMap<String, ByteArray>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ByteArray>?) = size > MAX_ART_CACHED
    }

    private val _host = MutableStateFlow(PartyHostState(settings = initial))
    val host: StateFlow<PartyHostState> = _host.asStateFlow()

    @Volatile var lastActivity: Long = clock()
        private set

    // --- host controls -----------------------------------------------------------

    fun updateSettings(change: (PartySettings) -> PartySettings) {
        synchronized(lock) { settings = change(settings); publishLocked() }
    }

    fun settingsNow(): PartySettings = synchronized(lock) { settings }

    /** Who added the song with [key], if a guest did and it has not played yet. */
    fun addedBy(key: String): String? = synchronized(lock) { entries.firstOrNull { it.key == key }?.guestName }

    suspend fun approve(requestId: String): Boolean {
        val req = synchronized(lock) { requests.firstOrNull { it.id == requestId } } ?: return false
        val ok = enqueueFor(req.guestId, req.guestName, req.ref) != null
        synchronized(lock) { requests.removeAll { it.id == requestId }; publishLocked() }
        return ok
    }

    fun decline(requestId: String) {
        synchronized(lock) { requests.removeAll { it.id == requestId }; publishLocked() }
    }

    // --- the guest API -----------------------------------------------------------

    suspend fun handle(
        route: PartyRoute,
        method: String,
        guestId: String?,
        remote: String,
        query: Map<String, String>,
        body: JsonObject?,
    ): PartyResponse {
        lastActivity = clock()
        return when (route) {
            is PartyRoute.Api -> api(route, method, guestId, remote, query, body)
            else -> PartyResponse.error(404, "Not found")
        }
    }

    private suspend fun api(
        route: PartyRoute.Api,
        method: String,
        guestId: String?,
        remote: String,
        query: Map<String, String>,
        body: JsonObject?,
    ): PartyResponse {
        val post = method == "POST"
        if (route.name == "join") {
            if (!post) return PartyResponse.error(405, "Use POST")
            if (!joinLimit.tryAcquire(remote)) return PartyResponse.error(429, "Too many tries. Give it a minute.")
            return join(body?.str("name"), body?.str("guestId"))
        }
        if (route.name == "art") {
            // An <img> cannot send the guest header, so covers are gated by the link's
            // token alone; their ids are random and only ever handed to joined guests.
            if (post) return PartyResponse.error(405, "Use GET")
            if (!artLimit.tryAcquire(remote)) return PartyResponse.error(429, "Slow down a little")
            return art(route.arg)
        }
        val guest = synchronized(lock) {
            guestId?.let { guests[it] }?.also { g -> guests[g.id] = g.copy(lastSeen = clock()) }
        } ?: return PartyResponse.error(401, "Join the party first")
        if (!generalLimit.tryAcquire(guest.id)) return PartyResponse.error(429, "Slow down a little")

        return when (route.name) {
            "state" -> if (post) PartyResponse.error(405, "Use GET") else state(guest)
            "search" -> if (post) PartyResponse.error(405, "Use GET") else search(guest, query["q"])
            "add" -> if (!post) PartyResponse.error(405, "Use POST") else add(guest, body?.str("id"))
            "skip" -> if (!post) PartyResponse.error(405, "Use POST") else skip(guest)
            else -> PartyResponse.error(404, "Not found")
        }
    }

    private fun join(rawName: String?, existingId: String?): PartyResponse {
        val name = PartyPaths.cleanName(rawName)
        val now = clock()
        val g = synchronized(lock) {
            // A returning guest (a refreshed page) keeps their identity and their queue.
            val back = existingId?.let { guests[it] }
            if (back != null) {
                guests[back.id] = back.copy(name = name, lastSeen = now)
                guests.getValue(back.id)
            } else {
                dropStaleGuestsLocked(now)
                if (guests.size >= maxGuests) return PartyResponse.error(403, "This party is full")
                PartyGuest(PartyAuth.randomHex(12), name, now, now).also { guests[it.id] = it }
            }
        }
        publish()
        return PartyResponse.json(200, buildJsonObject { put("guestId", g.id); put("name", g.name) })
    }

    private suspend fun state(guest: PartyGuest): PartyResponse {
        val now = backend.nowPlaying()
        val upcoming = refreshEntries()
        val snapshot = synchronized(lock) {
            resetVotesIfSongChangedLocked(now?.key)
            val active = activeGuestsLocked()
            Snapshot(
                entries = entries, mine = requests.count { it.guestId == guest.id },
                voted = guest.id in votes, haveVotes = votes.size,
                need = settings.skipMode.required(active), settings = settings, guests = active,
            )
        }
        return PartyResponse.json(200, buildJsonObject {
            put("guests", snapshot.guests)
            put("approval", snapshot.settings.approveFirst)
            put("pendingMine", snapshot.mine)
            if (now == null) put("nowPlaying", JsonPrimitive(null as String?))
            else putJsonObject("nowPlaying") {
                put("title", now.title); put("artist", now.artist); put("album", now.album)
                put("durationSec", now.durationMs / 1000); put("positionSec", now.positionMs / 1000)
                put("playing", now.playing)
                put("art", now.artRef?.let(ids::idFor)?.let { JsonPrimitive(it) } ?: JsonPrimitive(null as String?))
            }
            putJsonArray("upNext") {
                // Guest entries line up with the front of the real queue, position by
                // position, so the same song added by two guests is credited correctly.
                upcoming.take(UP_NEXT_SHOWN).forEachIndexed { i, u ->
                    add(buildJsonObject {
                        put("title", u.title); put("artist", u.artist)
                        put("addedBy", snapshot.entries.getOrNull(i)?.takeIf { it.key == u.key }?.guestName?.let { JsonPrimitive(it) } ?: JsonPrimitive(null as String?))
                        put("art", u.artRef?.let(ids::idFor)?.let { JsonPrimitive(it) } ?: JsonPrimitive(null as String?))
                    })
                }
            }
            putJsonObject("skip") {
                put("enabled", snapshot.need != null)
                put("votes", snapshot.haveVotes)
                put("needed", snapshot.need ?: 0)
                put("voted", snapshot.voted)
            }
        })
    }

    private data class Snapshot(
        val entries: List<PartyEntry>, val mine: Int, val voted: Boolean, val haveVotes: Int,
        val need: Int?, val settings: PartySettings, val guests: Int,
    )

    private suspend fun search(guest: PartyGuest, rawQuery: String?): PartyResponse {
        val q = PartyPaths.cleanQuery(rawQuery)
        if (q.length < 2) return PartyResponse.json(200, buildJsonObject { putJsonArray("results") {} })
        if (!searchLimit.tryAcquire(guest.id)) return PartyResponse.error(429, "Searching too fast. Try again in a moment.")
        val hits = runCatching { backend.search(q, MAX_RESULTS) }.getOrDefault(emptyList())
        rememberTitles(hits)
        return PartyResponse.json(200, buildJsonObject {
            putJsonArray("results") {
                hits.take(MAX_RESULTS).forEach { h ->
                    add(buildJsonObject {
                        put("id", ids.idFor(h.ref))
                        put("title", h.title); put("artist", h.artist); put("album", h.album)
                        put("durationSec", h.durationMs / 1000)
                        put("art", h.artRef?.let(ids::idFor)?.let { JsonPrimitive(it) } ?: JsonPrimitive(null as String?))
                    })
                }
            }
        })
    }

    private suspend fun add(guest: PartyGuest, publicId: String?): PartyResponse {
        if (!PartyPaths.isId(publicId)) return PartyResponse.error(400, "Pick a song from the search results")
        val ref = ids.refFor(publicId!!) ?: return PartyResponse.error(410, "Search again, that result has expired")
        if (!addLimit.tryAcquire(guest.id)) return PartyResponse.error(429, "That is a lot of songs. Give it a minute.")
        val approve = synchronized(lock) { settings.approveFirst }
        if (approve) {
            val cand = recentCandidate(ref)
            synchronized(lock) {
                if (requests.size >= MAX_REQUESTS || requests.count { it.guestId == guest.id } >= MAX_REQUESTS_PER_GUEST) {
                    return PartyResponse.error(429, "You have plenty waiting for approval already")
                }
                requests += PartyRequest(PartyAuth.randomHex(6), guest.id, guest.name, cand.first, cand.second, ref)
                publishLocked()
            }
            return PartyResponse.json(200, buildJsonObject { put("status", "pending") })
        }
        enqueueFor(guest.id, guest.name, ref)
            ?: return PartyResponse.error(502, "Could not add that one")
        return PartyResponse.json(200, buildJsonObject { put("status", "added") })
    }

    private val titles = object : LinkedHashMap<String, Pair<String, String>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Pair<String, String>>?) = size > MAX_IDS
    }

    private fun recentCandidate(ref: String): Pair<String, String> =
        synchronized(titles) { titles[ref] } ?: ("A song" to "")

    private suspend fun enqueueFor(guestId: String, guestName: String, ref: String): String? =
        queueLock.withLock {
            refreshEntries()
            val position = synchronized(lock) { FairQueue.insertIndex(entries.map { it.guestId }, guestId) }
            val key = runCatching { backend.enqueue(ref, position) }.getOrNull() ?: return@withLock null
            synchronized(lock) {
                entries = entries.toMutableList().apply { add(position.coerceAtMost(size), PartyEntry(key, guestId, guestName)) }
            }
            key
        }

    /** Drops entries that have played and returns the real queue after the current song. */
    private suspend fun refreshEntries(): List<PartyUpNext> {
        val upcoming = runCatching { backend.upNext(UP_NEXT_FETCH) }.getOrDefault(emptyList())
        synchronized(lock) {
            entries = FairQueue.reconcile(entries, { it.key }, upcoming.map { it.key })
        }
        return upcoming
    }

    private fun skip(guest: PartyGuest): PartyResponse {
        val now = backend.nowPlaying() ?: return PartyResponse.error(409, "Nothing is playing")
        var skipped = false
        val resp = synchronized(lock) {
            resetVotesIfSongChangedLocked(now.key)
            val need = settings.skipMode.required(activeGuestsLocked())
                ?: return PartyResponse.error(403, "Voting to skip is off")
            votes += guest.id
            if (votes.size >= need) { skipped = true; votes.clear() }
            publishLocked()
            buildJsonObject { put("votes", votes.size); put("needed", need); put("skipped", skipped) }
        }
        if (skipped) backend.skip()
        return PartyResponse.json(200, resp)
    }

    private suspend fun art(publicId: String?): PartyResponse {
        val ref = publicId?.let(ids::refFor) ?: return PartyResponse.error(404, "Not found")
        synchronized(artCache) { artCache[ref] }?.let { return PartyResponse(200, "image/jpeg", it) }
        val bytes = runCatching { backend.artwork(ref) }.getOrNull()
        if (bytes == null || bytes.isEmpty() || bytes.size > MAX_ART_BYTES) return PartyResponse.error(404, "No artwork")
        synchronized(artCache) { artCache[ref] = bytes }
        return PartyResponse(200, "image/jpeg", bytes)
    }

    // --- bookkeeping ---------------------------------------------------------------

    /** The search layer tells the engine titles so approval requests can show them. */
    internal fun rememberTitles(hits: List<PartyCandidate>) {
        synchronized(titles) { hits.forEach { titles[it.ref] = it.title to it.artist } }
    }

    private fun resetVotesIfSongChangedLocked(key: String?) {
        if (key != voteKey) { votes.clear(); voteKey = key }
    }

    private fun activeGuestsLocked(): Int {
        val cutoff = clock() - ACTIVE_MS
        return guests.values.count { it.lastSeen >= cutoff }.coerceAtLeast(1)
    }

    private fun dropStaleGuestsLocked(now: Long) {
        guests.values.removeAll { now - it.lastSeen > STALE_MS }
    }

    private fun publish() = synchronized(lock) { publishLocked() }

    private fun publishLocked() {
        val active = activeGuestsLocked()
        _host.value = PartyHostState(
            guests = guests.values.toList(),
            requests = requests.toList(),
            skipVotes = votes.size,
            skipNeeded = settings.skipMode.required(active),
            settings = settings,
        )
    }

    /** Called by the service's ticker so the guest list reflects who is still around. */
    fun refreshHostState() { publish() }

    private fun JsonObject.str(name: String): String? =
        (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull

    companion object {
        const val MINUTE = 60_000L
        const val ADDS_PER_MINUTE = 10
        const val SEARCHES_PER_MINUTE = 60
        const val REQUESTS_PER_MINUTE = 240
        const val JOINS_PER_MINUTE = 10
        const val ART_PER_MINUTE = 600
        const val MAX_RESULTS = 20
        const val MAX_IDS = 1_500
        const val MAX_ART_CACHED = 40
        const val MAX_ART_BYTES = 150 * 1024
        const val MAX_REQUESTS = 40
        const val MAX_REQUESTS_PER_GUEST = 5
        const val UP_NEXT_SHOWN = 15
        const val UP_NEXT_FETCH = 30
        const val ACTIVE_MS = 2 * MINUTE
        const val STALE_MS = 30 * MINUTE
    }
}

/** Random public ids for backend-private references, in both directions, bounded. */
class OpaqueIds(private val capacity: Int) {
    private val forward = object : LinkedHashMap<String, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String>?): Boolean {
            val drop = size > capacity
            if (drop && eldest != null) backward.remove(eldest.value)
            return drop
        }
    }
    private val backward = HashMap<String, String>()

    @Synchronized
    fun idFor(ref: String): String =
        backward[ref]?.also { forward[it] } ?: PartyAuth.randomHex(8).also { forward[it] = ref; backward[ref] = it }

    @Synchronized
    fun refFor(id: String): String? = forward[id]
}
