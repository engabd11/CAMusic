package com.engabd.sendpin.scrobble

import com.engabd.sendpin.util.runCatchingCancellable
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import com.engabd.sendpin.BuildConfig
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.library.MusicSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * Where a listen goes once it counts: the library server it came from, and
 * ListenBrainz and/or Last.fm when they are switched on — delivered now if possible,
 * and otherwise queued ([ScrobbleQueue]) and sent when a network comes back.
 *
 * Owned by [com.engabd.sendpin.service.PlaybackReporter], which decides *when* a
 * listen counts; this decides where it goes and makes sure it arrives.
 */
class Scrobbler(
    private val context: Context,
    /** The library a track with this provider tag came from — the reporter's lookup. */
    private val sinkFor: suspend (String?) -> MusicSource?,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val settings = AppSettings(context)
    private val queue = ScrobbleQueue(File(context.noBackupFilesDir, "scrobble_queue.json"))
    private val flushing = Mutex()

    // Counted on start(), off the main thread: building this used to read and parse
    // the whole backlog file right there in the constructor, on every process start.
    private val _pending = MutableStateFlow(0)
    /** Listens waiting to be delivered, for the settings page. */
    val pending: StateFlow<Int> = _pending

    fun start() {
        watchNetwork()
        scope.launch {
            _pending.value = queue.load().size
            flush()
        }
    }

    /** The services switched on and able to send, as configured right now. */
    suspend fun services(): List<ScrobbleService> = buildList {
        if (settings.listenBrainzEnabled.first()) {
            val token = settings.listenBrainzToken.first()
            if (token.isNotBlank()) add(ListenBrainzClient(token, settings.listenBrainzUrl.first()))
        }
        if (settings.lastFmEnabled.first()) {
            val session = settings.lastFmSession.first()
            val (key, secret) = lastFmApp()
            if (session.isNotBlank() && key.isNotBlank() && secret.isNotBlank()) {
                add(LastFmClient(key, secret, session, settings.lastFmApiRoot.first()))
            }
        }
    }

    /** The Last.fm app key and secret: the user's own if entered, else the build's. */
    suspend fun lastFmApp(): Pair<String, String> {
        val key = settings.lastFmApiKey.first().ifBlank { BuildConfig.LASTFM_API_KEY }
        val secret = settings.lastFmApiSecret.first().ifBlank { BuildConfig.LASTFM_API_SECRET }
        return key to secret
    }

    /** A track started: tell the services, best effort. Never queued. */
    fun nowPlaying(play: Play) {
        if (!play.scrobblable) return
        scope.launch {
            for (s in services()) runCatching { s.nowPlaying(play) }
        }
    }

    /**
     * A listen counted. The library server gets its completed-play report (the same
     * call the reporter always made), and every enabled service gets the listen; any
     * that cannot take it now is queued.
     */
    suspend fun listened(play: Play, provider: String?, trackId: String?) {
        if (provider != null && trackId != null) {
            val sink = sinkFor(provider)
            val ok = sink != null && runCatchingCancellable {
                sink.scrobble(trackId, completed = true, startedAtMs = play.startedAtMs, positionMs = null)
            }.isSuccess
            if (!ok && sink != null) enqueue(PendingScrobble(PendingScrobble.SERVER, play, provider, trackId))
        }
        if (!play.scrobblable) return
        for (s in services()) {
            try {
                s.submit(play)
            } catch (e: ScrobbleException) {
                if (e.retry) enqueue(PendingScrobble(s.id, play)) else Log.w(TAG, "${s.id} rejected a listen: ${e.message}")
            } catch (e: Exception) {
                enqueue(PendingScrobble(s.id, play))
            }
        }
    }

    private fun enqueue(entry: PendingScrobble) {
        queue.add(entry)
        _pending.value = queue.load().size
    }

    /**
     * Send what is queued, oldest first. A target that fails again keeps its place and
     * everything after it for that target waits too, so listens arrive in order; a
     * listen a service rejects outright, or one tried too often, is dropped.
     */
    suspend fun flush() = flushing.withLock {
        val entries = queue.load()
        if (entries.isEmpty()) { _pending.value = 0; return@withLock }
        val services = services().associateBy { it.id }
        val stalled = mutableSetOf<String>()
        val keep = mutableListOf<PendingScrobble>()
        for (e in entries) {
            if (e.target in stalled) { keep += e; continue }
            val outcome: Boolean? = try {
                when (e.target) {
                    PendingScrobble.SERVER -> {
                        val sink = sinkFor(e.provider)
                        if (sink == null) false else {
                            sink.scrobble(e.trackId ?: "", completed = true, startedAtMs = e.play.startedAtMs, positionMs = null)
                            true
                        }
                    }
                    else -> services[e.target]?.let { it.submit(e.play); true }
                        // The service was switched off since: its listens go with it.
                        ?: null
                }
            } catch (x: ScrobbleException) {
                if (x.retry) false else null
            } catch (x: Exception) {
                false
            }
            when (outcome) {
                true, null -> Unit                         // delivered, or no longer wanted
                false -> {
                    stalled += e.target
                    if (e.attempts + 1 < ScrobbleQueue.MAX_ATTEMPTS) keep += e.copy(attempts = e.attempts + 1)
                }
            }
        }
        queue.replace(keep)
        _pending.value = keep.size
    }

    /** Flush whenever a validated network appears — the moment listens made offline can go. */
    private fun watchNetwork() {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return
        runCatching {
            cm.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                    if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) && _pending.value > 0) {
                        scope.launch { flush() }
                    }
                }
            })
        }
    }

    private companion object {
        const val TAG = "Scrobbler"
    }
}
