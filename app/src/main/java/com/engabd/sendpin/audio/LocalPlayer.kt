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
import androidx.media3.common.PlaybackParameters
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
     * Lo-fi's pitch wobble — see [WowFlutterProcessor]. Rides Lo-fi's own
     * toggle rather than a setting of its own; see the `loFiConfig`
     * collector below.
     */
    val wowFlutter = WowFlutterProcessor()

    /**
     * Lo-fi music mode: bitcrusher, warm saturation, low-pass. Sits after
     * vinyl noise so it can share the crackle stage when both are active,
     * avoiding double-running the noise.
     */
    val loFiProcessor = LoFiProcessor()

    /**
     * Old Radio mode: telephone-band EQ, light saturation, AM static and
     * warble. Sits after lo-fi, the last coloration stage before the tap.
     */
    val oldRadio = OldRadioProcessor()

    /** The user's own volume, kept apart from the ReplayGain factor multiplied onto it. */
    private var userVolume = 1f

    /**
     * [ExclusiveOutput] is on for the player currently held in [livePlayer].
     *
     * Read once, in [buildPlayer], for the same reason [bitPerfect] is there:
     * this also governs the renderer factory, which is fixed at construction, so
     * there is no later moment a change could take effect anyway — see
     * [com.engabd.sendpin.data.AppSettings.bootExclusiveOutput]. [applyGain] and
     * [startTicker] read it to know that volume no longer belongs to this app:
     * with nothing of ours in the chain, the one thing left that would still
     * touch the signal is the AudioTrack gain `player.volume` applies, so that
     * is fixed at unity here and the fade/speed-adaptive arithmetic that would
     * otherwise feed it is skipped rather than computed and thrown away.
     */
    @Volatile
    private var exclusiveOutput = false

    /**
     * When both vinyl noise and lo-fi are active, the lo-fi processor skips
     * its own crackle stage and lets [VinylNoiseProcessor] handle it. This
     * avoids double-running the noise: two crackle generators on the same
     * signal read as a broken record, not a warm one.
     */
    private fun updateLoFiSharing() {
        val vinylOn = vinylNoise.currentConfigSafe().isActive()
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

    /**
     * The remote player's own live output format and device name — see
     * [RemotePlayback]/[RemoteState] — or both null when nothing remote is
     * active, or the remote didn't say. Only ever set from [applyRemoteState];
     * this phone's own local-decode playback has no remote to ask and leaves
     * these at their defaults, same as every other `RemoteState`-sourced flow
     * here.
     */
    private val _remoteOutputFormat = MutableStateFlow<RemoteAudioFormat?>(null)
    val remoteOutputFormat: StateFlow<RemoteAudioFormat?> = _remoteOutputFormat

    private val _remoteOutputDeviceName = MutableStateFlow<String?>(null)
    val remoteOutputDeviceName: StateFlow<String?> = _remoteOutputDeviceName

    private val _error = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** Playback failures worth telling the user about (a missing file, a dead server). */
    val errors: SharedFlow<String> = _error.asSharedFlow()

    private val _started = MutableSharedFlow<LocalTrack>(extraBufferCapacity = 4)
    /** Emitted each time a track actually begins — what scrobbling hangs off. */
    val started: SharedFlow<LocalTrack> = _started.asSharedFlow()

    private val _exhausted = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    /**
     * The queue has run out: the last track finished, or a skip found nothing after
     * it.
     *
     * "Keep the music going" tops the queue up two tracks before the end, watching
     * the play index — which cannot see either of these. A skip from the last track
     * produces no transition to observe, so pressing next simply left the same song
     * on screen; and with shuffle on the index is a position in the *list*, not in
     * the play order, so "two from the end" is not a statement about what is left to
     * play. This is the player saying it has nothing more, whatever the order, and
     * it is what [continueAfterEnd] is meant to be answered with.
     */
    val exhausted: SharedFlow<Unit> = _exhausted.asSharedFlow()

    /** Anything is loaded, so Now Playing should show this player rather than MA. */
    val active: StateFlow<Boolean> get() = _hasSession
    private val _hasSession = MutableStateFlow(false)

    val title: StateFlow<String> get() = _titleFlow
    private val _titleFlow = MutableStateFlow("")

    /**
     * Lo-fi's persistent vari-speed slow-down, as a rate multiplier (1 = none).
     *
     * Kept apart from [_speed], which is the listener's own audiobook/podcast
     * rate: the two multiply, but only this one also drags the pitch down with
     * it, and only this one is a property of a sound mode rather than of the
     * transport. See [MAX_LO_FI_SLOWDOWN].
     */
    private var loFiSpeedRatio = 1f

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
                // A remote player levels its own output — the scalar below acts on a
                // signal this phone isn't carrying — so the preference goes out to it
                // instead. See RemotePlayback.setReplayGain.
                remote?.let { r -> remoteCall { r.setReplayGain(mode) } }
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
                // Wow/flutter rides the same toggle and updates live, same as
                // the bitcrush/saturation above. So does the persistent
                // slow-down, which is playback parameters rather than a
                // processor — see applyPlaybackParameters for why.
                wowFlutter.setConfig(WowFlutterProcessor.Config(cfg.enabled, cfg.intensity))
                loFiSpeedRatio = if (cfg.isActive()) 1f - cfg.intensity * MAX_LO_FI_SLOWDOWN else 1f
                applyPlaybackParameters()
            }
        }
        scope.launch {
            settings.oldRadioConfig.collect { cfg -> oldRadio.setConfig(cfg) }
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
            // Which track a DJ Radio fade-in belongs to is decided here, because this
            // is the one place that knows which track actually arrived. A hand-over
            // set [fadeInPending] a moment ago and this consumes it; a transition
            // that was a skip, or the end of a track, clears it instead — arriving
            // somewhere is not the same as being mixed into, and a track the listener
            // skipped to must start at full volume.
            fadeInFor = if (fadeInPending) track?.id else null
            if (!fadeInPending) { fadeInFromMs = 0L; fadeInWindowS = 0f }
            fadeInPending = false
            // A new track gets its own attempt at a hand-over. Keyed on the id
            // rather than left to differ by itself, because under repeat-all a
            // one-track queue transitions back into the same id and would otherwise
            // get exactly one crossfade for the life of the session.
            crossfadeFor = null
            // The gain belongs to the track, so it has to be re-applied at every
            // boundary — including the gapless ones, where nothing else happens. The
            // fade resets with it, or a track entered mid-ramp would start quiet and
            // stay there until the next tick noticed. Except at the top of a
            // crossfade, where 1f is exactly wrong: the incoming track is supposed to
            // be silent for this instant, and a full-volume frame before the first
            // tick catches up is heard as a click.
            fadeFactor = if (fadeInFor != null) 0f else 1f
            applyGain()
            // A whole track's notice for the two scans the next transition needs.
            prefetchScans(track)
            track?.let { _started.tryEmit(it) }
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _playing.value = isPlaying
            // A crossfade's fade-in is driven by the ticker, and a hand-over seeks
            // into a part of the next track that may not be buffered yet — so
            // ExoPlayer reports not-playing for a moment right in the middle of the
            // mix. Stopping the loop there froze the incoming track's ramp at zero
            // and left it silent until playback resumed, which is the hole the
            // crossfade exists to remove. The loop ends itself once the transition
            // is over and playback really has stopped; see [startTicker].
            if (isPlaying) startTicker() else if (!crossfadeBusy) stopTicker()
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
                _exhausted.tryEmit(Unit)
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
        //
        // Exclusive output stacks on top of this rather than replacing it: it forces
        // the same float path bit-perfect asks for (see `shouldUseFloatOutput` in
        // SignalPath's class doc) regardless of whether the listener also turned
        // bit-perfect on, and on top of that strips every processor of ours out of
        // the chain and leaves OutputRate unreached — see ExclusiveOutput for exactly
        // what that trades away, and why the ceiling on what it can promise is
        // AudioFlinger, not the DAC.
        val settings = AppSettings(context)
        val bitPerfect = settings.bootBitPerfect
        val exclusive = settings.bootExclusiveOutput
        exclusiveOutput = exclusive

        // The Light Sync audio analysis tap is injected via TapRenderersFactory,
        // which overrides buildAudioSink to install the tap in the audio sink's
        // processor chain. It stays in the chain whether or not Light Sync is on,
        // because the sink decides membership once per configuration; the cost
        // when off is a buffer copy per callback and nothing else.
        //
        // Exclusive mode passes null for the tap and every processor instead: with
        // nothing of ours between the decoder and the DAC, a 16-bit file must not
        // keep the equaliser running just because float output only bypasses
        // processors media3 itself decided to skip.
        val renderers = TapRenderersFactory(
            context = context,
            tap = if (exclusive) null else audioAnalysisTap,
            lead = audioLead,
            dsp = if (exclusive) null else localDsp,
            vinylNoise = if (exclusive) null else vinylNoise,
            wowFlutter = if (exclusive) null else wowFlutter,
            loFi = if (exclusive) null else loFiProcessor,
            oldRadio = if (exclusive) null else oldRadio,
            exclusive = exclusive,
        ).setEnableAudioFloatOutput(bitPerfect || exclusive) as TapRenderersFactory

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
                // AudioLeadProbe.configure's report of what the decoder actually
                // handed over (SignalPath.onDecoderOutput), this is what makes "a
                // 24-bit file arriving as 16-bit PCM" visible rather than something
                // a listener has to infer from a flat number.
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
                // A rate chosen before this player existed — a Lo-fi toggle while
                // nothing was playing, or a speed left over from the last track —
                // has to land on the new one, or it silently reverts to 1.
                applyPlaybackParameters(p)
            }
    }


    // --- a player that isn't this phone -----------------------------------

    // Declared above `remote`, whose setter writes it: property initialisers run
    // in source order, and the other way round the setter would be reading a field
    // that does not exist yet. The same trap `playerListener`'s comment describes.
    private val _remoteActive = MutableStateFlow(false)

    /**
     * The library plays its own music and this player is its remote. Null for
     * every library that hands out a URL per track, which is all of them but MPD.
     *
     * Everything above this class is written against [LocalPlayer] — Now Playing,
     * the queue sheet, the mini bar, scrobbling — so a second player was never
     * going to be a second set of screens. It is this: the transport calls go out
     * to [RemotePlayback] instead of to ExoPlayer, and the state flows are filled
     * from what it reports back rather than from what ExoPlayer is doing. The
     * screens cannot tell the difference, and do not have to.
     *
     * Setting it stops whatever this phone was playing. The two cannot both be
     * running: they would be two players, in two rooms, both holding the queue.
     */
    var remote: RemotePlayback? = null
        set(value) {
            if (field === value) return
            field = value
            _remoteActive.value = value != null
            if (value == null) {
                // Stale MPD output info must not linger once this phone (or a
                // different remote) takes over — see remoteOutputFormat's doc.
                _remoteOutputFormat.value = null
                _remoteOutputDeviceName.value = null
            }
            stopRemoteLoop()
            // `livePlayer`, not `player`: the latter builds an ExoPlayer on demand,
            // and building one in order to stop it is how switching *to* a remote
            // library would leave an audio engine behind for nothing.
            livePlayer?.let { p -> runCatching { p.clearMediaItems(); p.stop() } }
            // The session, not `clear()`: clearing would go out to the player that
            // was just attached and empty a queue this app did not put there. MPD
            // may well have been playing before the app opened.
            resetSession()
            // The loudness setting has to be re-stated to the new player: it was
            // applied to this phone's output, which is not where the music is now.
            value?.let { r -> remoteCall { r.setReplayGain(replayGainMode) } }
        }

    /**
     * Whether the library is playing this itself.
     *
     * The screens need it for the few controls that are about *this phone* rather
     * than about playback: the volume slider moves the phone's own output, which
     * is the wrong knob entirely when the sound is coming out of a DAC across the
     * room.
     */
    val remoteActive: StateFlow<Boolean> = _remoteActive

    private var remoteJob: Job? = null

    /** Fire and forget, with the failure kept off the screen. See [RemotePlayback]. */
    private fun remoteCall(block: suspend () -> Unit) {
        scope.launch { runCatching { block() } }
    }

    /**
     * Follow the remote player: poll it, and fill in between the polls.
     *
     * A poll is a round trip, so it runs once a second and the position is carried
     * forward by hand on the ticks in between. Polling four times a second for a
     * smooth scrub bar would be four connections a second to a machine that is
     * also decoding audio; interpolating costs nothing and is right to within the
     * tick, because a playing track's position advances at exactly one second per
     * second.
     */
    private fun startRemoteLoop() {
        stopRemoteLoop()
        val r = remote ?: return
        remoteJob = scope.launch {
            var sincePoll = REMOTE_POLL_MS
            while (isActive) {
                if (sincePoll >= REMOTE_POLL_MS) {
                    sincePoll = 0
                    val state = runCatching { r.poll() }.getOrNull()
                    if (state != null) applyRemoteState(state)
                } else if (_playing.value) {
                    // Between polls, and while a poll is failing: the music has not
                    // stopped just because the Wi-Fi dropped a packet.
                    _positionMs.value += POSITION_TICK_MS
                }
                delay(POSITION_TICK_MS)
                sincePoll += POSITION_TICK_MS
            }
        }
    }

    private fun stopRemoteLoop() { remoteJob?.cancel(); remoteJob = null }

    /** Put one reading of the remote player onto the flows the screens read. */
    private fun applyRemoteState(state: RemoteState) {
        val queue = _queue.value
        val wasPlaying = _playing.value
        _playing.value = state.playing
        _remoteOutputFormat.value = state.outputFormat
        _remoteOutputDeviceName.value = state.outputDeviceName

        if (state.index in queue.indices && state.index != _index.value) {
            showRemoteIndex(state.index)
        }
        _positionMs.value = state.positionMs
        // The player's own duration is the true one; a track's tag is the fallback
        // for the moment before it starts, when MPD reports none.
        _durationMs.value = state.durationMs.takeIf { it > 0 }
            ?: _current.value?.durationMs
            ?: 0L

        // The queue ran out: MPD stops rather than sitting paused at the end, and
        // "keep the music going" is listening for exactly this.
        if (state.stopped && wasPlaying && queue.isNotEmpty()) {
            _positionMs.value = _durationMs.value
            _exhausted.tryEmit(Unit)
        }
    }

    /**
     * Move the screens to [at] — the index, the track, the title, and the scrobble.
     *
     * The same bookkeeping `onMediaItemTransition` does for this phone's own
     * playback, for the same reasons, minus everything about gain and fades: those
     * are about a signal this player isn't carrying.
     */
    private fun showRemoteIndex(at: Int) {
        val track = _queue.value.getOrNull(at) ?: return
        _index.value = at
        _current.value = track
        _titleFlow.value = track.title
        _positionMs.value = 0
        _durationMs.value = track.durationMs
        _started.tryEmit(track)
    }

    /**
     * Show what the remote player is already playing, and change nothing about it.
     *
     * The counterpart to [setQueue]: same bookkeeping, no commands. MPD does not
     * stop when the app is closed, so on connecting the app adopts what is there
     * rather than presenting an empty player beside music the user can hear.
     */
    fun adoptRemoteQueue(tracks: List<LocalTrack>, at: Int) {
        if (remote == null || tracks.isEmpty()) return
        _queue.value = tracks
        _hasSession.value = true
        smoothQueue = true
        showRemoteIndex(at.coerceIn(0, tracks.lastIndex))
        startRemoteLoop()
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
        dropCrossfade()
        fadeFactor = 1f
        remote?.let { r ->
            // Nothing goes to ExoPlayer: the remote player decodes this queue, and a
            // second copy of it playing here is the "same album out of two rooms"
            // the httpd stream produced. The index and the track are set now rather
            // than waited for, so the screen fills the moment the user taps.
            showRemoteIndex(start)
            // Optimistically playing, for the second before the first poll answers:
            // a play button that shows "paused" after a tap reads as a dropped tap.
            _playing.value = true
            remoteCall { r.setQueue(tracks, start) }
            startRemoteLoop()
            return
        }
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
        remote?.let { r ->
            if (wasEmpty) {
                _hasSession.value = true
                showRemoteIndex(0)
                remoteCall { r.setQueue(_queue.value, 0) }
                startRemoteLoop()
            } else {
                remoteCall { r.addToQueue(tracks) }
            }
            return
        }
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
        remote?.let { r ->
            remoteCall { r.playNext(tracks, _index.value) }
            return
        }
        player.addMediaItems(at, tracks.map(::mediaItem))
    }

    /**
     * Keep what is playing and replace everything queued behind it with [tracks].
     *
     * What "start DJ Radio" means when a record is already on. Appending would have
     * the listener sit through the rest of that album before hearing a single thing
     * the set chose, and replacing the whole queue would restart the very track that
     * told the set what to look for. Neither is what the button says.
     *
     * The current item is left untouched — not removed and re-added — so playback
     * does not so much as blink: ExoPlayer only has to edit the timeline behind the
     * window it is playing.
     */
    fun replaceUpcoming(tracks: List<LocalTrack>) {
        val list = _queue.value
        if (list.isEmpty()) { setQueue(tracks); return }
        val at = _index.value.coerceIn(0, list.lastIndex)
        _queue.value = list.take(at + 1) + tracks
        // A queue drawn from across the library is not a record, whatever the one
        // album it happened to be a moment ago.
        smoothQueue = true
        remote?.let { r ->
            remoteCall { r.setQueue(_queue.value, at) }
            return
        }
        val tail = list.size - (at + 1)
        if (tail > 0) player.removeMediaItems(at + 1, list.size)
        if (tracks.isNotEmpty()) player.addMediaItems(tracks.map(::mediaItem))
        _index.value = player.currentMediaItemIndex
    }

    fun removeAt(position: Int) {
        val list = _queue.value
        if (position !in list.indices) return
        _queue.value = list.toMutableList().apply { removeAt(position) }
        if (_queue.value.isEmpty()) { clear(); return }
        remote?.let { r ->
            remoteCall { r.removeAt(position) }
            // The entry that was playing may have been the one removed; the next
            // poll reports where the player actually landed.
            if (position < _index.value) _index.value = _index.value - 1
            return
        }
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
        remote?.let { r ->
            remoteCall { r.move(from, to) }
            return
        }
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
        remote?.let { r ->
            // Sent as a queue replacement rather than as the player's own shuffle:
            // MPD's `shuffle` would reorder its queue behind this list, and the two
            // are addressed by index. The list on screen has to be the list the
            // player holds.
            remoteCall { r.setQueue(_queue.value, _index.value.coerceAtLeast(0)) }
            return
        }
        // One removal and one insertion rather than a move per track: ExoPlayer
        // recomputes the timeline on every edit, and n moves is n timeline updates
        // the UI would animate through.
        player.removeMediaItems(from, list.size)
        player.addMediaItems(from, tail.map(::mediaItem))
        _index.value = player.currentMediaItemIndex
    }

    fun clear() {
        stopTicker()
        dropCrossfade()
        remote?.let { r ->
            stopRemoteLoop()
            remoteCall { r.clear() }
            resetSession()
            return
        }
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
        resetSession()
    }

    /** Everything the screens read, back to "nothing is loaded". */
    private fun resetSession() {
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
        dropCrossfade()
        remote?.let { r ->
            showRemoteIndex(position)
            _playing.value = true
            remoteCall { r.playAt(position) }
            startRemoteLoop()
            return
        }
        // A queue that ran to the end left the player idle, and an idle player takes
        // the seek and then plays nothing — the same reason [resume] prepares.
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        player.seekTo(position, C.TIME_UNSET)
        startOutput()
    }

    fun pause() {
        // A tail left running under a pause is the one thing worse than a gap: the
        // room keeps playing a song the listener has just stopped.
        dropCrossfade()
        remote?.let { r ->
            _playing.value = false
            remoteCall { r.pause() }
            return
        }
        player.pause()
    }

    /**
     * Nothing is loaded to resume: the queue ran off its end, or an error tore the
     * player down, or there has never been one.
     *
     * The distinction [resume] cannot make for its caller. Resuming a *paused* player
     * carries on where it was, which is right; resuming a *spent* one sits on a track
     * that has already finished, and the caller has to point it at something instead
     * — see `LibraryViewModel.startOrResume`.
     */
    val stopped: Boolean
        get() = livePlayer?.playbackState.let {
            it == null || it == Player.STATE_IDLE || it == Player.STATE_ENDED
        }

    fun resume() {
        if (_queue.value.isEmpty()) return
        remote?.let { r ->
            _playing.value = true
            remoteCall { r.resume() }
            startRemoteLoop()
            return
        }
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
    fun previous() {
        dropCrossfade()
        remote?.let { r ->
            // The same convention the local player follows, decided here because the
            // remote player has its own idea of it: past the opening seconds,
            // "previous" restarts the track rather than leaving it.
            // The same threshold ExoPlayer is built with, applied by hand because
            // a remote player has its own idea of what "previous" means.
            if (_positionMs.value > RESTART_THRESHOLD_MS) {
                seekTo(0)
            } else {
                remoteCall { r.previous() }
            }
            return
        }
        player.seekToPrevious()
    }

    fun next() {
        dropCrossfade()
        remote?.let { r ->
            // The end of the queue is the remote player's to report — it is the one
            // holding the queue — so unlike the local path this does not decide for
            // itself that there is nothing next. The poll sees the stop and emits
            // [exhausted] then.
            if (_index.value >= _queue.value.lastIndex) _exhausted.tryEmit(Unit)
            remoteCall { r.next() }
            return
        }
        if (player.hasNextMediaItem()) {
            player.seekToNextMediaItem()
            return
        }
        // Nothing after this one. Say so first — with "keep the music going" on, the
        // library answers by appending more and calling [continueAfterEnd], and
        // without this the skip was silently swallowed and the same track stayed on
        // screen looking like a frozen button.
        _exhausted.tryEmit(Unit)
        // Then stop: running off the end is the end of the session, and holding audio
        // focus would leave every other player on the phone ducked.
        player.stop()
        _positionMs.value = 0
        _playing.value = false
    }

    /**
     * How many tracks are still to come **in play order**, counted no further than
     * [limit].
     *
     * The caller only ever asks "are there at least this many left", and asking the
     * timeline is the only way to answer it once shuffle mode is on: ExoPlayer
     * shuffles the *order* the list is read in and leaves the list alone, so
     * `queue.size - index` — which is what the radio's top-up used — is a statement
     * about where a track sits in the list and not about how much is left to hear.
     * With shuffle on it is wrong in both directions at once.
     *
     * Counted with repeat **off** whatever the player's own repeat mode is: under
     * repeat-all there is always a next track and the honest answer to "how much is
     * left" would be infinity, which is not a useful thing to tell a radio.
     */
    fun upcomingCount(limit: Int): Int {
        val p = livePlayer ?: return 0
        val timeline = p.currentTimeline
        if (timeline.isEmpty) return 0
        var counted = 0
        var at = p.currentMediaItemIndex
        while (counted < limit) {
            val next = timeline.getNextWindowIndex(at, Player.REPEAT_MODE_OFF, p.shuffleModeEnabled)
            if (next == C.INDEX_UNSET) break
            counted++
            at = next
        }
        return counted
    }

    /**
     * Carry on into whatever was appended after the queue had already run out.
     *
     * Deliberately `seekToNextMediaItem` rather than an index: with shuffle mode on
     * "the next one" is a question only the player can answer, and doing the
     * arithmetic here would play the list in order the moment the user shuffled.
     *
     * `prepare` first because [next] stops the player when it runs off the end, which
     * leaves it idle — an idle player accepts the seek and then makes no sound.
     */
    fun continueAfterEnd() {
        if (_queue.value.isEmpty()) return
        remote?.let { r ->
            val next = (_index.value + 1).coerceIn(0, _queue.value.lastIndex)
            showRemoteIndex(next)
            _playing.value = true
            remoteCall { r.playAt(next) }
            startRemoteLoop()
            return
        }
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
        if (!player.hasNextMediaItem()) return
        player.seekToNextMediaItem()
        startOutput()
    }

    fun seekTo(ms: Long) {
        dropCrossfade()
        val max = _durationMs.value.takeIf { it > 0 } ?: ms
        val target = ms.coerceIn(0, max)
        // Moved on screen before it is asked for, on both paths: a scrub bar that
        // springs back to where it was until a round trip lands reads as a failure.
        _positionMs.value = target
        remote?.let { r ->
            remoteCall { r.seekTo(target) }
            return
        }
        player.seekTo(target)
    }

    fun setVolume(v: Float) {
        userVolume = v.coerceIn(0f, 1f)
        remote?.let { r ->
            remoteCall { r.setVolume(userVolume) }
            return
        }
        applyGain()
    }

    /**
     * Playback speed, for audiobooks and podcasts — the same control the MA player
     * exposes, so the option doesn't quietly do nothing on this backend.
     */
    fun setSpeed(value: Float) {
        // MPD has no playback rate of its own, and there is no signal here to
        // resample — the phone is not decoding. Left alone rather than moved to a
        // number that describes nothing.
        if (remote != null) {
            _error.tryEmit("The server's player has no speed control")
            return
        }
        _speed.value = value.coerceIn(0.5f, 3f)
        applyPlaybackParameters(player)
    }

    /**
     * Push [_speed] and [loFiSpeedRatio] onto the player together.
     *
     * Speed *and* pitch, not `setPlaybackSpeed`: a tape or a turntable running
     * slow drops the pitch with the tempo, and pitch-preserved time-stretching
     * is exactly the artefact Lo-fi is trying not to sound like. The listener's
     * own speed control keeps its pitch, so it multiplies into the tempo and
     * leaves the pitch to the mode.
     *
     * Through the player rather than a [androidx.media3.common.audio.SonicAudioProcessor]
     * of our own in [TapRenderersFactory]'s chain, for two reasons: media3
     * accounts for its own playback parameters when it reports a position, and
     * would not account for ours — a 4.5% slow-down would put every reported
     * position, and so the scrub bar and the light show, that far out by the end
     * of a track; and that chain is fixed when the player is built, so a mode
     * toggled mid-session would not be heard until the next player. Both are
     * live here.
     *
     * [exclusiveOutput] is the one case that opts out: it exists to put nothing
     * of this app's between the decoder and the DAC, and a resample is very much
     * something. The sound modes are already hidden in that mode for the same
     * reason.
     *
     * @param target the player to write to, for the one caller that has a player
     *   in hand but has not published it as [livePlayer] yet ([buildPlayer]).
     */
    private fun applyPlaybackParameters(target: ExoPlayer? = livePlayer) {
        val p = target ?: return
        val ratio = if (exclusiveOutput) 1f else loFiSpeedRatio
        p.playbackParameters = PlaybackParameters(_speed.value * ratio, ratio)
    }

    fun setShuffle(on: Boolean) {
        _shuffle.value = on
        remote?.let { r ->
            remoteCall { r.setShuffle(on) }
            return
        }
        // ExoPlayer shuffles the *play order* and leaves the list alone, which is
        // what the queue UI wants: the list stays as the user built it.
        player.shuffleModeEnabled = on
    }

    fun cycleRepeat() = setRepeatMode(
        when (_repeatMode.value) {
            "off" -> "all"
            "all" -> "one"
            else -> "off"
        },
    )

    /**
     * Set repeat mode directly, rather than stepping through [cycleRepeat]'s
     * three-tap cycle.
     *
     * Added for [RemoteSessionPlayer]'s `handleSetRepeatMode`: the OS's own
     * transport (a lock-screen repeat button, Android Auto) names the mode it
     * wants outright, and without this the only way to reach it was calling
     * [cycleRepeat] up to twice — issuing one or two throwaway `setRepeat` RPCs to
     * MPD before the one that actually mattered.
     */
    fun setRepeatMode(mode: String) {
        _repeatMode.value = mode
        remote?.let { r ->
            remoteCall { r.setRepeat(mode) }
            return
        }
        player.repeatMode = when (mode) {
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
        // The tail deck holds a decoder and a mixer client of its own, and neither
        // is reachable through [livePlayer] — releasing only the main player would
        // leave a second one playing into a process that thinks it has stopped.
        deck.cancel()
        crossfadeArmed = false
        crossfadeFor = null
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
     *
     * In [exclusiveOutput] mode this is unity and nothing else: `player.volume` is
     * an AudioTrack gain media3 applies, which is exactly the kind of thing
     * exclusive output promises to remove from the signal. Volume belongs to the
     * device or the DAC now, not this app — see [ExclusiveOutput].
     */
    private fun applyGain() {
        if (exclusiveOutput) {
            player.volume = 1f
            return
        }
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

    /**
     * The second deck, built once and reused: it holds no decoder between
     * transitions (see [CrossfadeDeck.cancel]) so an idle one costs an object.
     */
    private val deck = CrossfadeDeck(context, scope)

    /**
     * Seconds of **overlapping** crossfade, or 0 for none. DJ Radio's, and only
     * DJ Radio's.
     *
     * The difference from [fadeSeconds] is not the number, it is that two tracks
     * are audible at once: the outgoing one moves to a second player ([deck]) and
     * keeps playing while this player has already started the next. That is what
     * removes the hole in the middle of a sequential fade — see [CrossfadeDeck].
     *
     * Set while DJ Radio is running and cleared when it stops, so ordinary
     * playback keeps exactly the fade behaviour it had. When this is non-zero it
     * supersedes [fadeSeconds] rather than stacking with it, or a track would be
     * ridden down by one mechanism while being cut away from by the other.
     */
    @Volatile
    var djCrossfadeSeconds: Int = 0
        set(value) {
            field = value.coerceIn(0, AppSettings.MAX_DJ_CROSSFADE_S)
            // Turning it off has to take the deck with it, or a set stopped
            // mid-transition leaves a tail playing with nothing driving its ramp.
            if (field == 0) dropCrossfade()
        }

    /** The tail deck is rolling silently, waiting for its hand-over. */
    @Volatile
    private var crossfadeArmed = false

    /**
     * The track a hand-over has already been attempted for.
     *
     * Set whether or not the deck actually built, which is the point of it: a
     * second player that failed to construct once will fail again 250 ms later, and
     * without this the ticker would try — and throw away — a decoder on every tick
     * for the whole length of the window.
     *
     * Cleared by [onMediaItemTransition], so the next track gets its own attempt.
     */
    @Volatile
    private var crossfadeFor: String? = null

    /**
     * Plan the join off the offline analysis rather than off the clock — see
     * [SmartCrossfade]. Pushed down from `AppSettings.djRadioSmartFade` while a set
     * is running; with it off the transition is the plain "overlap the last N
     * seconds" one.
     */
    @Volatile
    var djSmartFade: Boolean = true

    /**
     * The transition this track is going to make, worked out once when the deck is
     * armed rather than re-derived on every tick.
     *
     * Null until then, and null again the moment the hand-over fires or is dropped.
     * Holding it is what lets the *incoming* side know where its own ramp starts —
     * a smart plan can drop the needle past a silent lead-in, and a fade measured
     * from zero would be over before the first note.
     */
    @Volatile
    private var mixPlan: MixPlan? = null

    /** A hand-over has just fired; the track arriving next is being mixed into. */
    @Volatile
    private var fadeInPending = false

    /** The window and entry point [fadeAt] ramps the incoming track over. */
    @Volatile
    private var fadeInFromMs: Long = 0L

    @Volatile
    private var fadeInWindowS: Float = 0f

    /**
     * The track the fade-*in* half of a crossfade applies to, or null.
     *
     * A track this player merely *arrived at* — the listener pressed next, or the
     * first track of a queue — must start at full volume. Only one that a
     * hand-over cut away to is coming up under an outgoing tail, and only that one
     * should ramp. Resolved in [onMediaItemTransition] rather than guessed here,
     * because that is the one place that knows which track actually arrived.
     */
    @Volatile
    private var fadeInFor: String? = null

    /**
     * A DJ Radio crossfade is running or armed on this track.
     *
     * Read by [fadeAt] to know it must ramp *in* only: the outgoing track is the
     * deck's job now, and this player leaves its track early rather than riding it
     * down to nothing.
     */
    private val crossfading: Boolean get() = djCrossfadeSeconds > 0

    /**
     * A transition is armed, running, or still ramping in.
     *
     * The position loop runs four times a second, which is right for a scrub bar and
     * far too coarse for a two-second fade: eight steps of 12% each is heard as
     * stepping, and a hand-over that can land up to 250 ms off its planned downbeat
     * has thrown away the alignment [SmartCrossfade] just worked out. While this is
     * true the loop runs at [CROSSFADE_TICK_MS] instead — the same rate the deck's
     * own ramp uses, so the two halves of the mix move together.
     */
    private val crossfadeBusy: Boolean
        get() = crossfading &&
            (crossfadeArmed || deck.active || (fadeInFor != null && fadeFactor < 0.999f))

    /**
     * Whatever the tail deck was doing, stop it.
     *
     * Called from every transport command that invalidates the transition in
     * progress — a skip, a seek, a pause, a new queue. The alternative is a tail
     * that keeps playing a song the listener has already left, which is the one
     * failure mode of a crossfade nobody forgives.
     */
    private fun dropCrossfade() {
        // Cleared unconditionally, ahead of the cheap exit below: a fade-in still
        // running with the deck already gone would otherwise keep the incoming
        // track quiet after the listener had asked for something else.
        fadeInPending = false
        fadeInFor = null
        mixPlan = null
        if (!crossfadeArmed && !deck.active) {
            if (fadeFactor != 1f) { fadeFactor = 1f; applyGain() }
            return
        }
        deck.cancel()
        crossfadeArmed = false
        crossfadeFor = null
        fadeFactor = 1f
        applyGain()
    }

    /**
     * Give up on this track's transition without offering to try again.
     *
     * The difference from [dropCrossfade] is one field: that one clears
     * [crossfadeFor], because a listener who skipped or seeked has changed what the
     * transition would even be and deserves a fresh attempt. This is the other case
     * — the attempt was made and did not come good — and clearing it there would
     * have the ticker build a second deck seconds from the end of the track, then a
     * third, each one later and more likely to hand over past the end.
     */
    private fun abandonCrossfade() {
        val attempted = crossfadeFor
        dropCrossfade()
        crossfadeFor = attempted
    }

    /**
     * Roll the tail deck up, and hand over to it when the window arrives.
     *
     * Runs off the position ticker rather than a scheduled callback, because the
     * thing it is timing against — the playhead — is what the ticker already reads,
     * and a 250 ms granularity on a four-second fade is a 6% error in a number the
     * listener chose by dragging a slider.
     *
     * Every early return here is a case where a crossfade would be wrong rather
     * than merely unavailable:
     *
     *  - **no next track** — there is nothing to cross *into*; the track ends.
     *  - **repeat one** — skipping forward is the opposite of what was asked.
     *  - **exclusive output** — the whole point of that mode is that nothing of
     *    ours touches the signal, and this is two gain stages and a second mixer
     *    client. See [ExclusiveOutput].
     *  - **not smooth** — the queue is one album, which is sequenced, and mixing
     *    a record into itself is vandalism.
     */
    private fun stepCrossfade(positionMs: Long, durationMs: Long) {
        val seconds = djCrossfadeSeconds
        if (seconds <= 0 || exclusiveOutput || !smoothQueue || remote != null) return
        val p = livePlayer ?: return
        if (!_playing.value) return
        val track = _current.value ?: return
        if (p.repeatMode == Player.REPEAT_MODE_ONE || !p.hasNextMediaItem()) {
            if (crossfadeArmed) dropCrossfade()
            return
        }

        if (!crossfadeArmed) {
            if (crossfadeFor == track.id) return
            val source = sourceOf(track) ?: return
            val local = track.offline
            val plan = planFor(track, p, durationMs) ?: return
            if (!SmartCrossfade.shouldArm(positionMs, plan, SmartCrossfade.prerollFor(local))) return
            crossfadeFor = track.id
            mixPlan = plan
            val gain = (userVolume * ReplayGain.factor(track.sourceQuality, replayGainMode))
                .coerceIn(0f, 1f)
            crossfadeArmed = deck.start(source, positionMs, gain, preferredOutput, _speed.value)
            if (!crossfadeArmed) mixPlan = null
            return
        }

        val plan = mixPlan ?: run { abandonCrossfade(); return }
        if (!SmartCrossfade.shouldHandOver(positionMs, plan)) {
            // Still in the pre-roll: keep the two copies together while it is free
            // to do so — see [CrossfadeDeck.align].
            deck.align(positionMs)
            return
        }

        // The deck was told to play seconds ago and may still be opening a
        // connection. Swapping to one that is not making sound yet is the failure
        // this whole mechanism exists to avoid: the outgoing side goes silent for
        // the length of the mix, which is a cut with a slow fade-in after it. So
        // wait — and if it is still not ready by the deadline, abandon the
        // transition and let the boundary play out gapless, which is a worse mix
        // and a perfectly good join.
        if (!deck.ready) {
            if (positionMs >= SmartCrossfade.handOverDeadlineMs(plan)) abandonCrossfade()
            return
        }

        // The swap. The deck comes up as this player leaves, and the incoming track
        // enters where the plan says its music actually starts.
        deck.handOver(plan.windowS)
        crossfadeArmed = false
        mixPlan = null
        fadeInPending = true
        fadeInFromMs = plan.incomingStartMs
        fadeInWindowS = plan.windowS
        fadeFactor = 0f
        applyGain()
        if (plan.incomingStartMs > 0) {
            // One seek rather than a skip and then a seek: two operations at a track
            // boundary is two chances for ExoPlayer to make a sound between them.
            p.seekTo(p.nextMediaItemIndex, plan.incomingStartMs)
        } else {
            p.seekToNextMediaItem()
        }
    }

    /**
     * How this boundary should be crossed, or null if it should not be crossfaded.
     *
     * Both scans come from [TrackScanStore.peek] — memory only, never disk, because
     * this runs on the position ticker. What makes that workable rather than a
     * lottery is [prefetchScans], which pulls both into memory a whole track ahead.
     * A scan that still is not there simply gets the fixed plan, which is what the
     * transition was before any of this.
     */
    private fun planFor(track: LocalTrack, p: ExoPlayer, durationMs: Long): MixPlan? {
        if (!djSmartFade) return SmartCrossfade.standardPlan(durationMs, djCrossfadeSeconds)
        val scans = SendpinApp.instance.trackScans.store
        val outgoing = scans.peek(TrackScanRepository.keyFor(track))
        val next = _queue.value.getOrNull(p.nextMediaItemIndex)
        val incoming = next?.let { scans.peek(TrackScanRepository.keyFor(it)) }
        return SmartCrossfade.plan(outgoing, incoming, durationMs, djCrossfadeSeconds, smart = true)
    }

    /**
     * Warm the scans for what is playing and what is next.
     *
     * The whole of what makes smart fades work in practice. [planFor] runs on the
     * position ticker and so cannot touch the disk; without this it would `peek` two
     * scans that are only resident if Light Sync happened to load them, which on a
     * library scanned last week is neither of them. Reading them a track ahead —
     * minutes of slack — turns "smart when you are lucky" into "smart whenever the
     * track has been scanned".
     *
     * Deliberately fire-and-forget and deliberately silent: a scan that is missing
     * or fails to load is a transition that falls back to the fixed plan, not an
     * error anybody needs to hear about.
     */
    private fun prefetchScans(current: LocalTrack?) {
        if (!crossfading || !djSmartFade) return
        val next = livePlayer?.let { p ->
            p.nextMediaItemIndex.takeIf { it >= 0 }?.let { _queue.value.getOrNull(it) }
        }
        val wanted = listOfNotNull(current, next)
        if (wanted.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            val repo = SendpinApp.instance.trackScans
            wanted.forEach { runCatching { repo.cached(it) } }
        }
    }

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
        // DJ Radio's overlapping crossfade owns the ramp outright when it is on —
        // it does not stack with [fadeSeconds], it replaces it. Both at once is not
        // a longer fade, it is two mechanisms disagreeing: the sequential one starts
        // riding the outgoing track down at its own window, and the hand-over then
        // passes a track already at a third of its volume to a deck that comes up at
        // full.
        //
        // What is left is a fade-*in* and only on the track a hand-over actually cut
        // away to. Everything else plays at full: its tail is the deck's job, not a
        // ramp here.
        if (crossfading && smoothQueue) {
            return if (fadeInFor != null && fadeInFor == _current.value?.id) {
                SmartCrossfade.fadeInAt(positionMs, fadeInFromMs, fadeInWindowS)
            } else {
                1f
            }
        }
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
            // Time since the playhead was last published, which is *not* the same as
            // time since the last tick — see below.
            var sincePublished = POSITION_TICK_MS
            while (isActive) {
                val pos = player.currentPosition.coerceAtLeast(0)
                // The playhead goes out at the rate a scrub bar needs, never at the
                // rate the fade ramp runs.
                //
                // This loop speeds up to 40 ms during a crossfade so the gain moves
                // smoothly and the hand-over lands on the downbeat it was planned
                // for. Publishing `_positionMs` on every one of those ticks was a
                // 25 Hz write into a StateFlow that `UnifiedNowPlaying` combines with
                // fifteen others and rebuilds a whole snapshot from — so the entire
                // now-playing state, and every composable reading it, was churning
                // twenty-five times a second at the exact moment the cover was
                // running its half-second page turn. Frames went on the floor and
                // the turn read as the screen flashing.
                //
                // Nothing outside this class ever wanted that resolution: it is the
                // ramp that needs a fine clock, and the ramp is entirely internal.
                if (sincePublished >= POSITION_TICK_MS) {
                    _positionMs.value = pos
                    sincePublished = 0
                }
                // In exclusive mode applyGain() is a fixed 1f whatever these compute,
                // so there is nothing to converge toward — skip the arithmetic rather
                // than spend it on a gain that is thrown away every tick.
                if (!exclusiveOutput) {
                    stepCrossfade(pos, _durationMs.value)
                    if ((fadeSeconds > 0 || crossfading) && smoothQueue) {
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
                }
                val wait = if (crossfadeBusy) CROSSFADE_TICK_MS else POSITION_TICK_MS
                delay(wait)
                sincePublished += wait
                // The loop outlives a pause only for as long as a transition needs it
                // — see [onIsPlayingChanged]. Once neither holds, it is done.
                if (!_playing.value && !crossfadeBusy) break
            }
        }
    }

    private fun stopTicker() { ticker?.cancel(); ticker = null }

    private companion object {
        /** Past this into a track, Previous restarts it instead of going back one. */
        const val RESTART_THRESHOLD_MS = 4_000L

        /**
         * Lo-fi's persistent slow-down at full intensity: up to 4.5% slower,
         * speed and pitch together — a physically slower playback, the way a
         * tape or turntable actually running slow sounds, not a pitch-
         * preserved time-stretch. Tasteful end of what "slowed" mixes
         * typically use (commonly cited from the low single digits up to
         * ~8%).
         */
        const val MAX_LO_FI_SLOWDOWN = 0.045f

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

        /**
         * How often the loop runs while a DJ Radio transition is in flight.
         *
         * 25 steps a second, matching [CrossfadeDeck]'s own ramp. It is what makes
         * the incoming fade a curve rather than a staircase, and it is also the
         * resolution of the hand-over itself — at 250 ms the swap could land a
         * quarter of a second off the downbeat [SmartCrossfade] chose for it, which
         * is most of a beat at any club tempo.
         */
        const val CROSSFADE_TICK_MS = 40L

        /**
         * How often a remote player is asked what it is doing.
         *
         * One second, with the position carried forward on the 250ms ticks in
         * between — see [startRemoteLoop]. Each poll is a connection to a machine
         * that is also decoding audio, so this is as often as is polite and as
         * rarely as a scrub bar can bear.
         */
        const val REMOTE_POLL_MS = 1000L
    }
}
