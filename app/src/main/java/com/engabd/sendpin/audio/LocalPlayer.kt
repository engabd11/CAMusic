package com.engabd.sendpin.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.os.Build
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * One track the local player can play. [localPath] wins over [streamUrl] whenever
 * it is present, so a downloaded track plays from storage even with the server up
 * — and still plays with the server gone.
 */
data class LocalTrack(
    val id: String,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long = 0,
    /** Cover art: a remote URL, or a `file://` path for a downloaded cover. */
    val artUrl: String? = null,
    val streamUrl: String? = null,
    val localPath: String? = null,
) {
    /**
     * There is a downloaded copy to play from. Deliberately *not* a `File.exists()`
     * check: this is read on every state emission and every notification rebuild,
     * and hitting the disk there put I/O on the main thread several times a second.
     * The one place the file has to really be there is [LocalPlayer.open], which
     * checks once and falls back to the stream.
     */
    val offline: Boolean get() = localPath != null
}

/**
 * The standalone player: a real queue on top of Android's [MediaPlayer], used when
 * there is no Music Assistant to route audio to — Navidrome-direct streaming and
 * offline playback of downloaded files.
 *
 * It is deliberately a *queue*, not a one-shot `play(url)`. Playing an album used
 * to mean playing its first track and stopping, which made the Navidrome backend
 * unusable for anything but auditioning single songs — and made "download for
 * offline" pointless, since the downloads could not be listened to as an album.
 *
 * Process-scoped (see `SendpinApp.localPlayer`): Now Playing, the library and the
 * media notification all drive the same instance.
 *
 * **Audio focus**: the local player requests focus on `USAGE_MEDIA` before playing
 * and abandons it when it stops, so it plays by the same rules as every other media
 * app on the phone — it gets out of the way for a call, and other players get out of
 * its way when it starts.
 *
 * Focus does *not* police the two backends against each other:
 * [com.engabd.sendpin.audio.SendspinAudioEngine] writes to its `AudioTrack` without
 * ever registering a focus listener, so Android has no way to tell it to stop.
 * Keeping MA and the local player off each other's toes is
 * [com.engabd.sendpin.ma.LibraryViewModel]'s job, and it does it by sending MA an
 * explicit stop.
 */
class LocalPlayer(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var mp: MediaPlayer? = null
    private var ticker: Job? = null

    /** True between `prepareAsync()` and `onPrepared`, when the player has no valid state. */
    @Volatile private var preparing = false

    // --- audio focus ------------------------------------------------------
    private val audioManager by lazy { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private var audioFocusRequest: AudioFocusRequest? = null
    @Volatile private var holdsFocus = false

    /**
     * Playback was stopped by a focus loss rather than by the user, so regaining
     * focus should start it again. Without this the music stays dead after a phone
     * call or a navigation prompt until someone taps play.
     */
    @Volatile private var pausedByFocusLoss = false

    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> mp?.setVolume(0.3f, 0.3f)
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> if (_playing.value) { pausedByFocusLoss = true; pause() }
            AudioManager.AUDIOFOCUS_GAIN -> {
                mp?.setVolume(1f, 1f)
                if (pausedByFocusLoss) { pausedByFocusLoss = false; resume() }
            }
            // A permanent loss pauses; it does not clear. The queue the user built
            // is not another app's to throw away, and they may come straight back
            // to it — [resume] re-claims focus when they do.
            AudioManager.AUDIOFOCUS_LOSS -> { pause(); abandonAudioFocus() }
        }
    }

    private fun requestAudioFocus(): Boolean {
        if (holdsFocus) return true
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener(focusListener)
                .build()
            audioFocusRequest = req
            audioManager.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        }.also { if (it) holdsFocus = true }
    }

    private fun abandonAudioFocus() {
        pausedByFocusLoss = false
        if (!holdsFocus) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            audioFocusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            audioFocusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusListener)
        }
        holdsFocus = false
    }

    private val _queue = MutableStateFlow<List<LocalTrack>>(emptyList())
    val queue: StateFlow<List<LocalTrack>> = _queue

    private val _index = MutableStateFlow(-1)
    val index: StateFlow<Int> = _index

    private val _current = MutableStateFlow<LocalTrack?>(null)
    val current: StateFlow<LocalTrack?> = _current

    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing

    private val _positionMs = MutableStateFlow(0L)
    val positionMs: StateFlow<Long> = _positionMs

    private val _durationMs = MutableStateFlow(0L)
    val durationMs: StateFlow<Long> = _durationMs

    private val _repeatMode = MutableStateFlow("off")   // off | one | all
    val repeatMode: StateFlow<String> = _repeatMode

    private val _shuffle = MutableStateFlow(false)
    val shuffle: StateFlow<Boolean> = _shuffle

    private val _speed = MutableStateFlow(1f)
    val speed: StateFlow<Float> = _speed

    private val _error = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** Playback failures worth telling the user about (a missing file, a dead server). */
    val errors: SharedFlow<String> = _error.asSharedFlow()

    private val _started = MutableSharedFlow<LocalTrack>(extraBufferCapacity = 4)
    /** Emitted each time a track actually begins — what scrobbling hangs off. */
    val started: SharedFlow<LocalTrack> = _started.asSharedFlow()

    /** Anything is loaded, so Now Playing should show this player rather than MA. */
    val active: StateFlow<Boolean> get() = _hasSession
    private val _hasSession = MutableStateFlow(false)

    /** The play order — identity, or a shuffled permutation of the queue's indices. */
    private var order: List<Int> = emptyList()

    val title: StateFlow<String> get() = _titleFlow
    private val _titleFlow = MutableStateFlow("")

    // --- queue ------------------------------------------------------------

    /** Replace the queue and start at [startIndex]. */
    fun setQueue(tracks: List<LocalTrack>, startIndex: Int = 0) {
        if (tracks.isEmpty()) { clear(); return }
        _queue.value = tracks
        rebuildOrder(keepCurrent = startIndex.coerceIn(0, tracks.lastIndex))
        _hasSession.value = true
        playAt(startIndex.coerceIn(0, tracks.lastIndex))
    }

    /** Append to the queue, starting playback if nothing is loaded. */
    fun addToQueue(tracks: List<LocalTrack>) {
        if (tracks.isEmpty()) return
        val wasEmpty = _queue.value.isEmpty()
        _queue.value = _queue.value + tracks
        rebuildOrder(keepCurrent = _index.value)
        if (wasEmpty) { _hasSession.value = true; playAt(0) }
    }

    /** Insert right after what's playing. */
    fun playNext(tracks: List<LocalTrack>) {
        if (tracks.isEmpty()) return
        if (_queue.value.isEmpty()) { setQueue(tracks); return }
        val at = (_index.value + 1).coerceIn(0, _queue.value.size)
        _queue.value = _queue.value.toMutableList().apply { addAll(at, tracks) }
        rebuildOrder(keepCurrent = _index.value)
    }

    fun removeAt(position: Int) {
        val list = _queue.value
        if (position !in list.indices) return
        val playingNow = _index.value
        _queue.value = list.toMutableList().apply { removeAt(position) }
        when {
            _queue.value.isEmpty() -> clear()
            position < playingNow -> { _index.value = playingNow - 1; rebuildOrder(keepCurrent = _index.value) }
            position == playingNow -> { rebuildOrder(keepCurrent = position.coerceAtMost(_queue.value.lastIndex)); playAt(position.coerceAtMost(_queue.value.lastIndex)) }
            else -> rebuildOrder(keepCurrent = _index.value)
        }
    }

    /**
     * Move the item at [from] by [shift] places. Whatever is playing keeps playing —
     * the play cursor follows the track, not the slot it used to be in.
     */
    fun move(from: Int, shift: Int) {
        val list = _queue.value.toMutableList()
        if (from !in list.indices) return
        val to = (from + shift).coerceIn(0, list.lastIndex)
        if (to == from) return
        val moved = list.removeAt(from)
        list.add(to, moved)
        val playing = _index.value
        _index.value = when {
            playing == from -> to
            from < playing && to >= playing -> playing - 1
            from > playing && to <= playing -> playing + 1
            else -> playing
        }
        _queue.value = list
        rebuildOrder(keepCurrent = _index.value)
    }

    fun clear() {
        stopInternal()
        abandonAudioFocus()
        _queue.value = emptyList()
        order = emptyList()
        _index.value = -1
        _current.value = null
        _titleFlow.value = ""
        _durationMs.value = 0
        _positionMs.value = 0
        _hasSession.value = false
    }

    // --- transport --------------------------------------------------------

    /** Play a single source directly — the "audition this one thing" path. */
    fun play(source: String, title: String = "") {
        setQueue(listOf(LocalTrack(id = source, title = title, streamUrl = source)))
    }

    fun playAt(position: Int) {
        val list = _queue.value
        if (position !in list.indices) return
        _index.value = position
        val track = list[position]
        _current.value = track
        _titleFlow.value = track.title
        _durationMs.value = track.durationMs
        _positionMs.value = 0
        open(track)
    }

    fun pause() {
        val p = mp ?: return
        if (preparing) return
        if (p.isPlaying) { p.pause(); _playing.value = false; stopTicker() }
    }

    fun resume() {
        val p = mp ?: run {
            // Nothing open but a queue is loaded (e.g. after an error) — re-open it.
            _index.value.takeIf { it >= 0 }?.let { playAt(it) }
            return
        }
        if (preparing) return
        // Focus can have been taken away while we sat paused — a call, another
        // player — so claim it back before making sound again.
        if (!requestAudioFocus()) {
            _error.tryEmit("Can't play — another app owns audio")
            return
        }
        if (!p.isPlaying) { p.start(); _playing.value = true; startTicker() }
    }

    fun toggle() = if (_playing.value) pause() else resume()

    /**
     * Previous behaves the way players conventionally do: past the first few seconds
     * it restarts the track rather than jumping back one.
     */
    fun previous() {
        if (_positionMs.value > RESTART_THRESHOLD_MS) { seekTo(0); return }
        val prev = step(-1) ?: run { seekTo(0); return }
        playAt(prev)
    }

    fun next() {
        val nxt = step(+1)
        // Running off the end of the queue is the end of the session, so let go of
        // focus — holding it leaves every other player on the phone ducked forever.
        if (nxt == null) { stopInternal(); _positionMs.value = 0; abandonAudioFocus() } else playAt(nxt)
    }

    fun seekTo(ms: Long) {
        val p = mp ?: return
        if (preparing) return
        val target = ms.coerceIn(0, _durationMs.value.takeIf { it > 0 } ?: ms)
        runCatching { p.seekTo(target.toInt()) }
        _positionMs.value = target
    }

    fun setVolume(v: Float) { mp?.setVolume(v, v) }

    /**
     * Playback speed, for audiobooks and podcasts — the same control the MA player
     * exposes, so the option doesn't quietly do nothing on this backend.
     *
     * `playbackParams` has the side effect of *starting* a paused player, so a
     * paused player is put straight back where it was.
     */
    fun setSpeed(value: Float) {
        _speed.value = value.coerceIn(0.5f, 3f)
        applySpeed()
    }

    private fun applySpeed() {
        val p = mp ?: return
        if (preparing) return
        runCatching {
            val wasPlaying = p.isPlaying
            p.playbackParams = p.playbackParams.setSpeed(_speed.value)
            if (!wasPlaying) p.pause()
        }
    }

    fun setShuffle(on: Boolean) {
        _shuffle.value = on
        rebuildOrder(keepCurrent = _index.value)
    }

    fun cycleRepeat() {
        _repeatMode.value = when (_repeatMode.value) {
            "off" -> "all"
            "all" -> "one"
            else -> "off"
        }
    }

    fun stop() = clear()

    // --- internals --------------------------------------------------------

    private fun open(track: LocalTrack) {
        stopInternal()
        // The downloaded copy wins — it is bit-identical, costs no bandwidth, and
        // plays with the server gone. If the file has been deleted out from under
        // the index, fall back to streaming rather than failing outright.
        val source = track.localPath?.takeIf { File(it).exists() } ?: track.streamUrl
        if (source == null) {
            _error.tryEmit("\"${track.title}\" isn't downloaded and the server is unreachable")
            return
        }
        // Claim audio focus before opening — this is what tells the Sendspin stream
        // (or any other media app) to get out of the way.
        if (!requestAudioFocus()) {
            _error.tryEmit("Can't play — another app owns audio")
            return
        }
        preparing = true
        mp = MediaPlayer().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            setOnPreparedListener {
                preparing = false
                // MediaPlayer knows the real duration; the library's metadata is only
                // ever a rounded second count, and downloads may carry none at all.
                if (it.duration > 0) _durationMs.value = it.duration.toLong()
                it.start()
                if (_speed.value != 1f) applySpeed()
                _playing.value = true
                startTicker()
                _started.tryEmit(track)
            }
            setOnCompletionListener { onCompleted() }
            setOnErrorListener { _, _, _ ->
                preparing = false
                _playing.value = false
                stopTicker()
                _error.tryEmit("Couldn't play \"${track.title}\"")
                true
            }
            try {
                setDataSource(source)
                prepareAsync()
            } catch (_: Exception) {
                preparing = false
                _playing.value = false
                _error.tryEmit("Couldn't open \"${track.title}\"")
            }
        }
    }

    private fun onCompleted() {
        if (_repeatMode.value == "one") { playAt(_index.value); return }
        val nxt = step(+1)
        if (nxt == null) {
            _playing.value = false
            stopTicker()
            _positionMs.value = _durationMs.value
        } else {
            playAt(nxt)
        }
    }

    /**
     * The queue index [delta] places away in *play* order, or null at the end.
     * Repeat-all wraps; shuffle walks the shuffled permutation rather than the list.
     */
    private fun step(delta: Int): Int? {
        if (order.isEmpty()) return null
        val here = order.indexOf(_index.value).takeIf { it >= 0 } ?: return order.firstOrNull()
        val next = here + delta
        return when {
            next in order.indices -> order[next]
            _repeatMode.value == "all" -> order[(next + order.size) % order.size]
            else -> null
        }
    }

    /**
     * Recompute the play order. [keepCurrent] stays first in a shuffled order, so
     * turning shuffle on doesn't jump away from the track already playing.
     */
    private fun rebuildOrder(keepCurrent: Int) {
        val indices = _queue.value.indices.toList()
        order = if (!_shuffle.value) indices
        else (indices - keepCurrent).shuffled().let { rest ->
            if (keepCurrent in indices) listOf(keepCurrent) + rest else rest
        }
    }

    private fun startTicker() {
        stopTicker()
        ticker = scope.launch {
            while (isActive) {
                val p = mp
                if (p != null && !preparing && p.isPlaying) {
                    _positionMs.value = runCatching { p.currentPosition.toLong() }.getOrDefault(_positionMs.value)
                }
                delay(500)
            }
        }
    }

    private fun stopTicker() { ticker?.cancel(); ticker = null }

    private fun stopInternal() {
        stopTicker()
        preparing = false
        mp?.let {
            it.setOnCompletionListener(null)
            it.setOnErrorListener(null)
            it.setOnPreparedListener(null)
            runCatching { it.stop() }
            it.release()
        }
        mp = null
        _playing.value = false
    }

    private companion object {
        /** Past this into a track, Previous restarts it instead of going back one. */
        const val RESTART_THRESHOLD_MS = 4_000L
    }
}
