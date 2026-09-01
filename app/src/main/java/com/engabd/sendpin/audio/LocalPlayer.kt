package com.engabd.sendpin.audio

import android.content.Context
import android.media.AudioDeviceInfo
import com.engabd.sendpin.SendpinApp
import com.engabd.sendpin.data.AppSettings
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import com.engabd.sendpin.BuildConfig
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
    /**
     * The track's first genre tag, or null when the library gives none.
     *
     * Carried here rather than looked up when wanted because this is the only
     * description of the playing track that reaches the light-show side of the
     * app: `MaItem` has the tags, `LocalTrack` is what crosses over. Used by the
     * genre-to-preset rules in `SendpinApp`; nothing else reads it, and a null
     * simply means no rule can match.
     */
    val genre: String? = null,
    val streamUrl: String? = null,
    val localPath: String? = null,
    /**
     * The Navidrome song id to report plays against, when this track came from
     * there. Separate from [id] because "play at original quality" plays a Music
     * Assistant item through a Navidrome stream — same song, two different ids, and
     * scrobbling MA's would name a track Navidrome has never heard of.
     */
    val scrobbleId: String? = null,
    /**
     * Which library [scrobbleId] belongs to, as a `MusicSource.providerId`.
     *
     * An id alone is not enough to report a play against: a Jellyfin guid sent to
     * Navidrome names nothing, and there is now more than one library it could have
     * come from. Null when the track has no library behind it at all.
     */
    val scrobbleProvider: String? = null,
    /**
     * The file's own format, when the library reported one — the "Source" half of the
     * quality badge. Null on a server that doesn't say (plain Subsonic sends no
     * `samplingRate`), in which case the badge shows Playing alone.
     */
    val sourceQuality: StreamQuality? = null,
    /** Composer credit, for classical / jazz tracks. */
    val composer: String? = null,
) {
    /**
     * There is a downloaded copy to play from. Deliberately *not* a `File.exists()`
     * check: this is read on every state emission and every notification rebuild,
     * and hitting the disk there put I/O on the main thread several times a second.
     * The one place the file has to really be there is [LocalPlayer.sourceOf], which
     * checks once and falls back to the stream.
     */
    val offline: Boolean get() = localPath != null
}

/**
 * The standalone player: a real queue used when there is no Music Assistant to
 * route audio to — Navidrome-direct streaming and offline playback of downloads.
 *
 * Built on **ExoPlayer**, which replaced `android.media.MediaPlayer`. MediaPlayer
 * could not do the things this audience notices:
 *
 *  - **Gapless.** `setNextMediaPlayer` is the framework's only gapless hook and its
 *    behaviour is OEM-dependent, so a live album or a DJ mix could gap on one phone
 *    and not another. ExoPlayer owns the whole playlist and buffers across the
 *    boundary itself, which is why the queue is handed to it wholesale here rather
 *    than fed one track at a time.
 *  - **ReplayGain.** There was no stage to apply it in, so a parsed gain was shown
 *    on the quality card and never acted on. See [applyGain].
 *  - **Accurate seek.** MediaPlayer seeks to the nearest sync sample; a scrub in a
 *    long classical movement could land seconds away. [SeekParameters.EXACT] does
 *    what the finger asked.
 *  - **Float output**, so 24-bit sources are not quietly requantised to 16 on the
 *    way to the mixer.
 *
 * Audio focus and the headphone-unplug pause are ExoPlayer's now
 * (`setAudioAttributes(handleAudioFocus = true)`, `setHandleAudioBecomingNoisy`),
 * which is why the hand-rolled focus listener is gone.
 *
 * Focus deliberately does **not** police the two backends against each other, and
 * that is now enforced rather than merely intended. `handleAudioFocus = true` means
 * starting a track here requests `AUDIOFOCUS_GAIN`, which the platform grants by
 * evicting the previous holder — and in this app the previous holder is routinely
 * the Sendspin path, in this same process, which read the eviction as "another app
 * took over" and released its engine. Keeping the two off each other's toes is
 * [com.engabd.sendpin.service.PlaybackOwner]'s job now: [startOutput] announces the
 * takeover, [com.engabd.sendpin.service.Playback] asks before tearing anything
 * down, and the actual handover stays where it always was —
 * `Playback.pauseForLocalPlayback`, which asks the *server* to pause rather than
 * silencing a group member locally.
 *
 * Process-scoped (see `SendpinApp.localPlayer`): Now Playing, the library and the
 * media notification all drive the same instance. Every method here must be called
 * on the main thread, which is where ExoPlayer is built.
 */
@OptIn(UnstableApi::class)
class LocalPlayer(private val context: Context) {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    /**
     * The live player, rebuilt on demand after a [release].
     *
     * A `by lazy` here was a one-way door, and the identical shape had already been
     * fixed once in [com.engabd.sendpin.audio.SendspinNativeEngine] — where it was
     * reachable through ordinary use and left the Music Assistant path deaf for the
     * rest of the connection. Nothing reaches it here today ([release] has no caller
     * outside process teardown), which is exactly why it was worth changing now: a
     * `release()` that cannot be recovered from is a trap laid for whoever adds the
     * first caller, and it would fail the same way — silently, with the object still
     * present and every later call posting to a dead playback thread.
     *
     * The rule this makes true, rather than merely intended: **release() leaves an
     * object that can start again.**
     *
     * Plain `var` rather than a synchronised holder because this class is
     * main-thread-only by contract (see the class doc) — the same reason ExoPlayer
     * itself can be touched here at all.
     */
    private var livePlayer: ExoPlayer? = null
    private val player: ExoPlayer
        get() = livePlayer ?: buildPlayer().also { livePlayer = it }

    /**
     * Called just before this player takes the audio output, so the Sendspin path
     * can tell an in-process handover from a foreign app stealing focus.
     *
     * Set by `SendpinApp`. A callback rather than a reach back into the singleton
     * so this class stays constructible on its own, and so the wiring is visible
     * where the two players are actually put together. See [PlaybackOwner]'s note
     * on internal focus arbitration for what goes wrong without it.
     */
    var onTakingOutput: (() -> Unit)? = null

    /**
     * The underlying ExoPlayer, exposed for [LocalPlaybackService] to wrap in a
     * media3 [androidx.media3.session.MediaSession]. The session reads play state,
     * position, metadata and artwork from the player directly, which is how media3
     * replaces the old manual `PlaybackStateCompat` / `MediaMetadataCompat` dance.
     */
    internal val exoPlayer: ExoPlayer get() = player

    private var ticker: Job? = null

    /** The output the user pinned in Settings (a USB DAC, typically). */
    private var preferredOutput: AudioDeviceInfo? = null

    /**
     * How far the tap runs ahead of the speaker, and where in the track the
     * audio entering the sink is. Shared with [DirectLightSync], which subtracts
     * Hue's own pipeline latency from the lead to decide how long to hold each
     * rendered frame so the light lands on the beat.
     *
     * Declared before the tap because the tap reads the track position out of
     * it, and Kotlin initialises properties in declaration order.
     */
    val audioLead = AudioLead()

    /**
     * The Light Sync audio analysis tap. Shared with [DirectLightSync], which
     * activates and deactivates it. The tap stays in ExoPlayer's processor chain
     * either way — see its docstring for why — so being inactive costs a buffer
     * copy, not nothing.
     */
    val audioAnalysisTap = AudioAnalysisTap(audioLead)

    /**
     * The equaliser for audio this phone decodes.
     *
     * Held here, not built per player: it sits in the sink's processor chain, and
     * that chain is fixed when the player is constructed - but the curve is edited
     * long after. One instance whose config can be replaced at any time is what
     * lets a slider move take effect on the next buffer instead of the next track.
     */
    val localDsp = LocalDsp()

    /**
     * Vinyl surface noise: crackle, dust pops, and low-end rumble. Sits after
     * the EQ and before the lo-fi processor in the chain, so both the lo-fi
     * mode and the light show hear the treated audio.
     *
     * One instance for the same reason as [localDsp]: the processor chain is
     * fixed at player construction, but the intensity slider moves long after.
     */
    val vinylNoise = VinylNoiseProcessor()

    /**
     * Lo-fi music mode: bitcrusher, warm saturation, low-pass. Sits after
     * vinyl noise so it can share the crackle stage when both are active,
     * avoiding double-running the noise.
     */
    val loFiProcessor = LoFiProcessor()

    /** The user's own volume, kept apart from the ReplayGain factor multiplied onto it. */
    private var userVolume = 1f

    /**
     * When both vinyl noise and lo-fi are active, the lo-fi processor skips
     * its own crackle stage and lets [VinylNoiseProcessor] handle it. This
     * avoids double-running the noise: two crackle generators on the same
     * signal read as a broken record, not a warm one.
     */
    private fun updateLoFiSharing() {
        val vinylOn = vinylNoise.currentConfigSafe().isActive()
        val loFiOn = loFiProcessor.currentConfigSafe().isActive()
        val current = loFiProcessor.currentConfigSafe()
        if (current.shareVinylCrackle != vinylOn) {
            loFiProcessor.setConfig(current.copy(shareVinylCrackle = vinylOn))
        }
    }

    private var replayGainMode = ReplayGain.ALBUM

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

    val title: StateFlow<String> get() = _titleFlow
    private val _titleFlow = MutableStateFlow("")

    init {
        val settings = com.engabd.sendpin.data.AppSettings(context)
        // Both preferences are process-wide, so this player follows them itself
        // rather than waiting for a screen to be open and push them down.
        scope.launch {
            settings.preferredAudioDeviceId.collect { id ->
                val am = context.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                setPreferredDevice(AudioOutputs.resolve(am, id))
            }
        }
        scope.launch {
            settings.replayGainMode.collect { mode ->
                replayGainMode = mode
                applyGain()
            }
        }
        // Sound modes: vinyl noise and lo-fi. Both are off by default and
        // take effect on the next buffer, so the listener can toggle them
        // mid-track without a skip or a gap.
        scope.launch {
            settings.vinylNoiseConfig.collect { cfg ->
                vinylNoise.setConfig(cfg)
                updateLoFiSharing()
            }
        }
        scope.launch {
            settings.loFiConfig.collect { cfg ->
                loFiProcessor.setConfig(cfg)
                updateLoFiSharing()
            }
        }
    }

    /**
     * Declared above [buildPlayer], which reads it — property initialisers run in
     * source order, so a listener declared below the function that installs it is
     * null for anything that builds the player during construction. That trap is
     * what killed the app on launch in 0.4.0, one class over.
     */
    private val playerListener = object : Player.Listener {
        override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
            val at = player.currentMediaItemIndex
            _index.value = at
            val track = _queue.value.getOrNull(at)
            _current.value = track
            _titleFlow.value = track?.title.orEmpty()
            _positionMs.value = 0
            _durationMs.value = track?.durationMs ?: 0
            perTrackFadeSeconds = computeBeatAlignedFadeSeconds(track)
            // The gain belongs to the track, so it has to be re-applied at every
            // boundary — including the gapless ones, where nothing else happens. The
            // fade resets with it, or a track entered mid-ramp would start quiet and
            // stay there until the next tick noticed.
            fadeFactor = 1f
            applyGain()
            track?.let { _started.tryEmit(it) }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _playing.value = isPlaying
            if (isPlaying) startTicker() else stopTicker()
        }

        override fun onPlaybackStateChanged(state: Int) {
            if (state == Player.STATE_READY) {
                // ExoPlayer knows the real duration; the library's metadata is a
                // rounded second count at best, and downloads may carry none at all.
                val d = player.duration
                if (d != C.TIME_UNSET && d > 0) _durationMs.value = d
            }
            if (state == Player.STATE_ENDED) {
                stopTicker()
                _playing.value = false
                _positionMs.value = _durationMs.value
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            stopTicker()
            _playing.value = false
            val name = _current.value?.title.orEmpty()
            _error.tryEmit(
                if (name.isBlank()) "Playback failed" else "Couldn't play \"$name\""
            )
            // One bad track should not end the album. Anything left goes on.
            if (player.hasNextMediaItem()) {
                player.seekToNextMediaItem()
                player.prepare()
            }
        }
    }

    private fun buildPlayer(): ExoPlayer {
        val attrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()

        // Float output: off unless the listener has asked for bit-perfect.
        //
        // This was once set unconditionally, on theory, and reverted: float output is
        // documented as experimental, misbehaves on some sinks at rates other than the
        // device's native one, and 44.1/16 came back audibly distorted on a 48 kHz
        // phone while 48 kHz was clean — exactly that shape. (`EXTENSION_RENDERER_MODE_PREFER`
        // went with it and has not come back: no decoder extensions are in this build,
        // so it only ever cost failed reflection at startup.)
        //
        // Without it, a 24/96 FLAC from Navidrome is requantised to 16 on the way to
        // the sink, which makes the whole hi-res path decorative on this backend — the
        // Sendspin path has honoured `bitPerfect24Bit` for a while and this one never
        // read it at all. So it is back, gated on the setting, which is the listener
        // saying they want the hi-res path and will notice if it misbehaves.
        //
        // Read synchronously rather than from the settings Flow: this is a renderer
        // factory option, fixed when the player is constructed, so there is no later
        // moment to apply it. A change therefore takes effect on the next player build
        // — Settings says so.
        val bitPerfect = AppSettings(context).bootBitPerfect

        // The Light Sync audio analysis tap is injected via TapRenderersFactory,
        // which overrides buildAudioSink to install the tap in the audio sink's
        // processor chain. It stays in the chain whether or not Light Sync is on,
        // because the sink decides membership once per configuration; the cost
        // when off is a buffer copy per callback and nothing else.
        val renderers = TapRenderersFactory(context, audioAnalysisTap, audioLead, localDsp, vinylNoise, loFiProcessor)
            .setEnableAudioFloatOutput(bitPerfect) as TapRenderersFactory

        // The defaults are sized for video-on-mobile-data. This is a lossless file
        // over a LAN, where the sensible trade is a deeper buffer: a 24/96 FLAC is
        // several Mbit/s and a Wi-Fi dropout mid-album is the failure that matters.
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ 30_000,
                /* maxBufferMs = */ 120_000,
                /* bufferForPlaybackMs = */ 1_000,
                /* bufferForPlaybackAfterRebufferMs = */ 2_500,
            )
            .build()

        // The media path had no DataSource.Factory at all, so it ran on
        // DefaultHttpDataSource's defaults: no User-Agent worth logging, and — the one
        // that breaks playback — cross-protocol redirects refused. Jellyfin's
        // `/Audio/{id}/universal` answers a direct-playable item with a 302 to the
        // static file, and a reverse-proxied server routinely redirects http↔https on
        // the way. Refusing that is an immediate load error, which the error handler
        // turns into a skip, which becomes a whole album flicking past.
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(USER_AGENT)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(30_000)
        val mediaSourceFactory = DefaultMediaSourceFactory(
            DefaultDataSource.Factory(context, httpFactory),
        )

        return ExoPlayer.Builder(context, renderers)
            .setLoadControl(loadControl)
            .setMediaSourceFactory(mediaSourceFactory)
            // handleAudioFocus = true replaces the entire hand-rolled focus listener:
            // ducking, transient loss, and resume-on-regain are all ExoPlayer's.
            .setAudioAttributes(attrs, /* handleAudioFocus = */ true)
            // Headphones out, or Bluetooth gone: pause rather than switching to the
            // phone's speaker at whatever volume was in the ears a moment ago.
            .setHandleAudioBecomingNoisy(true)
            // Past this into a track, Previous restarts it instead of going back one
            // — the convention every player follows.
            .setMaxSeekToPreviousPositionMs(RESTART_THRESHOLD_MS)
            // Off by default since media3 1.1, which left the session publishing no
            // device volume at all. The local player decodes straight to this phone's
            // output, so its volume genuinely *is* STREAM_MUSIC — and the Now Playing
            // slider already drives that through DeviceVolume. Enabling it is what
            // makes the session agree with the slider instead of ignoring the subject.
            .setDeviceVolumeControlEnabled(true)
            .build()
            .also { p ->
                // A scrub lands where the finger asked, not at the nearest sync
                // sample — which in a 20-minute movement can be seconds away.
                p.setSeekParameters(SeekParameters.EXACT)
                p.addListener(playerListener)
                // The decoder's *input* format - what the file declares. Paired with
                // SignalPathProbe's report of what the decoder actually handed over,
                // this is what makes "a 24-bit file arriving as 16-bit PCM" visible
                // rather than something a listener has to infer from a flat number.
                p.addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
                    override fun onAudioInputFormatChanged(
                        eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                        format: androidx.media3.common.Format,
                        decoderReuseEvaluation: androidx.media3.exoplayer.DecoderReuseEvaluation?,
                    ) {
                        SignalPath.onSourceFormat(format)
                    }

                    override fun onAudioDisabled(
                        eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                        counters: androidx.media3.exoplayer.DecoderCounters,
                    ) {
                        // Stop describing a chain that is no longer carrying anything.
                        SignalPath.clear()
                    }
                })
                SignalPath.onMixerRate(DeviceCapabilities.mixerRateHz())
                preferredOutput?.let { d -> runCatching { p.setPreferredAudioDevice(d) } }
            }
    }


    // --- queue ------------------------------------------------------------

    /** Replace the queue and start at [startIndex]. */
    fun setQueue(tracks: List<LocalTrack>, startIndex: Int = 0) {
        if (tracks.isEmpty()) { clear(); return }
        val start = startIndex.coerceIn(0, tracks.lastIndex)
        _queue.value = tracks
        _hasSession.value = true
        // Decided here rather than at each call site, because every path that starts
        // a local queue goes through this one and any of them could forget. A queue
        // that is one album is a sequenced record: fading between its tracks damages
        // it, and gapless is the entire reason the list is handed over in one go.
        smoothQueue = tracks.size < 2 || tracks.mapNotNull { it.album }.distinct().size > 1
        fadeFactor = 1f
        // The whole list goes to ExoPlayer at once — that is what lets it buffer
        // across a track boundary, and so what makes the transition gapless.
        player.setMediaItems(tracks.map(::mediaItem), start, C.TIME_UNSET)
        player.prepare()
        startOutput()
    }

    /**
     * Start output, announcing the takeover first.
     *
     * Every path that *begins* playback goes through here rather than calling
     * `player.play()` directly, because `play()` is where ExoPlayer requests
     * `AUDIOFOCUS_GAIN` — and that request evicts the Sendspin path in this same
     * process. Announcing before rather than after is load-bearing: the focus loss
     * is dispatched to the previous holder as part of granting the request, so it
     * can arrive before `playing` has even flipped, and an arbitration reading that
     * flow would sometimes see `false` and tear the MA engine down anyway.
     *
     * Resuming counts and skipping does not: a skip inside a queue that is already
     * playing never lost focus, so it has nothing to take back.
     */
    private fun startOutput() {
        onTakingOutput?.invoke()
        player.play()
    }

    /** Append to the queue, starting playback if nothing is loaded. */
    fun addToQueue(tracks: List<LocalTrack>) {
        if (tracks.isEmpty()) return
        val wasEmpty = _queue.value.isEmpty()
        _queue.value = _queue.value + tracks
        // Appending something from off the record — a radio top-up, a queued track —
        // means this is no longer one album, so the fade applies again.
        if (!wasEmpty && _queue.value.mapNotNull { it.album }.distinct().size > 1) smoothQueue = true
        player.addMediaItems(tracks.map(::mediaItem))
        if (wasEmpty) {
            _hasSession.value = true
            player.prepare()
            startOutput()
        }
    }

    /** Insert right after what's playing. */
    fun playNext(tracks: List<LocalTrack>) {
        if (tracks.isEmpty()) return
        if (_queue.value.isEmpty()) { setQueue(tracks); return }
        val at = (_index.value + 1).coerceIn(0, _queue.value.size)
        _queue.value = _queue.value.toMutableList().apply { addAll(at, tracks) }
        player.addMediaItems(at, tracks.map(::mediaItem))
    }

    fun removeAt(position: Int) {
        val list = _queue.value
        if (position !in list.indices) return
        _queue.value = list.toMutableList().apply { removeAt(position) }
        if (_queue.value.isEmpty()) { clear(); return }
        // ExoPlayer moves to the next item itself when the current one is removed.
        player.removeMediaItem(position)
        _index.value = player.currentMediaItemIndex
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
        list.add(to, list.removeAt(from))
        _queue.value = list
        player.moveMediaItem(from, to)
        _index.value = player.currentMediaItemIndex
    }

    /**
     * Shuffle the tracks that have not played yet, in place.
     *
     * Deliberately not [setShuffle]. That turns on ExoPlayer shuffle *mode*, which
     * reorders the play order behind the list and leaves the queue panel showing the
     * order the user built — correct for a mode, useless as an answer to "shuffle
     * this". This rewrites the list itself, so the panel shows what will actually
     * play.
     *
     * Only the tail moves. Reshuffling the whole list would either interrupt the
     * track that is playing or leave it stranded in the middle of a list it is no
     * longer at the head of; every other player shuffles what is coming up, and so
     * does this.
     */
    fun shuffleQueue() {
        val list = _queue.value
        val from = (_index.value + 1).coerceIn(0, list.size)
        if (list.size - from < 2) return
        val head = list.subList(0, from)
        val tail = list.subList(from, list.size).shuffled()
        _queue.value = head + tail
        // One removal and one insertion rather than a move per track: ExoPlayer
        // recomputes the timeline on every edit, and n moves is n timeline updates
        // the UI would animate through.
        player.removeMediaItems(from, list.size)
        player.addMediaItems(from, tail.map(::mediaItem))
        _index.value = player.currentMediaItemIndex
    }

    fun clear() {
        stopTicker()
        // The session flags below are what Now Playing switches on, and they must be
        // cleared even if the player itself refuses — ExoPlayer throws when touched
        // off its own thread, and an exception here used to leave `_hasSession` true
        // with no queue behind it. Now Playing then stayed pinned to a local player
        // that had nothing to play, which is what "switched to Music Assistant but
        // it's stuck on the local player" looks like from the outside.
        runCatching {
            player.clearMediaItems()
            player.stop()
        }
        _queue.value = emptyList()
        _index.value = -1
        _current.value = null
        _titleFlow.value = ""
        _durationMs.value = 0
        _positionMs.value = 0
        _playing.value = false
        _hasSession.value = false
    }

    // --- transport --------------------------------------------------------

    /** Play a single source directly — the "audition this one thing" path. */
    fun play(source: String, title: String = "") {
        setQueue(listOf(LocalTrack(id = source, title = title, streamUrl = source)))
    }

    fun playAt(position: Int) {
        if (position !in _queue.value.indices) return
        player.seekTo(position, C.TIME_UNSET)
        startOutput()
    }

    fun pause() = player.pause()

    fun resume() {
        if (_queue.value.isEmpty()) return
        // A playlist that ran to the end, or one an error tore down, needs preparing
        // again before it will make sound.
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        startOutput()
    }

    fun toggle() = if (_playing.value) pause() else resume()

    /**
     * Previous behaves the way players conventionally do: past the first few seconds
     * it restarts the track rather than jumping back one. That threshold is
     * `maxSeekToPreviousPositionMs`, set when the player is built.
     */
    fun previous() = player.seekToPrevious()

    fun next() {
        if (player.hasNextMediaItem()) {
            player.seekToNextMediaItem()
        } else {
            // Running off the end is the end of the session. Stopping releases audio
            // focus, which holding would leave every other player on the phone ducked.
            player.stop()
            _positionMs.value = 0
            _playing.value = false
        }
    }

    fun seekTo(ms: Long) {
        val max = _durationMs.value.takeIf { it > 0 } ?: ms
        val target = ms.coerceIn(0, max)
        player.seekTo(target)
        _positionMs.value = target
    }

    fun setVolume(v: Float) {
        userVolume = v.coerceIn(0f, 1f)
        applyGain()
    }

    /**
     * Playback speed, for audiobooks and podcasts — the same control the MA player
     * exposes, so the option doesn't quietly do nothing on this backend.
     */
    fun setSpeed(value: Float) {
        _speed.value = value.coerceIn(0.5f, 3f)
        player.setPlaybackSpeed(_speed.value)
    }

    fun setShuffle(on: Boolean) {
        _shuffle.value = on
        // ExoPlayer shuffles the *play order* and leaves the list alone, which is
        // what the queue UI wants: the list stays as the user built it.
        player.shuffleModeEnabled = on
    }

    fun cycleRepeat() {
        _repeatMode.value = when (_repeatMode.value) {
            "off" -> "all"
            "all" -> "one"
            else -> "off"
        }
        player.repeatMode = when (_repeatMode.value) {
            "all" -> Player.REPEAT_MODE_ALL
            "one" -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun stop() = clear()

    /**
     * Point playback at a specific output device, or null for the system route.
     * Applies to what is playing and to everything opened after.
     */
    fun setPreferredDevice(device: AudioDeviceInfo?) {
        preferredOutput = device
        runCatching { player.setPreferredAudioDevice(device) }
    }

    /**
     * Release the player.
     *
     * Released *and cleared*, so the next play builds a fresh one rather than
     * posting to this one's dead thread — see [livePlayer]. Deliberately not
     * `player.release()`, which would build a player only to destroy it if this
     * object had never started one.
     */
    fun release() {
        stopTicker()
        runCatching { livePlayer?.release() }
        livePlayer = null
    }

    // --- internals --------------------------------------------------------

    /**
     * The downloaded copy wins — it is bit-identical, costs no bandwidth, and plays
     * with the server gone. If the file has been deleted out from under the index,
     * fall back to streaming rather than failing outright.
     */
    private fun sourceOf(track: LocalTrack): String? =
        track.localPath?.takeIf { File(it).exists() } ?: track.streamUrl

    private fun mediaItem(track: LocalTrack): MediaItem =
        MediaItem.Builder()
            .setMediaId(track.id)
            // An unplayable entry still has to occupy its slot, or every index in the
            // queue would shift out from under the UI. ExoPlayer reports the error
            // and the listener moves on.
            .setUri(sourceOf(track) ?: "")
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(track.title)
                    .setArtist(track.artist)
                    .setAlbumTitle(track.album)
                    .setComposer(track.composer)
                    .build()
            )
            .build()

    /**
     * Set the output volume to the user's level, scaled by the current track's
     * ReplayGain. Re-run whenever either half changes, or a track boundary makes the
     * gain stale.
     */
    private fun applyGain() {
        val factor = ReplayGain.factor(_current.value?.sourceQuality, replayGainMode)
        player.volume = (userVolume * factor * fadeFactor * speedGainFactor).coerceIn(0f, 1f)
    }

    /**
     * Where [speedGainFactor] is ramping toward — set by [SendpinApp.speedMonitor]
     * from [SpeedAdaptiveGain.gainFactor] on each GPS reading, or left at 1f (no
     * offset) whenever `AppSettings.speedAdaptiveVolume` is off. Public because the
     * monitor that drives it lives one layer up, in `service/`.
     */
    @Volatile
    var speedGainTarget: Float = 1f

    /** Ramps toward [speedGainTarget] a step per tick, so a speed change never jumps the volume. */
    @Volatile
    private var speedGainFactor: Float = 1f

    /**
     * Seconds of fade at each end of a track, or 0 for none.
     *
     * Not a crossfade: one ExoPlayer has one output, so two tracks cannot overlap
     * through it. This is the honest version of what a single player can do — down at
     * the end, up at the start — which is what a party playlist wants and what an
     * album emphatically does not. See [smoothQueue].
     */
    @Volatile
    var fadeSeconds: Int = 0
        set(value) {
            field = value.coerceIn(0, 12)
            if (field == 0) { fadeFactor = 1f; applyGain() }
        }

    /**
     * Whether the *current queue* should fade at all.
     *
     * A record is sequenced; fading between its tracks damages it, and gapless is the
     * whole reason the queue is handed to ExoPlayer in one go. So the setting says
     * what the listener wants in general and this says whether it applies here —
     * set false when the queue is one album.
     */
    @Volatile
    var smoothQueue: Boolean = true

    /**
     * Time [fadeSeconds]' window to land on a beat instead of an arbitrary N
     * seconds before the end, when the current track has a scan already in
     * memory (see [computeBeatAlignedFadeSeconds] for why disk isn't touched
     * here). Off by default — see [AppSettings.beatMatchedCrossfade].
     */
    @Volatile
    var beatMatchedFade: Boolean = false

    /** This track's fade window if [beatMatchedFade] found a scan; null uses [fadeSeconds] as-is. */
    @Volatile
    private var perTrackFadeSeconds: Int? = null

    /** The ramp, multiplied into the output alongside user volume and ReplayGain. */
    @Volatile
    private var fadeFactor: Float = 1f

    /**
     * [beatMatchedFade]'s adjustment for [track], or null to leave [fadeSeconds]
     * unmodified.
     *
     * [TrackScanStore.peek] only — never [TrackScanStore.load] — because this runs
     * on `onMediaItemTransition`, ExoPlayer's own listener callback thread; a scan
     * not already resident (i.e. never touched by Light Sync this session) simply
     * doesn't beat-match this play, rather than blocking a playback callback on
     * disk I/O to fetch one.
     */
    private fun computeBeatAlignedFadeSeconds(track: LocalTrack?): Int? {
        if (!beatMatchedFade || track == null || fadeSeconds <= 0) return null
        val scan = SendpinApp.instance.trackScans.store.peek(TrackScanRepository.keyFor(track)) ?: return null
        return BeatAlignedFade.windowSeconds(scan.durationS, scan.beats, fadeSeconds)
    }

    /**
     * Where in the track the fade should be, as a multiplier.
     *
     * Deliberately linear in *amplitude* rather than dB: over two or three seconds
     * against a fading song, the difference is inaudible and the arithmetic is one
     * division.
     */
    private fun fadeAt(positionMs: Long, durationMs: Long): Float {
        val secs = perTrackFadeSeconds ?: fadeSeconds
        if (secs <= 0 || !smoothQueue || durationMs <= 0) return 1f
        val window = secs * 1000L
        // A track shorter than two windows would spend its whole length fading.
        if (durationMs < window * 3) return 1f
        val inFactor = if (positionMs < window) positionMs.toFloat() / window else 1f
        val remaining = durationMs - positionMs
        val outFactor = if (remaining in 0..window) remaining.toFloat() / window else 1f
        return minOf(inFactor, outFactor).coerceIn(0f, 1f)
    }

    private fun startTicker() {
        stopTicker()
        ticker = scope.launch {
            while (isActive) {
                val pos = player.currentPosition.coerceAtLeast(0)
                _positionMs.value = pos
                if (fadeSeconds > 0 && smoothQueue) {
                    val next = fadeAt(pos, _durationMs.value)
                    if (kotlin.math.abs(next - fadeFactor) > 0.001f) {
                        fadeFactor = next
                        applyGain()
                    }
                }
                // Steps a quarter of the remaining gap per 250ms tick — about a
                // second to converge on a new target, never an instant jump.
                if (kotlin.math.abs(speedGainTarget - speedGainFactor) > 0.001f) {
                    speedGainFactor += (speedGainTarget - speedGainFactor) * 0.25f
                    applyGain()
                }
                delay(POSITION_TICK_MS)
            }
        }
    }

    private fun stopTicker() { ticker?.cancel(); ticker = null }

    private companion object {
        /** Past this into a track, Previous restarts it instead of going back one. */
        const val RESTART_THRESHOLD_MS = 4_000L

        /**
         * What the media path calls itself to a server.
         *
         * ExoPlayer opens stream URLs without the app's OkHttp interceptors, so this
         * is the only place a Navidrome or Jellyfin log gets to see who was asking.
         */
        val USER_AGENT: String = "CAMusic/${BuildConfig.VERSION_NAME} (Android)"

        /**
         * How often the published playhead is refreshed.
         *
         * Was 500 ms, which is fine for a progress bar and too coarse for synced
         * lyrics: a line could light up a beat after it was sung, every time, which
         * reads as lyrics that are simply out of sync. Matches the Sendspin path's
         * own tick so both players feel the same.
         */
        const val POSITION_TICK_MS = 250L
    }
}
