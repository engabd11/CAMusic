package com.engabd.sendpin

import android.app.Application
import android.graphics.Bitmap
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.engabd.sendpin.audio.LocalPlayer
import com.engabd.sendpin.download.DownloadManager
import com.engabd.sendpin.ma.MaApiClient
import com.engabd.sendpin.service.AppLifecycleObserver
import com.engabd.sendpin.service.LocalPlaybackService
import com.engabd.sendpin.service.MaNowPlaying
import com.engabd.sendpin.service.Playback
import com.engabd.sendpin.service.SendspinService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okhttp3.HttpUrl
import okhttp3.Interceptor
import com.engabd.sendpin.data.LanOnlyCleartext
import okhttp3.OkHttpClient
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
     * Direct Hue Bridge Light Sync. Process-scoped so it survives Activity
     * destruction and shares the audio tap with [localPlayer]. Only active
     * when the user has configured a bridge and selected "direct" mode in
     * Settings. When "ha" mode is selected (or no bridge is configured),
     * this stays idle and the existing HA-based path handles Light Sync.
     */
    val directLightSync: com.engabd.sendpin.hue.DirectLightSync by lazy {
        com.engabd.sendpin.hue.DirectLightSync(this)
    }

    /**
     * What the *selected* Music Assistant player is playing, at process scope.
     *
     * The media notification has to outlive the Activity, and the Now Playing screen's
     * ViewModel does not — which is why the shade used to claim the phone was merely
     * "ready for announcements" while a speaker was playing an album.
     */
    val maNowPlaying: MaNowPlaying by lazy { MaNowPlaying(this) }

    private val appScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Register the process lifecycle observer for warm reconnect and
        // toggleable background connection (TTS battery saver).
        AppLifecycleObserver.register(this)
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
        val http = OkHttpClient.Builder()
        .addInterceptor(LanOnlyCleartext)
            .addInterceptor(authInterceptor)
            .addInterceptor(proxyInterceptor)
            .addNetworkInterceptor(cacheInterceptor)
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
