package com.engabd.sendpin.ma

import androidx.compose.runtime.Immutable
import com.engabd.sendpin.audio.StreamQuality
import kotlinx.serialization.json.*
import java.net.URLEncoder

/**
 * The source file's own format, off `provider_mappings[].audio_format`. Used to
 * decide whether Music Assistant would have to convert a track to stream it — see
 * [com.engabd.sendpin.audio.FormatNegotiator].
 */
data class MaAudioFormat(
    val codec: String,
    val sampleRate: Int,
    val bitDepth: Int,
    /** kbps, off MA's `AudioFormat.bit_rate`. 0 when the provider didn't say. */
    val bitRate: Int = 0,
    val channels: Int = 2,
    /** The stored file's size in bytes, when the library reported it. */
    val sizeBytes: Long = 0,
    /**
     * ReplayGain track-level adjustment in dB, off OpenSubsonic's `replayGain`
     * object. Null when the server didn't supply it — plain Subsonic servers omit
     * it entirely, so callers must read null as "no measurement" rather than 0 dB.
     *
     * Not populated on the Music Assistant path — but that is a **gap, not a
     * limitation**, and this comment used to claim otherwise. MA's `StreamDetails`
     * schema carries `loudness`, `loudness_album`, `prefer_album_loudness`,
     * `volume_normalization_mode`, `volume_normalization_gain_correct` and
     * `target_loudness`; the measurement and the correction MA actually applied are
     * both there to read, in different fields under a different name. Reading them
     * would let the quality card say what MA did to the loudness rather than going
     * quiet about it. Not wired yet.
     */
    val replayGainTrack: Float? = null,
    /**
     * ReplayGain album-level adjustment in dB. Null when absent. Album gain is
     * preferred for whole-album playback; track gain for shuffle.
     */
    val replayGainAlbum: Float? = null,
) {
    /**
     * The same reading as a [StreamQuality], for anything that wants to *show* it.
     *
     * Formatting lives there — including that Music Assistant reports a container or
     * MIME name rather than a codec, so an ordinary MP3 arrives as `mpeg` and reads
     * as "MPEG" if uppercased verbatim.
     */
    val quality: StreamQuality
        get() = StreamQuality(
            codec = codec,
            sampleRateHz = sampleRate,
            bitDepth = bitDepth,
            bitrateKbps = bitRate,
            replayGainTrack = replayGainTrack,
            replayGainAlbum = replayGainAlbum,
            channels = channels,
            sizeBytes = sizeBytes,
        )
}

/**
 * A Music Assistant media item (artist / album / track / playlist / radio).
 *
 * `@Immutable` because [providerDomains] and [genres] are `List<String>`, which Compose
 * otherwise infers as unstable. Strong skipping (on by default from Kotlin 2.0) means an
 * unstable item would still let a row skip — but only by comparing *instances*, so it
 * skips when the row is handed back the very object it already has, and not otherwise.
 * That is the wrong test here: every reconnect and refresh re-parses the library into
 * brand-new `MaItem`s, and under instance comparison every visible row rebuilds even
 * though nothing about it changed. Annotated, the comparison becomes the data class's
 * own `equals`, and an unchanged row stays put.
 *
 * The promise holds: both lists are built once during parsing and only ever read. The
 * same reasoning applies to the other annotated models here.
 */
@Immutable
data class MaItem(
    val itemId: String,
    val provider: String,
    val name: String,
    val uri: String?,
    val mediaType: String,
    val subtitle: String?,   // artist(s) / owner
    val image: String?,      // best-effort image URL
    val duration: Int?,
    val favorite: Boolean = false,
    val audioFormat: MaAudioFormat? = null,
    /**
     * The provider domains that can actually supply this item, off
     * `provider_mappings[].provider_domain`. A library item's own `provider` is
     * always "library" — it says where Music Assistant filed the track, not where
     * the bytes come from, which is what the source badge is asking about.
     */
    val providerDomains: List<String> = emptyList(),
    /** Album release year (from album metadata). */
    val year: Int? = null,
    /** Genres associated with this item. */
    val genres: List<String> = emptyList(),
    /** Track number within an album — also the sort order an offline album needs. */
    val trackNumber: Int? = null,
    /** Disc number for multi-disc albums (for tracks). */
    val discNumber: Int? = null,
    /**
     * Long-form prose about the item — an artist's biography, an album's notes.
     *
     * Music Assistant keeps it at `metadata.description`, which the app was not
     * reading, so the About section only ever appeared on the Navidrome backend
     * even when MA had the text sitting right there.
     */
    val description: String? = null,
    /**
     * The container this item sits in — a track's album id, an album's artist id.
     * Subsonic gives these directly; MA addresses items by uri instead and leaves
     * it null.
     */
    val parentId: String? = null,
    /** Album name, when the item carries one of its own (a Subsonic song does). */
    val album: String? = null,
    /**
     * Composer credits, when the item carries them (a Subsonic song or an MA track
     * with metadata). Shown below the artist on Now Playing for classical / jazz
     * tracks where the composer is the credit the listener actually cares about.
     */
    val composer: String? = null,
) {
    val browsable get() = mediaType in BROWSABLE
    val playable get() = uri != null && mediaType in PLAYABLE

    companion object {
        private val BROWSABLE = setOf("artist", "album", "playlist", "genre")
        private val PLAYABLE = setOf("track", "album", "playlist", "radio")
    }
}

/** What a player is currently playing (from a player's `current_media`). */
data class MaNowPlaying(
    val title: String,
    val artist: String,
    val album: String,
    val imageUrl: String?,
    val durationMs: Long?,
    val elapsedMs: Long?,
)

/** A Music Assistant player (a possible playback target / group). */
@Immutable
data class MaPlayer(
    val playerId: String,
    val name: String,
    val available: Boolean,
    val powered: Boolean,
    val type: String = "player",             // player | group | ...
    val state: String = "idle",              // playback_state: playing | paused | idle
    val volumeLevel: Int = 0,                // 0..100
    val groupVolume: Int? = null,            // 0..100 when this is a group/sync leader
    val syncedTo: String? = null,            // the leader this player is synced to (member)
    val groupChilds: List<String> = emptyList(), // members when this player is the leader
    val canGroupWith: List<String> = emptyList(), // player_ids this can be grouped with
    val supportedFeatures: List<String> = emptyList(),
    val icon: String? = null,
    val nowPlaying: MaNowPlaying? = null,
) {
    val isPlaying get() = state == "playing"
    /** This player leads an (ad-hoc sync or static) group. */
    val isLeader get() = groupChilds.isNotEmpty()

    /** This player follows another player's group. */
    val isMember get() = syncedTo != null

    /** The server lets us add/remove members with this player as target. */
    val canSetMembers get() = "set_members" in supportedFeatures
}

/** A player's Sendspin sync-delay config value + the (variable) key it lives under. */
data class SyncDelay(val key: String, val ms: Int)

/**
 * One entry of `current_item.streamdetails.dsp` — what Music Assistant's per-player
 * pipeline did to this track, and **whether it ran at all**.
 *
 * [state] is MA's `DSPState`, carried verbatim rather than mapped to an enum here.
 * Only `"disabled"` is confirmed against a real payload; a value this app has never
 * seen is worth showing as-is, because a guess rendered confidently is worse than an
 * unfamiliar word rendered honestly. The UI humanises it and falls through.
 *
 * This is the server's own answer to "why is my EQ doing nothing", and it was being
 * parsed and dropped — [MaParse] read the sibling `output_format` and nothing else.
 */
@Immutable
data class MaDspDetails(
    val state: String? = null,
    val outputFormat: StreamQuality? = null,
    val filterCount: Int = 0,
    val outputLimiter: Boolean = false,
) {
    /** MA ran the chain. Anything else — including an unknown state — has not. */
    val active: Boolean get() = state == "enabled"
}

/** A player queue: what's streaming, and how the queue itself is set up. */
@Immutable
data class MaQueue(
    val queueId: String,
    /**
     * What Music Assistant *opened*, off `current_item.streamdetails.audio_format`.
     *
     * This is the stream job's **input** — the file as the provider hands it over —
     * not what any speaker is fed. The app used to label it "Playing", which is why
     * the codec badge always agreed with the source no matter how much converting
     * MA did on the way out. See [dsp].
     */
    val inputFormat: StreamQuality? = null,
    /**
     * MA's per-player DSP pipeline, keyed by player_id, off
     * `current_item.streamdetails.dsp`.
     *
     * Each entry carries both the format that player is actually fed — that, and only
     * that, is the honest answer to "what is this speaker receiving" — and whether the
     * filter chain ran. Resolve with [outputFor] / [dspFor] rather than indexing
     * directly; a synced member may have no entry of its own.
     */
    val dsp: Map<String, MaDspDetails> = emptyMap(),
    val shuffleEnabled: Boolean = false,
    val repeatMode: String = "off",   // off | one | all
    val currentIndex: Int? = null,
    val itemCount: Int = 0,
    /** The library item behind `current_item` — the handle for favourite/lyrics/similar. */
    val currentItem: MaItem? = null,
    val currentQueueItemId: String? = null,
    val dontStopTheMusic: Boolean = false,
    val playbackSpeed: Float = 1f,
    /** The queue's own playhead, which the player object doesn't always agree with. */
    val elapsedMs: Long? = null,
    /**
     * Unix epoch **seconds** when the server last recomputed [elapsedMs].
     *
     * The staleness gate. For a remote speaker MA reports whatever it last scraped
     * from that provider, so `elapsed_time` on its own says nothing about how old it
     * is — two polls a second apart can carry the same reading. Comparing this stamp
     * is what tells a fresh reading from a repeat, and lets an out-of-order response
     * be dropped instead of dragging the bar back to a finished track.
     *
     * Null on a server that doesn't send it; callers must treat null as "can't tell"
     * rather than "stale", or a missing field would silently stop all updates.
     */
    val elapsedTimeLastUpdated: Double? = null,
    /**
     * The provider actually streaming the current item, off
     * `current_item.streamdetails.provider`. This is the honest answer to "where is
     * this coming from" — the media item itself is filed under "library" no matter
     * which backend holds the file.
     */
    val streamProvider: String? = null,
) {
    /**
     * Which entry speaks for [playerId], falling back until something is knowable.
     *
     * A synced member decodes on its own hardware and MA can hand it a different
     * format from the leader's, so its own entry wins. Failing that: the leader's
     * (some servers only file one entry, under the queue's own id), then a lone entry
     * when there is no ambiguity to resolve.
     *
     * Shared by [outputFor] and [dspFor] so the two can never disagree about whose
     * entry they are reading.
     */
    private fun <T : Any> resolve(entries: Map<String, T>, playerId: String, leaderId: String?): T? =
        entries[playerId]
            ?: leaderId?.let { entries[it] }
            ?: entries[queueId]
            ?: entries.values.singleOrNull()

    /**
     * The format [playerId] is being fed, or the input format — which is at least
     * honest about the file, if not about the wire.
     *
     * Resolved over the entries that actually carry a format. An entry may now arrive
     * with a `state` and no `output_format`, and letting one of those win the "lone
     * entry" rung would report nothing for a player MA is demonstrably feeding.
     */
    fun outputFor(playerId: String, leaderId: String? = null): StreamQuality? =
        resolve(dsp.filterValues { it.outputFormat != null }, playerId, leaderId)?.outputFormat
            ?: inputFormat

    /** What MA's pipeline did for [playerId], including whether it ran. */
    fun dspFor(playerId: String, leaderId: String? = null): MaDspDetails? =
        resolve(dsp, playerId, leaderId)
}

/** Grouped search hits. */
@Immutable
data class MaSearchResults(
    val artists: List<MaItem>,
    val albums: List<MaItem>,
    val tracks: List<MaItem>,
    val playlists: List<MaItem>,
)

/** A single queue item with its stream details. */
data class MaQueueItem(
    val queueItemId: String,
    val name: String,
    val duration: Int?,
    val sortIndex: Int,
    val mediaItem: MaItem?,
    val streamDetails: StreamQuality?,
    /** Position in the queue — what `player_queues/play_index` takes. */
    val index: Int = 0,
    val artist: String? = null,
    val image: String? = null,
    val available: Boolean = true,
)

/** One timed line of an LRC lyric. */
data class LyricLine(val atMs: Long, val text: String)

/**
 * Lyrics for a track, plain or LRC-timed.
 *
 * [lines] is a `by lazy` val — computed once on first read and never recomputed, so
 * it satisfies `@Immutable` the same way a stored field would.
 */
@Immutable
data class MaLyrics(
    val text: String,
    val synced: Boolean = false,
) {
    /**
     * The lyric as timed lines. Plain lyrics come back as one line per row with a
     * timestamp of 0, so callers can render either kind the same way and simply
     * not highlight when [synced] is false.
     */
    val lines: List<LyricLine> by lazy {
        if (!synced) return@lazy text.lines().map { LyricLine(0, it.trim()) }
        val out = mutableListOf<LyricLine>()
        for (raw in text.lines()) {
            val stamps = LRC_STAMP.findAll(raw).toList()
            if (stamps.isEmpty()) continue
            val body = raw.substring(stamps.last().range.last + 1).trim()
            for (m in stamps) {
                val (mm, ss, frac) = m.destructured
                // The fraction is 2 or 3 digits (centi- or milliseconds).
                val fracMs = when (frac.length) {
                    0 -> 0L
                    2 -> frac.toLong() * 10
                    else -> frac.padEnd(3, '0').take(3).toLong()
                }
                out += LyricLine(mm.toLong() * 60_000 + ss.toLong() * 1_000 + fracMs, body)
            }
        }
        out.sortedBy { it.atMs }
    }

    private companion object {
        val LRC_STAMP = Regex("""\[(\d{1,3}):(\d{2})(?:[.:](\d{2,3}))?]""")
    }
}

/**
 * One event pushed by Music Assistant on the authenticated socket.
 *
 * The app used to decide what an event meant by looking for the substrings "player"
 * or "queue" anywhere in the frame's JSON. Every event matched the same branch, so
 * `queue_items_updated` — the one that says the queue's *contents* changed — was
 * indistinguishable from the `queue_time_updated` MA emits about once a second.
 * That is why adding an album left an open queue panel showing the old list.
 *
 * [objectId] is the player_id or queue_id the event is about.
 */
data class MaEvent(
    val name: String,
    val objectId: String?,
    val data: JsonElement? = null,
) {
    /** Anything about a player or a queue — the metadata refresh trigger. */
    val isPlayerOrQueue: Boolean get() = name.startsWith("player") || name.startsWith("queue")

    /**
     * The set of items in a queue changed, as opposed to where in it we are.
     *
     * `queue_items_updated` is MA's own signal for this and fires whoever did the
     * adding, which is what lets the panel follow a "play album" issued from a
     * different screen without any wiring between their ViewModels.
     */
    val changesQueueContents: Boolean
        get() = name == "queue_items_updated" || name == "queue_added"
}

/** A track similar to the seed (from sonic_similarity or music/tracks/similar_tracks). */
data class MaSimilarTrack(
    val itemId: String,
    val name: String,
    val artist: String?,
    val image: String?,
    val uri: String?,
    val provider: String,
)

object MaParse {

    /**
     * The *upstream* music provider MA is pulling the bytes from, as a listener would
     * name it — "Subsonic", "Spotify", "Plex" — or null when there is nothing useful
     * to say.
     *
     * MA reports this as an instance id (`spotify--AbC123`) or a bare domain. It
     * answers "where did this file come from", which is **not** the same question as
     * "what is playing this", and conflating the two is what put "Subsonic" in the
     * corner badge while Music Assistant was plainly the thing playing. The badge
     * names the backend; this names the shelf the backend took the track off, and
     * belongs with the rest of the stream detail.
     *
     * Null rather than a fallback: an unrecognised or absent provider has no brand
     * worth printing, and inventing one would be the same mistake in a smaller font.
     */
    fun streamProviderLabel(instanceOrDomain: String?): String? =
        when (instanceOrDomain?.substringBefore("--")?.lowercase()) {
            "opensubsonic", "subsonic" -> "Subsonic"
            "filesystem_local", "filesystem_smb", "filesystem" -> "Local files"
            "spotify" -> "Spotify"
            "tidal" -> "Tidal"
            "qobuz" -> "Qobuz"
            "deezer" -> "Deezer"
            "apple_music" -> "Apple Music"
            "ytmusic", "youtube_music" -> "YT Music"
            "soundcloud" -> "SoundCloud"
            "radiobrowser", "tunein" -> "Radio"
            "plex" -> "Plex"
            "jellyfin" -> "Jellyfin"
            else -> null
        }

    /** One pushed event frame, or null if it isn't one. */
    fun event(o: JsonObject): MaEvent? {
        val name = (o["event"] as? JsonPrimitive)?.contentOrNull ?: return null
        return MaEvent(name, (o["object_id"] as? JsonPrimitive)?.contentOrNull, o["data"])
    }

    fun items(result: JsonElement?, serverUrl: String?): List<MaItem> = when (result) {
        is JsonArray -> result.mapNotNull { item(it, serverUrl) }
        is JsonObject -> (result["items"] as? JsonArray)?.mapNotNull { item(it, serverUrl) } ?: emptyList()
        else -> emptyList()
    }

    fun item(el: JsonElement?, serverUrl: String?): MaItem? {
        val o = el as? JsonObject ?: return null
        val itemId = o["item_id"]?.jsonPrimitive?.contentOrNull ?: return null
        return MaItem(
            itemId = itemId,
            provider = o["provider"]?.jsonPrimitive?.contentOrNull ?: "library",
            name = o["name"]?.jsonPrimitive?.contentOrNull ?: "?",
            uri = o["uri"]?.jsonPrimitive?.contentOrNull,
            mediaType = o["media_type"]?.jsonPrimitive?.contentOrNull ?: "track",
            subtitle = artistString(o) ?: o["owner"]?.jsonPrimitive?.contentOrNull,
            image = imageUrl(o, serverUrl),
            duration = o["duration"]?.jsonPrimitive?.let { it.intOrNull ?: it.doubleOrNull?.toInt() },
            favorite = o["favorite"]?.jsonPrimitive?.booleanOrNull ?: false,
            audioFormat = audioFormat(o),
            providerDomains = providerDomains(o),
            year = o["year"]?.jsonPrimitive?.intOrNull,
            genres = genreList(o),
            trackNumber = o["track_number"]?.jsonPrimitive?.intOrNull,
            discNumber = o["disc_number"]?.jsonPrimitive?.intOrNull,
            // MA carries composer in the item's metadata block, not at the top
            // level — `metadata.composer` is where it lives for tracks that have
            // one tagged. Classical/jazz listeners want to see it, so pass it
            // through rather than dropping it at parse time.
            composer = (o["metadata"] as? JsonObject)?.let {
                it["composer"]?.jsonPrimitive?.contentOrNull?.takeIf { c -> c.isNotBlank() }
            },
            // Same block: `metadata.description` is MA's biography/notes field, with
            // `review` as the album-side alternative some providers fill instead.
            description = (o["metadata"] as? JsonObject)?.let { m ->
                (m["description"] ?: m["review"])?.jsonPrimitive?.contentOrNull
                    ?.takeIf { d -> d.isNotBlank() }
            },
        )
    }

    /** Which backends can supply this item, in the order the server listed them. */
    private fun providerDomains(o: JsonObject): List<String> =
        (o["provider_mappings"] as? JsonArray)
            ?.mapNotNull { (it as? JsonObject) }
            ?.mapNotNull {
                it["provider_domain"]?.jsonPrimitive?.contentOrNull
                    ?: it["provider_instance"]?.jsonPrimitive?.contentOrNull
            }
            ?.distinct()
            ?: emptyList()

    /** The best (highest-rate) format any provider can supply this item in. */
    private fun audioFormat(o: JsonObject): MaAudioFormat? =
        (o["provider_mappings"] as? JsonArray)
            ?.mapNotNull { (it as? JsonObject)?.get("audio_format") as? JsonObject }
            ?.mapNotNull { f ->
                val rate = f["sample_rate"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                val br = (f["bit_rate"] as? JsonPrimitive)?.intOrNull ?: 0
                MaAudioFormat(
                    codec = f["content_type"]?.jsonPrimitive?.contentOrNull
                        ?: f["codec_type"]?.jsonPrimitive?.contentOrNull ?: "?",
                    sampleRate = rate,
                    // 0, not 16. A provider that doesn't report a depth has not told
                    // us it is CD depth, and the Subsonic side of the app has always
                    // defaulted to 0 for exactly that reason — the two disagreeing
                    // meant the same file read differently depending on which library
                    // it was browsed through. "Unknown" is a thing the badge can say.
                    bitDepth = f["bit_depth"]?.jsonPrimitive?.intOrNull ?: 0,
                    bitRate = if (br > 10_000) br / 1000 else br,
                    channels = (f["channels"] as? JsonPrimitive)?.intOrNull ?: 0,
                )
            }
            ?.maxByOrNull { it.sampleRate.toLong() * 100 + it.bitDepth }

    fun players(result: JsonElement?): List<MaPlayer> {
        val arr = result as? JsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o["player_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            MaPlayer(
                playerId = id,
                name = o["display_name"]?.jsonPrimitive?.contentOrNull
                    ?: o["name"]?.jsonPrimitive?.contentOrNull ?: id,
                available = o["available"]?.jsonPrimitive?.booleanOrNull ?: true,
                powered = o["powered"]?.jsonPrimitive?.booleanOrNull ?: true,
                type = o["type"]?.jsonPrimitive?.contentOrNull ?: "player",
                state = o["playback_state"]?.jsonPrimitive?.contentOrNull ?: "idle",
                volumeLevel = o["volume_level"]?.jsonPrimitive?.intOrNull ?: 0,
                groupVolume = o["group_volume"]?.jsonPrimitive?.intOrNull,
                syncedTo = o["synced_to"]?.jsonPrimitive?.contentOrNull,
                // MA renamed this to `group_members`; older servers still send
                // `group_childs`, so accept whichever one turns up.
                groupChilds = strList(o["group_members"] ?: o["group_childs"]),
                canGroupWith = strList(o["can_group_with"]),
                supportedFeatures = strList(o["supported_features"]),
                icon = o["icon"]?.jsonPrimitive?.contentOrNull,
                nowPlaying = nowPlaying(o["current_media"], o["elapsed_time"]),
            )
        }
    }

    private fun nowPlaying(el: JsonElement?, elapsed: JsonElement?): MaNowPlaying? {
        val m = el as? JsonObject ?: return null
        val title = m["title"]?.jsonPrimitive?.contentOrNull ?: return null
        fun ms(e: JsonElement?): Long? = (e as? JsonPrimitive)?.let {
            it.doubleOrNull?.let { d -> (d * 1000).toLong() } ?: it.longOrNull
        }
        return MaNowPlaying(
            title = title,
            artist = m["artist"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            album = m["album"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            imageUrl = m["image_url"]?.jsonPrimitive?.contentOrNull
                ?: ((m["image"] as? JsonObject)?.get("path")?.jsonPrimitive?.contentOrNull),
            durationMs = ms(m["duration"]),
            elapsedMs = ms(m["elapsed_time"] ?: elapsed),
        )
    }

    fun queues(result: JsonElement?, serverUrl: String? = null): List<MaQueue> {
        val arr = result as? JsonArray ?: return emptyList()
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o["queue_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val current = o["current_item"] as? JsonObject
            val (inputFormat, dsp) = streamFormats(current)
            MaQueue(
                queueId = id,
                inputFormat = inputFormat,
                dsp = dsp,
                shuffleEnabled = o["shuffle_enabled"]?.jsonPrimitive?.booleanOrNull ?: false,
                repeatMode = o["repeat_mode"]?.jsonPrimitive?.contentOrNull ?: "off",
                currentIndex = o["current_index"]?.jsonPrimitive?.intOrNull,
                itemCount = o["items"]?.jsonPrimitive?.intOrNull ?: 0,
                currentItem = item(current?.get("media_item"), serverUrl),
                currentQueueItemId = current?.get("queue_item_id")?.jsonPrimitive?.contentOrNull,
                dontStopTheMusic = o["dont_stop_the_music_enabled"]?.jsonPrimitive?.booleanOrNull ?: false,
                playbackSpeed = o["playback_speed"]?.jsonPrimitive?.floatOrNull ?: 1f,
                elapsedMs = o["elapsed_time"]?.jsonPrimitive?.doubleOrNull?.let { (it * 1000).toLong() },
                elapsedTimeLastUpdated = o["elapsed_time_last_updated"]?.jsonPrimitive?.doubleOrNull,
                streamProvider = (current?.get("streamdetails") as? JsonObject)
                    ?.get("provider")?.jsonPrimitive?.contentOrNull,
            )
        }
    }

    /**
     * One MA `AudioFormat` object → a [StreamQuality], or null when it says nothing
     * worth showing.
     */
    private fun audioFormatQuality(f: JsonObject?): StreamQuality? {
        if (f == null) return null
        val codec = str(f["content_type"])
            ?: str(f["codec_type"])
            // Older servers, and the legacy flat streamdetails shape.
            ?: str(f["codec"])
            ?: return null
        if (codec.isBlank() || codec.equals("unknown", ignoreCase = true) || codec == "?") return null
        // MA documents bit_rate in kbps, but a provider that fills it in bits per
        // second would otherwise render as "1411000k" on the badge.
        val br = (f["bit_rate"] as? JsonPrimitive)?.intOrNull ?: 0
        return StreamQuality(
            codec = codec,
            sampleRateHz = (f["sample_rate"] as? JsonPrimitive)?.intOrNull ?: 0,
            bitDepth = (f["bit_depth"] as? JsonPrimitive)?.intOrNull ?: 0,
            bitrateKbps = if (br > 10_000) br / 1000 else br,
            channels = (f["channels"] as? JsonPrimitive)?.intOrNull ?: 0,
        )
    }

    /**
     * A queue item's `streamdetails`, split into what MA read and what each player
     * is fed.
     *
     * `streamdetails.audio_format` is the stream job's **input**. The per-player
     * **output** lives in `streamdetails.dsp`, a map of player_id → `DSPDetails`
     * whose `output_format` is what actually goes down the wire. Reading only the
     * former is why the codec badge always matched the source file.
     *
     * A `dsp` entry whose `state` is disabled still carries an output format, and
     * that is still the honest answer — **no filtering on state**. That rule survives
     * `state` becoming readable: the temptation is to drop disabled entries now that
     * there is something to drop them by, and doing so brings back the badge that
     * always agreed with the source file. The state is reported *alongside* the
     * format, never instead of it.
     *
     * An entry is kept even when it carries no `output_format` at all, so a bare
     * `state` still reaches the UI. [MaQueue.outputFor] filters those back out.
     */
    private fun streamFormats(currentItem: JsonElement?): Pair<StreamQuality?, Map<String, MaDspDetails>> {
        val sd = (currentItem as? JsonObject)?.get("streamdetails") as? JsonObject
            ?: return null to emptyMap()
        // MA has moved these between the streamdetails root and a nested
        // `audio_format` across versions, so both shapes are accepted.
        val input = audioFormatQuality(sd["audio_format"] as? JsonObject) ?: audioFormatQuality(sd)
        val dsp = sd["dsp"] as? JsonObject ?: return input to emptyMap()
        val entries = buildMap {
            for ((playerId, entry) in dsp) {
                val o = entry as? JsonObject ?: continue
                put(
                    playerId,
                    MaDspDetails(
                        state = str(o["state"]),
                        outputFormat = audioFormatQuality(o["output_format"] as? JsonObject),
                        filterCount = (o["filters"] as? JsonArray)?.size ?: 0,
                        outputLimiter = (o["output_limiter"] as? JsonPrimitive)?.booleanOrNull ?: false,
                    ),
                )
            }
        }
        return input to entries
    }

    /**
     * A string field, read without assuming its shape. `.jsonPrimitive` *throws* on
     * an object or an array rather than returning null, so a field the server sends
     * in an unexpected shape would take down whatever was parsing it — which is
     * exactly the crash that showed up browsing artists.
     */
    private fun str(el: JsonElement?): String? = (el as? JsonPrimitive)?.contentOrNull

    private fun strList(el: JsonElement?): List<String> =
        (el as? JsonArray)?.mapNotNull { str(it) } ?: emptyList()

    private fun artistString(o: JsonObject): String? {
        val arr = o["artists"] as? JsonArray ?: return null
        val names = arr.mapNotNull { str((it as? JsonObject)?.get("name")) }
        return names.joinToString(", ").ifBlank { null }
    }

    /** Genres can arrive as a JSON array of strings, a comma-separated string, or a list of objects. */
    private fun genreList(o: JsonObject): List<String> = when (val g = o["genres"]) {
        is JsonArray -> g.mapNotNull { item -> str((item as? JsonObject)?.get("name")) ?: str(item) }
        is JsonPrimitive -> g.contentOrNull?.split(",")?.map { it.trim() }?.filter { it.isNotBlank() } ?: emptyList()
        else -> emptyList()
    }

    /**
     * Resolve a media item's artwork.
     *
     * The server sends images in more than one shape and only the first was being
     * handled, which is why most of the library came back blank:
     *  - full items (Album/Artist/Track) carry `metadata.images[]`, but that field
     *    is nullable and empty until a metadata scan has run;
     *  - an `ItemMapping` — what search results and nested artist/album refs are —
     *    carries a *single* `image` object instead, with no `metadata` at all;
     *  - a track usually has no art of its own and inherits its album's.
     *
     * `remotely_accessible` marks a path that is already a public URL; everything
     * else has to go through the server's `/imageproxy`.
     */
    private fun imageUrl(o: JsonObject, serverUrl: String?): String? =
        imageFrom(o, serverUrl)
            ?: imageFrom(o["album"] as? JsonObject, serverUrl)
            ?: (o["artists"] as? JsonArray)?.firstNotNullOfOrNull {
                imageFrom(it as? JsonObject, serverUrl)
            }

    /** Artwork carried directly by [o], ignoring anything it merely references. */
    private fun imageFrom(o: JsonObject?, serverUrl: String?): String? {
        if (o == null) return null
        val candidates: List<JsonObject> = buildList {
            ((o["metadata"] as? JsonObject)?.get("images") as? JsonArray)
                ?.forEach { (it as? JsonObject)?.let(::add) }
            (o["images"] as? JsonArray)?.forEach { (it as? JsonObject)?.let(::add) }
            (o["image"] as? JsonObject)?.let(::add)
        }
        if (candidates.isEmpty()) return null
        // Prefer a square thumb; the rest (fanart, banner, logo) crop badly in a tile.
        val img = candidates.firstOrNull { it["type"]?.jsonPrimitive?.contentOrNull == "thumb" }
            ?: candidates.first()
        val path = img["path"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } ?: return null
        val remote = img["remotely_accessible"]?.jsonPrimitive?.booleanOrNull ?: false
        if (remote || path.startsWith("http", ignoreCase = true)) return path
        val base = serverUrl?.trimEnd('/')?.takeIf { it.isNotBlank() } ?: return null
        val provider = img["provider"]?.jsonPrimitive?.contentOrNull ?: "builtin"
        return imageProxyUrl(base, provider, path)
    }

    /**
     * Music Assistant's own image-proxy URL shape, reproduced exactly:
     *
     * ```
     * {base}/imageproxy?provider={provider}&size=0&fmt={fmt}&path={quote_plus(quote_plus(path))}
     * ```
     *
     * The **double** encoding is not a mistake — the server encodes the path twice
     * on the way out and unquotes it twice on the way in, so a singly-encoded path
     * comes back mangled for anything containing a `%`, `+` or a nested URL. Sending
     * it singly-encoded is what left every non-remote cover blank.
     *
     * [base] is the *API* base here; MA actually serves the proxy from its stream
     * server, which usually sits on a different port. Rather than guess the port,
     * the app's image loader ([com.engabd.sendpin.SendpinApp]) probes the handful of
     * shapes MA has used and remembers whichever one the server answers.
     */
    fun imageProxyUrl(base: String, provider: String, path: String, size: Int = 0): String {
        val encoded = URLEncoder.encode(URLEncoder.encode(path, "UTF-8"), "UTF-8")
        return "${base.trimEnd('/')}/imageproxy?provider=${URLEncoder.encode(provider, "UTF-8")}" +
            "&size=$size&fmt=${imageFormat(path)}&path=$encoded"
    }

    /** MA's `_detect_image_format`: read off the extension, PNG when in doubt. */
    private fun imageFormat(path: String): String =
        when (path.substringAfterLast('.', "").substringBefore('?').lowercase()) {
            "jpg", "jpeg" -> "jpeg"
            "gif" -> "gif"
            "webp" -> "webp"
            "svg" -> "svg"
            else -> "png"
        }

    // --- queue items --------------------------------------------------------

    /**
     * [indexOffset] is the `offset` the page was fetched with. It only matters for a
     * server that omits `index`: without it, every page's fallback index restarts at
     * zero and `player_queues/play_index` plays the wrong track past the first page.
     */
    fun queueItems(result: JsonElement?, serverUrl: String?, indexOffset: Int = 0): List<MaQueueItem> {
        val arr = result as? JsonArray ?: return emptyList()
        return arr.mapIndexedNotNull { i, el ->
            val o = el as? JsonObject ?: return@mapIndexedNotNull null
            val id = o["queue_item_id"]?.jsonPrimitive?.contentOrNull ?: return@mapIndexedNotNull null
            val media = item(o["media_item"], serverUrl)
            MaQueueItem(
                queueItemId = id,
                name = o["name"]?.jsonPrimitive?.contentOrNull ?: media?.name ?: "?",
                duration = o["duration"]?.jsonPrimitive?.intOrNull,
                sortIndex = o["sort_index"]?.jsonPrimitive?.intOrNull ?: 0,
                mediaItem = media,
                // The item's input format. A per-player output only exists for the
                // item currently streaming, and that lives on the queue.
                streamDetails = streamFormats(o).first,
                // `index` is the queue position play_index takes; fall back to the
                // array order, offset by the page this row came from.
                index = o["index"]?.jsonPrimitive?.intOrNull ?: (indexOffset + i),
                artist = media?.subtitle,
                image = media?.image
                    ?: ((o["image"] as? JsonObject)?.let { imageUrl(JsonObject(mapOf("images" to JsonArray(listOf(it)))), serverUrl) }),
                available = o["available"]?.jsonPrimitive?.booleanOrNull ?: true,
            )
        }
    }

    // --- lyrics ------------------------------------------------------------

    fun lyrics(result: JsonElement?): MaLyrics? {
        // MA returns a (lyrics, lrc_lyrics) tuple — a 2-element array where [0] is
        // plain text and [1] is LRC-synced text (or null). It can also come back as
        // a bare string for older endpoints.
        when (result) {
            is JsonArray -> {
                if (result.size < 1) return null
                val lrc = result.getOrNull(1)?.jsonPrimitive?.contentOrNull
                val plain = result.getOrNull(0)?.jsonPrimitive?.contentOrNull
                val text = lrc ?: plain ?: return null
                return MaLyrics(text = text, synced = lrc != null)
            }
            is JsonObject -> {
                val text = result["lyrics"]?.jsonPrimitive?.contentOrNull
                    ?: result["text"]?.jsonPrimitive?.contentOrNull ?: return null
                return MaLyrics(
                    text = text,
                    synced = result["synced"]?.jsonPrimitive?.booleanOrNull ?: false,
                )
            }
            is JsonPrimitive -> {
                val text = result.contentOrNull ?: return null
                return MaLyrics(text = text, synced = false)
            }
            else -> return null
        }
    }

    // --- similar tracks ----------------------------------------------------

    fun similarTracks(result: JsonElement?, serverUrl: String?): List<MaSimilarTrack> {
        // music/tracks/similar_tracks → Array of Track; sonic_similarity/text_search
        // → object with string keys and Any values (a dict of matches). Both carry
        // item_id / name / artists at the leaf level.
        val arr = when (result) {
            is JsonArray -> result
            is JsonObject -> {
                // text_search returns a dict; try common result-wrapper keys, else treat values.
                (result["matches"] as? JsonArray)
                    ?: (result["result"] as? JsonArray)
                    ?: JsonArray(result.values.filter { it is JsonObject || it is JsonArray })
            }
            else -> return emptyList()
        }
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val id = o["item_id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            MaSimilarTrack(
                itemId = id,
                name = o["name"]?.jsonPrimitive?.contentOrNull ?: "?",
                artist = artistString(o),
                image = imageUrl(o, serverUrl),
                uri = o["uri"]?.jsonPrimitive?.contentOrNull,
                provider = o["provider"]?.jsonPrimitive?.contentOrNull ?: "library",
            )
        }
    }
}
