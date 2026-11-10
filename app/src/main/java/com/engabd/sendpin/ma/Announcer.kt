package com.engabd.sendpin.ma

import android.content.Context
import android.media.MediaRecorder
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.engabd.sendpin.util.runCatchingCancellable
import java.io.File
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Announcements through Music Assistant: typed (spoken by Android's text-to-speech)
 * or recorded on the phone's microphone, played on the chosen speakers over whatever
 * they are playing, which MA then resumes.
 *
 * One pipeline for both: make a clip in the cache, serve it from [AnnouncementServer],
 * and ask MA to play that URL on each speaker. Nothing leaves the local network and
 * no cloud speech service is involved.
 *
 * The clip formats are chosen for MA, which reads the URL with ffmpeg over plain HTTP:
 * WAV from text-to-speech and raw AAC (ADTS) from the microphone. Both stream from the
 * first byte. An `.m4a` would not: MediaRecorder writes its index at the end, which
 * ffmpeg cannot reach without range requests.
 */
class Announcer(
    private val app: Context,
    private val repo: MaRepository,
    private val scope: CoroutineScope,
    private val maBaseUrl: suspend () -> String,
) {
    /** What the announce dialog shows under its controls. [failed] tints it as a warning. */
    data class Status(val text: String, val failed: Boolean = false)

    /** One of this phone's IPv4 addresses, with its network's prefix length. */
    data class LocalAddress(val ip: String, val prefix: Int)

    /** Who to announce to (player id to display name), with the chime and a 0..100 volume (null: MA's own). */
    class Request(val players: Map<String, String>, val chime: Boolean, val volume: Int?)

    private val _status = MutableStateFlow<Status?>(null); val status: StateFlow<Status?> = _status.asStateFlow()
    private val _busy = MutableStateFlow(false); val busy: StateFlow<Boolean> = _busy.asStateFlow()
    private val _recording = MutableStateFlow(false); val recording: StateFlow<Boolean> = _recording.asStateFlow()

    private var recorder: MediaRecorder? = null
    private var clip: File? = null
    private var pending: Request? = null
    private var recordStartedAt = 0L
    private var server: AnnouncementServer? = null

    fun speak(text: String, request: Request) {
        val words = text.trim()
        if (_busy.value || words.isEmpty() || request.players.isEmpty()) return
        if (words.length > TextToSpeech.getMaxSpeechInputLength()) {
            _status.value = Status("That is too long to announce. Keep it to a few sentences.", failed = true)
            return
        }
        send(request, newClip(".wav"), "audio/wav") { synthesize(words, it) }
    }

    /** Start the microphone. The caller has already been granted RECORD_AUDIO. */
    fun startRecording(request: Request) {
        if (_busy.value || recorder != null || request.players.isEmpty()) return
        val file = newClip(".aac")
        val r = MediaRecorder(app)
        try {
            r.setAudioSource(MediaRecorder.AudioSource.MIC)
            r.setOutputFormat(MediaRecorder.OutputFormat.AAC_ADTS)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioChannels(1)
            r.setAudioSamplingRate(44_100)
            r.setAudioEncodingBitRate(96_000)
            r.setMaxDuration(MAX_RECORDING_MS)
            r.setOutputFile(file)
            // Reaching the cap sends what was said, as letting go would.
            r.setOnInfoListener { _, what, _ ->
                if (what == MediaRecorder.MEDIA_RECORDER_INFO_MAX_DURATION_REACHED) stopRecording()
            }
            r.prepare()
            r.start()
        } catch (e: Exception) {
            r.release()
            file.delete()
            _status.value = Status("Couldn't use the microphone: ${e.message ?: "it may be in use by another app"}", failed = true)
            return
        }
        recorder = r; clip = file; pending = request
        recordStartedAt = SystemClock.elapsedRealtime()
        _recording.value = true
        _status.value = null
    }

    /** Stop the microphone and, if [deliver] and something was said, announce it. */
    fun stopRecording(deliver: Boolean = true) {
        val r = recorder ?: return
        val file = clip ?: return
        val request = pending ?: return
        recorder = null; clip = null; pending = null
        _recording.value = false
        // Throws when almost nothing was captured, and after the duration cap has
        // already stopped it. ADTS needs no closing index, so the file stands either way.
        runCatching { r.stop() }
        r.release()
        val long = SystemClock.elapsedRealtime() - recordStartedAt >= MIN_RECORDING_MS
        if (!deliver || !long || file.length() == 0L) {
            file.delete()
            if (deliver) _status.value = Status("Hold the button while you speak, then let go to send.", failed = true)
            return
        }
        send(request, file, "audio/aac")
    }

    /** The screen is going. A clip already handed to MA is left to its server's own timer. */
    fun close() = stopRecording(deliver = false)

    private fun newClip(suffix: String) = File.createTempFile("announce", suffix, app.cacheDir)

    private fun send(request: Request, file: File, contentType: String, produce: suspend (File) -> Unit = {}) {
        _busy.value = true
        _status.value = Status("Announcing…")
        scope.launch {
            var srv: AnnouncementServer? = null
            // Once Music Assistant has the clip, its server keeps it until its own timer.
            var handedOver = false
            try {
                produce(file)
                val hosts = withContext(Dispatchers.IO) { candidatesToward(maBaseUrl()) }
                if (hosts.isEmpty()) {
                    throw IllegalStateException(
                        "Couldn't find this phone's address on the network. Connect to the same Wi-Fi as Music Assistant.",
                    )
                }
                // One clip on offer at a time; a newer announcement replaces an older one.
                server?.stop()
                server = null
                for (host in hosts) {
                    srv = AnnouncementServer(host, file, contentType)
                    srv.begin()
                    server = srv
                    val results = attempt(srv, request)
                    if (results != null) {
                        _status.value = summary(results)
                        // A speaker may still be fetching; if none took it, nothing will.
                        handedOver = results.any { it.second == null }
                        return@launch
                    }
                    // Never fetched from this address: the next one replays the chime, so it is tried only now.
                    srv.stopKeepingClip()
                    server = null
                }
                _status.value = Status(
                    "Music Assistant couldn't reach this phone to fetch the announcement " +
                        "(tried ${hosts.joinToString()}). A VPN on the phone can cause this: pause it and try again.",
                    failed = true,
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                _status.value = Status(e.message ?: "Couldn't send the announcement", failed = true)
            } finally {
                if (!handedOver) {
                    srv?.stopKeepingClip()
                    if (server === srv) server = null
                    file.delete()
                }
                _busy.value = false
            }
        }
    }

    /**
     * Ask MA to play [srv]'s clip on every speaker. The results, once MA has fetched the
     * clip or refused every speaker outright; null when it never came for it, which is
     * MA failing to reach this address (it still plays the chime and answers success).
     */
    private suspend fun attempt(srv: AnnouncementServer, request: Request): List<Pair<String, Throwable?>>? = coroutineScope {
        val calls = async {
            request.players.map { (id, name) ->
                async {
                    name to runCatchingCancellable {
                        repo.playAnnouncement(id, srv.url, request.chime, request.volume, timeoutMs = CALL_TIMEOUT_MS)
                    }.exceptionOrNull()
                }
            }.awaitAll()
        }
        val fetched = withTimeoutOrNull(FETCH_WAIT_MS) {
            select<Boolean> {
                srv.fetched.onAwait { true }
                calls.onAwait { srv.fetched.isCompleted }
            }
        } ?: false
        when {
            fetched -> {
                _status.value = Status("Playing the announcement…")
                calls.await()
            }
            // Refused before fetching anything (speaker off, unknown): another address won't help.
            calls.isCompleted && calls.await().all { it.second != null } -> calls.await()
            else -> { calls.cancel(); null }
        }
    }

    /**
     * Where MA might reach this phone, best first. Wi-Fi and Ethernet are read from
     * every network, not the default one: a VPN made the default hides them from the
     * route lookup, and MA on the LAN cannot reach a VPN address. Blocking.
     */
    private fun candidatesToward(maUrl: String): List<String> {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        @Suppress("DEPRECATION") // allNetworks: the one call that lists non-default networks, as PartyManager uses it.
        val lan = cm?.allNetworks.orEmpty().flatMap { network ->
            val caps = cm?.getNetworkCapabilities(network)
            val local = caps != null && !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
            if (!local) return@flatMap emptyList()
            cm?.getLinkProperties(network)?.linkAddresses.orEmpty().mapNotNull { la ->
                val a = la.address as? Inet4Address ?: return@mapNotNull null
                if (a.isLoopbackAddress || a.isLinkLocalAddress) null else LocalAddress(a.hostAddress ?: return@mapNotNull null, la.prefixLength)
            }
        }
        val ma = maAddress(maUrl)
        return orderCandidates(lan, ma?.let(::routeToward), ma?.hostAddress)
    }

    /** Android text-to-speech into [out] as WAV, or a message saying why not. */
    private suspend fun synthesize(text: String, out: File) {
        val ready = CompletableDeferred<Int>()
        val tts = TextToSpeech(app) { ready.complete(it) }
        try {
            if (withTimeoutOrNull(TTS_TIMEOUT_MS) { ready.await() } != TextToSpeech.SUCCESS) {
                throw IllegalStateException("Text-to-speech isn't available on this phone. Install or turn on a speech engine in Android settings.")
            }
            val done = CompletableDeferred<Boolean>()
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { done.complete(true) }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) { done.complete(false) }
                override fun onError(utteranceId: String?, errorCode: Int) { done.complete(false) }
            })
            val queued = tts.synthesizeToFile(text, null, out, "announce") == TextToSpeech.SUCCESS
            if (!queued || withTimeoutOrNull(TTS_TIMEOUT_MS) { done.await() } != true || out.length() == 0L) {
                throw IllegalStateException("Text-to-speech couldn't turn that into speech. Try again, or check the speech engine in Android settings.")
            }
        } finally {
            tts.shutdown()
        }
    }

    companion object {
        const val MAX_RECORDING_MS = 30_000
        /** Shorter than this is a tap, not a message. */
        const val MIN_RECORDING_MS = 600L
        const val TTS_TIMEOUT_MS = 15_000L
        /** MA answers once the announcement has played, so allow the longest clip plus the chime. */
        const val CALL_TIMEOUT_MS = 90_000L

        /** How long MA gets to fetch the clip from one address. Its ffmpeg gives up after about 5 s. */
        const val FETCH_WAIT_MS = 7_000L

        /**
         * The addresses to offer MA the clip on, best first, without repeats. A retry
         * replays the chime, so the first guess matters: one in the MA server's own subnet
         * when there is one, then the Wi-Fi/Ethernet addresses, then [route], the address
         * the system would use toward the server (which a VPN can make its own).
         */
        fun orderCandidates(lan: List<LocalAddress>, route: LocalAddress?, maIp: String?): List<String> {
            val all = lan + listOfNotNull(route)
            val ma = maIp?.let(::ipv4Bits)
            val sameSubnet = all.filter { a -> ma != null && ipv4Bits(a.ip)?.let { sameNetwork(it, ma, a.prefix) } == true }
            return (sameSubnet + all).map { it.ip }.distinct()
        }

        private fun ipv4Bits(ip: String): Int? {
            val parts = ip.split('.').map { it.toIntOrNull() ?: return null }
            if (parts.size != 4 || parts.any { it !in 0..255 }) return null
            return parts.fold(0) { acc, b -> (acc shl 8) or b }
        }

        private fun sameNetwork(a: Int, b: Int, prefix: Int): Boolean {
            if (prefix !in 1..32) return false
            val mask = if (prefix == 32) -1 else (-1 shl (32 - prefix))
            return (a and mask) == (b and mask)
        }

        /** The MA server's IPv4 address, from its URL. Blocking (it may resolve DNS). */
        fun maAddress(maUrl: String): Inet4Address? {
            val host = maUrl.toHttpUrlOrNull()?.host ?: return null
            return runCatching { InetAddress.getAllByName(host).firstOrNull { it is Inet4Address } as Inet4Address? }.getOrNull()
        }

        /** This phone's IPv4 address on the system's route to [target], with its prefix. */
        fun routeToward(target: Inet4Address): LocalAddress? = runCatching {
            // A UDP connect sends nothing; it only asks the system which interface would carry it.
            val local = DatagramSocket().use { s ->
                s.connect(target, 9)
                (s.localAddress as? Inet4Address)?.takeUnless { it.isAnyLocalAddress }
            } ?: return null
            val prefix = NetworkInterface.getByInetAddress(local)?.interfaceAddresses
                ?.firstOrNull { it.address == local }?.networkPrefixLength?.toInt() ?: 32
            LocalAddress(local.hostAddress ?: return null, prefix)
        }.getOrNull()

        /** One line for how the announcement went, naming any speaker that refused it. */
        fun summary(results: List<Pair<String, Throwable?>>): Status {
            val played = results.filter { it.second == null }.map { it.first }
            val failed = results.filter { it.second != null }
            if (failed.isEmpty()) return Status("Announced on ${played.joinToString()}.")
            val why = failed.first().second?.message?.trimEnd('.') ?: "no reason given"
            val also = if (played.isEmpty()) "" else " Played on ${played.joinToString()}."
            return Status("Couldn't announce on ${failed.joinToString { it.first }}: $why.$also", failed = true)
        }
    }
}
