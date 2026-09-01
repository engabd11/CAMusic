package com.engabd.sendpin

import android.app.Application
import android.graphics.Bitmap
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.engabd.sendpin.audio.LocalPlayer
import com.engabd.sendpin.audio.UsbDacMonitor
import com.engabd.sendpin.service.CallPauseObserver
import com.engabd.sendpin.crash.CrashReporter
import com.engabd.sendpin.download.DownloadManager
import com.engabd.sendpin.ma.MaApiClient
import com.engabd.sendpin.service.AppLifecycleObserver
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.data.Http
import com.engabd.sendpin.data.Platform
import com.engabd.sendpin.service.LocalPlaybackService
import com.engabd.sendpin.service.MaNowPlaying
import com.engabd.sendpin.service.Playback
import com.engabd.sendpin.service.PlaybackOwner
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
    val localPlayer: LocalPlayer by lazy {
        LocalPlayer(this).also { player ->
            // Announce a takeover to the Sendspin side rather than letting the two
            // fight it out through `AudioManager` — see [PlaybackOwner]. The lambda
            // body runs on invocation, long after this initialiser, so touching
            // [playbackOwner] here does not recurse into a half-built localPlayer.
            player.onTakingOutput = { playbackOwner.noteLocalTakingOver() }
        }
    }

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
     * The one authority on which player owns this phone's audio output.
     *
     * Five places used to infer this, each slightly differently, and four of the
     * five bugs found in the 2026-08-16 device session were instances of one of
     * them being right where another was wrong. See [PlaybackOwner] for the list
     * and for why it publishes *two* owners rather than one.
     */
    val playbackOwner: PlaybackOwner by lazy { PlaybackOwner(localPlayer, playback) }

    /**
     * What's playing right now, as one snapshot spanning [localPlayer], the Sendspin
     * path, and a remote MA speaker — the display-side counterpart to [playbackOwner].
     * Not read by anything shipped today ([SendspinService] and
     * [com.engabd.sendpin.ui.screens.DrivingBar] still compute their own, see
     * [com.engabd.sendpin.service.UnifiedNowPlaying]'s doc) — built for the next
     * surface that needs the same answer rather than a fourth derivation of it.
     */
    val unifiedNowPlaying: com.engabd.sendpin.service.UnifiedNowPlaying by lazy {
        com.engabd.sendpin.service.UnifiedNowPlaying(playback, localPlayer, playbackOwner, maNowPlaying, deviceVolume)
    }

    /**
     * Driving mode — the always-reachable transport for a phone in a cradle.
     *
     * Process-scoped and built eagerly in [onCreate] rather than on first use: its
     * whole job is to notice the car connecting, and something that only starts
     * watching once a screen asks for it would miss the one event it exists for.
     */
    val drivingMode: com.engabd.sendpin.service.DrivingMode by lazy {
        com.engabd.sendpin.service.DrivingMode(this)
    }

    /**
     * Notices a USB DAC connect and tells the user it can be pinned in Settings.
     * Process-scoped and started in [onCreate] for the same reason [drivingMode]
     * is: the connect it exists to notice can happen before any screen opens.
     */
    val usbDacMonitor: UsbDacMonitor by lazy { UsbDacMonitor(this) }

    /**
     * Pauses playback for a ringing/answered call. Opt-in — see
     * `AppSettings.pauseForCalls` — so unlike [drivingMode] this is not touched
     * unconditionally in [onCreate]; the settings collector there starts it only
     * when both the setting and the permission are already in place, and the
     * Settings row starts it directly the moment the permission is granted.
     */
    val callPauseObserver: CallPauseObserver by lazy { CallPauseObserver(this) }

    /**
     * Shake to skip, flip face-down to pause, double-tap the body to play/pause.
     * Opt-in like [callPauseObserver] — see `AppSettings.sensorGestures` — started
     * the same way: the settings collector in [onCreate] replays it from a prior
     * run, and the Settings row starts/stops it directly on toggle.
     */
    val playbackGestureMonitor: com.engabd.sendpin.gesture.PlaybackGestureMonitor by lazy {
        com.engabd.sendpin.gesture.PlaybackGestureMonitor(this)
    }

    /**
     * GPS speed for the speed-limit alert and speed-adaptive volume. Its own
     * `start()` gates location updates on driving-mode being active and at least
     * one of the two features being on, so — unlike [callPauseObserver] — this is
     * safe to call unconditionally from [onCreate]: it does nothing until both
     * conditions are true.
     */
    val speedMonitor: com.engabd.sendpin.service.SpeedMonitor by lazy {
        com.engabd.sendpin.service.SpeedMonitor(this, drivingMode)
    }

    /**
     * Whichever backend is actually producing sound right now, for
     * [directLightSync] — MA (via [com.engabd.sendpin.audio.SendspinNativeEngine])
     * when it has a tap installed *and* is playing, else [localPlayer] always.
     * That "else" is what keeps local playback's Light Sync behaviour identical
     * to before MA support existed: with nothing playing on MA, this is exactly
     * localPlayer's tap/lead/track — the same fixed values [directLightSync]
     * used to take as constructor arguments.
     *
     * The choice is [PlaybackOwner]'s `soundOwner`, not a second reading of the
     * same flows. This site and the direct-sync gate below both used to derive
     * it, and they derived it *differently* — this one from `isPlaying`, the gate
     * from a track being loaded — which is precisely how a paused MA player could
     * be driving one and not the other.
     *
     * `Eagerly`, not `Lazily`: [directLightSync] reads `.value` synchronously
     * in a few places (see its own docs), so this has to have emitted before
     * anything can call [directLightSync.start][com.engabd.sendpin.hue.DirectLightSync.start].
     */
    val activeLightSyncSource: StateFlow<com.engabd.sendpin.hue.ActiveLightSyncSource> by lazy {
        combine(
            localPlayer.current, playback.artworkUrl, playbackOwner.state,
            scanFrameSource.driving, com.engabd.sendpin.capture.PlaybackCapture.running,
        ) { values ->
            @Suppress("UNCHECKED_CAST")
            val localTrack = values[0] as com.engabd.sendpin.audio.LocalTrack?
            val maArtUrl = values[1] as String?
            val owner = values[2] as PlaybackOwner.State
            val scanDriving = values[3] as Boolean
            val captureRunning = values[4] as Boolean
            val maTap = owner.sendspinTap
            // The precedence lives in one pure function now — see
            // [com.engabd.sendpin.hue.LightSyncFeedPicker]. It used to be an `if` here
            // and a differently-shaped `if` in the gate below, which is how a paused MA
            // player could drive one and not the other.
            val feed = com.engabd.sendpin.hue.LightSyncFeedPicker.pick(
                sendspinPlayingHere = owner.soundOwner == PlaybackOwner.Who.SENDSPIN,
                hasSendspinTap = maTap != null,
                localPlaying = owner.soundOwner == PlaybackOwner.Who.LOCAL,
                captureRunning = captureRunning,
                scanDriving = scanDriving,
            )
            when (feed) {
                com.engabd.sendpin.hue.LightSyncFeed.SENDSPIN_PCM ->
                    com.engabd.sendpin.hue.ActiveLightSyncSource(
                        tap = maTap!!.first, lead = maTap.second, artUrl = maArtUrl,
                        scanTrack = null, feed = feed,
                    )
                // Another app's audio, through MediaProjection. Its own tap instance,
                // because the analysis ring has one writer by contract.
                com.engabd.sendpin.hue.LightSyncFeed.CAPTURE ->
                    com.engabd.sendpin.hue.ActiveLightSyncSource(
                        tap = com.engabd.sendpin.capture.PlaybackCapture.tap,
                        lead = com.engabd.sendpin.capture.PlaybackCapture.lead,
                        // No metadata for another app's track, so the palette falls
                        // back to whatever colour scheme is not album-derived.
                        artUrl = null, scanTrack = null, feed = feed,
                    )
                // Nothing audible reaches this phone, so the tap is a placeholder and
                // the frames come from [scanFrameSource] instead. The artwork is still
                // MA's, because the *colours* do not need audio.
                com.engabd.sendpin.hue.LightSyncFeed.SCAN_REMOTE ->
                    com.engabd.sendpin.hue.ActiveLightSyncSource(
                        tap = localPlayer.audioAnalysisTap, lead = localPlayer.audioLead,
                        artUrl = maArtUrl, scanTrack = null, feed = feed,
                    )
                else ->
                    com.engabd.sendpin.hue.ActiveLightSyncSource(
                        tap = localPlayer.audioAnalysisTap, lead = localPlayer.audioLead,
                        artUrl = localTrack?.artUrl, scanTrack = localTrack, feed = feed,
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
     * Who turned the master Light Sync switch on for a running ambience show, if
     * anyone — see [com.engabd.sendpin.hue.ambience.AmbienceSyncOwnership]. Lives
     * here, not on `EffectsViewModel`, because `LightSyncScreen`'s own master-switch
     * handler needs to read and update it too, and both are already process-scoped
     * consumers of this class.
     */
    val ambienceSyncOwnership =
        MutableStateFlow(com.engabd.sendpin.hue.ambience.AmbienceSyncOwnership.NONE)

    /**
     * The running ambience show's sound. Here for the same reason as
     * [ambienceSyncOwnership]: the show outlives the Effects screen's view model,
     * so its audio cannot be owned by it. See [AmbienceAudioHolder].
     */
    val ambienceAudio = com.engabd.sendpin.hue.ambience.AmbienceAudioHolder()

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
            // The scan-driven feed has to count as playing, or `renderLoop`'s
            // `isIdle = !playerPlaying || !fresh` runs the idle show straight over
            // perfectly good synthetic frames.
            isPlaying = combine(
                localPlayer.playing, playback.isPlaying, scanFrameSource.driving,
                com.engabd.sendpin.capture.PlaybackCapture.running,
            ) { local, ma, scanning, capturing -> local || ma || scanning || capturing },
        ).also { sync -> scanFrameSink = sync::onSynthFrame }
    }

    /**
     * Where [scanFrameSource] delivers its frames.
     *
     * Indirected through a var rather than passed in, because the two are mutually
     * dependent — the source is one of the inputs to [activeLightSyncSource], which
     * `DirectLightSync` takes in its constructor — and a `by lazy` cycle deadlocks
     * rather than failing loudly.
     */
    @Volatile
    private var scanFrameSink:
        ((com.engabd.sendpin.audio.AnalysisFrame, com.engabd.sendpin.audio.BeatGrid?, com.engabd.sendpin.audio.TrackScan, Float) -> Unit)? = null

    /**
     * The light show for a Music Assistant queue playing somewhere this phone cannot
     * hear. See [com.engabd.sendpin.hue.ScanFrameSource].
     */
    val scanFrameSource: com.engabd.sendpin.hue.ScanFrameSource by lazy {
        com.engabd.sendpin.hue.ScanFrameSource(
            scans = trackScans,
            nowPlaying = maNowPlaying,
            downloads = downloads,
            settings = com.engabd.sendpin.data.AppSettings(this),
            musicSource = { musicSource.value },
            sink = { frame, grid, scan, pos -> scanFrameSink?.invoke(frame, grid, scan, pos) },
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

    /**
     * End the ambience show from anywhere, including from the notification after
     * the Effects screen's view model has been cleared.
     *
     * `EffectsService.onStopRequested` used to be a lambda over that view model, so
     * once the Activity went away the notification's Stop button removed the
     * notification and left the show running. Routing it through the process scope
     * instead means the one place that knows how to end a show is reachable from
     * every place that can ask for one to end.
     */
    fun stopAmbienceShow() {
        appScope.launch {
            runCatching { directLightSync.stopAmbience() }
            ambienceAudio.release()
            if (ambienceSyncOwnership.value ==
                com.engabd.sendpin.hue.ambience.AmbienceSyncOwnership.AUTO_ENABLED
            ) {
                runCatching { AppSettings(this@SendpinApp).setLightSyncEnabled(false) }
            }
            ambienceSyncOwnership.value =
                com.engabd.sendpin.hue.ambience.AmbienceSyncOwnership.NONE
            com.engabd.sendpin.service.EffectsService.onStopRequested = null
            com.engabd.sendpin.service.EffectsService.stop(this@SendpinApp)
        }
    }

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
        // Driving mode, the USB DAC toast, call-pause and the speed alert are all
        // phone-in-your-pocket-or-car concepts with no TV equivalent — and the "tv"
        // flavor's manifest no longer declares the Bluetooth/phone-state/location
        // permissions two of them assume, so touching these on TV risks a
        // SecurityException rather than a graceful no-op. All four are `by lazy`,
        // so skipping them here means never constructing them, not just leaving
        // them idle.
        if (!Platform.isTelevision(this)) {
            // Touched here so its Bluetooth receiver is registered from process start.
            // Lazily built, it would only begin watching once something asked — and the
            // thing it is watching for is the car connecting, which happens before any
            // screen opens.
            drivingMode
            usbDacMonitor.start()
            // Opt-in, unlike the two above: only starts if a previous run already had
            // both the setting on and the permission granted. `CallPauseObserver.start`
            // itself no-ops without the permission, so this is safe to call blind — and
            // it runs off the main thread rather than blocking onCreate on a DataStore read.
            appScope.launch { if (AppSettings(this@SendpinApp).pauseForCalls.first()) callPauseObserver.start() }
            speedMonitor.start()
            appScope.launch { if (AppSettings(this@SendpinApp).sensorGestures.first()) playbackGestureMonitor.start() }
        }
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
        // Direct Light Sync runs whenever the user has switched it on. Not "whenever
        // something is playing" — that was the old rule and it made the feature
        // feel broken in the one moment it is most looked at: you turn Light Sync
        // on, and the room does nothing at all until you also start a track.
        //
        // Worse, it made the *start* unreliable rather than merely late. Opening the
        // bridge means fetching the entertainment configuration, a PUT to claim the
        // area, and a DTLS handshake with retransmit timeouts — a second or more of
        // work triggered by the first frame of audio. Navidrome tolerated that
        // because a local track's first second is quiet anyway; Music Assistant
        // often did not, and the show simply never picked the song up.
        //
        // So the bridge is claimed when the *switch* is, and the render loop's own
        // idle branch covers the gap: with nothing playing it runs the slow ambient
        // spatial glow (see `renderLoop`'s `isIdle`), and the moment audio arrives
        // it crosses to the reactive show with the channel already open and the
        // handshake already paid for. Something already playing when the switch goes
        // on gets the reactive show immediately, for the same reason.
        //
        // What this gate is *not* allowed to key on any more is playback state of
        // any kind. Both readings were tried and both were wrong in the room: on
        // `isPlaying` the bridge was torn down about two seconds after a pause
        // (`END_LINGER_MS`), so the lights snapped back instead of easing into the
        // idle show, which only begins fading in after five seconds of quiet; on
        // "has a session" it never opened for a paused-then-resumed MA player at
        // all. Neither question needed asking. See [PlaybackOwner] for the
        // active-vs-playing distinction that made both readings look reasonable.
        appScope.launch {
            val settings = com.engabd.sendpin.data.AppSettings(this@SendpinApp)
            // `started` keeps the lazy singleton lazy: a user on the Home Assistant
            // path never touches direct mode, so there is no reason to build its
            // bridge client and settings collectors just to stop something that
            // was never running.
            var started = false
            data class DirectSyncState(val mode: String, val enabled: Boolean, val configId: String)
            combine(
                settings.lightSyncMode,
                settings.lightSyncEnabled,
                settings.hueEntertainmentConfigId,
            ) { mode, enabled, configId -> DirectSyncState(mode, enabled, configId) }
                .distinctUntilChanged()
                .collect { state ->
                    val shouldRun = state.mode == "direct" && state.enabled
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
                        // Follows the same switch, for the same reason: what it costs
                        // while nothing is playing remotely is one idle collector.
                        scanFrameSource.start()
                    } else if (started) {
                        started = false
                        directLightSync.stop()
                        scanFrameSource.stop()
                        // Capture holds a foreground service and an ongoing
                        // notification; leaving it running for a show that is switched
                        // off would be a microphone-shaped permission doing nothing
                        // visible, which is the worst possible shape for it.
                        com.engabd.sendpin.capture.PlaybackCapture.stop(this@SendpinApp)
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
        // The chosen output rate. Read by the processor chain when it configures,
        // so this only has to keep the holder current - it does not rebuild
        // anything, and the setting's own copy says it applies to the next track.
        appScope.launch {
            AppSettings(this@SendpinApp).outputSampleRateHz.collect {
                com.engabd.sendpin.audio.OutputRate.hz = it
            }
        }

        // The local equaliser. One collector for the process: the processor sits in
        // a sink chain fixed when the player is built, so this is the only way a
        // slider move reaches it - and it reaches it on the next buffer rather than
        // the next track.
        appScope.launch {
            AppSettings(this@SendpinApp).localDsp.collect { localPlayer.localDsp.setConfig(it) }
        }

        // Genre-driven show presets. Here rather than inside DirectLightSync
        // because that class only ever *reads* settings, and this writes them —
        // the show it picks arrives back through the collectors DirectLightSync
        // already has, so there is one direction of flow and no new coupling.
        //
        // Keyed on the genre rather than on the track: a whole album of one genre
        // applies its preset once, at the first track, and re-applying the same
        // show between every track would re-roll a Song-scheme palette each time.
        appScope.launch {
            val settings = AppSettings(this@SendpinApp)
            activeLightSyncSource
                .map { it.scanTrack?.genre }
                .distinctUntilChanged()
                .collect { genre ->
                    if (!settings.genrePresetsEnabled.first()) return@collect
                    val preset = com.engabd.sendpin.hue.GenrePresetRule.presetFor(
                        rules = settings.genrePresetRules.first(),
                        presets = settings.showPresets.first(),
                        trackGenre = genre,
                    ) ?: return@collect
                    settings.applyShowPreset(preset)
                }
        }

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
            // MPD's covers don't come from a URL at all — they come back down its
            // protocol socket — so the loader is taught the scheme the MPD items
            // carry. See MpdArt.
            .components { add(com.engabd.sendpin.mpd.MpdArtFetcher.Factory()) }
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
