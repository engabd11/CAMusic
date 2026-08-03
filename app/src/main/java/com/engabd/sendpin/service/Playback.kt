package com.engabd.sendpin.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import com.engabd.sendpin.audio.AudioOutputs
import com.engabd.sendpin.audio.FormatNegotiator
import com.engabd.sendpin.audio.SendspinAudioEngine
import com.engabd.sendpin.audio.StreamQuality
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.discovery.MaDiscovery
import com.engabd.sendpin.discovery.PlayerIdentity
import com.engabd.sendpin.SendpinApp
import com.engabd.sendpin.ma.MaApiClient
import com.engabd.sendpin.ma.MaRepository
import com.engabd.sendpin.protocol.ProgressProjection
import com.engabd.sendpin.protocol.SendspinClient
import com.engabd.sendpin.protocol.SendspinIncoming
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The player connection, held at **process scope** (by [com.engabd.sendpin.SendpinApp])
 * so it survives the Activity/ViewModel being destroyed. The UI observes it through
 * [com.engabd.sendpin.ui.viewmodel.PlayerViewModel]; [SendspinConnectionService] keeps
 * the process alive, and [SendspinService] shows the media notification while playing.
 */
class Playback(private val app: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val settings = AppSettings(app)
    private val discovery = MaDiscovery(app)

    /**
     * This player's Sendspin `client_id`. Not a `val`: [reregister] mints a new one,
     * which is the only way to shake off a name Music Assistant has already committed
     * to for an existing player.
     */
    var playerId: String = PlayerIdentity.getPlayerId(app)
        private set
    /**
     * The hardware's name ("Samsung SM-S911B"), which is only the *fallback* for the
     * player's name. It used to be exposed as `playerName` and printed in Settings
     * under that heading, so a renamed player still read as the phone's model and the
     * rename looked like it had done nothing.
     */
    val deviceName: String = PlayerIdentity.getDefaultPlayerName()
    private val deviceInfo = PlayerIdentity.getDeviceInfo()

    val discoveredServers = discovery.discoveredServers
    val isDiscovering = discovery.isDiscovering

    private val _connected = MutableStateFlow(false); val connected: StateFlow<Boolean> = _connected
    private val _connectionStatus = MutableStateFlow("Disconnected"); val connectionStatus: StateFlow<String> = _connectionStatus

    private val _trackTitle = MutableStateFlow(""); val trackTitle: StateFlow<String> = _trackTitle
    private val _artist = MutableStateFlow(""); val artist: StateFlow<String> = _artist
    private val _album = MutableStateFlow(""); val album: StateFlow<String> = _album
    private val _artworkUrl = MutableStateFlow<String?>(null); val artworkUrl: StateFlow<String?> = _artworkUrl
    private val _isPlaying = MutableStateFlow(false); val isPlaying: StateFlow<Boolean> = _isPlaying
    private val _volume = MutableStateFlow(1.0f); val volume: StateFlow<Float> = _volume
    private val _currentFormat = MutableStateFlow("—"); val currentFormat: StateFlow<String> = _currentFormat
    private val _streamQuality = MutableStateFlow<StreamQuality?>(null); val streamQuality: StateFlow<StreamQuality?> = _streamQuality
    private val _serverUrl = MutableStateFlow(""); val serverUrl: StateFlow<String> = _serverUrl

    /**
     * `group/update` pushes from the player socket, forwarded to the UI. Group
     * membership changes reach us here the instant the server knows, which is
     * what lets the Speakers screen skip the wait for its next 5 s poll.
     */
    private val _groupUpdates = MutableSharedFlow<SendspinIncoming.GroupUpdate>(extraBufferCapacity = 16)
    val groupUpdates: SharedFlow<SendspinIncoming.GroupUpdate> = _groupUpdates.asSharedFlow()
    private val _connectionLog = MutableStateFlow<List<String>>(emptyList()); val connectionLog: StateFlow<List<String>> = _connectionLog
    // Exposed as flows so the media notification's seek bar tracks the track instead
    // of only refreshing when play/pause flips.
    private val _positionMs = MutableStateFlow(0L); val positionMs: StateFlow<Long> = _positionMs
    private val _durationMs = MutableStateFlow(0L); val durationMs: StateFlow<Long> = _durationMs
    val playbackPositionMs: Long get() = _positionMs.value
    val playbackDurationMs: Long get() = _durationMs.value

    val savedUsername: Flow<String> get() = settings.maUsername
    val savedPassword: Flow<String> get() = settings.maPassword
    val savedPlayerName: Flow<String> get() = settings.playerName

    val hasSavedServer: StateFlow<Boolean> =
        settings.maBaseUrl.map { it.isNotBlank() }.stateIn(scope, SharingStarted.Eagerly, false)
    private val _bootChecked = MutableStateFlow(false); val bootChecked: StateFlow<Boolean> = _bootChecked

    private var client: SendspinClient? = null
    private var engine: SendspinAudioEngine? = null
    private var discoveryStop: Job? = null
    private var volumeJob: Job? = null

    // --- audio focus -------------------------------------------------------

    private val audioManager by lazy { app.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    private var audioFocusRequest: AudioFocusRequest? = null
    @Volatile private var holdsFocus = false

    /**
     * A Sendspin player@v1 is a *clock slave*: the server drives the timeline and we
     * render it. Tearing the decoder down for a transient loss (a phone call) would
     * desync us from the rest of the group with no way to resume — the server keeps
     * streaming and we would simply go deaf. So transient losses attenuate to silence
     * and keep decoding; only a permanent loss stops the engine.
     *
     * [_isPlaying] is deliberately left alone for transient losses: it mirrors what the
     * *server* is doing, and the server is still playing.
     */
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when (change) {
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> engine?.setVolume(volume.value * 0.3f)
            AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> engine?.setVolume(0f)
            AudioManager.AUDIOFOCUS_GAIN -> engine?.setVolume(volume.value)
            AudioManager.AUDIOFOCUS_LOSS -> {
                // Another app took over media for good — stop and release.
                engine?.stop()
                _isPlaying.value = false
                abandonAudioFocus()
            }
        }
    }

    private fun requestAudioFocus() {
        if (holdsFocus) return   // already held — re-requesting would leak the old request
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                .build()
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(attrs)
                .setAcceptsDelayedFocusGain(false)
                .setOnAudioFocusChangeListener(focusListener)
                .build()
            audioFocusRequest = req
            audioManager.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN)
        }
        holdsFocus = true
    }

    private fun abandonAudioFocus() {
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

    init {
        scope.launch {
            val base = settings.maBaseUrl.first()
            val user = settings.maUsername.first()
            if (base.isNotBlank() && user.isNotBlank()) connectToServer(sendspinUrlFrom(base))
            _bootChecked.value = true
        }
        // Project the playhead between `server/state` messages, so the bar moves
        // smoothly instead of stepping whenever the server happens to speak.
        //
        // Deliberately here and not in [startSendspin]: this watches `_isPlaying`,
        // which belongs to *this* object rather than to any one client, so launching
        // it per connection would stack a fresh collector — and a fresh ticker — on
        // every reconnect, each overwriting the single [positionTicker] handle and
        // leaking the one before it.
        scope.launch {
            _isPlaying.collect { playing ->
                positionTicker?.cancel()
                republishPosition()
                positionTicker = if (!playing) null else scope.launch {
                    while (true) {
                        delay(POSITION_TICK_MS)
                        republishPosition()
                    }
                }
            }
        }
    }

    // --- discovery --------------------------------------------------------

    fun startDiscovery() {
        discovery.startDiscovery()
        discoveryStop?.cancel()
        discoveryStop = scope.launch { delay(8_000); discovery.stopDiscovery() }
    }

    fun stopDiscovery() {
        discoveryStop?.cancel()
        discovery.stopDiscovery()
    }

    // --- connection -------------------------------------------------------

    fun connectToServer(url: String, username: String = "", password: String = "", name: String = "") {
        disconnect(stopService = false)
        _serverUrl.value = url
        _connectionStatus.value = "Signing in…"
        scope.launch {
            val user = username.ifBlank { settings.maUsername.first() }
            val pass = password.ifBlank { settings.maPassword.first() }
            val playerName = name.ifBlank { settings.playerName.first() }.ifBlank { PlayerIdentity.getDefaultPlayerName() }
            val base = httpBase(url)
            settings.setMa(base, user, pass)
            settings.setPlayerName(playerName)
            val hasCreds = user.isNotBlank() && pass.isNotBlank()
            val token = if (hasCreds) fetchMaToken(base, user, pass) else null
            if (hasCreds && token == null) {
                _connectionStatus.value = "Sign-in failed — check username / password"
                return@launch
            }
            startSendspin(url, token, playerName)
        }
    }

    private suspend fun fetchMaToken(base: String, user: String, pass: String): String? {
        val api = MaApiClient()
        return try {
            api.connect(base, token = null, username = user, password = pass)
            val end = withTimeoutOrNull(12_000) {
                api.state.first { it == MaApiClient.State.CONNECTED || it == MaApiClient.State.ERROR }
            }
            if (end == MaApiClient.State.CONNECTED) api.authToken else null
        } catch (_: Exception) { null } finally { api.disconnect() }
    }

    private fun startSendspin(url: String, token: String?, name: String) {
        val c = SendspinClient(); client = c
        val eng = SendspinAudioEngine(c.clock); engine = eng

        scope.launch { c.state.collect { _connected.value = it == SendspinClient.State.CONNECTED } }
        scope.launch { c.statusText.collect { _connectionStatus.value = it } }
        scope.launch { c.events.collect { _connectionLog.value = it } }
        scope.launch {
            c.nowPlaying.collect { np ->
                _trackTitle.value = np?.title ?: ""
                _artist.value = np?.artist ?: ""
                _album.value = np?.album ?: ""
                _artworkUrl.value = np?.artworkUrl
                _durationMs.value = np?.durationMs ?: 0
                anchorProgress(c, np)
            }
        }
        scope.launch {
            c.streamFormat.collect { f ->
                // `StreamQuality.khz` rather than integer division, which rendered a
                // 44.1kHz stream as "44kHz" in the Settings readout.
                _currentFormat.value = f?.let {
                    "${StreamQuality.khz(it.sampleRate)}kHz / ${it.bitDepth}-bit / ${it.codec.uppercase()}"
                } ?: "—"
                _streamQuality.value = f?.let { StreamQuality(it.codec, it.sampleRate, it.bitDepth) }
            }
        }
        scope.launch {
            c.serverCommands.collect { cmd ->
                when (cmd.command) {
                    "volume" -> cmd.volume?.let { v -> _volume.value = v / 100f; eng.setVolume(v / 100f) }
                    "mute" -> cmd.mute?.let { m -> eng.setVolume(if (m) 0f else _volume.value) }
                }
            }
        }
        scope.launch { c.audioFrames.collect { bytes -> eng.submit(bytes) } }

        // `group/update` is a push on the player socket, so it lands the moment
        // grouping changes. Forwarded to whoever is showing group state — the
        // Speakers screen re-reads on it instead of waiting out its 5 s poll.
        scope.launch { c.groupUpdates.collect { _groupUpdates.tryEmit(it) } }

        // Audio output preferences. Read before the first stream/start, because
        // both are consumed when the AudioTrack is built (once per stream).
        scope.launch {
            settings.bitPerfect24Bit.collect { eng.bitPerfect = it }
        }
        scope.launch {
            settings.preferredAudioDeviceId.collect { id ->
                val am = app.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
                eng.setPreferredDevice(AudioOutputs.resolve(am, id))
            }
        }

        // The persistent connection service keeps the process (and this WebSocket)
        // alive in the background and shows a small "connected" notification with a
        // Stop action. It survives idle periods so TTS/announcements still arrive.
        SendspinConnectionService.start(app)

        // The media notification service shows album art + transport + seek bar,
        // but only while music is playing. It comes and goes; the connection stays.
        scope.launch {
            c.streamEvents.collect { ev ->
                when (ev) {
                    is SendspinClient.StreamEvent.Start -> {
                        requestAudioFocus()
                        eng.start(ev.format); _isPlaying.value = true
                        SendspinService.startMedia(app)
                    }
                    SendspinClient.StreamEvent.End -> {
                        eng.stop(); _isPlaying.value = false
                        // Not a stop: the server ends the stream between every track,
                        // so tearing the notification down here made it blink on every
                        // skip. The service holds it for a minute and cancels that on
                        // the next stream/start.
                        SendspinService.idleMedia(app)
                    }
                    SendspinClient.StreamEvent.Clear -> eng.flush()
                }
            }
        }

        // The advertised format list is what stops the server converting: it may only
        // stream something we listed. Built from the user's audio preferences, and sent
        // once in the hello — which is why changing those settings needs a reconnect.
        scope.launch {
            val codec = settings.sendspinCodec.first()
            connectedCodec = codec
            val formats = FormatNegotiator.supportedFormats(
                preferHiRes = settings.preferHiRes.first(),
                preferFlac = settings.preferFlac.first(),
                codec = codec.takeIf { it != "auto" },
                bitPerfect = settings.bitPerfect24Bit.first(),
            )
            c.connect(url, playerId, name, deviceInfo, formats, token)
            // The hello only *registers* a name; a player Music Assistant already
            // knows keeps whatever it was first registered under until its config is
            // changed. Push both the name and the format preference over the MA API
            // once the socket is up, so a rename sticks and MA's own per-player
            // setting agrees with what we just advertised.
            syncPlayerConfig(name, codec)
        }
    }

    // --- playhead ----------------------------------------------------------
    //
    // The Sendspin server pushes `server/state` on every change and stamps it with the
    // server-clock instant it was true at. That is a far better basis for the position
    // bar than Music Assistant's five-second poll: it needs no polling, it arrives the
    // moment anything happens, and — because the timestamp shares the audio frames'
    // time domain — the clock filter can say exactly how old the reading is instead of
    // the app assuming it describes the moment it was parsed.
    //
    // Assuming that is what made the bar jitter: a reading a second old was rendered as
    // "now", so the bar ran ahead, and the next message pulled it back.

    @Volatile private var progressAnchor: ProgressProjection.Anchor? = null
    private var positionTicker: Job? = null

    /**
     * Take a `server/state` progress reading, dated by the server's own clock.
     *
     * `metadata.timestamp` is in server time, so [ClockSync.serverTimeToLocal] converts
     * it to the instant on *this* phone's clock that the reading described. Sanity
     * checked before it is trusted: a server that sends a timestamp from a different
     * epoch (or one that arrives before the filter has anything useful to say) would
     * otherwise anchor the bar somewhere absurd. Falling back to "now" is what the app
     * did unconditionally before, so the fallback is no worse than the old behaviour.
     */
    private fun anchorProgress(c: SendspinClient, np: com.engabd.sendpin.protocol.NowPlaying?) {
        val position = np?.progressMs
        if (position == null) {
            progressAnchor = null
            _positionMs.value = 0
            return
        }
        val nowUs = c.clock.nowUs()
        val stampedLocalUs = np.progressAtServerUs
            ?.takeIf { it > 0L && c.clock.isSynced() }
            ?.let { c.clock.serverTimeToLocal(it) }
            ?.takeIf { kotlin.math.abs(nowUs - it) <= MAX_ANCHOR_AGE_US }
        progressAnchor = ProgressProjection.Anchor(
            positionMs = position,
            atLocalUs = stampedLocalUs ?: nowUs,
            speedMilli = np.speedMilli,
            durationMs = np.durationMs ?: 0L,
        )
        republishPosition()
    }

    /** Publish the anchor projected to now — see [ProgressProjection]. */
    private fun republishPosition() {
        val a = progressAnchor
        _positionMs.value =
            if (a == null) 0L else ProgressProjection.project(a, clockNowUs(), _isPlaying.value)
    }

    private fun clockNowUs(): Long = client?.clock?.nowUs() ?: (System.nanoTime() / 1000)

    /**
     * What the last attempt to write this player's Music Assistant config did.
     * Surfaced in Settings — a rename that silently fails is indistinguishable from
     * one that never happened, which is exactly how the previous attempt at this
     * looked from the outside.
     */
    private val _configStatus = MutableStateFlow("")
    val configStatus: StateFlow<String> = _configStatus.asStateFlow()

    /**
     * Tell Music Assistant what this player is called and what it should be sent.
     *
     * The Sendspin hello only *registers* a name — the spec is explicit that the
     * protocol "provides no mechanism for updating the client name post-connection" —
     * so after first contact the display name lives in MA's per-player config and
     * nothing but this call can change it.
     *
     * Uses the app's own authenticated connection ([maRepo]), not a throwaway one.
     * The previous version opened a second, separately-authenticated client and
     * wrapped every call in a bare `runCatching`: `config/players/save` is an **ADMIN**
     * command, so on a server where that client wasn't an admin the save was refused
     * and the error was swallowed. The result is verified by reading the config back.
     */
    private suspend fun syncPlayerConfig(name: String, codec: String) {
        if (settings.maBaseUrl.first().isBlank()) return
        // MA has to have seen the hello before there is a player to configure. Asked
        // directly with `players/get` rather than scanning `players/all`, whose default
        // filters exclude unavailable and protocol players. A miss
        // here is not worth reporting: the hello already carried the right name, so a
        // freshly-registered player is correct with or without this call. Saying
        // "hasn't registered this player yet" after a rename that plainly worked was
        // just wrong.
        val known = withTimeoutOrNull(20_000) {
            while (maRepo.getPlayer(playerId) == null) delay(750)
            true
        }
        if (known != true) return
        // It registered, so the hello's name is what MA has. Remember it, so enabling
        // again with the same name doesn't needlessly mint another identity.
        settings.setRegisteredPlayerName(name)
        _configStatus.value = ""
        try {
            // Only meaningful for a player MA already knew under another name; a
            // brand-new registration already carries the right one.
            maRepo.renamePlayer(playerId, name)
        } catch (_: Exception) {
            // Refused (it is an admin command) — but the registration name stands.
        }
        // Best-effort by contrast: not every server build has this key, and the
        // advertised format list already decides what we actually receive.
        runCatching {
            maRepo.setPreferredSendspinFormat(playerId, if (codec == "auto") "automatic" else codec)
        }
    }

    // --- transport ---------------------------------------------------------
    //
    // The Sendspin protocol itself carries no transport: the server decides what
    // plays and when. Transport therefore goes back out over the Music Assistant
    // API against *this phone's own player*, which is exactly what the Now Playing
    // screen already does — so notification, lock screen and in-app controls all
    // drive the same queue.

    private val maRepo by lazy { MaRepository((app.applicationContext as SendpinApp).maApi) }

    /** Fire-and-forget an MA player command; a disconnected client just no-ops. */
    private fun transport(block: suspend (MaRepository) -> Unit) {
        scope.launch { runCatching { block(maRepo) } }
    }

    fun onPlayPause() = transport { if (_isPlaying.value) it.pause(playerId) else it.play(playerId) }

    fun onMediaNext() = transport { it.next(playerId) }

    fun onMediaPrevious() = transport { it.previous(playerId) }

    /** [positionSec] — seconds from the start of the current item, per `players/cmd/seek`. */
    fun onMediaSeek(positionSec: Int) = transport { it.seek(playerId, positionSec) }

    fun onVolumeChange(vol: Float) {
        _volume.value = vol
        engine?.setVolume(vol)
        volumeJob?.cancel()
        volumeJob = scope.launch { delay(180); client?.sendClientState(volume = (vol * 100).toInt()) }
    }

    /**
     * Whether a codec change can be applied to the live connection.
     *
     * The server may only send a format the client advertised in its hello, so a
     * mid-stream switch is only legal when everything was advertised — which is
     * what "auto" does. Connect on a single codec and the list has one entry, so
     * asking for another would be asking for something never offered; that case
     * still needs the reconnect.
     */
    val canSwitchFormatLive: Boolean get() = connectedCodec == "auto" && _connected.value

    /** The codec setting the current connection was opened with. */
    @Volatile private var connectedCodec: String = "auto"

    /**
     * Ask the server to switch codec mid-stream. It replies with a new
     * `stream/start` carrying the new format — no reconnect, and the engine
     * rebuilds itself off that message as it does for any other stream.
     *
     * Rate and depth follow whatever is playing now rather than being re-chosen
     * here: this is a codec change, and pinning 48/16 into it would quietly
     * resample a 44.1 kHz album as a side effect.
     */
    fun requestFormat(codec: String) {
        val live = client?.streamFormat?.value
        client?.sendRequestFormat(
            codec,
            sampleRate = live?.sampleRate ?: 48_000,
            bitDepth = live?.bitDepth ?: FormatNegotiator.DEFAULT_BIT_DEPTH,
        )
    }

    /**
     * Bring the player back up, optionally under a new [name].
     *
     * The name is saved *here*, before the connection is opened, because the hello is
     * the only place it's announced — a rename that lands after the socket is up is a
     * rename the server never hears about.
     */
    /**
     * Bring the player up under [name], registering afresh if the name has changed.
     *
     * Music Assistant keys a Sendspin player on its `client_id` and keeps the name it
     * was **first registered under** — the protocol has no rename message, and the
     * official app has no rename call at all, because there a name is chosen before
     * the player ever registers. So a name that differs from the one we last
     * successfully registered can only take effect by arriving as a new player, and
     * doing that here means enabling is all the user has to do. The old entry is left
     * in MA, unavailable, for them to delete.
     */
    fun enablePlayer(name: String = "", codec: String = "") = scope.launch {
        if (name.isNotBlank()) settings.setPlayerName(name)
        if (codec.isNotBlank()) settings.setSendspinCodec(codec)
        val wanted = settings.playerName.first()
        if (wanted.isNotBlank() && wanted != settings.registeredPlayerName.first()) {
            PlayerIdentity.newIdentity(app)
            playerId = PlayerIdentity.getPlayerId(app)
        }
        val base = settings.maBaseUrl.first()
        if (base.isNotBlank()) connectToServer(sendspinUrlFrom(base))
    }

    /**
     * Register with Music Assistant as a brand-new player, under [name].
     *
     * The last resort for a stuck name. MA keys a Sendspin player on its `client_id`
     * and keeps whatever name it first registered under — the protocol has no rename
     * message, and the official app has no rename call at all because its users pick a
     * name before the player ever registers. When the server-side config edit is
     * refused, arriving as someone new is the only move left, and it always works. The
     * old player stays in MA as an unavailable entry for the user to delete.
     */
    fun reregister(name: String) = scope.launch {
        if (name.isNotBlank()) settings.setPlayerName(name)
        disconnect(stopService = false)
        PlayerIdentity.newIdentity(app)
        playerId = PlayerIdentity.getPlayerId(app)
        _configStatus.value = ""
        val base = settings.maBaseUrl.first()
        if (base.isNotBlank()) connectToServer(sendspinUrlFrom(base))
    }

    /**
     * Rename a connected player without dropping the socket.
     *
     * The hello can't be re-sent on a live connection, but MA's player config is what
     * actually decides the displayed name, so a rename is just a config write.
     */
    fun renamePlayer(name: String) = scope.launch {
        if (name.isBlank()) return@launch
        settings.setPlayerName(name)
        syncPlayerConfig(name, settings.sendspinCodec.first())
    }

    fun disablePlayer() = disconnect()

    /**
     * Tear the player down. [reason] rides along in `client/goodbye`: pass
     * `"restart"` when the process is going away but expects to come back, so
     * MA holds the player slot instead of dropping it from the speaker list.
     */
    fun disconnect(stopService: Boolean = true, reason: String = "user_request") {
        abandonAudioFocus()
        engine?.stop(); engine = null
        client?.close(reason); client = null
        positionTicker?.cancel(); positionTicker = null
        progressAnchor = null
        _connected.value = false
        _connectionStatus.value = "Disconnected"
        _currentFormat.value = "—"
        _streamQuality.value = null
        _isPlaying.value = false
        _positionMs.value = 0
        _durationMs.value = 0
        if (stopService) {
            SendspinService.stopMedia(app)
            SendspinConnectionService.stop(app)
        }
    }

    private fun sendspinUrlFrom(base: String): String {
        val ws = base.trim().replace("https://", "wss://").replace("http://", "ws://")
            .let { if (it.startsWith("ws")) it else "ws://$it" }.trimEnd('/')
        return "$ws/sendspin"
    }

    private companion object {
        /** How often the projected playhead is republished while playing. */
        const val POSITION_TICK_MS = 250L

        /**
         * How far a `server/state` timestamp may sit from now and still be believed.
         * Beyond this the server is not speaking the clock we think it is, and the
         * arrival time is the safer anchor.
         */
        const val MAX_ANCHOR_AGE_US = 30_000_000L
    }

    private fun httpBase(url: String): String {
        var u = url.trim()
        if (u.startsWith("wss://")) u = "https://" + u.removePrefix("wss://")
        else if (u.startsWith("ws://")) u = "http://" + u.removePrefix("ws://")
        else if (!u.startsWith("http")) u = "http://$u"
        val schemeEnd = u.indexOf("://") + 3
        val pathStart = u.indexOf('/', schemeEnd)
        return if (pathStart >= 0) u.substring(0, pathStart) else u
    }
}
