package com.engabd.sendpin.ui.viewmodel

import com.engabd.sendpin.library.MusicSources
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.engabd.sendpin.SendpinApp
import com.engabd.sendpin.audio.FormatNegotiator
import com.engabd.sendpin.audio.LocalTrack
import com.engabd.sendpin.audio.StreamQuality
import com.engabd.sendpin.audio.TrackScan
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.discovery.PlayerIdentity
import com.engabd.sendpin.local.db.LocalMediaDatabase
import com.engabd.sendpin.local.db.PlayHistoryEntity
import com.engabd.sendpin.ma.MaApiClient
import com.engabd.sendpin.ma.MaDspDetails
import com.engabd.sendpin.ma.MaItem
import com.engabd.sendpin.ma.MaLoudness
import com.engabd.sendpin.ma.MaLyrics
import com.engabd.sendpin.ma.MaNowPlaying
import com.engabd.sendpin.ma.MaParse
import com.engabd.sendpin.ma.MaPlayer
import com.engabd.sendpin.ma.MaQueue
import com.engabd.sendpin.ma.MaQueueItem
import com.engabd.sendpin.ma.MaRepository
import com.engabd.sendpin.ma.MaSimilarTrack
import com.engabd.sendpin.subsonic.SubsonicClient
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Now-Playing as a **controller**: reflects and controls the currently *selected*
 * player (the Speakers "Play here" target, "" = this phone) via the Music Assistant
 * API, so the screen shows whatever that player is playing — title/artist/art,
 * playing/paused, position — not just this phone's own Sendspin stream.
 *
 * It also owns the three panels hung off that track — queue, lyrics and sonically
 * similar tracks — which load on demand rather than on every poll.
 */
@OptIn(kotlinx.coroutines.FlowPreview::class)
class NowPlayingViewModel(app: Application) : AndroidViewModel(app) {

    data class State(
        val playerName: String = "",
        val isSelf: Boolean = true,
        val title: String = "",
        val artist: String = "",
        /** Composer credit — shown below the artist for classical / jazz tracks. */
        val composer: String = "",
        val album: String = "",
        val artworkUrl: String? = null,
        val isPlaying: Boolean = false,
        val volume: Float = 1f,
        val positionMs: Long = 0,
        val durationMs: Long = 0,
        /** The shown track is loaded on the player right now (vs. carried over). */
        val hasTrack: Boolean = false,
        /** The player has nothing loaded — show the screen, but say so. */
        val idle: Boolean = true,
        /** Nothing has been seen playing yet, so there is not even a stale track. */
        val blank: Boolean = true,
        /** What is actually coming out of the pipe right now — the negotiated/decoded format. */
        val quality: StreamQuality? = null,
        /** The original source format from the library (before any transcoding). */
        val sourceQuality: StreamQuality? = null,
        /**
         * Which **backend owns playback**: "MA", the server's display name
         * (Navidrome, Jellyfin, etc.), or "Offline". Never blank — that is
         * knowable even before a queue exists, which is why the badge asks
         * this question and not a harder one.
         *
         * Deliberately not the upstream music provider. Reporting that here read
         * "Subsonic" whenever Music Assistant streamed a track out of a Subsonic
         * library, which is true about the *file* and wrong about the *player*: the
         * transport buttons, the queue and the speaker list were all MA's. See
         * [streamProvider] for where that reading went.
         */
        val source: String = "MA",
        /**
         * The upstream provider MA pulled the bytes from ("Subsonic", "Spotify"), or
         * null when the server doesn't say. Stream detail, shown in the quality card
         * rather than the badge — see [source].
         */
        val streamProvider: String? = null,
        /** Players sharing this stream, when the target leads a sync group. */
        val groupSize: Int = 1,
        val shuffle: Boolean = false,
        val repeatMode: String = "off",   // off | one | all
        val queueSize: Int = 0,
        /** Which queue row is playing. Matched by id, since indexes shift on edits. */
        val currentQueueItemId: String? = null,
        val playbackSpeed: Float = 1f,
        val dontStopTheMusic: Boolean = false,
        val powered: Boolean = true,
        /** The player exposes a power switch, so the control is worth showing. */
        val canPower: Boolean = false,
        /**
         * This phone is playing on its own (Navidrome-direct or offline) rather than
         * reflecting a Music Assistant player. The MA connection being down is then
         * not worth complaining about — nothing on screen depends on it.
         */
        val isLocalSession: Boolean = false,
        /** Radio mode: MA auto-generates a radio queue after the current one ends. */
        val radioMode: Boolean = false,
        /**
         * What Music Assistant's per-player DSP did to this stream, and whether it ran
         * at all — the server's own `streamdetails.dsp[<player_id>].state`.
         *
         * MA-path only, and deliberately left null on the local path: the equaliser is
         * a server-side pipeline, so a phone playing straight from Navidrome has no DSP
         * for MA to have an opinion about.
         */
        val dsp: MaDspDetails? = null,
        /**
         * What Music Assistant did to the *level* of this stream.
         *
         * MA-path only. On the local path the app's own ReplayGain setting is what
         * acts on the level, and the card already says so.
         */
        val loudness: MaLoudness = MaLoudness(),
    )

    /** A panel's load state — the UI has to tell "empty" from "not fetched yet". */
    sealed interface Load<out T> {
        data object Idle : Load<Nothing>
        data object Loading : Load<Nothing>
        data class Ready<T>(val value: T) : Load<T>
        data class Failed(val message: String) : Load<Nothing>
    }

    private val settings = AppSettings(app)
    /** Live, not captured — see [PlayerIdentity.getPlayerId]. */
    private val myPlayerId: String get() = PlayerIdentity.getPlayerId(getApplication<Application>())
    private val api = (app as SendpinApp).maApi
    /** The one play-history watch in flight, cancelled and replaced on every track change. */
    private var historyJob: Job? = null

    /**
     * The process-scoped poller that owns `players/all` and `player_queues/all`.
     *
     * Read from rather than duplicated: it already polls on a 5 s floor and refreshes
     * on sampled player/queue events, and it outlives this screen.
     */
    private val maNowPlaying = (app as SendpinApp).maNowPlaying
    private val repo = MaRepository(api)
    /**
     * The standalone queue player (Navidrome-direct / offline). While it has a
     * session loaded it *is* what's playing on this phone, so it takes the screen
     * over from the Music Assistant view — otherwise starting a Navidrome album
     * left Now Playing showing whatever MA last had, with transport buttons that
     * controlled something the user couldn't hear.
     */
    private val local = (app as SendpinApp).localPlayer
    /** Offline per-track analysis (bpm, key, sections) — for the Music Map timeline. */
    private val trackScans = (app as SendpinApp).trackScans
    /** This phone's own Sendspin connection and stream. */
    private val playback = (app as SendpinApp).playback
    /** This phone's own Sendspin stream — the authoritative format when we're the player. */
    private val localQuality = playback.streamQuality
    /** True while this phone's Sendspin stream is actually running. */
    private val sendspinPlaying = playback.isPlaying
    /** What this phone can put out on its own, for locally-decoded playback. */
    private val deviceQuality = FormatNegotiator.deviceOutputQuality()

    /**
     * The phone's media volume — the slider's meaning while the local player owns
     * playback, where there is no server-side player level to set.
     */
    private val deviceVolume = (app as SendpinApp).deviceVolume

    /**
     * The Navidrome transcode currently asked for, or null when streaming the stored
     * file. Held as plain state rather than read per frame — it changes rarely and the
     * badge is rebuilt on every poll.
     */
    @Volatile private var navFormat: String? = null

    /**
     * The format a Navidrome transcode token describes, for the Source row. Rates and
     * depths are the codecs' own defaults — the token only pins codec and bitrate, and
     * claiming a sample rate the server never promised would be worse than omitting it.
     */
    private fun transcodeQuality(token: String): StreamQuality? {
        val codec = token.substringBefore('-')
        val kbps = token.substringAfter('-', "").toIntOrNull() ?: 0
        return when (codec) {
            "flac" -> StreamQuality("FLAC", 0, 16)
            "mp3" -> StreamQuality("MP3", 0, 0, kbps)
            "opus" -> StreamQuality("OPUS", 48_000, 0, kbps)
            else -> null
        }
    }

    /** Everything the local player exposes, folded into one value to combine with. */
    private data class LocalSnap(
        val active: Boolean = false,
        val track: LocalTrack? = null,
        val playing: Boolean = false,
        val durationMs: Long = 0,
        val queueSize: Int = 0,
        val index: Int = -1,
        val shuffle: Boolean = false,
        val repeat: String = "off",
        val speed: Float = 1f,
    )

    /** Holds the five flows that feed the local-playback state so the 6-way combine stays type-safe. */
    private data class LocalInfo(
        val ma: State,
        val l: LocalSnap,
        val devVol: Float,
        val backend: String,
        val radio: Boolean,
    )

    private val localSnap: StateFlow<LocalSnap> = combine(
        local.active, local.current, local.playing, local.durationMs,
        combine(local.queue, local.index, local.shuffle, local.repeatMode, local.speed) { q, i, s, r, sp ->
            LocalSnap(queueSize = q.size, index = i, shuffle = s, repeat = r, speed = sp)
        },
    ) { active, track, playing, dur, queue ->
        queue.copy(active = active, track = track, playing = playing, durationMs = dur)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, LocalSnap())

    /** True while the phone is playing on its own rather than through MA. */
    /**
     * Which library the app is pointed at — and so, which player owns the screen.
     *
     * On the Navidrome backend this phone *is* the player: there is no Music
     * Assistant player to reflect, which is why Speakers and Light Sync are off
     * there too. Owning the screen therefore cannot depend on the local player
     * having a session loaded, or switching to Navidrome with nothing playing yet
     * leaves Now Playing showing an MA player whose transport controls drive
     * something the user has just switched away from. That asymmetry is why
     * Navidrome → MA looked right and MA → Navidrome did not.
     */
    private val backendPref: StateFlow<String> =
        settings.backend.stateIn(viewModelScope, SharingStarted.Eagerly, "ma")

    /** The display name of the active library server (Navidrome, Jellyfin, etc.). */
    private val activeServerName: StateFlow<String> =
        settings.activeServer.map { it?.displayName ?: "Library" }
            .stateIn(viewModelScope, SharingStarted.Eagerly, "Library")

    private val onSubsonic get() = backendPref.value == "subsonic"

    private val isLocal get() = localSnap.value.active || onSubsonic

    private val _target = MutableStateFlow("")
    private val _players = MutableStateFlow<List<MaPlayer>>(emptyList())
    private val _queues = MutableStateFlow<List<MaQueue>>(emptyList())

    /**
     * The last track we actually saw playing. Polls can come back empty between
     * tracks, on reconnect, or while the server is still waking up; without this
     * the screen would blank out and then repopulate every time.
     */
    private val _lastTrack = MutableStateFlow<MaNowPlaying?>(null)

    /**
     * Radio mode: when the queue runs out, MA carries on with similar tracks
     * generated from the seed. This is the queue-level version of "don't stop
     * the music".
     *
     * It is a parameter of `player_queues/play_media`, so it takes effect on the
     * *next* thing played rather than the queue already running — hence persisted
     * in [AppSettings], where the library's play paths read it. Toggling it here
     * cannot retrofit the queue that is already going.
     *
     * **Declared here, with the other backing flows, and not next to
     * [toggleRadioMode] where it reads more naturally.** [maState] is an eagerly
     * started `stateIn` on `viewModelScope`, which is `Dispatchers.Main.immediate`
     * — so on the main thread its combine runs *during construction*, and every
     * property it touches must already be initialised. Sitting further down the
     * file, this was still null when it was read, and the app died on launch with
     * an NPE before the first frame. Same reasoning as [maEvents] below.
     */
    private val _radioMode = MutableStateFlow(false)
    val radioMode: StateFlow<Boolean> = _radioMode

    // ── Server-anchored position engine ──────────────────────────────────────
    // The bar is not snapped to whatever the last poll said; it is projected
    // forward from an anchor by [PlayerPositionTracker], a port of the official
    // Music Assistant app's tracker. See that file for why the shape matters —
    // in short, a seek or a skip freezes the anchor until audio is confirmed, and
    // server readings are only accepted when they are demonstrably fresher.
    private val positions = PlayerPositionTracker()

    /** Which queue the tracker is keyed on: the group leader, since members share it. */
    private fun positionKey(): String = streamQueueId()

    /**
     * True when this phone's own Sendspin stream is the best answer about *when a
     * change landed* — not about where the playhead is.
     *
     * Position comes from Music Assistant's `elapsed_time` /
     * `elapsed_time_last_updated` on both paths now, via [PlayerPositionTracker]. What
     * being the player still buys is the audible edge: our own decoder can say the
     * instant a new stream starts making a sound, which a poll cannot. So this gates
     * *who releases an optimistic freeze* — the [Playback.audibleSeq] collector when
     * we are the player, the poll's own corroboration when a speaker is.
     */
    private fun sendspinAuthoritative(): Boolean =
        !isLocal && sendspinPlaying.value && targetIsThisPhone()

    /**
     * Is the current target this phone's own Sendspin player?
     *
     * Not a plain id comparison since Music Assistant 2.10: the target is the
     * `universal_player` wrapper (`up…`) and [myPlayerId] is the protocol client it
     * renders through, so the two are never equal. Comparing them directly left
     * [sendspinAuthoritative] permanently false, which handed the playhead to MA's
     * five-second poll - and a poll that has not yet noticed a skip re-anchors the bar
     * on the outgoing track's elapsed time, then on zero, which is the seek bar
     * jumping forward and then back at the start of every track.
     */
    private fun targetIsThisPhone(): Boolean {
        val id = targetId()
        if (id == myPlayerId) return true
        return _players.value.firstOrNull { it.playerId == id }
            ?.isSelfOrActiveOutput(myPlayerId) == true
    }

    /** Identity of the current track, for detecting a change between polls. */
    @Volatile private var lastTrackId: String? = null

    private val _positionMs = MutableStateFlow(0L)

    /**
     * Where the scrubber sits. The local player knows its own position exactly, so
     * when it is the one playing there is nothing to project forward from a server
     * anchor — the anchored engine only applies to the MA-side player.
     */
    val positionMs: StateFlow<Long> =
        combine(localSnap, _positionMs, local.positionMs) { l, server, here ->
            if (l.active) here else server
        }.stateIn(viewModelScope, SharingStarted.Eagerly, 0L)

    private val _currentScan = MutableStateFlow<TrackScan?>(null)

    /**
     * The current track's offline scan, if it has one — for the Music Map timeline.
     *
     * [TrackScanRepository.peek] only answers from memory, and nothing keeps that
     * cache warm for the currently-playing track unless Light Sync is also on (it
     * warms it for its own reasons). So this calls the suspending [TrackScanRepository.cached]
     * itself, exactly as [com.engabd.sendpin.hue.DirectLightSync] already does for
     * its own lookup, rather than relying on that as a side effect.
     */
    val currentScan: StateFlow<TrackScan?> = _currentScan.asStateFlow()

    private var tickerJob: Job? = null

    /**
     * Republish the tracker's projection into [_positionMs] for [positionKey].
     *
     * Restarted whenever the key changes; the tracker's own [PlayerPositionTracker.observe]
     * is cold and stops ticking on its own when the queue is paused or frozen.
     */
    private fun followPosition(queueId: String) {
        tickerJob?.cancel()
        tickerJob = viewModelScope.launch {
            var lastEndPoll = 0L
            positions.observe(queueId).collect { ms ->
                _positionMs.value = ms
                // The projection has run out the track but no fresh anchor arrived —
                // the server has almost certainly moved on. Ask, rather than sitting
                // pinned at the duration until the next 5s poll. Rate-limited: the
                // ticker keeps emitting while pinned, and one poll per second is
                // plenty to catch a boundary.
                if (positions.isAtEnd(queueId)) {
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastEndPoll > 1_000L) { lastEndPoll = now; refresh() }
                }
            }
        }
    }

    /**
     * The player this screen is actually driving.
     *
     * [_target] is the user's choice, and blank means this phone. But this phone is
     * only a Music Assistant player once MA has seen it register, and on a first run —
     * or with the player disabled, or before the socket is up — it is not in the list
     * at all. Resolving blindly to [myPlayerId] pointed the whole screen at an id MA
     * had never heard of: the transport addressed nothing, and the only way out was to
     * open Speakers and pick something by hand before any music could start.
     *
     * So the stored choice is honoured when it names a player that exists, and
     * otherwise falls through — this phone if MA knows it, then the first *available*
     * player MA does know, then whatever is left. Availability first, because falling
     * back to a speaker that is itself offline just moves the dead end.
     *
     * Only the resolution changes; the stored preference is untouched. When this phone
     * finishes registering it satisfies the first branch again and takes back over,
     * without the user having to re-pick it.
     *
     * On Navidrome this cannot fire: that backend has no MA players and the local
     * session owns the screen.
     */
    private fun resolveTarget(players: List<MaPlayer>, target: String): String {
        val wanted = target.ifBlank { myPlayerId }
        if (players.isEmpty() || players.any { it.playerId == wanted }) return wanted
        if (players.any { it.playerId == myPlayerId }) return myPlayerId
        return players.firstOrNull { it.available }?.playerId
            ?: players.first().playerId
    }

    private fun targetId() = resolveTarget(_players.value, _target.value)

    private val maState: StateFlow<State> = combine(_players, _target, _queues, localQuality, _lastTrack) { players, target, queues, local, last ->
        val id = resolveTarget(players, target)
        val p = players.firstOrNull { it.playerId == id }
        val isSelf = id == myPlayerId
        // A synced member plays the leader's stream, so read quality off the leader's queue.
        val streamId = p?.syncedTo ?: id
        val queue = queues.firstOrNull { it.queueId == streamId }
        // The *target's* own dsp entry first: a synced member decodes on its own
        // hardware and MA can hand it a different format from the leader's.
        val outputQuality = queue?.outputFor(playerId = id, leaderId = streamId)

        val live = p?.nowPlaying?.takeIf { it.title.isNotBlank() }
        // Fall back to the last track so the screen keeps its shape when the
        // player goes quiet, rather than collapsing to an empty state.
        val np = live ?: last
        State(
            playerName = p?.name ?: "This phone",
            isSelf = isSelf,
            title = np?.title.orEmpty(),
            artist = np?.artist.orEmpty(),
            composer = queue?.currentItem?.composer.orEmpty(),
            album = np?.album.orEmpty(),
            artworkUrl = np?.imageUrl,
            isPlaying = live != null && p?.isPlaying == true,
            volume = ((p?.volumeLevel ?: 100) / 100f).coerceIn(0f, 1f),
            positionMs = if (live != null) live.elapsedMs ?: 0 else 0,
            durationMs = if (live != null) live.durationMs ?: 0 else 0,
            hasTrack = live != null,
            idle = live == null,
            blank = np == null,
            // `quality` is Playing: what this device is actually putting out, and the
            // same reading the Settings screen prints under "Format". When this phone
            // is the player that is the negotiated Sendspin stream and nothing else —
            // falling back to the queue's stream details here is what made the badge
            // disagree with Settings on the very same track. A remote speaker gives us
            // no output handle at all, so there the queue's details are all there is.
            quality = when {
                // A remote speaker decodes on its own hardware and Music Assistant is
                // the only thing that knows what it was handed. This phone's output
                // format says nothing about it — reporting `deviceQuality` here claimed
                // every speaker was playing whatever this phone would have played.
                !isSelf -> outputQuality
                // This phone is the player: the negotiated Sendspin stream *is* the
                // output, and it is the same reading Settings prints under "Format".
                local != null -> local
                // Nothing negotiated yet. Better to show nothing than to invent the
                // phone's output ceiling and have the badge disagree with Settings.
                else -> null
            },
            // `sourceQuality` is Source: the format before the per-player pipeline
            // touched it.
            //
            // `streamdetails.audio_format` comes first now — it is what MA actually
            // opened for *this* playback, and pairs with `outputQuality` off the same
            // streamdetails object, so "Transcoded from X to Y" compares two readings
            // of one stream rather than a stream against a catalogue entry. The media
            // item's `provider_mappings.audio_format` stays as the fallback for a
            // server that sends no stream details.
            sourceQuality = queue?.inputFormat
                ?: queue?.currentItem?.audioFormat?.let {
                    StreamQuality(
                        it.codec, it.sampleRate, it.bitDepth, it.bitRate,
                        replayGainTrack = it.replayGainTrack,
                        replayGainAlbum = it.replayGainAlbum,
                    )
                },
            // Music Assistant is what is playing, whatever shelf it took the file off.
            source = "MA",
            // …and that shelf, off `streamdetails.provider`, as detail. The media
            // item's own provider is always "library" — it says where MA *filed* the
            // track, not where it streamed it from.
            streamProvider = MaParse.streamProviderLabel(queue?.streamProvider),
            groupSize = run {
                // The selected player may be a follower; groupChilds only lives on the leader.
                val leader = _players.value.firstOrNull { it.playerId == (p?.syncedTo ?: p?.playerId) }
                1 + (leader?.groupChilds?.size ?: 0)
            },
            shuffle = queue?.shuffleEnabled == true,
            repeatMode = queue?.repeatMode ?: "off",
            queueSize = queue?.itemCount ?: 0,
            currentQueueItemId = queue?.currentQueueItemId,
            playbackSpeed = queue?.playbackSpeed ?: 1f,
            dontStopTheMusic = queue?.dontStopTheMusic == true,
            powered = p?.powered ?: true,
            canPower = p?.let { "power" in it.supportedFeatures } ?: false,
            radioMode = _radioMode.value,
            // Same entry [outputQuality] came from, read for whether the chain ran
            // rather than for what came out of it.
            dsp = queue?.dspFor(playerId = id, leaderId = streamId),
            loudness = queue?.loudness ?: MaLoudness(),
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, State())

    /**
     * What the screen shows: the local player when it has a session, the Music
     * Assistant view otherwise. There is no third state — the phone is either
     * playing something itself or reflecting a player MA owns.
     */
    val state: StateFlow<State> = combine(
        combine(maState, localSnap, deviceVolume.level, backendPref, _radioMode) { ma, l, devVol, backend, radio ->
            LocalInfo(ma, l, devVol, backend, radio)
        },
        activeServerName,
    ) { info, serverName ->
        val (ma, l, devVol, backend, radio) = info
        // Either the local player has a session, or the library is Navidrome and this
        // phone is the only player there is. The second case is the one that was
        // missing: with nothing playing yet it fell through to the MA view.
        if (!l.active && backend != "subsonic") return@combine ma
        val t = l.track
        State(
            playerName = "This phone",
            isSelf = true,
            title = t?.title.orEmpty(),
            artist = t?.artist.orEmpty(),
            composer = t?.composer.orEmpty(),
            album = t?.album.orEmpty(),
            artworkUrl = t?.artUrl,
            isPlaying = l.playing,
            // The device's own media volume — the same level the rocker moves, and
            // the only volume that means anything for a stream this phone decodes
            // itself. It used to show MA's per-player level, which belongs to a
            // different player entirely: the number was wrong and dragging it did
            // nothing.
            volume = devVol,
            positionMs = 0,
            durationMs = l.durationMs,
            hasTrack = t != null,
            idle = t == null,
            blank = t == null,
            // Playing is what this phone is being fed and decoding.
            //
            // It used to be `deviceQuality`, which is hard-coded "PCM" — the
            // AudioTrack's own encoding — so the badge read PCM no matter what
            // Navidrome had been asked for, and choosing a format looked like it did
            // nothing. A downloaded file plays from disk untouched; a requested
            // transcode is what arrives; otherwise it is the library file itself, and
            // only when the library said nothing at all do we fall back to describing
            // the output.
            quality = when {
                t == null -> null
                t.offline -> t.sourceQuality ?: deviceQuality
                else -> navFormat?.let { transcodeQuality(it) } ?: t.sourceQuality ?: deviceQuality
            },
            // Source stays the library file, so a transcode shows as one.
            sourceQuality = t?.sourceQuality,
            // The name of the library server this phone is playing from — Navidrome,
            // Jellyfin, "This device", etc. — rather than a hard-coded "Navidrome"
            // that was wrong the moment a second provider arrived.
            source = if (t?.offline == true) "Offline" else serverName,
            groupSize = 1,
            shuffle = l.shuffle,
            repeatMode = l.repeat,
            queueSize = l.queueSize,
            currentQueueItemId = localQueueItemId(l.index, t?.id),
            playbackSpeed = l.speed,
            dontStopTheMusic = false,
            powered = true,
            canPower = false,
            isLocalSession = true,
            // The local path's own answer to "don't stop the music": there is no server
            // to ask, so the app tops the queue up itself (see LibraryViewModel's
            // local radio). The switch for it lives in the player options card, which
            // is why the flag has to reach this side of the state too.
            radioMode = radio,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, State())

    /**
     * Identity of the queue's current track.
     *
     * `queue_item_id` is the real answer, but a server that omits it would make track
     * changes structurally undetectable — and the old engine's `trackId != null` guard
     * turned that into "the bar never resets". Title/artist/duration is a coarse but
     * always-available fallback.
     */
    private fun currentTrackId(): String? {
        val q = _queues.value.firstOrNull { it.queueId == streamQueueId() }
        q?.currentQueueItemId?.let { return it }
        q?.currentItem?.uri?.let { return it }
        val np = _players.value.firstOrNull { it.playerId == targetId() }?.nowPlaying ?: return null
        if (np.title.isBlank()) return null
        return "${np.title}|${np.artist}|${np.durationMs}"
    }

    // ── Optimistic freeze bookkeeping ────────────────────────────────────────
    // A freeze makes the tracker ignore server readings, which is exactly what we
    // want right after a seek or a skip — and exactly what must not wedge. Every
    // freeze is therefore paired with a watchdog that force-confirms, so a server
    // that never catches up costs a stuck second, not a stuck bar.
    private var freezeWatchdog: Job? = null
    @Volatile private var pendingSeekMs: Long? = null
    @Volatile private var pendingSkipFromTrack: String? = null

    /**
     * [Playback.audibleSeq] as it was when the current optimistic freeze was armed.
     *
     * The freeze may only be released by a stream that became audible *after* it. A
     * level check cannot express that: when a skip is asked for, the outgoing track is
     * still playing and stays audible for over a second, so "audio is flowing" is
     * already true and released the freeze immediately - letting the old track's
     * playhead drive the bar under the new track's title.
     */
    @Volatile private var freezeAtAudibleSeq: Long = -1L

    private fun freezeForSeek(target: Long) {
        val key = positionKey()
        pendingSeekMs = target
        pendingSkipFromTrack = null
        freezeAtAudibleSeq = playback.audibleSeq.value
        positions.setOptimisticSeek(key, target, state.value.durationMs.takeIf { it > 0 })
        armFreezeWatchdog(key)
    }

    private fun freezeForTrackChange() {
        val key = positionKey()
        pendingSeekMs = null
        pendingSkipFromTrack = lastTrackId
        freezeAtAudibleSeq = playback.audibleSeq.value
        positions.setOptimisticTrackChange(key)
        armFreezeWatchdog(key)
    }

    private fun armFreezeWatchdog(key: String) {
        freezeWatchdog?.cancel()
        freezeWatchdog = viewModelScope.launch {
            delay(FREEZE_TIMEOUT_MS)
            releaseFreeze(key)
        }
    }

    private fun releaseFreeze(key: String) {
        pendingSeekMs = null
        pendingSkipFromTrack = null
        freezeAtAudibleSeq = -1L
        freezeWatchdog?.cancel(); freezeWatchdog = null
        positions.confirmPlaying(key)
    }

    // Drive the position engine off the raw player/queue state rather than the
    // derived [state], because the staleness stamp doesn't survive that projection.

    private fun anchorFromServer(players: List<MaPlayer>, queues: List<MaQueue>) {
        val id = targetId()
        val p = players.firstOrNull { it.playerId == id }
        val key = p?.syncedTo ?: id
        val q = queues.firstOrNull { it.queueId == key }

        val live = p?.nowPlaying?.takeIf { it.title.isNotBlank() }
        val isPlaying = live != null && p.isPlaying
        val duration = live?.durationMs ?: 0L
        // The queue's own playhead is the better reading — the player object can lag
        // it — but not every server fills it in. Nullable, and deliberately not
        // defaulted to zero: MA sends `"title": null` metadata around a queue restart,
        // and a poll carrying no reading at all used to anchor the bar at 0:00 and drop
        // it to the start of the track. Absent is not "at the beginning".
        val elapsed = q?.elapsedMs ?: live?.elapsedMs

        val trackId = currentTrackId()
        val trackChanged = trackId != null && trackId != lastTrackId
        if (trackId != null) lastTrackId = trackId

        // Release an optimistic freeze once the server corroborates it.
        if (positions.isFrozen(key)) {
            val seekTarget = pendingSeekMs
            val skipFrom = pendingSkipFromTrack
            val confirmed = when {
                // A skip is confirmed by the server naming a different track.
                skipFrom != null -> trackId != null && trackId != skipFrom
                // A seek is confirmed when the server's clock lands near the target.
                // Deliberately not gated on isPlaying: a seek while paused stays
                // paused (see [seekOnServer]) and still has to release.
                // A poll with no reading cannot corroborate anything; the watchdog
                // still bounds the freeze.
                seekTarget != null ->
                    elapsed != null && kotlin.math.abs(elapsed - seekTarget) < SEEK_CONFIRM_MS
                else -> true
            }
            if (!confirmed) return
            // When this phone is the player, the server naming the new track is not
            // enough to lift the freeze: our own stream is still warming up for over a
            // second after that poll, and releasing here hands the bar straight back to
            // the *outgoing* track's playhead, under the new track's title. The
            // [Playback.audibleSeq] collector releases it instead, on the edge where
            // the new stream is first heard.
            if (sendspinAuthoritative()) return
            releaseFreeze(key)
            // [releaseFreeze] already snapped the anchor to exactly the confirmed
            // target via [PlayerPositionTracker.confirmPlaying]. Falling through to
            // the raw-elapsed [setAnchor] below on this same poll would immediately
            // overwrite that with the poll's own reading — which is only guaranteed
            // to be within [SEEK_CONFIRM_MS] of the target, not equal to it — and the
            // bar would visibly jump by up to that much right as the freeze lifted.
            // The next poll re-anchors normally; this one stops here.
            return
        }

        if (elapsed == null) return

        val speed = q?.playbackSpeed ?: 1f

        // The server's own capture timestamp (`elapsed_time_last_updated`, a Unix
        // epoch in seconds) as local wall-clock ms, handed over raw: the tracker
        // decides whether it is fresh enough to project from, and null means the
        // server said nothing. Only taken when `elapsed` came from the queue too —
        // the stamp describes the queue's reading, so pairing it with the player's
        // fallback would anchor one number on another's timestamp.
        val capturedAtMs = q?.elapsedTimeLastUpdated
            ?.takeIf { q.elapsedMs != null }
            ?.let { (it * 1000).toLong() }

        // A track change is news no matter what the clock says: anchor at zero.
        if (trackChanged) {
            positions.setAnchor(
                key, 0L,
                capturedAtMs = null,
                isPlaying = isPlaying, durationMs = duration, speed = speed,
            )
            return
        }

        positions.setAnchor(
            key, elapsed,
            capturedAtMs = capturedAtMs,
            isPlaying = isPlaying, durationMs = duration, speed = speed,
        )
    }

    /**
     * The library item behind what's playing, taken from the queue's `current_item`.
     * Everything track-specific — the heart, lyrics, "more like this" — needs a real
     * `item_id` and provider, which the player's `current_media` doesn't carry.
     */
    val currentItem: StateFlow<MaItem?> = combine(_queues, _players, _target, localSnap) { queues, players, target, l ->
        // Nothing MA-side is playing while the local player holds the session, so
        // the MA-scoped controls (lyrics, similar, DSP) have no handle to act on and
        // correctly show as unavailable. The *heart* is not one of them — see
        // [localFavouriteItem], which is what it reads instead.
        if (l.active) return@combine null
        val id = resolveTarget(players, target)
        val streamId = players.firstOrNull { it.playerId == id }?.syncedTo ?: id
        queues.firstOrNull { it.queueId == streamId }?.currentItem
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * What the local player is playing, as something favouritable.
     *
     * The heart on Now Playing was greyed out for the whole of a Navidrome or
     * Jellyfin session, and reported as "Jellyfin has no favourite button for tracks
     * or albums". It was not a Jellyfin gap — `JellyfinSource` has declared
     * `Capability.FAVORITES` and implemented `setStarred` since it was written, and
     * the album screen's hearts have always worked. It was this screen: the chip is
     * disabled when [currentItem] is null, and [currentItem] is deliberately null for
     * the entire local session because it is sourced from the *Music Assistant*
     * queue.
     *
     * A `LocalTrack` carries the library id and the provider it came from
     * ([com.engabd.sendpin.audio.LocalTrack.scrobbleId] and its `scrobbleProvider`),
     * which is exactly what `setStarred` needs, so the handle was there all along.
     * Null when the track has no library behind it — a bare stream URL has nothing to
     * star on any server.
     */
    private val localFavouriteItem: StateFlow<MaItem?> =
        combine(localSnap, local.current) { l, track ->
            if (!l.active || track == null) return@combine null
            val id = track.scrobbleId ?: return@combine null
            val provider = track.scrobbleProvider ?: return@combine null
            MaItem(
                itemId = id,
                provider = provider,
                name = track.title,
                // No uri: that is Music Assistant's identifier for an item, and this
                // one came from a self-hosted library. `setStarred` takes the id and
                // the provider, which is everything it needs.
                uri = null,
                mediaType = "track",
                subtitle = track.artist,
                image = track.artUrl,
                duration = (track.durationMs / 1000L).toInt().takeIf { it > 0 },
            )
        }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Whichever of the two is actually favouritable right now. */
    val favouritableItem: StateFlow<MaItem?> =
        combine(currentItem, localFavouriteItem) { ma, localItem -> ma ?: localItem }
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** Un-acknowledged favourite flip: item id → wanted state. */
    private val _favoriteOverride = MutableStateFlow<Pair<String, Boolean>?>(null)

    val favorite: StateFlow<Boolean> = combine(favouritableItem, _favoriteOverride) { item, override ->
        if (item == null) false
        else if (override != null && override.first == item.itemId) override.second
        // A local track's `favorite` is not known from the player — a LocalTrack
        // carries no starred flag — so the optimistic override is the only state
        // there is until the next library read. That is honest: it shows what this
        // session did rather than claiming to know what the server holds.
        else item.favorite
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // --- panels (loaded on demand) ----------------------------------------

    private val _queueItems = MutableStateFlow<Load<List<MaQueueItem>>>(Load.Idle)
    val queueItems: StateFlow<Load<List<MaQueueItem>>> = _queueItems

    /**
     * How much is left in the queue — the current track's remaining time, plus
     * everything after it — for "1h 23m left in queue" in the driving bar.
     *
     * Null while there's nothing to sum: no track loaded, or — on the MA path —
     * the queue's items haven't been fetched yet. [queueItems] otherwise only
     * loads on demand when the panel opens; [onStart] below triggers that same
     * silent fetch the first time anything actually collects this flow, so the
     * driving bar doesn't have to know that detail to get a real number.
     */
    val queueRemainingMs: StateFlow<Long?> =
        combine(state, _queueItems, local.queue, local.current) { s, items, localQueue, localCurrent ->
            val remainingCurrent = (s.durationMs - s.positionMs).coerceAtLeast(0)
            if (isLocal) {
                val idx = localQueue.indexOfFirst { it.id == localCurrent?.id }
                if (idx < 0) return@combine null
                remainingCurrent + localQueue.drop(idx + 1).sumOf { it.durationMs }
            } else {
                val ready = items as? Load.Ready ?: return@combine null
                val idx = ready.value.indexOfFirst { it.queueItemId == s.currentQueueItemId }
                if (idx < 0) return@combine null
                remainingCurrent + ready.value.drop(idx + 1).sumOf { (it.duration ?: 0) * 1_000L }
            }
        }
            .onStart { if (!isLocal && _queueItems.value is Load.Idle) loadQueue(silent = true) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _lyrics = MutableStateFlow<Load<MaLyrics?>>(Load.Idle)
    val lyrics: StateFlow<Load<MaLyrics?>> = _lyrics

    /**
     * What is playing, whichever engine is playing it — the identity the track-scoped
     * panels reload on.
     *
     * [currentItem] cannot serve: it is deliberately null for the whole of a local
     * session (`if (l.active) return@combine null`), because the MA-scoped controls
     * have no handle to act on there. A panel keyed on it therefore never re-keyed on
     * Navidrome, Jellyfin or offline — which is exactly how the lyrics pane came to
     * sit on a spinner for every track after the first.
     */
    private val lyricsKey: StateFlow<String?> = combine(currentItem, localSnap) { item, l ->
        if (l.active) l.track?.let { it.id.ifBlank { it.streamUrl ?: it.title } } else item?.itemId
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /**
     * Whether the lyrics pane is on screen.
     *
     * The pane used to fetch for itself from a `LaunchedEffect` guarded on the state
     * being [Load.Idle], which made the load depend on whether composition ran before
     * or after the view model reset that state on a track change. It is a race, and
     * losing it means no fetch is ever issued and the spinner stays up. Told when the
     * pane opens and closes, the view model can drive the load off the track identity
     * alone — no ordering to get right.
     */
    private val _lyricsOpen = MutableStateFlow(false)

    /**
     * Counted rather than set, because there are two players — the full screen and the
     * overlay — and a moment where both are composed would otherwise have one pane's
     * disposal report the other's open pane as closed. Main-thread only, which is where
     * composition effects run.
     */
    private var openLyricsPanes = 0

    fun setLyricsOpen(open: Boolean) {
        openLyricsPanes = (openLyricsPanes + if (open) 1 else -1).coerceAtLeast(0)
        _lyricsOpen.value = openLyricsPanes > 0
    }

    /**
     * The listener's manual trim on synced lyrics, in milliseconds.
     *
     * Read here rather than in the pane so the pane stays a renderer and the setting
     * has one reader.
     */
    val lyricsOffsetMs: StateFlow<Int> = settings.lyricsOffsetMs
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    private val _similar = MutableStateFlow<Load<List<MaSimilarTrack>>>(Load.Idle)
    val similar: StateFlow<Load<List<MaSimilarTrack>>> = _similar

    private val _toast = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val toast: SharedFlow<String> = _toast.asSharedFlow()

    val connected: StateFlow<Boolean> = api.state
        .map { it == MaApiClient.State.CONNECTED }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * MA's pushed events, parsed by name. Shared so the collectors in [init] each see
     * every event rather than competing over one cold flow.
     *
     * Declared before [init] deliberately — property initialisers run in source order.
     */
    private val maEvents = api.events
        .mapNotNull { MaParse.event(it) }
        .shareIn(viewModelScope, SharingStarted.Eagerly)


    /**
     * Ask for a fresh read of players and queues.
     *
     * Delegates rather than fetching: [maNowPlaying] owns the only copy of these two
     * commands in the process, and the results arrive back through the collectors in
     * the init block below. This used to issue its own `players/all` +
     * `player_queues/all` pair, which — with the Speakers screen doing the same — meant
     * three identical requests every five seconds against one server, none of them
     * sharing a cache.
     *
     * The serialisation this function used to do itself moved with the fetch:
     * `MaNowPlaying.refresh` coalesces overlapping requests into one round trip plus a
     * repeat pass, so a slow earlier read can still not overwrite a newer one, and an
     * empty result is still refused while the socket is down (a dropped request
     * completes as `null`, which parses to an empty list, and adopting that would blank
     * the screen mid-track).
     */
    private fun refresh() {
        maNowPlaying.refreshNow()
    }

    // --- transport (act on the selected player) ---------------------------

    fun playPause() {
        if (isLocal) { local.toggle(); return }
        act { if (state.value.isPlaying) repo.pause(targetId()) else repo.play(targetId()) }
    }

    /**
     * Stop playback entirely — not just pause, but stop and clear the session.
     * Works for both the local player (Navidrome/offline) and the MA player.
     */
    fun stop() {
        if (isLocal) { local.stop(); return }
        act { repo.stop(targetId()) }
    }

    // In-flight guards prevent button-mash spam: rapid taps fire only one MA
    // command, the rest are dropped. A short cooldown after the command lands
    // prevents overlapping refresh coroutines.
    private val nextInFlight = java.util.concurrent.atomic.AtomicBoolean(false)
    private val prevInFlight = java.util.concurrent.atomic.AtomicBoolean(false)

    fun next() {
        if (isLocal) { local.next(); return }
        if (!nextInFlight.compareAndSet(false, true)) return
        // Optimistic, and *held*: MA keeps reporting the outgoing track's position
        // for a beat after the skip, so simply zeroing the bar would let the next
        // poll drag it straight back. The freeze holds zero until the server names
        // a different track.
        freezeForTrackChange()
        viewModelScope.launch {
            try { repo.next(targetId()) } catch (_: Exception) {}
            finally {
                delay(250)   // small cooldown for server to settle
                nextInFlight.set(false)
            }
        }
    }

    fun previous() {
        if (isLocal) { local.previous(); return }
        if (!prevInFlight.compareAndSet(false, true)) return
        freezeForTrackChange()
        viewModelScope.launch {
            try { repo.previous(targetId()) } catch (_: Exception) {}
            finally {
                delay(400)   // 400ms cooldown - matches massdroid_native's prev cooldown
                prevInFlight.set(false)
            }
        }
    }

    fun toggleShuffle() {
        if (isLocal) { local.setShuffle(!state.value.shuffle); return }
        act { repo.setShuffle(streamQueueId(), !state.value.shuffle) }
    }

    /** Cycles the way players conventionally do: off → all → one → off. */
    fun cycleRepeat() {
        if (isLocal) { local.cycleRepeat(); return }
        act {
            val next = when (state.value.repeatMode) {
                "off" -> "all"
                "all" -> "one"
                else -> "off"
            }
            repo.setRepeat(streamQueueId(), next)
        }
    }

    /** Queue commands go to the group leader, since members share its queue. */
    /**
     * The queue behind the current target, resolved locally from `synced_to`.
     *
     * A synchronous best guess, because this is read on every state emission. The
     * server's own answer (`player_queues/get_active_queue`) is authoritative and is
     * what [MaRepository.playOn] uses for anything that *changes* the queue; this is
     * only for reading and for keying the position tracker, where being one poll
     * behind on a group change costs nothing.
     */
    private fun streamQueueId(): String {
        val id = targetId()
        return _players.value.firstOrNull { it.playerId == id }?.syncedTo ?: id
    }
    fun seekTo(fraction: Float) {
        if (isLocal) {
            val dur = state.value.durationMs
            if (dur > 0) local.seekTo((fraction.coerceIn(0f, 1f) * dur).toLong())
            return
        }
        seekOnServer(fraction)
    }

    /**
     * Seek the MA player.
     *
     * Two server behaviours have to be worked around here:
     *
     *  - `players/cmd/seek` resolves into `play_index(..., seek_position=)`, which
     *    **starts** the queue. Seeking while paused therefore begins playback, which
     *    is not what dropping the scrubber means. Re-pause afterwards when that's the
     *    state the user was in.
     *  - The seek takes a moment to land, and until it does MA reports the *old*
     *    position. [freezeForSeek] holds the bar at the target so it doesn't snap
     *    back before jumping forward again.
     */
    private fun seekOnServer(fraction: Float) = act {
        val dur = state.value.durationMs
        if (dur <= 0) return@act
        // Clamp to [0, duration - 1s] so MA doesn't interpret a seek-to-end
        // as "skip to next track". 1 second of headroom is enough.
        val maxFraction = ((dur - 1000L).coerceAtLeast(0L).toFloat() / dur)
        val clamped = fraction.coerceIn(0f, maxFraction)
        val targetMs = (clamped * dur).toLong()
        val wasPlaying = state.value.isPlaying

        freezeForSeek(targetMs)
        repo.seek(targetId(), (targetMs / 1000).toInt())
        if (!wasPlaying) {
            // The seek restarted it. Put it back where it was — after a beat, or the
            // pause races the play the seek just issued.
            delay(250)
            runCatching { repo.pause(targetId()) }
        }
    }

    private var volJob: kotlinx.coroutines.Job? = null
    fun setVolume(level01: Float) {
        // Locally, the slider *is* the phone's media volume — not an attenuation
        // inside the player. ExoPlayer's own volume is left alone because ReplayGain
        // rides on it, and mixing the two would make a quiet album read as a
        // turned-down phone.
        if (isLocal) { deviceVolume.set(level01.coerceIn(0f, 1f)); return }
        val lvl = (level01 * 100).toInt().coerceIn(0, 100)
        // When *this phone* is the Music Assistant player, its player volume is the
        // phone's media volume — see `Playback.applyUserVolume`. Setting it here as
        // well as sending the RPC means the rocker's level and the slider agree the
        // instant the finger lifts, rather than after the server echoes the change
        // back down the socket.
        if (targetId() == myPlayerId) deviceVolume.set(level01.coerceIn(0f, 1f))
        _players.update { list -> list.map { if (it.playerId == targetId()) it.copy(volumeLevel = lvl) else it } }
        volJob?.cancel()
        volJob = viewModelScope.launch { delay(180); try { repo.setVolume(targetId(), lvl) } catch (_: Exception) {} }
    }

    // --- player + queue options -------------------------------------------

    fun setPower(on: Boolean) = act(toastOnError = "Couldn't switch the player") {
        repo.setPower(targetId(), on)
    }

    private var speedJob: kotlinx.coroutines.Job? = null
    fun setPlaybackSpeed(speed: Float) {
        if (isLocal) { local.setSpeed(speed); return }
        val q = streamQueueId()
        _queues.update { list -> list.map { if (it.queueId == q) it.copy(playbackSpeed = speed) else it } }
        speedJob?.cancel()
        speedJob = viewModelScope.launch {
            delay(200)
            try { repo.setPlaybackSpeed(q, speed) } catch (e: Exception) { _toast.tryEmit(e.message ?: "Couldn't set speed") }
        }
    }

    fun toggleDontStopTheMusic() {
        if (isLocal) { _toast.tryEmit("Don't stop the music needs Music Assistant"); return }
        val q = streamQueueId()
        val next = !state.value.dontStopTheMusic
        _queues.update { list -> list.map { if (it.queueId == q) it.copy(dontStopTheMusic = next) else it } }
        act(toastOnError = "Couldn't change Don't stop the music") { repo.setDontStopTheMusic(q, next) }
    }

    // --- radio mode ---------------------------------------------------------

    /**
     * One setting, two machines behind it.
     *
     * On Music Assistant it is `radio_mode` on `play_media`, a parameter of the *next*
     * thing you play rather than of the queue already running — the toast says so,
     * because a switch that quietly does nothing to what you are hearing is worse than
     * no switch. On the local path there is no server to ask: the app watches its own
     * queue and appends more like this before the last track ends, which it can do to
     * the queue that is already going.
     */
    fun toggleRadioMode() {
        val local = isLocal
        val next = !_radioMode.value
        viewModelScope.launch {
            settings.setRadioMode(next)
            _toast.tryEmit(
                when {
                    !next -> if (local) "The music will stop at the end of the queue" else "Radio mode off"
                    local -> "Keeping the music going - more like this when the queue runs out"
                    else -> "Radio mode on - applies to the next thing you play"
                }
            )
        }
    }

    // --- stream format switching (Sendspin) ---------------------------------

    /**
     * Ask the server to switch codec mid-stream — no reconnect. Only offered
     * where [Playback.canSwitchFormatLive] holds; see it for why.
     */
    fun requestFormat(codec: String) {
        playback.requestFormat(codec)
        _toast.tryEmit("Switching to ${codec.uppercase()}")
    }

    // --- favourite ---------------------------------------------------------

    fun toggleFavorite() {
        val item = favouritableItem.value ?: return
        val wanted = !favorite.value
        _favoriteOverride.value = item.itemId to wanted
        viewModelScope.launch {
            try {
                // Routed by which library the item belongs to, not by which one the
                // app is nominally browsing: what is playing is what the heart is
                // about. The self-hosted path goes through `MusicSource.setStarred`,
                // which Navidrome and Jellyfin both implement — this screen simply
                // never called it, which is the whole of "Jellyfin has no favourite
                // button".
                val sc = SendpinApp.instance.musicSource.value
                    ?.takeIf { it.providerId == item.provider }
                when {
                    MusicSources.isLocalProvider(item.provider) && sc != null ->
                        sc.setStarred(item, wanted)
                    MusicSources.isLocalProvider(item.provider) ->
                        throw IllegalStateException("That library isn't connected")
                    wanted -> repo.addFavorite(item)
                    else -> repo.removeFavorite(item)
                }
                _toast.tryEmit(if (wanted) "Added to favourites" else "Removed from favourites")
                // Let MA write it through before we trust `favorite` off the queue
                // again. The local path has nothing to re-read from — a LocalTrack
                // carries no starred flag — so its override is simply held.
                if (!MusicSources.isLocalProvider(item.provider)) {
                    delay(1_200); refresh(); delay(1_500)
                    _favoriteOverride.value = null
                }
            } catch (e: Exception) {
                _favoriteOverride.value = null
                _toast.tryEmit(e.message ?: "Couldn't change favourite")
            }
        }
    }

    // --- long-press quick actions --------------------------------------------

    /**
     * Resolve the currently playing track's album, for the long-press "Go to
     * album" action.
     *
     * Subsonic-family tracks carry the album id directly in [MaItem.parentId] —
     * exact. A Music Assistant track doesn't (MA addresses everything by uri), so
     * that path falls back to searching [albumName]: a heuristic, not an id
     * lookup, but there is no richer one exposed anywhere in this codebase to fall
     * back to instead. Null (with a toast) when neither resolves.
     */
    suspend fun resolveAlbum(albumName: String): MaItem? {
        val item = favouritableItem.value
        val result = try {
            val parentId = item?.parentId
            val sc = item?.let { i -> SendpinApp.instance.musicSource.value?.takeIf { it.providerId == i.provider } }
            when {
                parentId != null && sc != null -> sc.albumDetail(parentId).first
                albumName.isNotBlank() -> {
                    val albums = repo.search(albumName, limit = 15).albums
                    albums.firstOrNull { it.name.equals(albumName, ignoreCase = true) } ?: albums.firstOrNull()
                }
                else -> null
            }
        } catch (e: Exception) { null }
        if (result == null) _toast.tryEmit("Couldn't find that album")
        return result
    }

    /**
     * Resolve the currently playing track's artist, for "Go to artist". Same
     * shape as [resolveAlbum]: the Subsonic id chain (track → album → artist) is
     * exact where it's available; [artistName] search is the fallback for
     * Music Assistant and for anything the id chain couldn't resolve.
     */
    suspend fun resolveArtist(artistName: String): MaItem? {
        val item = favouritableItem.value
        val result = try {
            val sc = item?.let { i -> SendpinApp.instance.musicSource.value?.takeIf { it.providerId == i.provider } }
            val albumParentId = item?.parentId
            val viaId = if (albumParentId != null && sc != null) {
                sc.albumDetail(albumParentId).first?.parentId?.let { sc.artistDetail(it).first }
            } else null
            viaId ?: artistName.takeIf { it.isNotBlank() }?.let { name ->
                val artists = repo.search(name, limit = 15).artists
                artists.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: artists.firstOrNull()
            }
        } catch (e: Exception) { null }
        if (result == null) _toast.tryEmit("Couldn't find that artist")
        return result
    }

    // --- queue -------------------------------------------------------------

    /**
     * Fetch the queue's items.
     *
     * [silent] keeps whatever is already on screen while the new list is in flight.
     * Dropping to [Load.Loading] tore the list out from under the user on every
     * refresh — the panel flashed a spinner, lost its scroll position and came back
     * re-collapsed, which is what made playing a track from the queue feel like it
     * scrambled the list. There is nothing to show a spinner *for* when the list is
     * already there and only its ordering might have moved.
     */
    fun loadQueue(silent: Boolean = false) {
        // The local queue is already in memory — there is nothing to fetch, and a
        // spinner in front of a list we are holding would be theatre.
        if (isLocal) { _queueItems.value = Load.Ready(localQueueItems()); return }
        val q = streamQueueId()
        if (!silent || _queueItems.value !is Load.Ready) _queueItems.value = Load.Loading
        viewModelScope.launch {
            val next = try {
                Load.Ready(repo.allQueueItems(q))
            } catch (e: Exception) {
                Load.Failed(e.message ?: "Couldn't load the queue")
            }
            // A silent refresh that failed leaves the good list up rather than
            // replacing it with an error for a blip we can retry on the next poll.
            if (silent && next is Load.Failed && _queueItems.value is Load.Ready) return@launch
            _queueItems.value = next
        }
    }

    /**
     * Jump to the tapped queue row. The queue's *contents* don't change, only which
     * row is current — which the ordinary player poll already reports — so this
     * deliberately does not re-fetch the items.
     *
     * Addressed by **queue item id**, not by position. `player_queues/items` is a
     * paged read and its array order is not promised to be the play order MA counts
     * positions in, so a row's place in the list is not a reliable index — sending
     * one played the wrong track, or restarted the queue from the top. MA's
     * `play_index` takes `int | str` and resolves a string as a queue_item_id, which
     * cannot be off by one. The local player is matched by the same id.
     */
    fun playQueueItem(item: MaQueueItem) {
        if (isLocal) {
            val at = localQueueItems().indexOfFirst { it.queueItemId == item.queueItemId }
            local.playAt(if (at >= 0) at else item.index)
            return
        }
        val q = streamQueueId()
        freezeForTrackChange()
        viewModelScope.launch {
            try {
                repo.playQueueItem(q, item.queueItemId)
            } catch (e: Exception) {
                // A server that types `index` as an int rather than `int | str`
                // rejects the id. Fall back to the position rather than doing
                // nothing at all.
                try {
                    repo.playQueueIndex(q, item.index)
                } catch (_: Exception) {
                    _toast.tryEmit(e.message ?: "Couldn't play that")
                    return@launch
                }
            }
            delay(350); refresh()
        }
    }

    fun removeQueueItem(item: MaQueueItem) {
        if (isLocal) { local.removeAt(item.index); loadQueue(); return }
        queueAction { repo.deleteQueueItem(it, item.queueItemId) }
    }

    /** [shift] is relative: -1 moves the item one place earlier, +1 one later. */
    fun moveQueueItem(item: MaQueueItem, shift: Int) {
        if (isLocal) { local.move(item.index, shift); loadQueue(); return }
        queueAction { repo.moveQueueItem(it, item.queueItemId, shift) }
    }

    fun clearQueue() {
        if (isLocal) { local.clear(); loadQueue(); return }
        queueAction { repo.clearQueue(it) }
    }

    /**
     * Shuffle the tracks still to come, in place — the queue sheet action, not the
     * transport shuffle beside the play button.
     *
     * The two are genuinely different and both are wanted. [toggleShuffle] is a
     * *mode*: it leaves the list alone and changes the order it is read in. This
     * rewrites the order itself, once, and leaves the mode where it was.
     *
     * Music Assistant has no "shuffle now" command. What it does have is a
     * `player_queues/shuffle` that re-rolls the order whenever it is switched on, so
     * an off/on cycle is the server-side equivalent — and a great deal cheaper than
     * the alternative, which is one `move_item` round-trip per track.
     */
    fun shuffleQueueNow() {
        if (isLocal) { local.shuffleQueue(); loadQueue(silent = true); return }
        val q = streamQueueId()
        viewModelScope.launch {
            try {
                if (state.value.shuffle) repo.setShuffle(q, false)
                repo.setShuffle(q, true)
            } catch (e: Exception) {
                _toast.tryEmit(e.message ?: "Couldn't shuffle the queue")
                return@launch
            }
            loadQueue(silent = true)
        }
    }

    /** The local queue as queue rows, so one panel renders either session. */
    private fun localQueueItems(): List<MaQueueItem> =
        local.queue.value.mapIndexed { i, t ->
            MaQueueItem(
                queueItemId = localQueueItemId(i, t.id) ?: "$i",
                name = t.title,
                duration = (t.durationMs / 1000).toInt().takeIf { it > 0 },
                sortIndex = i,
                mediaItem = null,
                streamDetails = null,
                index = i,
                artist = t.artist,
                image = t.artUrl,
            )
        }

    /**
     * A stable row key. The track id alone isn't enough — the same track can sit in
     * the queue twice, and two rows sharing a key breaks the drag-reorder list.
     */
    private fun localQueueItemId(index: Int, id: String?): String? = id?.let { "$index-$it" }

    fun saveQueueAsPlaylist(name: String) {
        if (isLocal) { _toast.tryEmit("Saving a playlist needs Music Assistant"); return }
        if (name.isBlank()) return
        val q = streamQueueId()
        viewModelScope.launch {
            try {
                repo.saveQueueAsPlaylist(q, name.trim())
                _toast.tryEmit("Saved \"${name.trim()}\"")
            } catch (e: Exception) {
                _toast.tryEmit(e.message ?: "Couldn't save the playlist")
            }
        }
    }

    private inline fun queueAction(crossinline block: suspend (String) -> Unit) {
        val q = streamQueueId()
        viewModelScope.launch {
            try {
                block(q)
            } catch (e: Exception) {
                _toast.tryEmit(e.message ?: "Queue command failed")
                return@launch
            }
            // MA rebuilds the queue asynchronously, so re-read rather than guess —
            // in place, so the panel doesn't blink out and back. Read twice: the
            // first can land before the server has finished applying a move, and a
            // stale read would visibly undo the row the user just dragged. The
            // second is free when nothing changed, since an equal list is not
            // re-emitted.
            delay(350); refresh(); loadQueue(silent = true)
            delay(900); loadQueue(silent = true)
        }
    }

    // --- lyrics ------------------------------------------------------------

    fun loadLyrics() {
        // When the local player is active (Navidrome/offline), try Subsonic lyrics.
        if (isLocal) {
            val track = local.current.value
            if (track == null) { _lyrics.value = Load.Failed("Nothing playing"); return }
            _lyrics.value = Load.Loading
            viewModelScope.launch {
                _lyrics.value = try {
                    // Asked of the *library this track came from*, not of Navidrome by
                    // name. This built its own Subsonic client, so on Jellyfin it
                    // either found nothing or — with both servers configured — sent a
                    // Jellyfin guid to Navidrome and asked why it had no lyrics.
                    val id = track.scrobbleId ?: track.id
                    val lyrics: MaLyrics? = lyricsSource(track)?.lyrics(id)
                        // The play-original path plays a Navidrome file while Music
                        // Assistant is the library, so there is no live source to ask
                        // — but the lyrics are there, and this is where they come from.
                        ?: subsonicClient()?.takeIf { track.scrobbleProvider == SubsonicClient.PROVIDER }
                            ?.getLyrics(id)
                    if (lyrics != null) Load.Ready(lyrics)
                    else Load.Failed("No lyrics found")
                } catch (e: Exception) {
                    Load.Failed(e.message ?: "Couldn't fetch lyrics")
                }
            }
            return
        }
        val item = currentItem.value ?: run {
            _lyrics.value = Load.Failed("Nothing playing")
            return
        }
        _lyrics.value = Load.Loading
        viewModelScope.launch {
            _lyrics.value = try {
                Load.Ready(repo.getLyrics(item))
            } catch (e: Exception) {
                Load.Failed(e.message ?: "Couldn't fetch lyrics")
            }
        }
    }

    // --- sonic similarity ---------------------------------------------------

    /** Acoustically similar tracks to what's playing. */
    /**
     * Every copy of this track Music Assistant can see, across every provider.
     *
     * `music/tracks/track_versions` has been in [MaRepository] with no callers. It is
     * the command that speaks to why anyone runs a local library next to a streaming
     * one: the same song exists as a lossy stream and as a FLAC on a NAS, and until
     * now there was no way to say which one to play.
     *
     * The version being played is filtered out — offering to switch to what is
     * already playing is not an offer — and the rest are ordered best-format-first,
     * because that is the ranking someone opening this list has in mind.
     */
    private val _versions = MutableStateFlow<Load<List<MaItem>>>(Load.Idle)
    val versions: StateFlow<Load<List<MaItem>>> = _versions

    fun loadVersions() {
        val item = currentItem.value ?: run {
            _versions.value = Load.Failed("Nothing playing")
            return
        }
        _versions.value = Load.Loading
        viewModelScope.launch {
            _versions.value = try {
                val all = repo.trackVersions(item)
                    .filterNot { it.uri != null && it.uri == item.uri }
                    .sortedByDescending { v ->
                        val f = v.audioFormat
                        // Lossless first, then rate, then depth — a stable ordering
                        // that puts the copy worth switching to at the top.
                        val lossless = if (f?.quality?.lossless == true) 1L else 0L
                        lossless * 1_000_000_000L +
                            (f?.sampleRate?.toLong() ?: 0L) * 100L +
                            (f?.bitDepth?.toLong() ?: 0L)
                    }
                Load.Ready(all)
            } catch (e: Exception) {
                Load.Failed(e.message ?: "Couldn't list versions")
            }
        }
    }

    /** Play a specific copy of the current track, in place of the one playing. */
    fun playVersion(version: MaItem) {
        val uri = version.uri ?: return
        viewModelScope.launch {
            runCatching { repo.playOn(targetId(), listOf(uri), "replace") }
                .onSuccess { _toast.tryEmit("Playing ${version.audioFormat?.quality?.shortLabel ?: version.name}") }
                .onFailure { _toast.tryEmit(it.message ?: "Couldn't switch version") }
        }
    }

    fun loadSimilar() {
        val item = currentItem.value ?: run {
            _similar.value = Load.Failed("Nothing playing")
            return
        }
        _similar.value = Load.Loading
        viewModelScope.launch {
            _similar.value = try {
                Load.Ready(repo.similarTracks(item.itemId, item.provider))
            } catch (e: Exception) {
                Load.Failed(e.message ?: "Couldn't find similar tracks")
            }
        }
    }

    /** Natural-language search over the sonic embeddings ("rainy sunday piano"). */
    fun sonicSearch(query: String) {
        if (query.isBlank()) return loadSimilar()
        _similar.value = Load.Loading
        viewModelScope.launch {
            _similar.value = try {
                Load.Ready(repo.sonicTextSearch(query.trim()))
            } catch (e: Exception) {
                Load.Failed(e.message ?: "Search failed")
            }
        }
    }

    /** [option] is MA's QueueOption: `next` to play after this, `add` to append. */
    fun enqueue(track: MaSimilarTrack, option: String) {
        // The local player has its own queue; sending this to Music Assistant while
        // Navidrome is what's actually playing put the track somewhere the user
        // couldn't hear and reported success. There is no MA URI to hand over either —
        // a local track is a Subsonic id — so this branch has to build one.
        if (isLocal) {
            viewModelScope.launch {
                val queued = localTrackFor(track)
                if (queued == null) {
                    _toast.tryEmit("Can't queue that here")
                    return@launch
                }
                if (option == "next") local.playNext(listOf(queued)) else local.addToQueue(listOf(queued))
                _toast.tryEmit(if (option == "next") "Playing next" else "Added to queue")
                if (_queueItems.value !is Load.Idle) _queueItems.value = Load.Ready(localQueueItems())
            }
            return
        }
        val uri = track.uri ?: "track://${track.provider}/${track.itemId}"
        val q = streamQueueId()
        viewModelScope.launch {
            try {
                repo.playOn(targetId(), listOf(uri), option)
                _toast.tryEmit(if (option == "next") "Playing next" else "Added to queue")
                delay(350); refresh()
                if (_queueItems.value !is Load.Idle) loadQueue(silent = true)
            } catch (e: Exception) {
                _toast.tryEmit(e.message ?: "Couldn't queue that")
            }
        }
    }

    /**
     * The library that can answer for a locally-playing track.
     *
     * `scrobbleProvider` is the track's record of which server its id belongs to, so
     * this refuses rather than guessing when the live source is a different one —
     * a Jellyfin guid asked of Navidrome is not a lookup that can succeed, and the
     * "no lyrics found" it produces looks like a missing lyric rather than a
     * misrouted request.
     */
    private fun lyricsSource(track: LocalTrack): com.engabd.sendpin.library.MusicSource? {
        val live = (getApplication<Application>() as SendpinApp).musicSource.value ?: return null
        val want = track.scrobbleProvider ?: return live
        return live.takeIf { it.providerId == want }
    }

    /** Built on demand for the local-queue path; null when no Navidrome is configured. */
    private var subsonic: SubsonicClient? = null

    private suspend fun subsonicClient(): SubsonicClient? {
        subsonic?.let { return it }
        val url = settings.navUrl.first().trim()
        if (url.isBlank()) return null
        return SubsonicClient(url, settings.navUsername.first(), settings.navPassword.first())
            .also { it.streamFormat = settings.navStreamFormat.first(); subsonic = it }
    }

    /**
     * A [LocalTrack] for a similar-track suggestion, when one can be made.
     *
     * Only a Subsonic-provided suggestion can be: an MA-only track has no URL this
     * phone could fetch on its own, and there is nothing honest to queue.
     */
    private suspend fun localTrackFor(track: MaSimilarTrack): LocalTrack? {
        if (track.provider != SubsonicClient.PROVIDER) return null
        val sc = subsonicClient() ?: return null
        return LocalTrack(
            id = track.itemId,
            title = track.name,
            artist = track.artist.orEmpty(),
            album = "",
            artUrl = track.image,
            streamUrl = sc.streamUrl(track.itemId),
        )
    }

    /** A short preview of a track, without disturbing what's playing. */
    suspend fun previewUrl(itemId: String, provider: String): String? =
        try { repo.trackPreview(itemId, provider) } catch (_: Exception) { null }

    // --- sleep timer -------------------------------------------------------

    private var sleepTimerJob: kotlinx.coroutines.Job? = null
    private val _sleepTimerMin = MutableStateFlow(0); val sleepTimerMin: StateFlow<Int> = _sleepTimerMin

    /**
     * Milliseconds left on the running timer, ticking once a second — the chip had
     * no readout at all before, so a timer that was quietly counting down looked
     * exactly like a button that did nothing.
     */
    private val _sleepTimerRemainingMs = MutableStateFlow(0L)
    val sleepTimerRemainingMs: StateFlow<Long> = _sleepTimerRemainingMs

    /**
     * Start a sleep timer that fades playback to silence over the last 10 seconds,
     * then pauses. [minutes] = 0 cancels an existing timer.
     *
     * The countdown is driven off a wall-clock deadline rather than accumulated
     * delays, so a timer stays honest across a doze or a long GC pause.
     */
    fun setSleepTimer(minutes: Int) {
        sleepTimerJob?.cancel()
        if (minutes <= 0) {
            _sleepTimerMin.value = 0
            _sleepTimerRemainingMs.value = 0
            return
        }
        _sleepTimerMin.value = minutes
        val totalMs = minutes * 60_000L
        _sleepTimerRemainingMs.value = totalMs
        _toast.tryEmit("Sleeping in ${minutes}m")

        sleepTimerJob = viewModelScope.launch {
            val deadline = System.currentTimeMillis() + totalMs
            while (true) {
                val left = deadline - System.currentTimeMillis()
                _sleepTimerRemainingMs.value = left.coerceAtLeast(0)
                if (left <= FADE_MS) break
                delay(minOf(1_000L, left - FADE_MS))
            }
            // Fade out over the last stretch by stepping the volume down.
            val player = targetId()
            val startVol = state.value.volume
            val steps = 20
            for (i in 1..steps) {
                setVolume(startVol * (1f - i.toFloat() / steps))
                _sleepTimerRemainingMs.value = (deadline - System.currentTimeMillis()).coerceAtLeast(0)
                delay(FADE_MS / steps)
            }
            if (isLocal) local.pause() else try { repo.pause(player) } catch (_: Exception) {}
            setVolume(startVol)   // restore so the user's volume isn't stuck at zero
            _sleepTimerMin.value = 0
            _sleepTimerRemainingMs.value = 0
            _toast.tryEmit("Sleep timer ended")
        }
    }

    fun cancelSleepTimer() {
        val wasRunning = _sleepTimerMin.value > 0
        setSleepTimer(0)
        if (wasRunning) _toast.tryEmit("Sleep timer cancelled")
    }

    private inline fun act(toastOnError: String? = null, crossinline block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
            } catch (e: Exception) {
                if (toastOnError != null) _toast.tryEmit(e.message ?: toastOnError)
            }
            // No delay(300) — the MA WebSocket pushes player/queue events which
            // trigger refresh() and re-anchor the position engine. The 300ms wait
            // was adding visible latency to every transport action.
            refresh()
        }
    }

    override fun onCleared() {
        super.onCleared()
        // Shared MaApiClient — don't disconnect it when one ViewModel is destroyed.
    }

    private companion object {
        /** How long the sleep timer spends fading out before it pauses. */
        const val FADE_MS = 10_000L

        /**
         * How long an optimistic seek/skip may ignore the server before it gives up
         * and accepts whatever the server says. A freeze that never releases would
         * wedge the bar permanently, so this is the liveness backstop, not a tuning
         * knob — keep it comfortably longer than a slow round trip.
         */
        const val FREEZE_TIMEOUT_MS = 6_000L

        /** How close the server's clock must land to a seek target to count as landed. */
        const val SEEK_CONFIRM_MS = 3_000L

        /**
         * Matches LibraryViewModel.SCROBBLE_MAX_MS — the same "was this a real play" call.
         * Used by [recordHistoryWhenPlayed] so a skip doesn't inflate the stats the same
         * way it doesn't inflate a scrobble.
         */
        const val HISTORY_THRESHOLD_MS = 4 * 60 * 1000L
    }

    /**
     * Deliberately the **last** thing in the class, along with the init below it.
     *
     * Property initialisers and init blocks run in source order, and these blocks
     * start collectors on `viewModelScope` — `Dispatchers.Main.immediate`, which does
     * not defer on the main thread. A StateFlow hands over its current value
     * synchronously, so these bodies run *during construction*: anything they touch
     * that is declared below them is still null. That cost three separate launch
     * crashes (`_radioMode`, `_frequent`, and `refreshing`), each fixed by moving one
     * property up, each time leaving the next one waiting.
     *
     * This block used to sit in the middle of the class, and was the fourth instance:
     * `api.state.collect { if (CONNECTED) refresh() }` below fired synchronously
     * whenever the socket was already up when the ViewModel was built, and `refresh()`
     * touches `refreshing`, which was declared further down. It only showed on a first
     * clean start, because that is the run where connecting finishes during onboarding
     * *before* Now Playing is first composed; on every later start the ViewModel is
     * built while still disconnected and the collector hands over DISCONNECTED.
     *
     * Sitting at the bottom, every property in the class is initialised before any of
     * this runs, and the whole class of bug is gone rather than one more instance of
     * it. Nothing here needs to run early — it cannot: construction has to finish
     * before anyone can call into the object anyway.
     *
     * **Do not add an init or a property below these two.**
     */
    init {
        viewModelScope.launch {
            val url = settings.maBaseUrl.first()
            val user = settings.maUsername.first()
            val pass = settings.maPassword.first()
            if (url.isNotBlank()) api.connect(url, token = null, username = user.ifBlank { null }, password = pass.ifBlank { null })
        }
        viewModelScope.launch { settings.targetPlayer.collect { _target.value = it } }
        viewModelScope.launch { settings.radioMode.collect { _radioMode.value = it } }
        // The Music Map timeline's scan lookup. collectLatest so a fast skip-skip-skip
        // cancels an in-flight disk read rather than racing the UI with a stale scan.
        // Local sessions only — TrackScanRepository has no data for an MA-streamed track.
        viewModelScope.launch {
            combine(state.map { it.isLocalSession }, local.current) { isLocal, track -> isLocal to track }
                .distinctUntilChanged()
                .collectLatest { (isLocalTrack, track) ->
                    _currentScan.value = null
                    _currentScan.value = if (isLocalTrack && track != null) trackScans.cached(track) else null
                }
        }
        viewModelScope.launch {
            settings.navStreamFormat.collect { navFormat = it.takeIf { f -> f != "raw" } }
        }
        // When this phone *is* the Music Assistant player, audio actually coming out is
        // proof the skip landed - which is what releases an optimistic freeze, rather
        // than a guess made from polled state. A remote speaker gives us no such signal
        // and still relies on the poll corroborating the skip (see [anchorFromServer]).
        //
        // Deliberately [Playback.audibleSeq] and not `sendspinPlaying`: the latter goes
        // true on `stream/start`, over a second before the decoder and audio track have
        // warmed up. Releasing the freeze there let the *outgoing* track's playhead
        // through for that second - the bar jumped back up to where the previous track
        // had reached, ran on, then dropped to zero when the new stream finally
        // anchored, which is the "jumps forward and backwards" at the start of a track.
        // And an edge rather than a level, because when the skip is asked for the old
        // stream is still audible: only "a *new* stream has been heard" answers it.
        viewModelScope.launch {
            playback.audibleSeq.collect { seq ->
                if (isLocal) return@collect
                val armedAt = freezeAtAudibleSeq
                if (armedAt < 0 || seq <= armedAt) return@collect
                val key = positionKey()
                if (positions.isFrozen(key)) releaseFreeze(key)
            }
        }
        // Remember whatever the selected player last had loaded.
        viewModelScope.launch {
            combine(_players, _target) { players, target ->
                val id = resolveTarget(players, target)
                players.firstOrNull { it.playerId == id }?.nowPlaying
            }.collect { np -> if (np != null && np.title.isNotBlank()) _lastTrack.value = np }
        }
        // A new track invalidates whatever the panels were showing. Lyrics are not in
        // here: they follow [lyricsKey], which is the identity of what is playing on
        // *either* engine — see the collector below.
        viewModelScope.launch {
            currentItem.map { it?.itemId }.distinctUntilChanged().collect {
                _similar.value = Load.Idle
                _favoriteOverride.value = null
                if (_queueItems.value !is Load.Idle) loadQueue(silent = true)
            }
        }
        // Lyrics, in one place for both engines.
        //
        // Driven from the view model rather than from the pane's own effect, because
        // the pane's effect and this reset are two independent things racing: whichever
        // ran second decided whether a fetch happened at all. Here there is only one
        // rule — the track changed, so throw the words away; the pane is open, so go and
        // get the new ones — and it holds no matter which engine is playing or when the
        // pane was opened.
        viewModelScope.launch {
            var lastKey: String? = null
            var seeded = false
            combine(lyricsKey, _lyricsOpen) { key, open -> key to open }
                .distinctUntilChanged()
                .collect { (key, open) ->
                    if (key != lastKey || !seeded) {
                        lastKey = key
                        seeded = true
                        _lyrics.value = Load.Idle
                    }
                    // Idle-guarded so closing and reopening the pane on the same track
                    // shows what was already fetched instead of asking again.
                    if (open && _lyrics.value is Load.Idle) loadLyrics()
                }
        }
        // The local queue lives in memory and can change under an open panel — a
        // track finishing, a "play next", a drag — so the panel follows it.
        viewModelScope.launch {
            local.queue.collect {
                if (isLocal && _queueItems.value !is Load.Idle) _queueItems.value = Load.Ready(localQueueItems())
            }
        }
        // `local.current` re-emits for reasons that are not a track change — a
        // re-tagged entry, a stream URL refreshed — so it is compared by identity
        // first. Without that, a mid-song re-emission threw away lyrics that were
        // already on screen.
        viewModelScope.launch {
            local.current.map { it?.id }.distinctUntilChanged().collect {
                if (!isLocal) return@collect
                _similar.value = Load.Idle
            }
        }
        // Player and queue state come from the process-scoped MaNowPlaying, which
        // already polls both every 5 s *and* refreshes on sampled player/queue events.
        // This screen used to run its own 5-second loop and its own `sample(300)`
        // collector, and the Speakers screen a third pair — so a single MA server was
        // answering three copies of `players/all` and `player_queues/all` on the same
        // schedule, with no shared cache between them.
        //
        // No slower than the poll it replaces: MaNowPlaying samples player/queue events
        // at 300 ms, the same cadence this screen's own collector used.
        viewModelScope.launch {
            maNowPlaying.players.collect { players ->
                val connected = api.state.value == MaApiClient.State.CONNECTED
                if (players.isNotEmpty() || connected) _players.value = players
            }
        }
        viewModelScope.launch {
            maNowPlaying.queues.collect { queues ->
                val connected = api.state.value == MaApiClient.State.CONNECTED
                if (queues.isNotEmpty() || connected) _queues.value = queues
            }
        }
        // Opening the screen is the moment the state has to be right, rather than up
        // to five seconds later.
        maNowPlaying.refreshNow()
        // The queue's *contents* changed.
        //
        // A separate collector on purpose: `sample` drops events, and a
        // `queue_items_updated` lost behind a burst of `queue_time_updated` is exactly
        // the "added an album, the open panel never showed it" report. Matching by
        // event name rather than by substring over the whole frame is what makes the
        // two distinguishable at all.
        viewModelScope.launch {
            maEvents
                .filter { it.changesQueueContents }
                .filter { it.objectId == null || it.objectId == streamQueueId() }
                .sample(400)
                .collect { if (_queueItems.value !is Load.Idle) loadQueue(silent = true) }
        }
        // Belt and braces for a server that doesn't emit `queue_items_updated`: the
        // item count moving is itself proof the list on screen is stale. This is also
        // what covers "add to queue" issued from the library and the detail screens,
        // whose ViewModels have no handle on this one.
        viewModelScope.launch {
            combine(_queues, _target, _players) { queues, _, _ ->
                queues.firstOrNull { it.queueId == streamQueueId() }?.itemCount ?: 0
            }.distinctUntilChanged().drop(1).collect {
                if (_queueItems.value !is Load.Idle) loadQueue(silent = true)
            }
        }
        // Listening history, for the Stats screen. Driven off [state] rather than
        // separately for the MA and local paths — title/artist/album identity is
        // already unified there, which a per-backend hook (mirroring how the
        // scrobble path in LibraryViewModel is local-only) would not be.
        viewModelScope.launch {
            state.map { TrackIdentity(it.title, it.artist, it.album) to it.hasTrack }
                .distinctUntilChanged()
                .collect { (identity, hasTrack) ->
                    historyJob?.cancel()
                    historyJob = if (hasTrack && identity.title.isNotBlank()) {
                        viewModelScope.launch { recordHistoryWhenPlayed(identity) }
                    } else null
                }
        }
    }

    /**
     * The second of the two init blocks that close the class. See the note on the
     * first: every `init` here has to stay below every property.
     */
    init {
        viewModelScope.launch {
            combine(_players, _queues, _target) { players, queues, _ ->
                Triple(players, queues, Unit)
            }.collect { (players, queues, _) ->
                // The local player reports its own position; projecting a server
                // anchor forward on top of it would fight it.
                if (isLocal) return@collect
                anchorFromServer(players, queues)
            }
        }
        // Keep the republishing ticker pointed at whichever queue is current.
        viewModelScope.launch {
            combine(_players, _target) { _, _ -> streamQueueId() }
                .distinctUntilChanged()
                .collect { followPosition(it) }
        }
    }

    // --- listening history ---------------------------------------------------

    /** Title/artist/album, as the "is this still the same track" identity for history. */
    private data class TrackIdentity(val title: String, val artist: String, val album: String)

    /**
     * Wait until [identity] has been listened to, then log it for the Stats screen.
     *
     * Same threshold as [com.engabd.sendpin.ma.LibraryViewModel.submitWhenPlayed]'s
     * scrobble — half the track, or four minutes, whichever comes first — so a
     * skip doesn't inflate the stats the same way it doesn't inflate a scrobble.
     * Cancelled by the caller the moment the track identity changes, so this only
     * ever runs to completion for the track it started watching.
     */
    private suspend fun recordHistoryWhenPlayed(identity: TrackIdentity) {
        val played = state.map { s ->
            when {
                TrackIdentity(s.title, s.artist, s.album) != identity -> false
                s.durationMs > 0 && s.positionMs >= minOf(s.durationMs / 2, HISTORY_THRESHOLD_MS) -> true
                else -> null
            }
        }.first { it != null }
        if (played != true) return
        val s = state.value
        try {
            // Listening DNA snapshot — opt-in, and only ever available for a local
            // session (TrackScanRepository has no data for an MA-streamed track).
            // peek(), not cached(): by the time a track has played this long it has
            // very likely already been adopted into the memory cache by whatever
            // warmed it (Light Sync, the Music Map timeline) if it was going to be;
            // a miss here just means a null snapshot for this one play.
            val scan = if (settings.listeningDna.first() && s.isLocalSession) {
                local.current.value?.let { trackScans.peek(it) }
            } else null
            val dao = LocalMediaDatabase.get(getApplication()).playHistoryDao()
            dao.insert(
                PlayHistoryEntity(
                    timestamp = System.currentTimeMillis(),
                    trackId = s.currentQueueItemId ?: "${identity.title}|${identity.artist}|${identity.album}",
                    title = identity.title,
                    artist = identity.artist,
                    album = identity.album,
                    provider = s.source,
                    codec = s.sourceQuality?.codec,
                    sampleRate = s.sourceQuality?.sampleRateHz ?: 0,
                    bitDepth = s.sourceQuality?.bitDepth ?: 0,
                    durationPlayedMs = s.positionMs,
                    bpm = scan?.bpm,
                    keyTonic = scan?.key?.tonic,
                    keyMode = scan?.key?.mode?.name,
                    energy = scan?.intensity?.character,
                ),
            )
            dao.trimTo()
        } catch (e: Exception) {
            // Best-effort: a failed history write is not worth surfacing to the
            // listener, and must not be allowed to look like a playback problem.
        }
    }

}
