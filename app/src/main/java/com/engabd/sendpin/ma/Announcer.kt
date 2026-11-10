package com.engabd.sendpin.ma

import android.content.Context
import android.media.MediaRecorder
import android.os.SystemClock
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.engabd.sendpin.util.runCatchingCancellable
import java.io.File
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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
    /** What the announce sheet shows under its controls. [failed] tints it as a warning. */
    data class Status(val text: String, val failed: Boolean = false)

    /** Who to announce to (player id to display name), with the chime and a 0..100 volume. */
    class Request(val players: Map<String, String>, val chime: Boolean, val volume: Int)

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
            try {
                produce(file)
                val host = withContext(Dispatchers.IO) { addressToward(maBaseUrl()) }
                    ?: throw IllegalStateException(
                        "Couldn't find this phone's address on the network Music Assistant is on. " +
                            "Connect to the same Wi-Fi as the server.",
                    )
                // One clip on offer at a time; a newer announcement replaces an older one.
                server?.stop()
                srv = AnnouncementServer(host, file, contentType)
                srv.begin()
                server = srv
                val url = srv.url
                val results = request.players.map { (id, name) ->
                    async { name to runCatchingCancellable { repo.playAnnouncement(id, url, request.chime, request.volume, timeoutMs = CALL_TIMEOUT_MS) }.exceptionOrNull() }
                }.awaitAll()
                _status.value = summary(results)
                // On success it stays up until its timer: a speaker may still be fetching.
                if (results.all { it.second != null }) srv.stop()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                srv?.stop()
                _status.value = Status(e.message ?: "Couldn't send the announcement", failed = true)
            } finally {
                // Never served: nothing else will delete it.
                if (srv == null) file.delete()
                _busy.value = false
            }
        }
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

        /**
         * This phone's IPv4 address on its route to the Music Assistant server: the one
         * address the server can be expected to call back. Blocking (it may resolve DNS).
         */
        fun addressToward(maUrl: String): String? {
            val host = maUrl.toHttpUrlOrNull()?.host ?: return null
            return runCatching {
                val target = InetAddress.getAllByName(host).first { it is Inet4Address }
                // A UDP connect sends nothing; it only asks the system which interface would carry it.
                DatagramSocket().use { s ->
                    s.connect(target, 9)
                    (s.localAddress as? Inet4Address)?.takeUnless { it.isAnyLocalAddress }?.hostAddress
                }
            }.getOrNull()
        }

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
