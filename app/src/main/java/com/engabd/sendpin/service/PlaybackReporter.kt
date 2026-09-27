package com.engabd.sendpin.service

import com.engabd.sendpin.SendpinApp
import com.engabd.sendpin.audio.LocalTrack
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.library.MusicSource
import com.engabd.sendpin.library.MusicSources
import com.engabd.sendpin.local.db.LocalMediaDatabase
import com.engabd.sendpin.local.db.PlayHistoryEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Tells the library servers what this phone played, for as long as the process
 * lives — not for as long as a screen does.
 *
 * All of this used to run in `LibraryViewModel`'s `viewModelScope`: the "now playing"
 * ping, the completed-play scrobble, Jellyfin/Emby/Plex progress and stop reports, the
 * saved play queue, and (in `NowPlayingViewModel`) the Stats history row. A ViewModel
 * lives with its Activity, so listening with the app swiped away reported nothing,
 * and a session started from Android Auto — which plays straight into the player and
 * never opens the Activity — reported nothing at all: no play counts, no "recently
 * played", no Jellyfin resume points, no Stats.
 *
 * Built once, on [SendpinApp], and started from `onCreate`.
 *
 * The reports themselves are unchanged — the same Subsonic two-step (ping at start,
 * completion at half the track or four minutes), the same Jellyfin session keepalive.
 * What changed is who owns them, and how a server is found: the active library if it
 * is the one the track came from, and otherwise one built from the saved server list
 * the way Android Auto builds its own, so nothing depends on a library screen having
 * been opened this session.
 */
class PlaybackReporter(private val app: SendpinApp) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val settings = AppSettings(app)
    private val player get() = app.localPlayer

    /** Sources built for reporting, by provider tag — see [sinkFor]. */
    private val built = ConcurrentHashMap<String, MusicSource>()

    private var started = false

    fun start() {
        if (started) return
        started = true
        scope.launch { reportPlays() }
        scope.launch { closeOnQueueEnd() }
        scope.launch { recordLocalHistory() }
        scope.launch { syncSavedQueue() }
    }

    // ── Which server hears about a track ────────────────────────────────────

    /**
     * The library that produced a track with [provider] as its tag.
     *
     * The active library when it is that one — the common case, and the one with a
     * live, signed-in client. Otherwise a source built from the saved servers,
     * preferring the active server's own config: a track from "play at original
     * quality" is Navidrome's while Music Assistant is the library, and a car session
     * may have no active source published at all.
     */
    suspend fun sinkFor(provider: String?): MusicSource? {
        provider ?: return null
        app.musicSource.value?.takeIf { it.providerId == provider }?.let { return it }
        built[provider]?.let { return it }
        val active = settings.activeServer.first()
        val candidates = listOfNotNull(active) + settings.servers.first().filter { it.id != active?.id }
        for (config in candidates) {
            val source = runCatching { MusicSources.create(app, config) }.getOrNull() ?: continue
            if (source.providerId == provider) {
                built[provider] = source
                return source
            }
        }
        return null
    }

    // ── Scrobbles and session reports ───────────────────────────────────────

    private var submissionJob: Job? = null
    private var progressJob: Job? = null
    private var reportedSession: Pair<MusicSource, String>? = null
    private var reportedPositionMs = 0L
    private var reportedDurationMs = 0L

    /**
     * Two reports per track, which is what the Subsonic spec asks for: a "now
     * playing" ping the moment it starts, and a completed play once it has been
     * listened to. Keyed on the track carrying a library id rather than on the
     * selected backend, so "play at original quality" from Navidrome counts while
     * Music Assistant is the library.
     */
    private suspend fun reportPlays() {
        player.started.collect { track ->
            // The previous track's session ends here, before the next one opens —
            // whatever the next one is, even a track no server hears about.
            progressJob?.cancel()
            closeReportedSession()
            val songId = track.scrobbleId ?: return@collect
            val sink = sinkFor(track.scrobbleProvider) ?: return@collect
            val startedAtMs = System.currentTimeMillis()
            runCatching { sink.scrobble(songId, completed = false) }
            reportedSession = sink to songId
            reportedPositionMs = 0L
            reportedDurationMs = player.durationMs.value
            submissionJob?.cancel()
            submissionJob = scope.launch { submitWhenPlayed(sink, songId, startedAtMs) }
            progressJob = scope.launch { reportProgressWhile(sink, songId) }
        }
    }

    /** The last track finished with nothing after it: its session is over. */
    private suspend fun closeOnQueueEnd() {
        player.exhausted.collect {
            reportedPositionMs = reportedDurationMs.takeIf { it > 0 } ?: reportedPositionMs
            closeReportedSession()
        }
    }

    /**
     * Keep the server's session alive, and its playhead honest, while [id] plays.
     *
     * A no-op for every provider except the Jellyfin family, whose session — its
     * "Now Playing" panel and its resume positions — times out after about a minute
     * of silence. A pause, resume or seek is reported the moment it happens, with the
     * position read at send time.
     */
    private suspend fun reportProgressWhile(sink: MusicSource, id: String) = coroutineScope {
        val changed = Channel<Unit>(Channel.CONFLATED)
        val watch = launch {
            merge(player.playing.drop(1).map { }, player.seeks.map { })
                .collect { changed.trySend(Unit) }
        }
        try {
            while (true) {
                withTimeoutOrNull(PROGRESS_REPORT_MS) { changed.receive() }
                if (player.current.value?.scrobbleId != id) {
                    if (player.current.value == null) closeReportedSession(id)
                    return@coroutineScope
                }
                val position = player.livePositionMs()
                reportedPositionMs = position
                reportedDurationMs = player.durationMs.value
                runCatching { sink.reportProgress(id = id, positionMs = position, paused = !player.playing.value) }
            }
        } finally {
            watch.cancel()
        }
    }

    private suspend fun closeReportedSession(onlyId: String? = null) {
        val (sink, id) = reportedSession ?: return
        if (onlyId != null && onlyId != id) return
        reportedSession = null
        runCatching { sink.reportStopped(id, reportedPositionMs, reportedDurationMs) }
    }

    /**
     * Wait until [id] has been listened to — half the track or four minutes, the
     * threshold Last.fm and Navidrome both use — then report the completed play.
     * Bails if the listener moved on: a skip is not a play.
     */
    private suspend fun submitWhenPlayed(sink: MusicSource, id: String, startedAtMs: Long) {
        val played = playedThreshold { it?.scrobbleId == id || it?.id == id }
        if (played) {
            runCatching {
                sink.scrobble(id, completed = true, startedAtMs = startedAtMs, positionMs = player.positionMs.value)
            }
        }
    }

    /** True once the current track has passed the "counts as a play" point; false if it changes first. */
    private suspend fun playedThreshold(isSame: (LocalTrack?) -> Boolean): Boolean =
        combine(player.positionMs, player.durationMs, player.current) { position, duration, current ->
            when {
                !isSame(current) -> false
                duration > 0 && position >= minOf(duration / 2, PLAYED_MAX_MS) -> true
                else -> null
            }
        }.first { it != null } == true

    // ── Listening history (the Stats screen) ────────────────────────────────

    private var historyJob: Job? = null

    /**
     * A row per local play that was actually listened to, for the Stats screen.
     *
     * Local plays only: Music Assistant sessions are still recorded by the Now
     * Playing screen, which is where the stream details a row carries for them live.
     * The local half moved here because it is the half that plays with no screen at
     * all — in the car, or with the app swiped away.
     */
    private suspend fun recordLocalHistory() {
        player.current.map { it?.id }.distinctUntilChanged().collect {
            historyJob?.cancel()
            val track = player.current.value ?: return@collect
            if (track.title.isBlank()) return@collect
            historyJob = scope.launch {
                if (!playedThreshold { it?.id == track.id }) return@launch
                recordHistory(track)
            }
        }
    }

    private suspend fun recordHistory(track: LocalTrack) {
        runCatching {
            val scan = if (settings.listeningDna.first()) app.trackScans.peek(track) else null
            val source = if (track.localPath != null && track.streamUrl == null) "Offline"
            else settings.activeServer.first()?.displayName ?: "This phone"
            val dao = LocalMediaDatabase.get(app).playHistoryDao()
            dao.insert(
                PlayHistoryEntity(
                    timestamp = System.currentTimeMillis(),
                    trackId = "${player.queue.value.indexOfFirst { it.id == track.id }}-${track.id}",
                    title = track.title,
                    artist = track.artist.orEmpty(),
                    album = track.album.orEmpty(),
                    provider = source,
                    streamProvider = null,
                    codec = track.sourceQuality?.codec,
                    sampleRate = track.sourceQuality?.sampleRateHz ?: 0,
                    bitDepth = track.sourceQuality?.bitDepth ?: 0,
                    durationPlayedMs = player.positionMs.value,
                    durationMs = player.durationMs.value.takeIf { it > 0 } ?: track.durationMs,
                    bpm = scan?.bpm,
                    keyTonic = scan?.key?.tonic,
                    keyMode = scan?.key?.mode?.name,
                    energy = scan?.intensity?.character,
                ),
            )
            dao.trimTo()
        }
    }

    // ── The server's saved play queue ───────────────────────────────────────

    /**
     * Hand this phone's queue back to the server, so another device can pick it up.
     * On track change and on pause — the two moments the answer changes in a way
     * another device would care about.
     */
    private suspend fun syncSavedQueue() {
        combine(player.current, player.playing) { track, playing -> track to playing }
            .distinctUntilChanged()
            .collect { (track, _) ->
                if (track == null) return@collect
                val sink = sinkFor(track.scrobbleProvider) ?: return@collect
                val ids = player.queue.value.filter { it.scrobbleProvider == track.scrobbleProvider }
                    .mapNotNull { it.scrobbleId }
                if (ids.isEmpty()) return@collect
                runCatching { sink.saveQueue(songIds = ids, currentId = track.scrobbleId, positionMs = player.positionMs.value) }
            }
    }

    private companion object {
        /** Well inside Jellyfin's roughly one-minute session timeout. */
        const val PROGRESS_REPORT_MS = 5_000L
        /** A play counts at half the track or this, whichever comes first. */
        const val PLAYED_MAX_MS = 4 * 60 * 1000L
    }
}
