package com.engabd.sendpin.service

import com.engabd.sendpin.util.runCatchingCancellable
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

    /** Where a counted listen goes — the server, ListenBrainz, Last.fm — and its queue. */
    val scrobbler = com.engabd.sendpin.scrobble.Scrobbler(app) { provider -> sinkFor(provider) }

    fun start() {
        if (started) return
        started = true
        scope.launch { reportPlays() }
        scope.launch { closeOnQueueEnd() }
        scope.launch { recordLocalHistory() }
        scope.launch { syncSavedQueue() }
        scrobbler.start()
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
            val source = runCatchingCancellable { MusicSources.create(app, config) }.getOrNull() ?: continue
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
            // ListenBrainz / Last.fm hear about every track with a title and an artist,
            // library server or not — a file on the phone is a listen too. The server's
            // own play count goes the same way, so both share one threshold and queue.
            val play = playOf(track, System.currentTimeMillis())
            if (play != null) {
                scrobbler.nowPlaying(play)
                submissionJob?.cancel()
                submissionJob = scope.launch { submitWhenPlayed(track, play) }
            }
            val songId = track.scrobbleId ?: return@collect
            val sink = sinkFor(track.scrobbleProvider) ?: return@collect
            val startedAtMs = System.currentTimeMillis()
            // The live playhead, not an implied zero: the start report is the first
            // anchor a follower of the session gets, and by the time it is sent a
            // gapless transition is already a few hundred milliseconds in.
            val startPosition = compensated(player.livePositionMs(), playing = player.playing.value)
            timed { runCatchingCancellable { sink.scrobble(songId, completed = false, positionMs = startPosition) } }
            reportedSession = sink to songId
            reportedPositionMs = 0L
            reportedDurationMs = player.durationMs.value
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
     * A no-op for every provider except the Jellyfin family, whose session — its "Now
     * Playing" panel, its resume positions, and everything that follows it (Hue Ghost
     * drives the lights from it) — lives on these reports. Jellyfin has no timestamp in
     * a progress report and no playback-rate field: it stamps the report on arrival and
     * extrapolates at 1x until the next one. So three things decide how well a follower
     * can track the phone, and each is handled here:
     *
     *  - **What the position means on arrival.** The position is read at send time and
     *    then spends a network round trip's first half in flight, so it lands that much
     *    behind the playhead. Measured against a live server this was the dominant
     *    error: reports landed 30–560 ms behind. [compensated] adds the measured
     *    one-way delay (half of a smoothed round trip of these same requests).
     *  - **How often.** Every [PROGRESS_REPORT_MS] normally — on a fixed period
     *    measured from the start of the last send, so the interval no longer creeps by
     *    each request's own duration — but every [FAST_REPORT_MS] for [FAST_WINDOW_MS]
     *    after a start, a resume or a seek, when a follower has no lock yet, and every
     *    [RATE_REPORT_MS] while playback runs at anything but 1x (Lo-fi's slowdown, a
     *    speed change), because Jellyfin extrapolates at 1x regardless.
     *  - **When things change.** A pause, a resume or a seek is reported at once.
     */
    private suspend fun reportProgressWhile(sink: MusicSource, id: String) = coroutineScope {
        val changed = Channel<Unit>(Channel.CONFLATED)
        val watch = launch {
            merge(player.playing.drop(1).map { }, player.seeks.map { })
                .collect { changed.trySend(Unit) }
        }
        // The stop report carries where the track actually stopped. It used to carry
        // the last *progress* report's position — up to a report interval stale, so a
        // skip 4 s after a report told Jellyfin to resume 4 s early. Followed here
        // for as long as this is the track playing; the transition that ends it
        // changes `current` before it resets the position, so 0 never lands here.
        val follow = launch {
            player.positionMs.collect { pos ->
                if (player.current.value?.scrobbleId == id) reportedPositionMs = pos
            }
        }
        var fastUntil = android.os.SystemClock.elapsedRealtime() + FAST_WINDOW_MS
        var lastSendAt = android.os.SystemClock.elapsedRealtime()
        try {
            while (true) {
                val now = android.os.SystemClock.elapsedRealtime()
                val period = when {
                    player.effectiveSpeed() != 1f -> RATE_REPORT_MS
                    now < fastUntil -> FAST_REPORT_MS
                    else -> PROGRESS_REPORT_MS
                }
                val wait = (lastSendAt + period - now).coerceAtLeast(0L)
                val event = withTimeoutOrNull(wait) { changed.receive() }
                if (event != null) fastUntil = android.os.SystemClock.elapsedRealtime() + FAST_WINDOW_MS
                if (player.current.value?.scrobbleId != id) {
                    if (player.current.value == null) closeReportedSession(id)
                    return@coroutineScope
                }
                val playing = player.playing.value
                val live = player.livePositionMs()
                reportedPositionMs = live
                reportedDurationMs = player.durationMs.value
                lastSendAt = android.os.SystemClock.elapsedRealtime()
                timed {
                    runCatchingCancellable {
                        sink.reportProgress(
                            id = id,
                            positionMs = compensated(live, playing),
                            paused = !playing,
                            repeatMode = player.repeatMode.value,
                            shuffle = player.shuffle.value,
                        )
                    }
                }
            }
        } finally {
            watch.cancel()
            follow.cancel()
        }
    }

    /** Smoothed one-way delay of a report, in ms: half a round trip, as an EWMA. */
    @Volatile private var oneWayMs = 0.0

    /** Run a report, folding how long it took into [oneWayMs]. */
    private suspend fun <T> timed(block: suspend () -> T): T {
        val t0 = android.os.SystemClock.elapsedRealtime()
        val result = block()
        val rtt = (android.os.SystemClock.elapsedRealtime() - t0).toDouble()
        // A slow outlier (a cold connection, a server busy scanning) must not drag
        // every later report ahead, so each sample is capped before it is averaged.
        val sample = (rtt / 2).coerceIn(0.0, MAX_ONE_WAY_MS)
        oneWayMs = if (oneWayMs == 0.0) sample else oneWayMs * (1 - ONE_WAY_ALPHA) + sample * ONE_WAY_ALPHA
        return result
    }

    /**
     * Where the playhead will be when a report sent now arrives: [positionMs] plus the
     * one-way delay, at the current rate. A paused position does not move in flight.
     */
    private fun compensated(positionMs: Long, playing: Boolean): Long =
        if (!playing) positionMs
        else positionMs + (oneWayMs * player.effectiveSpeed()).toLong()

    private suspend fun closeReportedSession(onlyId: String? = null) {
        val (sink, id) = reportedSession ?: return
        if (onlyId != null && onlyId != id) return
        reportedSession = null
        runCatchingCancellable { sink.reportStopped(id, reportedPositionMs, reportedDurationMs) }
    }

    /**
     * Wait until [track] has been listened to — half the track or four minutes, the
     * threshold Last.fm and ListenBrainz both use — then hand the listen to the
     * [scrobbler]: the library server's completed-play report, and ListenBrainz and
     * Last.fm when they are on, each queued if it cannot be delivered now. A skip is not
     * a listen.
     */
    private suspend fun submitWhenPlayed(track: LocalTrack, play: com.engabd.sendpin.scrobble.Play) {
        val played = playedThreshold { it?.id == track.id }
        if (played) scrobbler.listened(play, provider = track.scrobbleProvider, trackId = track.scrobbleId)
    }

    /**
     * The listen [track] would make. The artist may be blank — the library server
     * counts a play by id and does not care — but the services need one and skip it.
     */
    private fun playOf(track: LocalTrack, startedAtMs: Long): com.engabd.sendpin.scrobble.Play? {
        if (track.title.isBlank() && track.scrobbleId == null) return null
        return com.engabd.sendpin.scrobble.Play(
            title = track.title,
            artist = track.artist?.takeIf { it != "<unknown>" }.orEmpty().trim(),
            album = track.album?.takeIf { it.isNotBlank() && it != "<unknown>" },
            durationMs = track.durationMs,
            startedAtMs = startedAtMs,
        )
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
        runCatchingCancellable {
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
                runCatchingCancellable { sink.saveQueue(songIds = ids, currentId = track.scrobbleId, positionMs = player.positionMs.value) }
            }
    }

    private companion object {
        /** Well inside Jellyfin's roughly one-minute session timeout. */
        const val PROGRESS_REPORT_MS = 5_000L
        /** Just after a start, resume or seek — while a follower has no lock yet. */
        const val FAST_REPORT_MS = 2_000L
        const val FAST_WINDOW_MS = 10_000L
        /** While playing at anything but 1x, which Jellyfin cannot be told about. */
        const val RATE_REPORT_MS = 1_000L
        /** Weight of each new round-trip sample in [oneWayMs]. */
        const val ONE_WAY_ALPHA = 0.3
        /** Ceiling on one sample: past this a report is an outlier, not the network. */
        const val MAX_ONE_WAY_MS = 250.0
        /** A play counts at half the track or this, whichever comes first. */
        const val PLAYED_MAX_MS = 4 * 60 * 1000L
    }
}
