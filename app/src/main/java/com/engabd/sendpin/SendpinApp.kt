package com.engabd.sendpin

import android.app.Application
import android.graphics.Bitmap
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.engabd.sendpin.audio.LocalPlayer
import com.engabd.sendpin.crash.CrashReporter
import com.engabd.sendpin.download.DownloadManager
import com.engabd.sendpin.ma.MaApiClient
import com.engabd.sendpin.service.AppLifecycleObserver
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.data.Http
import com.engabd.sendpin.service.LocalPlaybackService
import com.engabd.sendpin.service.MaNowPlaying
import com.engabd.sendpin.service.Playback
import com.engabd.sendpin.service.SendspinService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/** Holds the process-scoped [Playback] connection and the shared [MaApiClient] so they outlive the Activity. */
class SendpinApp : Application(), ImageLoaderFactory {
    val playback: Playback by lazy { Playback(this) }

    /**
     * Shared Music Assistant API client — one WebSocket, one auth handshake, one
     * reconnection loop for the entire app. ViewModels that need library browse /
     * queue / transport use this instead of opening their own connection.
     */
    val maApi: MaApiClient by lazy { MaApiClient() }

    /**
     * The standalone queue player (Navidrome-direct and offline downloads). Shared
     * process-wide: the library fills it, Now Playing reflects and drives it, and
     * [LocalPlaybackService] puts it in the notification shade. A per-ViewModel
     * instance meant the phone could be playing something no other screen knew
     * about — and that nothing but the Library tab could pause.
     */
    val localPlayer: LocalPlayer by lazy { LocalPlayer(this) }

    /**
     * The phone's media volume. Process-scoped so the observer is registered once
     * and every screen reads the same live value.
     */
    val deviceVolume: com.engabd.sendpin.audio.DeviceVolume by lazy {
        com.engabd.sendpin.audio.DeviceVolume(this)
    }

    /** The offline download index, shared for the same reason. */
    val downloads: DownloadManager by lazy { DownloadManager(this) }

    /**
     * Whichever backend is actually producing sound right now, for
     * [directLightSync] — MA (via `SendspinExoEngine`, the experimental
     * ExoPlayer path) when it has a tap installed *and* is playing, else
     * [localPlayer] always. That "else" is what keeps local playback's Light
     * Sync behaviour identical to before MA support existed: with the
     * ExoPlayer toggle off, or with nothing playing on MA, this is exactly
     * localPlayer's tap/lead/track — the same fixed values [directLightSync]
     * used to take as constructor arguments.
     *
     * `Eagerly`, not `Lazily`: [directLightSync] reads `.value` synchronously
     * in a few places (see its own docs), so this has to have emitted before
     * anything can call [directLightSync.start][com.engabd.sendpin.hue.DirectLightSync.start].
     */
    val activeLightSyncSource: StateFlow<com.engabd.sendpin.hue.ActiveLightSyncSource> by lazy {
        combine(
            localPlayer.current, playback.isPlaying, playback.artworkUrl, playback.maAudioSource,
        ) { localTrack, maPlaying, maArtUrl, maSource ->
            if (maPlaying && maSource != null) {
                com.engabd.sendpin.hue.ActiveLightSyncSource(
                    tap = maSource.first, lead = maSource.second, artUrl = maArtUrl, scanTrack = null,
                )
            } else {
                com.engabd.sendpin.hue.ActiveLightSyncSource(
                    tap = localPlayer.audioAnalysisTap, lead = localPlayer.audioLead,
                    artUrl = localTrack?.artUrl, scanTrack = localTrack,
                )
            }
        }.stateIn(
            appScope, SharingStarted.Eagerly,
            com.engabd.sendpin.hue.ActiveLightSyncSource(
                tap = localPlayer.audioAnalysisTap, lead = localPlayer.audioLead,
                artUrl = localPlayer.current.value?.artUrl, scanTrack = localPlayer.current.value,
            ),
        )
    }

    /**
     * Direct Hue Bridge Light Sync. Process-scoped so it survives Activity
     * destruction and switches between [localPlayer] and MA's audio tap via
     * [activeLightSyncSource]. Only active when the user has configured a
     * bridge and selected "direct" mode in Settings. When "ha" mode is
     * selected (or no bridge is configured), this stays idle and the existing
     * HA-based path handles Light Sync.
     */
    val directLightSync: com.engabd.sendpin.hue.DirectLightSync by lazy {
        com.engabd.sendpin.hue.DirectLightSync(
            this,
            activeLightSyncSource,
            trackScans,
            isPlaying = combine(localPlayer.playing, playback.isPlaying) { l, m -> l || m },
        )
    }

    /**
     * Offline track analyses for Light Sync.
     *
     * Process-scoped and independent of [directLightSync], because pre-analysing
     * a library is something you do before the lights are on — with nothing
     * playing and no bridge connected — and because a scan wanted by the show is
     * worth keeping long after that particular show ended.
     */
    val trackScans: com.engabd.sendpin.audio.TrackScanRepository by lazy {
        com.engabd.sendpin.audio.TrackScanRepository(this)
    }

    /**
     * What the *selected* Music Assistant player is playing, at process scope.
     *
     * The media notification has to outlive the Activity, and the Now Playing screen's
     * ViewModel does not — which is why the shade used to claim the phone was merely
     * "ready for announcements" while a speaker was playing an album.
     */
    val maNowPlaying: MaNowPlaying by lazy { MaNowPlaying(this) }

    /**
     * The library the app browses and plays itself — Navidrome, Jellyfin, or whatever
     * else is added next. Null while Music Assistant is the active library, or before
     * anything has connected.
     *
     * Process-scoped and published by [com.engabd.sendpin.ma.LibraryViewModel], which
     * owns the connect lifecycle. It lives here because four view models need it and
     * each used to build *its own client* from the saved credentials — four connection
     * pools against one server, four separate ideas of whether it was reachable, and
     * four places to edit when a second kind of server arrived.
     *
     * A plain holder rather than a manager: connecting is a stateful business with
     * retries, superseded attempts and an offline fallback, and that logic already
     * exists in one careful place. This is only how everything else finds the result.
     */
    val musicSource = MutableStateFlow<com.engabd.sendpin.library.MusicSource?>(null)

    private val appScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Before any client is built: [Http.base] is lazy, and a cache installed after
        // the first request would be attached to a client nobody is using any more.
        Http.initCache(cacheDir)
        // Catch and store crashes locally before anything else starts. The previous
        // handler is chained so the app still terminates as the platform expects.
        CrashReporter.install(this)
        // Register the process lifecycle observer for warm reconnect and
        // toggleable background connection (TTS battery saver).
        AppLifecycleObserver.register(this)
        // The storage cap evicts oldest-first, and the one file it must never take is
        // the one being listened to. Published here rather than looked up inside the
        // download manager, which has no business holding a reference to the player.
        appScope.launch {
            localPlayer.current.collect { downloads.protectedId = it?.id }
        }
        // When the app is crashing and we already know about it, optionally open a
        // GitHub issue automatically if the user has supplied a PAT. Do this in a
        // fire-and-forget IO coroutine; we must not block the crash path.
        appScope.launch {
            val settings = AppSettings(this@SendpinApp)
            if (settings.crashAutoUpload.first()) {
                val token = settings.crashGitHubToken.first()
                val repo = settings.crashGitHubRepo.first()
                if (token.isNotBlank() && repo.isNotBlank()) {
                    CrashReporter.lastUnreported()?.let { report ->
                        CrashReporter.postToGitHub(repo, token, report).onSuccess {
                            CrashReporter.markLastReported()
                        }
                    }
                }
            }
        }
        // The media notification follows the local player's session rather than being
        // started by whichever screen happened to press play.
        //
        // Ending the session holds the notification for a grace period first: a queue
        // rolling between tracks briefly clears it, and retiring the notification on
        // that made it blink on every skip. A real stop still costs the wait, which is
        // the right trade — a notification that lingers a minute is a much smaller
        // annoyance than one that flickers on every track.
        appScope.launch {
            var retire: Job? = null
            localPlayer.active.collect { active ->
                retire?.cancel(); retire = null
                if (active) {
                    LocalPlaybackService.start(this@SendpinApp)
                } else {
                    retire = appScope.launch {
                        delay(SendspinService.IDLE_GRACE_MS)
                        LocalPlaybackService.stop(this@SendpinApp)
                    }
                }
            }
        }
        // One player at a time, enforced rather than assumed.
        //
        // Switching library backend already stops the local player, so that
        // direction was covered. The reverse was not: starting a Navidrome track
        // while a Music Assistant queue was playing *to this phone* left both
        // decoding into the same output — two songs at once, and two media
        // notifications arguing over the lock screen. The manifest asserts the two
        // "are never both playing"; this is what makes that true.
        //
        // Keyed on the local player becoming active rather than on every state
        // change, so a pause on the local side does not resume the remote one.
        // (`playing` is a StateFlow, which already conflates equal values.)
        appScope.launch {
            localPlayer.playing.collect { playing -> if (playing) playback.pauseForLocalPlayback() }
        }
        // Light Sync transport follows the library, because the library is what
        // decides where the audio actually comes out. Navidrome always plays
        // through this phone's own player, which the direct bridge path taps;
        // Music Assistant plays to whatever speaker is targeted, which is Home
        // Assistant's business — including when that speaker is this phone over
        // Sendspin, a path the tap cannot see because it bypasses ExoPlayer.
        //
        // Here rather than in a ViewModel: this is process-scoped, outlives every
        // screen, and sits downstream of whoever writes the backend, so it
        // catches the Settings toggle, a boot restore and any future writer
        // alike. LibraryViewModel would be the wrong place twice over — it is
        // Activity-scoped, and it is itself a follower of the same setting, so
        // writing from there would race the screen that wrote it.
        appScope.launch {
            val settings = com.engabd.sendpin.data.AppSettings(this@SendpinApp)
            combine(settings.backend, settings.lightSyncModeAuto) { backend, auto -> backend to auto }
                .distinctUntilChanged()
                .collect { (backend, auto) ->
                    val next = com.engabd.sendpin.data.AppSettings.lightSyncModeChange(
                        backend, auto, settings.lightSyncMode.first(),
                    ) ?: return@collect

                    // Written first, and that ordering is the whole fix.
                    //
                    // The Home Assistant cleanup below used to run before this
                    // line. It opens a WebSocket and makes three registry calls
                    // with a fifteen-second timeout each, on a socket that has
                    // not finished authenticating — so switching library could
                    // take the better part of a minute to visibly change
                    // transport, and against an HA that was asleep or gone it
                    // looked like the switch simply did nothing. The transport is
                    // the user-visible state and everything else keys off it; it
                    // cannot wait on a round trip to the server being left behind.
                    settings.setLightSyncMode(next)

                    // Leaving the Home Assistant transport has to switch its
                    // areas off. Nothing else will: the Light Sync ViewModel only
                    // closes its socket when the screen goes away, so an area
                    // would stay enabled in HA, still following a player that has
                    // stopped, with no UI left to turn it off from.
                    //
                    // Off the collector as well as after the write, so a second
                    // library switch is not queued behind the first one's
                    // housekeeping.
                    if (next == com.engabd.sendpin.data.AppSettings.MODE_DIRECT) {
                        appScope.launch { disableHaLightSync() }
                    }
                }
        }
        // Direct Light Sync follows whichever of this phone's players has a live
        // session. Direct mode streams this phone's own decoded audio, and there are
        // two sources of that: the local player, and Music Assistant when MA is
        // playing *to this phone* through the ExoPlayer engine, whose render chain
        // carries the same analysis tap.
        //
        // The MA half was missing here, and it was the whole of "the lights never
        // react to Music Assistant". Two of the three wirings already accounted for
        // it — [activeLightSyncSource] switches between the two taps correctly, and
        // [directLightSync]'s own `isPlaying` counts both — but this gate, the one
        // that decides whether to open the bridge stream at all, still asked only
        // about the local player. So with MA playing, `start()` was never called:
        // the screen sat on "Waiting for this phone to play" with a perfectly good
        // tap going unused. This comment used to say "there is exactly one thing
        // that can drive it", which was true when it was written and stopped being
        // true when MA playback was wired into the tap.
        //
        // `maAudioSource != null`, not `isPlaying` alone: the default
        // SendspinAudioEngine has no tap at all, so opening the bridge for it would
        // leave the room reacting to the *local* player's silent tap — worse than
        // honestly reporting that nothing is playing. That is the same condition
        // [activeLightSyncSource] uses to choose a source, so the two cannot
        // disagree about what is actually drivable.
        //
        // The session rather than `playing`: a pause would otherwise tear down the
        // DTLS channel and re-run discovery and the handshake on every resume. The
        // keepalive holds the last frame while the music is stopped, which is what
        // it is for.
        appScope.launch {
            val settings = com.engabd.sendpin.data.AppSettings(this@SendpinApp)
            // `started` keeps the lazy singleton lazy: a user on the Home Assistant
            // path never touches direct mode, so there is no reason to build its
            // bridge client and settings collectors just to stop something that
            // was never running.
            var started = false
            data class DirectSyncState(val active: Boolean, val mode: String, val enabled: Boolean, val configId: String)
            // MA counts as live only when its engine actually carries a tap — see above.
            //
            // Keyed on having a *track loaded*, not on `isPlaying`, which is the MA
            // equivalent of `localPlayer.active` rather than `localPlayer.playing` — the
            // same distinction the comment above draws, and getting it wrong here is
            // visible in the room. On `isPlaying` the bridge was torn down about two
            // seconds after a pause (`END_LINGER_MS`), so the lights snapped back to
            // their normal state instead of easing into the ambient idle show, which
            // only begins fading in after four seconds of quiet. Navidrome kept the show
            // because a paused local player still has a session; MA lost it because a
            // paused MA player is simply "not playing".
            val maLive = combine(playback.trackTitle, playback.maAudioSource) { title, source ->
                title.isNotBlank() && source != null
            }
            combine(
                combine(localPlayer.active, maLive) { local, ma -> local || ma },
                settings.lightSyncMode,
                settings.lightSyncEnabled,
                settings.hueEntertainmentConfigId,
            ) { active, mode, enabled, configId -> DirectSyncState(active, mode, enabled, configId) }
                .distinctUntilChanged()
                .collect { state ->
                    val shouldRun = state.active && state.mode == "direct" && state.enabled
                    // configId is part of what `distinctUntilChanged` watches but not of
                    // `shouldRun` — a mid-session area change needs the bridge stream
                    // restarted on the new area's channels, which calling `start()` on
                    // top of an already-running session won't do (it is a no-op while
                    // `running` is true). Stopping first also covers the switch-on that
                    // follows a `start()` which earlier bailed out for want of a
                    // configured area — enabling the toggle before picking an area used
                    // to leave the session permanently stuck in that failed state.
                    //
                    // The ordering here is load-bearing on an area change, and it holds
                    // because `stop()` suspends until the bridge has been told to stop.
                    // While teardown was fire-and-forget, its `action:stop` PUT could
                    // land after the new area's `action:start` and close the session
                    // that had just been opened.
                    if (shouldRun) {
                        if (started) directLightSync.stop()
                        started = true
                        directLightSync.start()
                    } else if (started) {
                        started = false
                        directLightSync.stop()
                    }
                }
        }
        // Analyse what is playing, and what is about to.
        //
        // Here rather than inside Light Sync, and running whether or not the
        // bridge is connected, because a scan outlives the session that wanted
        // it: listening this evening is what makes the lights right at the
        // weekend. The next queue item matters more than it looks — a scan is
        // only adopted if it is ready in the first few seconds of a track (see
        // DirectLightSync), so scanning ahead is the difference between a queue
        // that is exact from the second song and one that is exact from the
        // second *listen*.
        appScope.launch {
            combine(localPlayer.current, localPlayer.queue, localPlayer.index) { current, queue, at ->
                current to queue.getOrNull(at + 1)
            }
                .distinctUntilChanged()
                .collect { (current, next) ->
                    current?.let { trackScans.request(it, urgent = true) }
                    next?.let { trackScans.request(it) }
                }
        }
        // A remote speaker playing is as much a reason for a media notification as
        // this phone's own stream is — it is the one the user is actually listening
        // to. `idleMedia` rather than `stopMedia` so the same grace period covers the
        // gap between tracks; `MaNowPlaying` nulls itself out while the local player
        // owns the phone, so this and LocalPlaybackService can't both post.
        appScope.launch {
            maNowPlaying.now
                .map { it != null && it.title.isNotBlank() }
                .distinctUntilChanged()
                .collect { active ->
                    // Starting a foreground service from the background is restricted
                    // on Android 12+. SendspinConnectionService is normally already up
                    // and exempts this, but a refusal must not take the process down.
                    runCatching {
                        if (active) SendspinService.startMedia(this@SendpinApp)
                        else SendspinService.idleMedia(this@SendpinApp)
                    }
                }
        }
    }

    /**
     * Album art is the loudest thing in this app, so quality is not negotiable:
     * covers decode at full ARGB_8888 (Coil's RGB_565 fallback bands badly on the
     * smooth gradients these covers are full of) and cache generously on disk, so
     * a re-opened album is instant and doesn't re-fetch through the MA proxy.
     *
     * Three interceptors sit under it:
     *  - **auth** — the MA bearer token, for servers that gate the proxy;
     *  - **imageproxy resolution** — see [imageProxyCandidates]; Music Assistant has
     *    moved both the *host* and the *shape* of its image proxy between releases,
     *    so the first cover probes the known forms and the winner is reused;
     *  - **cache hardening** — MA answers the proxy with no cache headers at all, so
     *    Coil re-fetched every cover on every scroll. They are content-addressed
     *    URLs, so a long max-age is safe and the library stops flickering.
     */
    /**
     * Switch every Home Assistant light-sync area off.
     *
     * Called when the transport moves away from Home Assistant. Opens a
     * short-lived connection of its own because the Light Sync ViewModel — the
     * only other thing that talks to HA — is gone by the time this matters, and
     * an area left enabled keeps following a player that has stopped.
     *
     * Best effort. No Home Assistant configured, or one that cannot be reached,
     * is not a reason to block the library switch.
     */
    private suspend fun disableHaLightSync() = withContext(Dispatchers.IO) {
        val settings = com.engabd.sendpin.data.AppSettings(this@SendpinApp)
        val url = settings.haUrl.first()
        val token = settings.haToken.first()
        if (url.isBlank() || token.isBlank()) return@withContext
        val client = com.engabd.sendpin.ha.HaClient()
        try {
            client.connect(url, token)
            val repo = com.engabd.sendpin.ha.LightSyncRepository(client)
            for (area in repo.discover()) {
                if (area.enabled) repo.setEnabled(area, false)
            }
        } catch (e: Exception) {
            android.util.Log.w("SendpinApp", "Could not switch off HA light sync: ${e.message}")
        } finally {
            client.disconnect()
        }
    }

    override fun newImageLoader(): ImageLoader {
        val authInterceptor = Interceptor { chain ->
            val req = chain.request()
            val token = maApi.authToken
            // Only add auth to imageproxy requests that go to the MA server.
            if (token != null && req.url.encodedPath.contains("/imageproxy")) {
                chain.proceed(req.newBuilder().addHeader("Authorization", "Bearer $token").build())
            } else {
                chain.proceed(req)
            }
        }
        val proxyInterceptor = Interceptor { chain -> resolveImageProxy(chain) }
        val cacheInterceptor = Interceptor { chain ->
            val res = chain.proceed(chain.request())
            if (res.isSuccessful && chain.request().url.encodedPath.contains("/imageproxy")) {
                res.newBuilder()
                    .removeHeader("Pragma")
                    .header("Cache-Control", "public, max-age=$ART_MAX_AGE_SEC")
                    .build()
            } else res
        }
        // Derived from the shared client so covers share its connection pool with the
        // API calls to the same host — they are almost always the same server, and two
        // pools meant two TLS handshakes and two sets of idle sockets to it.
        val http = Http.base.newBuilder()
            .addInterceptor(authInterceptor)
            .addInterceptor(proxyInterceptor)
            .addNetworkInterceptor(cacheInterceptor)
            // Coil keeps its own disk cache for the decoded bytes; the shared HTTP
            // cache would store a second copy of every cover.
            .cache(null)
            .build()
        return ImageLoader.Builder(this)
            .bitmapConfig(Bitmap.Config.ARGB_8888)
            .allowRgb565(false)
            .crossfade(true)
            .memoryCache { MemoryCache.Builder(this).maxSizePercent(0.25).build() }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("art"))
                    .maxSizeBytes(256L * 1024 * 1024)
                    .build()
            }
            .okHttpClient(http)
            .build()
    }

    /** Which of [imageProxyCandidates] this server answered; -1 until one does. */
    private val imageProxyVariant = AtomicInteger(-1)

    /**
     * Try the candidate proxy URLs until the server answers, then stick with it.
     *
     * Nothing else in the app can know which shape a given Music Assistant speaks:
     * the proxy lives on the *stream* server (a different port from the API) in the
     * 2.x line, and newer builds replaced the `?path=&provider=` query with an
     * opaque `sha256("<provider>/<path>")` path segment. Probing costs one round of
     * connection refusals on the first cover of a session and nothing afterwards.
     */
    private fun resolveImageProxy(chain: Interceptor.Chain): Response {
        val req = chain.request()
        if (!req.url.encodedPath.contains("/imageproxy")) return chain.proceed(req)
        val candidates = imageProxyCandidates(req.url)
        if (candidates.isEmpty()) return chain.proceed(req)

        val known = imageProxyVariant.get()
        val order = if (known in candidates.indices) {
            listOf(known) + candidates.indices.filter { it != known }
        } else {
            candidates.indices.toList()
        }

        var lastResponse: Response? = null
        var lastError: IOException? = null
        for ((attempt, i) in order.withIndex()) {
            lastResponse?.close()
            lastResponse = null
            try {
                val res = chain.proceed(req.newBuilder().url(candidates[i]).build())
                if (res.isSuccessful) {
                    imageProxyVariant.set(i)
                    return res
                }
                lastResponse = res
                // A shape that is known to work and merely has no such image is a
                // plain miss — don't re-probe every other shape for every cover.
                if (attempt == 0 && known == i) return res
            } catch (e: IOException) {
                lastError = e
            }
        }
        return lastResponse ?: throw (lastError ?: IOException("image proxy unreachable"))
    }

    /**
     * Every image-proxy URL shape Music Assistant has served, most likely first.
     *
     * The app builds the 2.x query form against the API base (see
     * `MaParse.imageProxyUrl`); from that one URL both the raw path and the provider
     * can be recovered, which is everything the other shapes need.
     */
    private fun imageProxyCandidates(url: HttpUrl): List<HttpUrl> {
        // Already once-decoded by OkHttp; MA encodes the path twice.
        val once = url.queryParameter("path") ?: return emptyList()
        val provider = url.queryParameter("provider") ?: "builtin"
        val size = url.queryParameter("size") ?: "0"
        val fmt = url.queryParameter("fmt") ?: "png"
        val raw = try { URLDecoder.decode(once, "UTF-8") } catch (_: Exception) { once }
        val id = sha256("$provider/$raw")
        // The stream server is where the 2.x proxy lives; the API port is the
        // fallback for add-on/reverse-proxy setups that put both behind one host.
        val ports = linkedSetOf(MA_STREAM_PORT, url.port)

        return buildList {
            // 1. The query form, path double-encoded — MA 2.x.
            for (port in ports) add(url.newBuilder().port(port).build())
            // 2. The opaque id form — recent MA.
            for (port in ports) {
                add(
                    HttpUrl.Builder()
                        .scheme(url.scheme).host(url.host).port(port)
                        .addPathSegment("imageproxy").addPathSegment(id)
                        .addQueryParameter("size", size).addQueryParameter("fmt", fmt)
                        .build()
                )
            }
            // 3. The query form with a singly-encoded path — older servers that
            //    unquote once rather than twice.
            for (port in ports) {
                add(url.newBuilder().port(port).setQueryParameter("path", raw).build())
            }
        }
    }

    private fun sha256(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }

    companion object {
        lateinit var instance: SendpinApp
            private set

        /** Music Assistant's default stream-server port, where the proxy lives. */
        private const val MA_STREAM_PORT = 8097

        /** Cover URLs are content-addressed, so a week is conservative. */
        private const val ART_MAX_AGE_SEC = 7 * 24 * 60 * 60
    }
}
