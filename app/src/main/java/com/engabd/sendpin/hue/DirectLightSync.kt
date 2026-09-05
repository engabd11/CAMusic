package com.engabd.sendpin.hue

import android.animation.ValueAnimator
import android.content.Context
import android.util.Log
import com.engabd.sendpin.audio.ANALYSIS_HOP
import com.engabd.sendpin.audio.ANALYSIS_SAMPLE_RATE
import com.engabd.sendpin.audio.AnalysisFrame
import com.engabd.sendpin.audio.AudioAnalysisTap
import com.engabd.sendpin.audio.AudioLead
import com.engabd.sendpin.audio.BeatGrid
import com.engabd.sendpin.audio.GestureKind
import com.engabd.sendpin.audio.GestureState
import com.engabd.sendpin.audio.GestureTracker
import com.engabd.sendpin.audio.LocalTrack
import com.engabd.sendpin.audio.SongPhase
import com.engabd.sendpin.audio.StructureState
import com.engabd.sendpin.audio.StructureTracker
import com.engabd.sendpin.audio.TempoTracker
import com.engabd.sendpin.audio.TrackScan
import com.engabd.sendpin.audio.TrackScanRepository
import com.engabd.sendpin.game.GameBand
import com.engabd.sendpin.game.GameChartSource
import androidx.core.graphics.drawable.toBitmap
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.engabd.sendpin.data.AppSettings
import com.engabd.sendpin.data.Crypto
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Orchestrates the direct Hue Bridge Light Sync connection.
 *
 * Lifecycle:
 * 1. [start] — fetch entertainment configs from the bridge, start the stream
 *    (PUT action:start), open the DTLS channel, create the [SyncoEngine].
 * 2. The [AudioAnalysisTap] feeds [AnalysisFrame]s at ~50 Hz. Each one advances
 *    the rhythm and structure models in [onAnalysisFrame] and is published for
 *    the render loop; nothing is rendered or sent there.
 * 3. [renderLoop] runs at 60 Hz on its own coroutine, renders the latest frame,
 *    holds it in [delayQueue] until the audio it describes is audible, then puts
 *    it through the safety limiters and the encoder onto the wire.
 * 4. [stop] — stop the stream (PUT action:stop), close the DTLS channel,
 *    release the engine.
 *
 * Three threads, and the split matters. The tap's analysis thread owns the
 * analyzer and the two trackers. This orchestrator's IO scope owns the session.
 * The render loop is what talks to the bridge — deliberately not the analysis
 * thread, which delivers hops in bursts as the decoder hands over buffers and
 * would send the bridge clusters of frames instead of an even stream.
 *
 * Keepalive: the bridge drops the entertainment session after ~10 s of silence,
 * so a loop resends the last frame every 9 s when the render loop has gone
 * quiet, and watches for bridge-side alerts.
 *
 * Failure handling distinguishes two cases. A network fault is retried with
 * backoff, keeping the engine so the show resumes rather than restarts. A
 * *bridge-initiated* teardown is never retried — that is the Hue app taking the
 * area, and grabbing it back is what makes its stop button look broken.
 */

private const val TAG = "DirectLightSync"

/**
 * The bridge closes an entertainment session after 10 s of silence, so the
 * keepalive has to beat that. Matches syncoV2's `KEEPALIVE_INTERVAL`.
 */
private const val KEEPALIVE_INTERVAL_MS = 9000L

/**
 * How long the artwork URL has to hold still before it is acted on.
 *
 * A backend handover emits a null between two covers, and a run of skips emits one URL
 * per track. Neither is worth an extraction. Short enough that the room still changes
 * colour with the song rather than after it.
 */
private const val ART_SETTLE_MS = 250L

/** The wire value that means "let the picker choose". */
const val INTENSITY_AUTO = "auto"

/** Minimum gap between repeats of a per-frame warning. See `logThrottled`. */
private const val LOG_THROTTLE_MS = 5000L

/**
 * Consecutive failed sends before the session is rebuilt. Half a second at
 * 60 Hz — long enough that a single dropped datagram is ignored, short enough
 * that a real Wi-Fi drop is noticed before the bridge times the area out.
 */
private const val SEND_FAILURES_BEFORE_RECONNECT = 30

/**
 * Reconnect backoff. Matches syncoV2's window: doubling from a second to ten,
 * over fourteen attempts, is a bit under two minutes of trying — long enough to
 * ride out a Wi-Fi roam or a router reboot without retrying forever.
 */
private const val RECONNECT_ATTEMPTS = 14
private const val RECONNECT_BASE_MS = 1_000L
private const val RECONNECT_MAX_MS = 10_000L

/**
 * Longest run of skipped identical frames before one is resent anyway.
 *
 * The Entertainment API asks for a continuous stream because UDP frames are
 * dropped without retry. Skipping a frame the bulbs already show is still safe —
 * a lost duplicate changes nothing — but the run has to be bounded so a packet
 * lost at the moment the room *does* change cannot leave it stale until the 9 s
 * keepalive. syncoV2 leans on its keepalive for this; a quarter second is a much
 * tighter floor for the same saving.
 */
private const val RESEND_INTERVAL_NANOS = 250_000_000L

/**
 * Below this, a colour change cannot be displayed. The lamps carry 12-bit xy and
 * 11-bit brightness, so 1/4096 is the finest step that can reach a bulb.
 */
private const val COLOUR_QUANT = 1f / 4096f

/**
 * Entertainment stream rate. The Hue Entertainment API is streamed at 50–60 Hz;
 * syncoV2 uses 60 (`const.DEFAULT_STREAM_FPS`) and so does this. Deliberately
 * higher than the ~50 Hz analysis rate — see [DirectLightSync.renderLoop].
 */
private const val STREAM_FPS = 60
private const val FRAME_PERIOD_NANOS = 1_000_000_000L / STREAM_FPS

/** Longest step the engine may be advanced by after a stall, in seconds. */
private const val MAX_STEP_S = 0.1f

/**
 * How long without an analysis frame before the room is treated as silent.
 *
 * This decides two things at once — whether the room runs the show or the idle
 * pattern, and which of "Reacting to the beat" / "Waiting for music on this phone"
 * the screen says — and it is deliberately one number so those two can never
 * disagree. See [ShowStatus].
 *
 * A quarter of a second sounds generous and is not. A frame gap is not a silence:
 * it is the analysis thread being descheduled, a decoder handing over a buffer late,
 * or a producer that writes in chunks. At 250 ms an ordinary hiccup crossed the line
 * both ways within a few frames, so the status line flickered between the two
 * sentences and the lamps flickered between two shows with it — which is what "the
 * status keeps changing and the light is erratic" was.
 *
 * Six hundred milliseconds is past anything a working pipeline produces and still
 * well short of a pause anyone would notice waiting for: the idle pattern is a slow
 * fade that does not begin for [IDLE_FADE_IN_DELAY_S] seconds anyway, so the extra
 * third of a second is invisible at the one moment it costs anything.
 */
private const val FRAME_STALE_NANOS = 600_000_000L

/** How long the player must be paused/stopped before the idle ambient show fades in. */
private const val IDLE_FADE_IN_DELAY_S = 5f

/** How long the idle ambient movement takes to fully fade in. */
private const val IDLE_FADE_IN_DURATION_S = 4f

/** What the engine renders when nothing is feeding it. */
private val SILENCE = AnalysisFrame()

/**
 * How far into a track a scan may arrive and still be adopted.
 *
 * One regime per track, decided once. A grid that appears mid-song replaces a
 * PLL that has by then found the beat itself, and the handover between two
 * clocks that agree on tempo but not quite on phase is a visible stumble — the
 * room skips. Better to stay causal for the rest of this play and be exact from
 * the first bar of the next one. syncoV2 draws the line in the same place
 * (`coordinator._MAP_COMMIT_WINDOW_S`) for the same reason.
 */
private const val MAP_COMMIT_WINDOW_S = 6f

/**
 * How far ahead a louder section counts as an approaching drop. Matches
 * syncoV2's `_PREDROP_WINDOW_S`.
 */
private const val PREDROP_WINDOW_S = 2f

/** How much louder the next section must be to read as a drop rather than a change. */
private const val DROP_LEVEL_STEP = 0.15f

// ── Rhythm game ──────────────────────────────────────────────────────────────────

/**
 * The dim level the room rests at in game mode.
 *
 * A floor, and an absolute one. It is not scaled by the brightness ceiling, because
 * it is not competing with it: the ceiling says how bright the show may *peak*, and
 * this says how dark the room sits between the moments the player earns. Scaling the
 * floor by the ceiling was the first version's mistake in miniature — it made the
 * whole room, reactions included, a function of a setting that was only ever meant
 * to cap the top.
 *
 * Clamped under the ceiling at the point of use, since a floor above the ceiling
 * would be a floor holding the room *up*.
 */
private const val GAME_FLOOR_LEVEL = 0.08f

/**
 * The floor's colour, as a multiplier per channel on [GAME_FLOOR_LEVEL].
 *
 * Very slightly cool, so a room being held down reads as deliberate rather than as a
 * warm lamp that failed to come up.
 */
private const val FLOOR_TINT_R = 0.90f
private const val FLOOR_TINT_G = 0.95f
private const val FLOOR_TINT_B = 1.15f

/** How long the gate takes to open. Short enough to read as the tap itself. */
private const val GATE_ATTACK_MS = 25f

/** How long it stays fully open on a hit with no combo behind it. */
private const val GATE_HOLD_MS = 90f

/**
 * Extra hold per unbroken note, and the combo at which that stops growing.
 *
 * At the cap this is roughly a third of a second of hold on top of the base, which
 * turns a long run from a string of separate flashes into a show that is very nearly
 * continuous. That is the reward, and it needs no explaining because it is simply
 * more of the thing the player is already playing for.
 */
private const val GATE_HOLD_PER_COMBO_MS = 10f
private const val GATE_HOLD_COMBO_CAP = 30

/**
 * How long the gate takes to close.
 *
 * Long enough that a room of Zigbee bulbs relaying at ~25 Hz renders the fall rather
 * than receiving one bright frame and one dark one, and long enough that a beat's
 * own reaction has time to play out inside the window rather than being cut off
 * mid-swell.
 */
private const val GATE_RELEASE_MS = 340f

/**
 * Whichever backend is actually producing sound right now, and what
 * [DirectLightSync] needs from it.
 *
 * Exactly one backend plays at a time (local or MA — see
 * `com.engabd.sendpin.service.Playback`'s `setBackend`-style exclusivity), and
 * [SendpinApp] is what decides which one this describes moment to moment.
 *
 * @param tap The tap actually installed in that backend's render chain — it
 *   must be *the same instance* the backend handed to its `TapRenderersFactory`
 *   (or, for MA via [com.engabd.sendpin.audio.SendspinNativeEngine], its Oboe
 *   sink), not a second one. Activating a tap the audio never flows through
 *   yields a connected bridge and lights that never move. A fresh instance
 *   every time the MA engine reconnects — see [DirectLightSync]'s rewiring.
 * @param lead How far the tap runs ahead of the speaker for *this* backend.
 * @param artUrl The cover art of whatever this backend is currently playing.
 * @param scanTrack The local library identity of what's playing, for offline
 *   scan lookup — null for MA, which has no stable per-track id in its
 *   protocol to key a scan by. Null is exactly the existing "not scanned yet"
 *   path [DirectLightSync] already degrades to gracefully.
 */
data class ActiveLightSyncSource(
    val tap: AudioAnalysisTap,
    val lead: AudioLead,
    val artUrl: String?,
    val scanTrack: LocalTrack?,
    /**
     * The album and artist a hand-picked palette is filed under, for
     * [CoverPaletteOverride.keysFor].
     *
     * Separate from [scanTrack] because the Music Assistant feed has no [LocalTrack]
     * and yet does know what record is playing — it simply knew it somewhere else, in
     * `Playback`'s own metadata. Keying MA's overrides on the artwork URL alone (the
     * only key left without this) filed them under a `/imageproxy` address that MA
     * re-issues with a different id and token on the next session, so a correction
     * saved on Monday was orphaned by Tuesday. Album-and-artist survives that.
     */
    val paletteAlbum: String? = null,
    val paletteArtist: String? = null,
    /**
     * Which of the four feeds this is. [tap] stays non-null for all of them — for
     * [LightSyncFeed.SCAN_REMOTE] it is simply the local player's tap, which is
     * receiving nothing, so wiring it is harmless and saves making every reader of
     * this field nullable for one case.
     */
    val feed: LightSyncFeed = LightSyncFeed.LOCAL_PCM,
)

class DirectLightSync(
    private val context: Context,
    /**
     * Whichever backend is currently playing, and what this needs from it. A
     * [StateFlow] specifically (not a plain [Flow]): its `.value` must be
     * synchronously available the moment [start] runs, with no "hasn't emitted
     * yet" race - see [SendpinApp.activeLightSyncSource]'s construction.
     */
    private val activeSource: StateFlow<ActiveLightSyncSource>,
    /**
     * Offline track analyses. Optional: with no scans the direct path behaves
     * exactly as it did before they existed — causal grid, live-estimated
     * character, no section knowledge — which is also what happens for any
     * individual track that has not been scanned yet (and always, for MA -
     * see [ActiveLightSyncSource.scanTrack]).
     */
    private val scans: TrackScanRepository? = null,
    /**
     * Whether whichever backend is currently active is playing audio. Used to
     * decide when the room has been idle long enough to switch from the
     * music-reactive show to the gentle ambient idle glow.
     */
    private val isPlaying: Flow<Boolean> = flowOf(false),
    private val settings: AppSettings = AppSettings(context),
) {
    /**
     * The tap [onFrame]/[onAnalysisReset] are currently hooked to, or null when
     * no session is running. Tracked separately from `activeSource.value.tap`
     * because the two can legitimately disagree for an instant — the source can
     * change (a backend switch, or MA reconnecting to a fresh engine) before
     * [rewireTap] has run - and because [onAnalysisFrame] (line ~522) needs
     * "the tap that is actually calling back right now", not "whatever the
     * source flow says right now".
     */
    @Volatile private var wiredTap: AudioAnalysisTap? = null

    /**
     * Point [onFrame]/[onAnalysisReset] at [source]'s tap, unhooking whichever
     * tap they previously pointed to. A no-op if it's already the right one -
     * every emission of [activeSource] runs through here, not just changes.
     */
    private fun rewireTap(source: ActiveLightSyncSource) {
        val old = wiredTap
        if (old === source.tap) return
        old?.let { it.setActive(false); it.onFrame = null; it.onAnalysisReset = null }
        source.tap.onFrame = ::onAnalysisFrame
        source.tap.onAnalysisReset = ::onAnalysisReset
        source.tap.setActive(true)
        wiredTap = source.tap
        latestFrameLagMs = 0f
        // A different player is a different lead, and the two are not close: the local
        // path's sink buffer is a couple of hundred milliseconds, while Music
        // Assistant's engine keeps seconds of decoded PCM in its native ring. Slewing
        // between them at [FrameDelayQueue.SLEW_MS_PER_S] takes the better part of
        // twenty seconds, and for all of it the lights are running ahead of the music
        // by the difference. Seed instead — the reason [FrameDelayQueue.resetDelay]
        // exists is "no history to slew from", and a source that has just been swapped
        // in has none.
        seedDelayOnNextLead = true
    }

    /**
     * The tap was just rewired and the delay should be seeded rather than slewed, as
     * soon as the new source reports a lead at all.
     *
     * Deferred rather than done in [rewireTap] because the switch happens on
     * `stream/start`, before the new engine has written its first chunk — its lead is
     * still [com.engabd.sendpin.audio.AudioLead.UNKNOWN] at that moment, and seeding
     * from null would just set zero and slew from there, which is the thing this
     * avoids.
     */
    @Volatile private var seedDelayOnNextLead = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * The paired bridge's id, mirrored off settings so the TLS hostname verifier
     * can read it synchronously during a handshake.
     */
    @Volatile private var pairedBridgeId: String = ""

    /** The bridge client — mDNS discovery, CLIP v2 API. */
    val bridgeClient = HueBridgeClient(context, expectedBridgeId = { pairedBridgeId })

    // Written on the orchestrator scope, read on the ExoPlayer audio thread and
    // by the settings collectors — volatile so a start/stop is seen promptly and
    // a half-built session is never observed.

    /** The DTLS client — opened on start, closed on stop. */
    @Volatile private var dtls: DtlsPskClient? = null

    /** The stream encoder — one per entertainment area. */
    @Volatile private var encoder: HueStreamEncoder? = null

    /** The effects engine. */
    @Volatile private var engine: SyncoEngine? = null

    /** Entertainment area channels. */
    private var channels: List<EntertainmentChannel> = emptyList()

    /** Last rendered frame, for keepalive resends. */
    @Volatile private var lastFrame: ByteArray? = null

    /** Latest analysis frame from the audio thread, and when it landed. */
    @Volatile private var latestFrame: AnalysisFrame? = null
    @Volatile private var latestFrameAt = 0L

    /** Playback state from this phone's player. */
    @Volatile private var playerPlaying = false
    /** When the player last stopped being truly playing, in engine time seconds. */
    @Volatile private var idleStartS = -1f
    /** How long the room has been idle for ambient fade calculations. */
    @Volatile private var idleT = 0f

    /**
     * Rhythm and structure models, advanced once per analysis hop in
     * [onAnalysisFrame]. Both are stateful and single-threaded — only the
     * analysis thread touches them — and their outputs are published as
     * immutable snapshots for the render loop to read.
     */
    private var tempo: TempoTracker? = null
    private var structure: StructureTracker? = null
    private var gestures: GestureTracker? = null

    @Volatile private var latestGrid: BeatGrid? = null
    @Volatile private var latestStructure: StructureState? = null
    @Volatile private var latestGesture: GestureState? = null

    /** Last gesture id logged, so each one is reported exactly once. */
    private var loggedGestureId = 0L

    /**
     * Whether the user has asked for room gestures, and whether the system will
     * allow them.
     *
     * Two flags rather than one because they mean different things: the setting
     * is a preference, and reduced motion is an accessibility instruction. A
     * gesture crossing the room is exactly the large peripheral movement that
     * setting exists to suppress.
     *
     * `ValueAnimator.areAnimatorsEnabled()` is read directly rather than through
     * `LocalReducedMotion`, which is a Compose CompositionLocal and unreachable
     * from here — but it is the same source of truth that composable reads.
     */
    private var spatialEnabled = false
    private var reducedMotion = false

    /** Whether [PhantomStageLayer] may read real stem energy off the scan. See [LayerContext.stems]. */
    private var stemSeparationEnabled = false

    // ── Light show layers ──────────────────────────────────────────────────
    //
    // Four additive, off-by-default post-processing layers — see
    // `docs/creative-light-shows.md`. Each is a small pure `LightShowLayer`;
    // [layerChain] is rebuilt whenever a flag flips (rare — a settings
    // toggle, not a per-frame event) rather than filtered per frame.

    /** Normalised room-cube position of every channel, cached alongside [channels]. */
    private var roomPositions: Map<Int, Vec3> = emptyMap()

    /**
     * True while the rhythm game owns the room — see [setGameMode] and
     * [applyGameGate].
     */
    @Volatile private var gameMode = false

    /**
     * The light engine's beat clock for the rhythm game, published each analysis
     * frame beside [latestGrid]. The game charts against what the room is about
     * to light — one clock, not two — and the null-grid case degrades to the
     * game's own PLL without any special handling here.
     *
     * Volatile reference, immutable payload, never mutated in place: same
     * discipline as [latestGrid].
     */
    @Volatile private var chartSource: GameChartSource? = null

    /** The engine's beat clock for the rhythm game's chart, as of the last frame. */
    fun gameChartSource(): GameChartSource? = chartSource

    /**
     * A stable identity for the track the room is currently lit by — the same key
     * the scan store uses, so the game's records line up with the tracks people
     * actually played, across backends. Null when nothing identifiable is playing.
     */
    fun currentGameTrackKey(): String? = scanKeyOf(currentTrack)

    /**
     * How far open the game's gate is, and since when.
     *
     * A single envelope over the whole room rather than a per-lamp overlay, because
     * what it gates is the show itself: when it is open the engine's own frame goes
     * out untouched, so a kick lights the room the way a kick does and a hi-hat the
     * way a hi-hat does, scan and all. There is nothing here that needs to know
     * which lamps those are — the engine already decided.
     *
     * The gate is additionally **shaped** by the struck note's band (see
     * [receiveGameHit]): low-band hits favour the floor lamps, high-band the
     * ceiling, and a melody hit rotates colour rather than punching brightness —
     * the room answers in the shape of the music.
     */
    @Volatile private var gateOpenedAtMs = 0L
    @Volatile private var gatePeak = 0f
    @Volatile private var gateHoldMs = 0f
    @Volatile private var gateBand: GameBand = GameBand.FULL

    /**
     * The game's lamp classes, rebuilt with the room. Index 0 = bass-leaning
     * lamps, 1 = top-leaning; a lamp's split is its height-band blend, the same
     * arithmetic the engine's heightFreq path uses, so "bass lamps" means the
     * same thing here as it does in the show.
     */
    private var gameBassLamps: Map<Int, Float> = emptyMap()
    private var gameTopLamps: Map<Int, Float> = emptyMap()

    /** What shape the lamps are in, cached alongside [channels]. */
    private var roomTopology: RoomTopology = RoomTopology.CLUSTER

    /** Seconds into the current track, published from [onAnalysisFrame]. */
    @Volatile private var latestPositionS: Float = -1f

    /**
     * How far behind the newest sample its backend has handed over [latestFrame]
     * sits, in milliseconds. Published from [onAnalysisFrame] alongside it.
     *
     * See [effectiveLeadMs] for what it is for, and
     * [com.engabd.sendpin.audio.AudioAnalysisTap.analysisLagS] for where it comes
     * from.
     */
    @Volatile private var latestFrameLagMs: Float = 0f

    /**
     * How long the frame being rendered right now should be held, expressed as a
     * lead for [FrameDelayQueue].
     *
     * [com.engabd.sendpin.audio.AudioLead] answers a question about the *newest*
     * sample the backend has handed to the tap: how long until that one is heard.
     * The frame being rendered describes audio [latestFrameLagMs] older than that,
     * which is heard exactly that much sooner — so the hold has to come down by the
     * same amount or the room runs late by it.
     *
     * On the local player the lag is a couple of hops and the term barely moves.
     * On Music Assistant it is the whole of `SendspinNativeEngine`'s start burst,
     * seconds of it, on every track change and every pause — which is what "the
     * lights are smooth on Navidrome and not on MA" was. Both paths compute the
     * hold the same way now; only the numbers differ.
     *
     * Null when the backend has no position yet, which [FrameDelayQueue] reads as
     * "hold what you have" rather than as zero.
     */
    private fun effectiveLeadMs(): Float? =
        activeSource.value.lead.leadMs?.let { (it - latestFrameLagMs).coerceAtLeast(0f) }

    /**
     * The user's brightness ceiling, mirrored from [SyncoEngine.brightness] so
     * the layer chain can see it.
     *
     * The engine applies it as the last step of its own render, and the chain
     * runs after that, so a layer with no view of it would be adding light back
     * on top of a ceiling the user set. Written by [applyBrightness] alongside
     * every write to the engine's own copy, so the two cannot drift.
     */
    @Volatile private var layerBrightness = 1f

    /** Set the brightness ceiling on the engine and on the layer chain together. */
    private fun applyBrightness(fraction: Float) {
        val clamped = fraction.coerceIn(0f, 1f)
        engine?.brightness = clamped
        layerBrightness = clamped
        // An ambience show bypasses the engine entirely, so it needs telling
        // separately or the ceiling would apply to music and not to effects.
        ambience?.setBrightness(clamped)
    }

    private val musicDnaLayer = MusicDnaLayer()
    private val emotionalArcLayer = EmotionalArcLayer()
    private val phantomStageLayer = PhantomStageLayer()
    private val phoneConductorLayer = PhoneConductorLayer(context)

    /**
     * Every layer, in chain order.
     *
     * The order is the contract: [PhoneConductorLayer] modulates the combined
     * output of everything else, so it goes last. [layerChain] is this list
     * filtered by [enabledLayerIds], which is what keeps enabling one layer from
     * changing where the others sit relative to each other.
     */
    private val allLayers = listOf(musicDnaLayer, emotionalArcLayer, phantomStageLayer, phoneConductorLayer)

    private val layerLock = Any()

    /** [LightShowLayer.id]s of the layers currently switched on. Guarded by [layerLock]. */
    private val enabledLayerIds = HashSet<String>()

    @Volatile private var layerChain: LayerChain = LayerChain.EMPTY

    /**
     * Switch one layer on or off and rebuild the chain.
     *
     * A layer coming *on* is reset first, so it starts from where the room
     * actually is rather than from wherever it was when the user last switched
     * it off — an Emotional Arc switched off mid-drop should not resume at that
     * drop's temperature an hour later. Only the layer that changed is reset:
     * flipping one toggle must not disturb a layer that was already running.
     */
    private fun setLayerEnabled(layer: LightShowLayer, on: Boolean) = synchronized(layerLock) {
        val changed = if (on) enabledLayerIds.add(layer.id) else enabledLayerIds.remove(layer.id)
        if (changed && on) layer.reset()
        layerChain = LayerChain(allLayers.filter { it.id in enabledLayerIds })
    }

    private val phoneConductorEnabled: Boolean
        get() = synchronized(layerLock) { phoneConductorLayer.id in enabledLayerIds }

    // ── Track scan state ───────────────────────────────────────────────────
    //
    // Written by the orchestrator scope as the track changes, read by the
    // analysis thread once a hop. The analysis thread is the only place the
    // scan is *applied*, because that is where the track position and the two
    // trackers already are.
    //
    /** The scan for the track playing now, once one is available. */
    @Volatile private var activeScan: TrackScan? = null

    /**
     * Whether this track's show is scheduled or causal. Null until decided; see
     * [MAP_COMMIT_WINDOW_S] for why it is decided exactly once.
     */
    @Volatile private var mapCommitted: Boolean? = null

    /**
     * Previous queried position, so a scheduled beat fires exactly once.
     *
     * Volatile because a track change clears it from the orchestrator scope
     * while the analysis thread is reading it — the value only ever goes stale
     * by a frame, but a stale *non-null* one is what makes a beat fire twice.
     */
    @Volatile private var mapPrevPos: Float? = null

    /** The section the playhead was in last frame, for scheduled-drop detection. */
    @Volatile private var mapSection: com.engabd.sendpin.audio.ScanSection? = null

    /**
     * The last album-art colours extracted, cached so they survive the race
     * between the `init` collector (which fires before [start] creates the
     * engine) and the engine becoming available. Applied in [start] if present.
     */
    @Volatile private var lastAlbumColours: AlbumColours? = null

    /**
     * Exactly the weights handed to [SyncoEngine.setAlbumColors] alongside
     * [lastAlbumColours], null included.
     *
     * Null and "even weights" are different requests to the engine, and the
     * stream-start replay used to re-derive which one to send from the colour scheme
     * — which cannot distinguish an override the user spaced evenly from one they
     * balanced by hand, because by then both are just a list of numbers. Storing
     * what was actually sent makes the replay a replay.
     */
    @Volatile private var lastAlbumWeights: List<Float>? = null

    /**
     * The ambience show that owns the room, or null when the music show does.
     *
     * Volatile because the render loop reads it every 16 ms on its own coroutine while
     * the UI starts and stops it from another.
     */
    @Volatile private var ambience: com.engabd.sendpin.hue.ambience.AmbienceSession? = null

    /**
     * True when [startAmbience] opened the bridge session itself.
     *
     * Effects has to work with the master Light Sync switch off — someone who wants a
     * fireplace has not necessarily asked for music-reactive lighting — so it opens the
     * session when there is not one. It must then close only what it opened, or stopping
     * an effect would tear down a music show that was running before it.
     */
    @Volatile private var ambienceOwnsSession = false

    private val _ambienceRunning = MutableStateFlow<String?>(null)

    /** Wire name of the running effect, for the UI. */
    val ambienceRunning: StateFlow<String?> = _ambienceRunning.asStateFlow()

    /** The art URL the collector last saw, for re-extraction on scheme change. */
    @Volatile private var lastArtUrl: String? = null

    /**
     * The last art URL that actually produced a palette.
     *
     * Distinct from [lastArtUrl], and the distinction is the whole fix for "the colours
     * are wrong when I play from the library, but right from the queue".
     *
     * `activeSource.artUrl` is not "the artwork of the current track" — it is derived
     * from whichever feed is currently winning, and
     * [com.engabd.sendpin.service.PlaybackOwner]'s `soundOwner` passes through `NONE`
     * during *any* backend handover. With NONE the feed picker falls back to LOCAL_PCM,
     * whose `localPlayer.current` was just cleared by `stop()`. So a transient null
     * reaches this class on every play started from the library — which calls
     * `localPlayer.stop()` / `stopMaPlayback()` first — while a queue advance stays
     * inside one backend and never emits one.
     *
     * That null used to clear both caches and call `setAlbumColors(emptyList())`, which
     * drops the engine to the Sunset fallback. Remembering the last good URL means a
     * handover blip cannot erase a palette, and the scheme-change collector has
     * something real to re-extract from.
     */
    @Volatile private var lastGoodArtUrl: String? = null

    /**
     * Recently extracted palettes, keyed by `url + scheme`.
     *
     * Extraction is a network fetch, a decode and a k-means over CIELAB, so switching
     * colour mode and back used to mean doing all of it again — and if that refetch
     * failed (MA covers go through `/imageproxy`, which the loader has to probe three
     * URL shapes to satisfy) the palette was simply lost. Keyed by scheme as well as
     * URL because v1 and v2 are different extractions of the same cover.
     *
     * Four entries: enough for a mode switch and a couple of tracks back, small enough
     * that it is never worth thinking about.
     */
    private val paletteCache = object : LinkedHashMap<String, AlbumColours>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, AlbumColours>) = size > 4
    }

    /**
     * The scanned Auto-intensity parameters for this track, or null when it is
     * being played causally. The picker takes its live-estimated defaults then,
     * exactly as it did before scans existed.
     */
    @Volatile private var activeProfile: com.engabd.sendpin.audio.IntensityProfile? = null

    /** The lag-free intensity signal at the current playhead. */
    @Volatile private var scannedSignal: Float? = null

    /** When the last packet went out, so the keepalive knows if it is needed. */
    @Volatile private var lastSendAt = 0L

    /** Whether the stream is active. */
    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active.asStateFlow()

    /**
     * Whether analysis frames are actually arriving, as [renderLoop] itself judges it.
     *
     * Published from inside the loop rather than recomputed by the UI, so the text on
     * screen and the pattern in the room can never disagree — which is exactly what
     * happened while the screen keyed on [active], a flag that only says the DTLS
     * session is open.
     */
    private val _framesFresh = MutableStateFlow(false)
    val framesFresh: StateFlow<Boolean> = _framesFresh.asStateFlow()

    /** Error state, surfaced to the UI. */
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // ── Entertainment areas ───────────────────────────────────────────────
    //
    // The bridge's list of entertainment areas, held here rather than fetched by
    // the Light Sync screen.
    //
    // The screen used to read them in a `LaunchedEffect` over screen-local state, and
    // a tab is a NavHost destination whose state does not survive being left — so
    // every visit to the tab emptied the list, showed a spinner and re-queried the
    // bridge over the network, which looked like the areas being rediscovered each
    // time. Nothing about them changes while the app runs unless the bridge itself
    // is re-paired, so they are read once at startup and again only when the bridge
    // credentials change. [refreshEntertainmentConfigs] is there for the one case
    // where a re-read is genuinely what the user asked for.

    private val _entertainmentConfigs = MutableStateFlow<List<EntertainmentConfig>>(emptyList())
    val entertainmentConfigs: StateFlow<List<EntertainmentConfig>> = _entertainmentConfigs.asStateFlow()

    private val _configsLoading = MutableStateFlow(false)
    val configsLoading: StateFlow<Boolean> = _configsLoading.asStateFlow()

    private val _configsError = MutableStateFlow<String?>(null)
    val configsError: StateFlow<String?> = _configsError.asStateFlow()

    /** The one in-flight read, so a forced refresh cannot stack two. */
    private var configsJob: Job? = null

    /**
     * Re-read the bridge's entertainment areas.
     *
     * Called for you when the bridge address or key changes; call it by hand only
     * when the user has asked for a re-read (the retry on the Light Sync screen).
     */
    fun refreshEntertainmentConfigs() {
        scope.launch {
            loadEntertainmentConfigs(
                host = settings.hueBridgeIp.first(),
                appKey = settings.hueAppKey.first(),
            )
        }
    }

    private suspend fun loadEntertainmentConfigs(host: String, appKey: String) {
        configsJob?.cancelAndJoin()
        if (host.isBlank() || appKey.isBlank()) {
            _entertainmentConfigs.value = emptyList()
            _configsError.value = null
            _configsLoading.value = false
            return
        }
        configsJob = scope.launch {
            _configsLoading.value = true
            _configsError.value = null
            try {
                val configs = bridgeClient.getEntertainmentConfigs(host, appKey)
                _entertainmentConfigs.value = configs
                // If an area is already streaming, default to it so opening the tab
                // shows the live room rather than the first area in the list. Only
                // when the stored choice is blank or not itself streaming — never
                // over a user's deliberate selection of an inactive area.
                val storedId = settings.hueEntertainmentConfigId.first()
                val stored = configs.find { it.id == storedId }
                if (stored == null || !stored.isStreaming) {
                    configs.firstOrNull { it.isStreaming }?.let { settings.setHueConfigId(it.id) }
                }
            } catch (e: Exception) {
                _configsError.value = e.message ?: "Could not reach the bridge"
            }
            _configsLoading.value = false
        }
    }

    /** Current tunables applied to the engine. */
    @Volatile private var activeTunables: Map<String, Float> = emptyMap()

    private var keepaliveJob: Job? = null
    private var renderJob: Job? = null
    private val running = AtomicBoolean(false)

    /** Set for the duration of [start]'s handshake. See the note at its use. */
    private val starting = AtomicBoolean(false)


    /**
     * Start the direct Light Sync session.
     *
     * Reads bridge credentials from [AppSettings], fetches the entertainment
     * area, starts the stream on the bridge, opens the DTLS channel, and
     * activates the audio tap.
     *
     * Safe to call from any dispatcher: the whole sequence moves to [Dispatchers.IO],
     * because the DTLS handshake is a blocking UDP exchange with retransmit timeouts
     * and would hang the caller's thread.
     */
    suspend fun start() = withContext(Dispatchers.IO) {
        if (running.get()) return@withContext

        val host = settings.hueBridgeIp.first()
        val appKey = settings.hueAppKey.first()
        val clientKey = settings.hueClientKey.first()
        val appId = settings.hueAppId.first()
        val configId = settings.hueEntertainmentConfigId.first()

        if (host.isBlank() || appKey.isBlank() || clientKey.isBlank() || configId.isBlank()) {
            _error.value = "Bridge not configured. Set up a bridge in Settings first."
            return@withContext
        }

        // `running` is not a guard on its own: it is set only at the *end* of the
        // sequence below, after an HTTPS round trip and a DTLS handshake, so two
        // callers arriving inside that window both read false and both opened a
        // session. A second `action: start` on one area looks to the bridge exactly
        // like another app taking it away, and poisons it for good — see the note on
        // [startAmbience]. This closes that window; the check above stays as the
        // cheap path for the overwhelmingly common already-running case.
        if (!starting.compareAndSet(false, true)) return@withContext

        try {
            // 1. Fetch the entertainment configuration (channels + positions).
            val configs = bridgeClient.getEntertainmentConfigs(host, appKey)
            val config = configs.firstOrNull { it.id == configId }
                ?: configs.firstOrNull()
                ?: throw DtlsException("No entertainment area found on the bridge")

            channels = config.channels
            roomPositions = normalizePositions(channels)
            roomTopology = classifyTopology(channels)

            // The game's lamp classes: each lamp's height-band blend, the same
            // arithmetic the engine's heightFreq path uses. Rebuilt here because
            // the lamps do not move while a session is up — the same argument
            // roomPositions makes.
            val bassLamps = HashMap<Int, Float>(channels.size)
            val topLamps = HashMap<Int, Float>(channels.size)
            for (ch in channels) {
                val nz = roomPositions[ch.channelId]?.z ?: 0.5f
                val lower = heightBandLower(nz)
                val frac = heightBandFrac(nz)
                // Bass weight: 1.0 pure sub_bass, 0.0 pure high, blended between.
                val bassW = 1f - (lower + frac) / (HEIGHT_BANDS.size - 1).coerceAtLeast(1)
                bassLamps[ch.channelId] = bassW.coerceIn(0f, 1f)
                topLamps[ch.channelId] = (1f - bassW).coerceIn(0f, 1f)
            }
            gameBassLamps = bassLamps
            gameTopLamps = topLamps

            // 2. Start the stream on the bridge (PUT action:start).
            // Passing our own application id lets the bridge tell "someone else has
            // this area" from "we already do" — a reconnect must still reclaim it.
            bridgeClient.startStream(host, appKey, config.id, appId)

            // 3. Open the DTLS channel.
            val psk = clientKey.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
            val identity = (appId.ifBlank { appKey }).toByteArray(Charsets.US_ASCII)
            val client = DtlsPskClient(host, 2100, identity, psk)
            client.connect()
            dtls = client

            // 4. Create the stream encoder + effects engine.
            delayQueue.resetDelay(effectiveLeadMs())
            lastSent = null
            sendFailures = 0
            revoked = false
            picker.reset()
            limiterMode = null
            selectLimiter(SyncMode.fromWire(settings.lightSyncIntensity.first()))
            safety?.reset()
            // Per-channel gamuts where the bridge reported them; anything it
            // didn't answer for falls back to Gamut C inside the encoder.
            encoder = HueStreamEncoder(
                config.id,
                gamuts = channels.mapNotNull { ch -> ch.gamut?.let { ch.channelId to it } }.toMap(),
            )
            engine = SyncoEngine(channels, config.configurationType).also {
                // The first thing to check when a room gesture looks wrong: a ring
                // misread as a field renders as a gesture that simply did nothing,
                // and no amount of watching the lights distinguishes the two.
                Log.i(TAG, "Entertainment area geometry: ${it.roomSummary}")
                it.mode = SyncMode.fromWire(settings.lightSyncIntensity.first())
                it.setScheme(ColorScheme.fromWire(settings.lightSyncColor.first()))
                it.brightness = settings.lightSyncBrightness.first() / 100f
                // Not via applyBrightness: the [engine] field is not assigned
                // until this `also` block returns, so it would write the ceiling
                // to the *previous* engine (or to nothing at all on the first
                // stream). Mirrored by hand here, and only here.
                layerBrightness = it.brightness
                it.setTunables(activeTunables)
                // Apply cached album colours if the collector already extracted
                // them before the engine existed. Without this the first track's
                // colours are silently lost — the collector fired from init, the
                // engine was null, and distinctUntilChanged won't re-emit.
                val ac = lastAlbumColours
                val scheme = ColorScheme.fromWire(settings.lightSyncColor.first())
                if (ac != null && (scheme == ColorScheme.ALBUM_ART || scheme == ColorScheme.ALBUM_ART_V2)) {
                    // Replayed exactly as sent — colours and weights both — rather
                    // than re-derived from the scheme. A user override is genuinely
                    // respected here now; it previously was not, because
                    // `lastAlbumColours` held the *extraction* and this line put the
                    // cover's own palette back over corrected colours whenever a
                    // bridge session opened mid-track.
                    it.setAlbumColors(ac.colors, lastAlbumWeights)
                }
            }

            // 5. Activate the audio tap.
            latestFrame = null
            latestFrameAt = 0L
            latestGrid = null
            latestStructure = null
            latestGesture = null
            latestPositionS = -1f
            latestFrameLagMs = 0f
            // A new session starts from the room as it is, not from whatever the
            // last one left behind — the layers outlive any one stream, since
            // they are plain fields on this object rather than per-session state.
            layerChain.reset()
            // The analyzer's frame period, not the render period: these step once
            // per hop, off the analyzer's own clock.
            val framePeriod = ANALYSIS_HOP.toFloat() / ANALYSIS_SAMPLE_RATE
            tempo = TempoTracker(framePeriod)
            structure = StructureTracker(framePeriod)
            gestures = GestureTracker(framePeriod)
            // Re-read per session: the user can turn animations off in system
            // settings between one song and the next.
            reducedMotion = !ValueAnimator.areAnimatorsEnabled()
            applySpatialGestures()
            rewireTap(activeSource.value)
            acquireLocks()

            running.set(true)
            _active.value = true
            _error.value = null

            // The setting may already have been on when this session started —
            // its own collector only fires on a *change*, and running was false
            // the last time it did. Battery consciousness: sensors only ever
            // run while both the setting and a stream are live.
            if (phoneConductorEnabled) phoneConductorLayer.start()

            // 6. Start the render/send loop and the keepalive.
            renderJob = scope.launch { renderLoop() }
            keepaliveJob = scope.launch { keepaliveLoop() }

            Log.i(TAG, "Direct Light Sync started: ${config.name} (${channels.size} channels)")
        } catch (e: HueStreamBusyException) {
            // Not a fault: someone else is using these lights. Reported at info,
            // because an error-level stack trace for "the Hue app is running" is
            // noise — and the message is the actionable part, not the trace.
            _error.value = e.message
            Log.i(TAG, "Entertainment area is in use by another app")
            cleanup()
        } catch (e: Exception) {
            _error.value = e.message ?: "Failed to start Light Sync"
            Log.e(TAG, "start failed", e)
            cleanup()
        } finally {
            starting.set(false)
        }
    }

    /**
     * Stop the direct Light Sync session and release the entertainment area.
     *
     * Suspends until the bridge has actually been told to stop. Two reasons it
     * cannot be fire-and-forget:
     *
     * - Its caller collects on the main dispatcher, and teardown does real
     *   network I/O — `close()` builds an AES-GCM `close_notify` record and puts
     *   it on the wire, and `stopStream` is an HTTPS PUT.
     * - An area change is a stop followed immediately by a start. When the stop's
     *   PUT was launched asynchronously it could land *after* the start's, and
     *   kill the session that had just been opened on the new area.
     */
    suspend fun stop() = withContext(Dispatchers.IO) {
        // Ownership is cleared *before* the show is stopped, so [stopAmbience] cannot
        // turn round and call this again — the session is already being closed here.
        ambienceOwnsSession = false
        stopAmbience()
        if (!running.getAndSet(false)) return@withContext
        cleanup()
    }

    // ---- Ambience shows -------------------------------------------------

    /**
     * Hand the room to an ambience show.
     *
     * Opens the bridge session if there is not one, because Effects has to work with the
     * master Light Sync switch off. Never opens a *second* one: a Hue bridge allows a
     * single streaming client per entertainment area, and the render loop's
     * `DtlsPeerClosed` branch deliberately never retries a bridge-initiated teardown —
     * so a second `action: start` from this same app would look exactly like the Hue app
     * taking the area away, and would poison the session for good.
     *
     * @return false if there is no bridge to talk to, or audio focus was refused.
     */
    suspend fun startAmbience(
        effect: com.engabd.sendpin.hue.ambience.AmbienceEffect,
        sink: com.engabd.sendpin.hue.ambience.AudioSink?,
        focus: com.engabd.sendpin.hue.ambience.FocusGate?,
        params: com.engabd.sendpin.hue.ambience.AmbienceParams,
        onAudioFailed: (String) -> Unit = {},
        /**
         * The recording the show should react to, when it is playing one.
         *
         * Null for a synthesised or silent show. Mutually exclusive with [sink]; see
         * the note on `AmbienceSession`'s own parameter.
         */
        analysis: com.engabd.sendpin.hue.ambience.AmbienceAudioAnalysis? = null,
    ): Boolean = withContext(Dispatchers.IO) {
        stopAmbience()
        if (!running.get()) {
            start()
            // start() is best-effort: no bridge, no key, no area all land here.
            if (!running.get()) return@withContext false
            ambienceOwnsSession = true
        }
        val chans = channels
        if (chans.isEmpty()) {
            if (ambienceOwnsSession) { ambienceOwnsSession = false; stop() }
            return@withContext false
        }
        val session = com.engabd.sendpin.hue.ambience.AmbienceSession(
            script = com.engabd.sendpin.hue.ambience.scriptFor(effect),
            room = com.engabd.sendpin.hue.ambience.RoomModel(chans),
            sink = sink,
            focus = focus,
            params = params,
            onAudioFailed = onAudioFailed,
            analysis = analysis,
        )
        if (!session.start(scope)) {
            if (ambienceOwnsSession) { ambienceOwnsSession = false; stop() }
            return@withContext false
        }
        // Both limiters start clean, or a per-channel value held from the music show
        // would leak into the first frames of the effect.
        rateLimiter.reset()
        safety?.reset()
        ambience = session
        _ambienceRunning.value = effect.wire
        true
    }

    /** Stop whatever ambience show is running, and close the session if Effects opened it. */
    suspend fun stopAmbience() {
        val session = ambience ?: return
        // Cleared *first*, so the very next render tick falls through to the ordinary
        // path rather than calling into a session that is being torn down.
        ambience = null
        _ambienceRunning.value = null
        runCatching { session.stop() }
        rateLimiter.reset()
        safety?.reset()
        // A queue still holding frames from before the effect would replay them into
        // the music show that resumes after it.
        delayQueue.clear()
        idleStartS = -1f
        idleT = 0f
        if (ambienceOwnsSession) {
            ambienceOwnsSession = false
            stop()
        }
    }

    /** Live parameter change — intensity or brightness moved while a show is running. */
    fun retuneAmbience(params: com.engabd.sendpin.hue.ambience.AmbienceParams) {
        ambience?.retune(params)
    }

    fun pauseAmbience() { ambience?.pause() }

    fun resumeAmbience() { ambience?.resume() }

    private suspend fun cleanup() {
        wiredTap?.let { it.setActive(false); it.onFrame = null; it.onAnalysisReset = null }
        wiredTap = null
        tempo = null
        structure = null
        latestGrid = null
        latestStructure = null

        // Always, regardless of the setting's last-known value: a session
        // stop must tear sensors down, not leave them registered because the
        // last toggle collector happened to fire while a stream was up.
        phoneConductorLayer.stop()

        renderJob?.cancel(); renderJob = null
        keepaliveJob?.cancel(); keepaliveJob = null

        engine = null
        encoder = null
        delayQueue.clear()
        lastSent = null

        // Close DTLS (sends close_notify) so the bridge frees the session at
        // once; without it a restart inside the ~10 s linger is ignored.
        dtls?.close()
        dtls = null

        // Stop the stream on the bridge, inline so callers can sequence against it.
        try {
            val host = settings.hueBridgeIp.first()
            val appKey = settings.hueAppKey.first()
            val configId = settings.hueEntertainmentConfigId.first()
            if (host.isNotBlank() && appKey.isNotBlank() && configId.isNotBlank()) {
                bridgeClient.stopStream(host, appKey, configId)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to stop stream on bridge: ${e.message}")
        }

        releaseLocks()
        _active.value = false
        lastFrame = null
    }

    // ── Analysis frame callback ────────────────────────────────────────────

    /**
     * Called on the tap's analysis thread with a new analysis frame.
     *
     * The rhythm and structure models are advanced here rather than in
     * [renderLoop] because both are driven by the analyzer's own `tAudio` clock
     * and expect exactly one step per hop. Stepping them from the 60 Hz render
     * loop would advance them at the wrong rate and against a clock they do not
     * share.
     *
     * Rendering still does not happen here. Hops arrive in bursts — the decoder
     * hands over a buffer at a time — so rendering on arrival would send the
     * bridge clusters of frames instead of an even stream. [renderLoop] paces
     * the output.
     */
    private fun onAnalysisFrame(frame: AnalysisFrame) {
        // The causal tracker runs every frame whether or not a scan is adopted.
        // It is the floor: if the scan turns out not to cover this position, or
        // its grid was never good enough to trust, the room still keeps time.
        var grid = tempo?.update(
            tAudio = frame.tAudio,
            fluxValue = frame.flux,
            beat = frame.beat,
            beatStrength = frame.beatStrength,
            bass = max(frame.bands["sub_bass"] ?: 0f, frame.bands["bass"] ?: 0f),
        )
        var arc = structure?.update(frame)
        var published = frame

        val scan = activeScan
        // The tap that is actually calling back right now, not activeSource.value.tap:
        // the two can disagree for an instant across a backend switch or MA
        // reconnect, and this callback only ever fires from whichever tap is
        // actually wired (see rewireTap).
        val pos = wiredTap?.analysisPositionS ?: Float.NaN
        // Read here rather than in the render loop for the same reason as `pos`:
        // both describe *this* frame, and the next hop moves them on.
        latestFrameLagMs = (wiredTap?.analysisLagS ?: 0f) * 1000f
        if (!pos.isNaN()) {
            // Published unconditionally (not just once a scan is committed) —
            // the light-show layers want "where in the track are we" whether
            // or not a scan exists for it.
            latestPositionS = pos
            // Decided once per track, and only inside the window. Before it, a
            // missing scan means "not arrived yet", not "there isn't one".
            if (mapCommitted == null) {
                if (scan != null && pos <= MAP_COMMIT_WINDOW_S) mapCommitted = true
                else if (pos > MAP_COMMIT_WINDOW_S) mapCommitted = false
            }
            if (mapCommitted == true && scan != null) {
                scan.gridAt(pos, mapPrevPos)?.let { grid = it }
                arc = arc?.let { enrichWithScan(it, scan, pos) }
                scannedSignal = scan.intensitySignalAt(pos)
                // The one absolute measure a per-bin AGC cannot leave behind.
                // Extreme's per-lamp brightness reads it; every other rung
                // ignores it, so attaching it unconditionally costs nothing.
                if (scan.melbankRef.isNotEmpty()) {
                    published = frame.copy(melbankRef = scan.melbankRef)
                }
                mapPrevPos = pos
            }
        }

        // Fed the structure this frame produced, not the previous one: a swell is
        // a build, and the tracker has just finished saying whether this is one.
        val gest = gestures?.update(frame, arc)
        if (gest != null && gest.kind != GestureKind.NONE && gest.id != loggedGestureId) {
            loggedGestureId = gest.id
            // Logged whether or not anything is rendered, which is what makes the
            // detector judgeable against real tracks before trusting it with the
            // room. The budget being *hit* is the interesting signal.
            logThrottled(
                "Gesture ${gest.kind} strength=${"%.2f".format(gest.strength)} " +
                    "dur=${"%.1f".format(gest.durationS)}s " +
                    "pan=${"%.2f".format(gest.fromPan)}->${"%.2f".format(gest.toPan)} " +
                    "rise=${"%.2f".format(gest.rise)} stereo=${"%.2f".format(gest.stereo)}" +
                    if (gestures?.budgetHit == true) " (budget hit this track)" else ""
            )
        }

        latestGrid = grid
        latestStructure = arc
        latestGesture = gest
        latestFrame = published
        latestFrameAt = System.nanoTime()
        // The rhythm game charts against the very grid this frame will light up.
        // Published alongside the volatile snapshot trio with the same discipline:
        // a reference assignment, read by the game on the main thread, never
        // mutated in place afterwards (BeatGrid and TrackScan are immutable).
        chartSource = GameChartSource(
            grid = grid,
            scan = if (mapCommitted == true) activeScan else null,
            frameTAudioS = if (pos.isNaN()) frame.tAudio else pos,
        )
    }

    /**
     * A frame synthesised from a [TrackScan] for a player this phone cannot hear.
     *
     * Deliberately **not** routed through [onAnalysisFrame]. That path feeds the
     * causal [TempoTracker], [StructureTracker] and [GestureTracker], whose entire job
     * is to *find* what this frame was already built from — handing them their own
     * output would have them lock onto it and then be overridden by it. It would also
     * ask [GestureTracker] to read a `pan` that cannot exist here.
     *
     * Everything downstream is unchanged: [renderLoop] sees `latestFrameAt` move, so
     * `fresh` goes true and the ordinary render path runs.
     */
    internal fun onSynthFrame(frame: AnalysisFrame, grid: BeatGrid?, scan: TrackScan, posS: Float) {
        // Only when the scan really is the feed. [ScanFrameSource] decides to drive
        // from [com.engabd.sendpin.service.MaNowPlaying] alone — "a Music Assistant
        // player that is not this one is playing" — and that is not the same question
        // as "this phone can hear nothing". Put this phone in a group behind another
        // speaker and both are true at once: MA reports the group's leader, which is
        // not us, while the Sendspin engine is decoding the group's audio right here
        // and feeding the tap.
        //
        // [LightSyncFeedPicker] already resolves that correctly and hands the room to
        // the real PCM. Nothing stopped the synth from writing anyway, though, so the
        // two producers took turns overwriting `latestFrame`, `latestGrid` and
        // `latestStructure` at ~50 Hz each and the render loop drew whichever landed
        // last — a show alternating between a spectrum and a beat schedule, frame by
        // frame. That is the erratic half of "the light is erratic on the MA player".
        //
        // Cheap enough to test per frame: one StateFlow read of a value that changes
        // only on a backend handover.
        if (activeSource.value.feed != LightSyncFeed.SCAN_REMOTE) return
        latestGrid = grid
        // The scan's own structure, through the very function the live path uses to
        // enrich a causal tracker's guess — here there is no guess to enrich, so it
        // starts from a neutral state and everything in the result comes from the
        // scan. One implementation of "what does this section mean", not two.
        latestStructure = enrichWithScan(StructureState(), scan, posS)
        // No stereo field, so no gestures. See [AnalysisFrame.pan].
        latestGesture = null
        latestPositionS = posS
        // What the layer chain reads for track-relative effects. Set here because the
        // usual owner ([onTrackChanged]) follows the *local* player's track, which is
        // not what is playing.
        activeScan = scan
        latestFrame = frame
        latestFrameAt = System.nanoTime()
    }

    /**
     * Fill in the two things a causal structure tracker cannot know: which
     * section this is, and when the next one lands.
     *
     * The live tracker guesses a drop from "a build that has nearly maxed out",
     * which is right often enough to be worth having and never early enough to
     * anticipate. A scanned section list turns that into a countdown, so the
     * pull-down before a drop can be timed against the boundary instead of
     * reacting once it has already happened.
     */
    private fun enrichWithScan(state: StructureState, scan: TrackScan, pos: Float): StructureState {
        val section = scan.sectionAt(pos) ?: return state
        val previous = mapSection
        mapSection = section

        var dropImminent = state.dropImminent
        var dropEtaS = state.dropEtaS
        val boundary = scan.nextBoundary(pos)
        if (boundary != null &&
            boundary.first <= PREDROP_WINDOW_S &&
            boundary.second > section.energy + DROP_LEVEL_STEP
        ) {
            dropImminent = true
            dropEtaS = boundary.first
        }
        // Crossing into a clearly louder section *is* the drop, known exactly
        // rather than inferred from a surge that has already been heard.
        val dropNow = state.dropNow ||
            (previous != null && previous !== section && section.energy > previous.energy + DROP_LEVEL_STEP)

        return state.copy(
            phase = if (dropNow) SongPhase.DROP else state.phase,
            dropNow = dropNow,
            dropImminent = dropImminent,
            dropEtaS = dropEtaS,
            sectionLevel = section.energy,
        )
    }

    /**
     * Drop rhythm and structure history when the analyzer does.
     *
     * A seek or a track change invalidates both: a beat grid locked to the
     * previous song would keep predicting beats against audio that no longer
     * matches it, and the structure arc would carry the old track's build into
     * the new one. Runs on the analysis thread, before the first frame of the
     * new audio.
     */
    private fun onAnalysisReset() {
        tempo?.reset()
        structure?.reset()
        gestures?.reset()
        latestGrid = null
        latestStructure = null
        latestGesture = null
        // The scan itself survives a seek — it describes the whole track, and
        // seeking does not make it less true. Only the per-query state goes.
        mapPrevPos = null
        mapSection = null
        // For the same reason the structure tracker resets: a layer's smoothed
        // temperature, decaying flash or accumulated phase describes the audio
        // that was playing, and a new track is not that audio. Each layer
        // decides what of its own state is per-track and what outlives it —
        // Phantom Stage's room layout, for instance, deliberately survives.
        layerChain.reset()
    }

    // ── Render loop ─────────────────────────────────────────────────────────

    /**
     * Render and send at [STREAM_FPS], independent of the analysis rate.
     *
     * This is the rate the Entertainment API is streamed at, which syncoV2 puts
     * at 60 Hz against a ~50 Hz analysis rate — so some analysis frames are
     * rendered twice, which is the point: the engine's continuous layers (colour
     * drift, envelopes, melbank) advance on real elapsed time and come out
     * smoother than the frames driving them. The bridge relays to bulbs at ~25 Hz
     * over Zigbee, so this is not about the visible update rate; it is about the
     * temporal resolution of what gets sampled down to it.
     *
     * [dt] is measured, not assumed. The engine integrates everything against it,
     * so a dropped or late tick has to shorten or lengthen the step rather than
     * silently bend engine time away from the clock. It is clamped so that a stall
     * (a GC pause, a thread starved by the decoder) resumes rather than jumping
     * the animation forward by however long it was gone.
     */
    private suspend fun renderLoop() {
        var last = System.nanoTime()
        var next = last
        render@ while (running.get()) {
            next += FRAME_PERIOD_NANOS
            val sleep = (next - System.nanoTime()) / 1_000_000L
            if (sleep > 0) kotlinx.coroutines.delay(sleep)
            // A long stall would otherwise leave the loop sprinting to catch up
            // on a backlog of deadlines nobody is waiting for.
            if (System.nanoTime() - next > FRAME_PERIOD_NANOS * 4) next = System.nanoTime()
            if (!running.get()) break

            val now = System.nanoTime()
            val dt = ((now - last) / 1e9f).coerceIn(0f, MAX_STEP_S)
            last = now

            val eng = engine ?: continue
            val enc = encoder ?: continue
            val client = dtls ?: continue

            // An ambience show, if one is running, takes the room outright — unless
            // the game is up, which outranks it. A scripted effect is a show of its
            // own and would paint the room on its own schedule, which is the one
            // thing game mode is for preventing.
            //
            // An ambience show, if one is running, takes the room outright.
            //
            // Checked *before* `fresh` and `isIdle` below, and that ordering is
            // load-bearing: a scripted effect produces no analysis frames at all,
            // so `fresh` is always false and `isIdle` always true, and the idle
            // show would quietly paint over the effect on every single tick.
            val amb = if (gameMode) null else ambience
            if (amb != null) {
                if (_framesFresh.value) _framesFresh.value = false
                val painted = try {
                    amb.renderLights()
                } catch (e: Exception) {
                    logThrottled("Ambience render failed: ${e.message}")
                    continue
                }
                if (painted != null) {
                    // The delay queue and the layer chain are both skipped here.
                    // The queue's delay tracks the *music* sink's AudioLead, which
                    // is not this show's: an effect is either generating its own
                    // sound, and clocked on that synth's playhead, or reacting to
                    // its own recording, and clocked on that recording's position
                    // with the light pipeline already allowed for inside
                    // `AmbienceMediaClock`. Either way the correction has been made
                    // upstream, against the right audio.
                    // The layers all read an AnalysisFrame, a track scan or live
                    // structure, none of which exist. Safety and the rate limiter
                    // are kept, and the session brings its own limiter so the
                    // music show's flash budget and rung cannot leak into this one.
                    val ambGuarded = amb.safety.process(painted, dt)
                    val ambDue = rateLimiter.process(ambGuarded, dt)
                    if (isUnchanged(ambDue) && (now - lastSendAt) < RESEND_INTERVAL_NANOS) continue
                    when (emitFrame(ambDue, now, enc, client)) {
                        EmitResult.ABORT -> return
                        EmitResult.RETRY -> continue@render
                        EmitResult.OK -> {
                            lastSent = HashMap(ambDue)
                            continue@render
                        }
                    }
                }
                // A show that has not started painting yet falls through to the
                // ordinary path, so the room is not blanked while it warms up.
            }

            // A tap that has gone quiet means paused, seeking, or a gap between
            // tracks. A player that is not playing at all means the same thing.
            // In either case we switch to the slow ambient idle show so the room
            // stays visible without brightening it.
            val fresh = (now - latestFrameAt) < FRAME_STALE_NANOS
            val frame = if (fresh) latestFrame ?: SILENCE else SILENCE
            val isIdle = !playerPlaying || !fresh
            if (_framesFresh.value != fresh) _framesFresh.value = fresh

            val colours = try {
                if (isIdle) {
                    updateIdle(dt)
                    eng.renderIdleShow(idleT, dt, idleIntensity())
                } else {
                    idleStartS = -1f
                    idleT = 0f
                    if (autoIntensity) applyAutoIntensity(eng, frame, dt)
                    eng.render(frame, dt, latestGrid, latestStructure, latestGesture)
                }
            } catch (e: Exception) {
                logThrottled("Render failed: ${e.message}")
                continue
            }

            // Hold the rendered frame until the audio it describes is actually
            // audible. See FrameDelayQueue: the tap runs ahead of the speaker, so
            // without this the lights lead the music rather than trail it.
            val leadMs = effectiveLeadMs()
            if (seedDelayOnNextLead && leadMs != null) {
                seedDelayOnNextLead = false
                delayQueue.resetDelay(leadMs)
            } else {
                delayQueue.updateDelay(leadMs, dt)
            }
            delayQueue.offer(colours, now)
            val held = delayQueue.poll(now) ?: continue

            // The creative light-show layers — see `docs/creative-light-shows.md`.
            // Skipped while idle: `frame` is SILENCE and `activeScan`/
            // `latestStructure` are stale in that branch, so every layer would
            // either no-op or read garbage anyway. Applied before Safety, not
            // after, so every layer's output still gets flash-safety and
            // rate-limiting for free.
            val layered = if (isIdle) {
                held
            } else {
                layerChain.apply(
                    held,
                    LayerContext(
                        frame = frame,
                        structure = latestStructure,
                        scan = activeScan,
                        positions = roomPositions,
                        topology = roomTopology,
                        trackPositionS = latestPositionS,
                        dt = dt,
                        brightness = layerBrightness,
                        stems = if (stemSeparationEnabled) currentSectionStems(activeScan, latestPositionS) else null,
                    ),
                )
            }

            // The rhythm game holds the room down and lets the *real* show through
            // in the moments the player earns. See [applyGameGate].
            //
            // Applied here, on the finished frame, rather than as a branch that
            // replaces the whole render: everything that makes the show worth
            // watching — the scan's beat grid, the per-instrument reactions, the
            // layer chain — has to still be running, because "the lights react
            // correctly" is the reward. Gating the output is what makes them react
            // *only* when a note lands; rendering something else entirely, which is
            // what this used to do, threw away the reaction along with the flashing.
            val gated = if (gameMode) applyGameGate(layered) else layered

            // Safety runs on what is about to be sent, at the moment it is sent —
            // its flash budget is measured in wall-clock seconds, so it has to
            // sit after the delay queue rather than before it.
            val guarded = safety?.process(gated, dt) ?: gated
            val due = rateLimiter.process(guarded, dt)

            // The bridge relays at 25 Hz over Zigbee and the spec asks for a
            // continuous stream because UDP frames are dropped without retry. A
            // frame the bulbs are already showing is safe to skip — losing a
            // duplicate changes nothing — but only for a bounded run, so a lost
            // packet can never leave the room stale until the 9 s keepalive.
            if (isUnchanged(due) && (now - lastSendAt) < RESEND_INTERVAL_NANOS) continue

            when (emitFrame(due, now, enc, client)) {
                EmitResult.ABORT -> return
                EmitResult.RETRY -> continue@render
                EmitResult.OK -> Unit
            }

            // Recorded only once the frame has been through the socket. Setting
            // it before the send would let a failed write count as displayed, and
            // the next identical frame would then be skipped as a duplicate of
            // something the bulbs never received.
            //
            // Copied when it is the layer chain's own buffer. [LayerChain] hands
            // back a map it reuses between frames, which is safe for everything
            // that reads it within the frame — [FieldSafety] and
            // [EffectRateLimiter] each build their own — but `lastSent` outlives
            // the frame, and both of those stages pass their input straight
            // through when the field is empty. A reference here would therefore
            // become, on the next frame, a comparison of the new field against
            // itself: [isUnchanged] would always say yes and nothing would go out
            // until the keepalive. An empty field means no channels and so cannot
            // happen while a stream is up, which makes this a guard against the
            // next stage that learns to pass its input through rather than a live
            // bug — and it costs one map copy on frames that actually differ.
            lastSent = if (due === layered) HashMap(due) else due
            // (`gated` is a fresh map on every frame the gate is applied, so it is
            // never the chain's reused buffer and needs no copy of its own.)
        }
    }

    /** What [emitFrame] wants the render loop to do next. */
    private enum class EmitResult { OK, RETRY, ABORT }

    /**
     * Encode one frame and put it on the wire.
     *
     * Extracted so the music path and the ambience path share it rather than keeping two
     * copies of the DTLS failure handling — which is the part that is easy to get subtly
     * wrong and expensive to get wrong twice. Everything here was previously inline in
     * [renderLoop] and is unchanged in behaviour.
     */
    private suspend fun emitFrame(
        due: Map<Int, Rgb>,
        now: Long,
        enc: HueStreamEncoder,
        client: DtlsPskClient,
    ): EmitResult {
        val packets = try {
            enc.buildPackets(due)
        } catch (e: Exception) {
            logThrottled("Encode failed: ${e.message}")
            return EmitResult.RETRY
        }

        for (packet in packets) {
            try {
                client.send(packet)
                lastFrame = packet
                lastSendAt = now
            } catch (e: DtlsPeerClosed) {
                // Bridge-initiated teardown: the Hue app took the area, or the user
                // pressed stop there. Deliberately never retried — reconnecting here is
                // what makes that stop button look broken, because the app immediately
                // takes the area back.
                Log.w(TAG, "Bridge revoked the stream: ${e.message}")
                revoked = true
                running.set(false)
                _error.value = "The bridge revoked the stream (another app may have taken over)"
                scope.launch { cleanup() }
                return EmitResult.ABORT
            } catch (e: Exception) {
                // A network fault, by elimination. Wi-Fi drops and roams are ordinary
                // events on a phone, and until this they ended the session for good.
                logThrottled("DTLS send failed: ${e.message}")
                if (++sendFailures >= SEND_FAILURES_BEFORE_RECONNECT) {
                    if (!reconnect()) return EmitResult.ABORT
                    return EmitResult.RETRY
                }
            }
            sendFailures = 0
        }
        return EmitResult.OK
    }

    /**
     * Decode the cover at [url] and hand its colours, with their dwell weights,
     * to the engine.
     *
     * Uses syncoV2's own extraction, not the app's UI palette. The UI one ranks
     * candidates by `chroma * sqrt(population)` so a small vivid splash outranks
     * a large flat field, and then lifts lightness and saturation for legibility
     * against black. Both are right for picking a UI accent and wrong for
     * lighting a room — they surface colours the sleeve barely contains, which
     * is what "the album colours are wrong" was.
     *
     * Two extraction modes, matching syncoV2:
     * - `album_art` (even): v1 — accent/base separation, uniform palette.
     * - `album_art_v2` (weighted): v2 — population-weighted, dwell-time faithful.
     *
     * The extracted colours are cached in [lastAlbumColours] so they survive the
     * race between this collector (which fires from `init`) and [start] (which
     * creates the engine). Without the cache, the first extraction lands while
     * the engine is still null and is silently lost; `distinctUntilChanged` then
     * suppresses the re-emit, and the room stays on the Sunset fallback.
     *
     * **A null or blank [url] keeps the palette that is already there.** It used to
     * clear both caches and hand the engine an empty list, which drops it to the Sunset
     * fallback for every dynamic scheme — see [lastGoodArtUrl] for why that null arrives
     * on every library-initiated play and never on a queue advance, which is exactly the
     * shape of the reported bug. "No artwork right now" is not the same claim as "this
     * track has no artwork", and only the second would justify throwing colours away.
     * [clearAlbumArt] exists for the case that really does mean it.
     */
    private suspend fun applyAlbumArt(url: String?) = withContext(Dispatchers.IO) {
        val scheme = ColorScheme.fromWire(settings.lightSyncColor.first())

        // A hand-picked palette is checked *before* the artwork, and applied without
        // it. These colours were not derived from an image, so nothing about them
        // needs one — and every path below gives up when the cover is missing, is a
        // `file://` that will not decode, or simply never arrives. That is exactly
        // the offline and downloaded case, which is where a saved palette most needs
        // to still be the one showing: the user corrected these colours precisely
        // because they wanted them next time, and "next time" was silently excluding
        // every track whose cover did not load.
        //
        // It also saves a decode on every track that has one.
        if (scheme.isDynamic) {
            val saved = findOverride(url)
            if (saved != null) {
                val colours = saved.toAlbumColours()
                val weights = overrideWeights(saved, scheme)
                lastAlbumColours = colours
                lastAlbumWeights = weights
                if (!url.isNullOrBlank()) lastGoodArtUrl = url
                engine?.setAlbumColors(colours.colors, weights)
                return@withContext
            }
        }

        if (url.isNullOrBlank()) return@withContext
        try {
            val key = "$url|${scheme.wire}"
            // A mode switch, or coming back to a track played a moment ago, should not
            // cost a refetch — and must not be able to *lose* a palette to a failed one.
            val cached = synchronized(paletteCache) { paletteCache[key] }
            val extracted = cached ?: run {
                val request = ImageRequest.Builder(context)
                    .data(url)
                    .allowHardware(false)  // getPixels needs a software bitmap
                    .build()
                val result = context.imageLoader.execute(request)
                val bitmap = (result as? SuccessResult)?.drawable?.toBitmap() ?: run {
                    // Loudly, and with the URL: MA covers go through /imageproxy and the
                    // loader probes three URL shapes to find one the server answers, so a
                    // miss here is a real and diagnosable thing rather than a curiosity.
                    Log.w(TAG, "Album art did not load, keeping previous palette: $url")
                    return@withContext
                }
                val fresh = when (scheme) {
                    ColorScheme.ALBUM_ART -> extractAlbumColoursV1(bitmap)
                    ColorScheme.ALBUM_ART_V2 -> extractAlbumColours(bitmap)
                    else -> extractAlbumColours(bitmap)  // SONG and statics don't use album colours
                } ?: run {
                    Log.w(TAG, "Album art yielded no colours, keeping previous palette: $url")
                    return@withContext
                }
                synchronized(paletteCache) { paletteCache[key] = fresh }
                fresh
            }
            // What was actually *applied*, not what was extracted. The stream-start
            // path re-applies this when a bridge session opens mid-track, and it has
            // always claimed in a comment to be respecting user overrides — which it
            // could not, because this line stored the extraction and the override was
            // applied only to the local variable below. Opening the stream on a track
            // with corrected colours put the cover's own palette back.
            //
            // An override returns above, so reaching here means there is none and the
            // two are the same thing. The variable is kept faithful to its name
            // regardless: the next person to add a branch here should not have to
            // rediscover that.
            lastAlbumColours = extracted
            lastGoodArtUrl = url

            // v1 (album_art/even) has no weights — pass null so the engine uses
            // pure even interpolation (Palette.evenSample), matching syncoV2's
            // Palette(colors, weights=None). v2 passes the real population weights
            // for dwell-time-faithful hold-and-crossfade sampling.
            val paletteWeights = if (scheme == ColorScheme.ALBUM_ART) null else extracted.weights
            lastAlbumWeights = paletteWeights
            engine?.setAlbumColors(extracted.colors, paletteWeights)
        } catch (e: Exception) {
            // A cover that will not load is not a reason to stop the show; the
            // engine keeps whatever palette it already had.
            Log.w(TAG, "Album art palette failed for $url: ${e.message}")
        }
    }

    /**
     * The weights to hand the engine for a hand-picked palette.
     *
     * Null means "space these evenly", which is a different request to the engine
     * than a weighted sample whose weights happen to be equal — see
     * [SyncoEngine.setAlbumColors]. So an override the user left alone is expressed
     * as no weights at all, exactly as before percentages existed, and only one they
     * actually adjusted carries numbers.
     *
     * v1 never takes weights: even interpolation is its whole definition.
     */
    private fun overrideWeights(override: CoverPaletteOverride, scheme: ColorScheme): List<Float>? = when {
        scheme == ColorScheme.ALBUM_ART -> null
        override.hasWeights -> override.normalisedWeights()
        else -> null
    }

    /**
     * A user-corrected palette for whatever is currently playing, if there is one.
     *
     * Tries every key the editor could have saved under, in the same order the
     * editor prefers them — see [CoverPaletteOverride.keysFor]. The album and
     * artist come from [ActiveLightSyncSource.paletteAlbum] and
     * [ActiveLightSyncSource.paletteArtist] rather than from [ActiveLightSyncSource.scanTrack],
     * which is null on the Music Assistant feed — that used to leave the artwork
     * URL as MA's only key, and MA re-issues those between sessions.
     *
     * Suspends because it reads [AppSettings.coverPaletteOverrides], which is
     * backed by DataStore.
     */
    private suspend fun findOverride(url: String?): CoverPaletteOverride? {
        val overrides = settings.coverPaletteOverrides.first()
        if (overrides.isEmpty()) return null
        val source = activeSource.value
        return CoverPaletteOverride
            .keysFor(source.paletteAlbum, source.paletteArtist, url, source.scanTrack?.id)
            .firstNotNullOfOrNull { key -> overrides[key]?.takeIf { it.colors.isNotEmpty() } }
    }

    /**
     * Forget the album palette outright — the deliberate version of what a null URL
     * used to do by accident.
     *
     * Only for a real stop, where there is no longer a track whose colours these are.
     * A handover between backends is not that, and neither is artwork that has not
     * arrived yet.
     */
    private fun clearAlbumArt() {
        lastAlbumColours = null
        lastAlbumWeights = null
        lastGoodArtUrl = null
        engine?.setAlbumColors(emptyList())
    }

    /**
     * Let the picker choose the rung for this frame.
     *
     * Run here rather than on the analysis thread because it is a rendering
     * decision measured in render frames, and because switching the rung also
     * has to swap the safety limiter — Intense and Extreme relax or bypass it,
     * so the two must move together or a rung change could leave the wrong
     * limiter in place.
     */
    private fun applyAutoIntensity(eng: SyncoEngine, frame: AnalysisFrame, dt: Float) {
        val grid = latestGrid
        // The five parameters the picker has always accepted and nothing has
        // ever supplied. With them it knows, from the first bar, how hard this
        // song goes on an absolute scale, how much of its own range it uses, and
        // where in that range this moment sits — instead of spending twenty
        // seconds working the first out and never learning the rest.
        val profile = activeProfile
        val picked = picker.update(
            dt = dt,
            energy = frame.energy,
            salience = frame.salience,
            bpm = if (grid?.locked == true) grid.bpm else 0f,
            beat = frame.beat,
            allowed = autoLevels,
            onsetWidth = frame.onsetWidth,
            centroid = frame.centroid,
            flux = frame.flux,
            signal = scannedSignal,
            character = profile?.character,
            lo = profile?.sigLo ?: SIG_LO_REF,
            hi = profile?.sigHi ?: SIG_HI_REF,
            dynamics = profile?.dynamics,
            mood = profile?.mood ?: 0f,
        )
        if (picked != eng.mode) {
            eng.mode = picked
            selectLimiter(picked)
        }
    }

    /**
     * Update idle-time bookkeeping for the ambient show.
     *
     * The first moment of idle is recorded so we can wait [IDLE_FADE_IN_DELAY_S]
     * before the wandering movement starts, and [idleT] accumulates elapsed
     * seconds for the renderIdleShow phase parameter.
     */
    private fun updateIdle(dt: Float) {
        if (idleStartS < 0f) idleStartS = 0f
        idleStartS += dt
        idleT += dt
    }

    /** Movement intensity for the idle show, 0 until the delay then fading to 1. */
    private fun idleIntensity(): Float {
        if (idleStartS < 0f) return 0f
        return ((idleStartS - IDLE_FADE_IN_DELAY_S) / IDLE_FADE_IN_DURATION_S).coerceIn(0f, 1f)
    }

    /**
     * Rebuild the bridge session after a network fault, keeping the show going.
     *
     * Only reached from repeated send failures — a bridge-initiated revocation
     * sets [revoked] and never comes here, because retrying that is what makes
     * the Hue app's stop button appear not to work.
     *
     * The engine is deliberately kept across the reconnect: it holds the
     * envelopes, the colour phase and the role assignment, so reusing it means
     * the room picks up where it left off rather than restarting the show. The
     * encoder is rebuilt, since its sequence numbers belong to the old session.
     *
     * Returns false when the attempts are exhausted or the session was stopped
     * meanwhile, in which case the caller should give up.
     */
    private suspend fun reconnect(): Boolean {
        var delayMs = RECONNECT_BASE_MS
        for (attempt in 1..RECONNECT_ATTEMPTS) {
            if (!running.get() || revoked) return false
            Log.i(TAG, "Reconnecting to the bridge (attempt $attempt of $RECONNECT_ATTEMPTS)")
            _error.value = "Reconnecting to the bridge…"
            try {
                dtls?.close()
            } catch (e: Exception) {
                // The old socket is already gone; that is why we are here.
            }
            dtls = null

            kotlinx.coroutines.delay(delayMs)
            delayMs = min(RECONNECT_MAX_MS, delayMs * 2)
            if (!running.get() || revoked) return false

            try {
                val host = settings.hueBridgeIp.first()
                val appKey = settings.hueAppKey.first()
                val clientKey = settings.hueClientKey.first()
                val appId = settings.hueAppId.first()
                val configId = settings.hueEntertainmentConfigId.first()
                if (host.isBlank() || appKey.isBlank() || clientKey.isBlank() || configId.isBlank()) return false

                bridgeClient.startStream(host, appKey, configId, appId)
                val psk = clientKey.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                val identity = (appId.ifBlank { appKey }).toByteArray(Charsets.US_ASCII)
                val client = DtlsPskClient(host, 2100, identity, psk)
                client.connect()
                dtls = client
                // With the same per-channel gamuts `start()` built it with. Rebuilding
                // bare — as this did — silently dropped them, so any Wi-Fi drop left a
                // mixed-bulb room clamped to Gamut C for the rest of the session: every
                // saturated colour subtly wrong, and nothing to indicate why.
                encoder = HueStreamEncoder(
                    configId,
                    gamuts = channels.mapNotNull { ch -> ch.gamut?.let { ch.channelId to it } }.toMap(),
                )
                sendFailures = 0
                lastSent = null
                delayQueue.clear()
                _error.value = null
                Log.i(TAG, "Reconnected to the bridge")
                return true
            } catch (e: Exception) {
                Log.w(TAG, "Reconnect attempt $attempt failed: ${e.message}")
            }
        }
        Log.w(TAG, "Giving up on the bridge after $RECONNECT_ATTEMPTS attempts")
        _error.value = "Lost the connection to the bridge"
        running.set(false)
        scope.launch { cleanup() }
        return false
    }

    /**
     * Log at most once per [LOG_THROTTLE_MS]. This loop runs at 60 Hz, so a dead
     * network or a persistent render fault would otherwise emit 60 identical
     * lines a second and bury whatever else is in logcat.
     */
    private fun logThrottled(message: String) {
        val now = System.nanoTime()
        if (now - lastLogAt < LOG_THROTTLE_MS * 1_000_000L) return
        lastLogAt = now
        Log.w(TAG, message)
    }

    private var lastLogAt = 0L

    /** Consecutive failed sends, the trigger for a reconnect. */
    private var sendFailures = 0

    /**
     * Auto rung selection. Kept out of [SyncMode] deliberately — that enum keys
     * the engine's parameter table, and an "auto" entry there would be a rung
     * with no parameters. Auto is a *choice between* rungs, so it lives a level
     * up: the picker resolves it each frame and sets the engine's real rung.
     */
    private val picker = AutoIntensityPicker()

    @Volatile private var autoIntensity = false
    @Volatile private var autoLevels: List<SyncMode> = DEFAULT_AUTO_LEVELS

    private var wakeLock: android.os.PowerManager.WakeLock? = null
    private var wifiLock: android.net.wifi.WifiManager.WifiLock? = null

    /**
     * Hold the CPU and the Wi-Fi radio awake while streaming.
     *
     * Light Sync is the one thing here that keeps working with the screen off,
     * and it is exactly the case that breaks without this: a 60 Hz UDP sender
     * driven by `delay` is throttled by doze, and Wi-Fi power-save adds latency
     * spikes that show as the room stuttering behind the music. The Sendspin
     * receiver already does this for the same reasons; the local player never
     * needed to, because ExoPlayer's own foreground service covered it and
     * nothing depended on a steady send rate.
     *
     * `WIFI_MODE_FULL_LOW_LATENCY` rather than `WIFI_MODE_FULL_HIGH_PERF`: the
     * traffic is tiny and constant, and latency is the thing that matters.
     */
    @android.annotation.SuppressLint("WakelockTimeout")
    private fun acquireLocks() {
        if (wakeLock == null) {
            wakeLock = (context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager)
                .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "camusic:lightsync")
                .apply { setReferenceCounted(false); acquire() }
        }
        if (wifiLock == null) {
            wifiLock = (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager)
                .createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "camusic:lightsync")
                .apply { setReferenceCounted(false); acquire() }
        }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        wifiLock = null
    }

    /**
     * Set when the *bridge* ended the stream. Distinct from a network fault, and
     * the difference decides whether reconnecting is right: a revoked session
     * means something else wants the area, and taking it straight back is what
     * makes the Hue app's stop button look broken.
     */
    @Volatile private var revoked = false

    /** Rendered frames waiting for the audio they describe to become audible. */
    private val delayQueue = FrameDelayQueue<Map<Int, Rgb>>()

    /**
     * Eye-safety limiter, or null on Extreme.
     *
     * The rung decides which one, following syncoV2: Subtle, Medium and High get
     * the strict WCAG limiter; Intense gets the relaxed budget, which never
     * engages on real music but still caps a true strobe; Extreme bypasses it
     * entirely at the user's explicit request. That last decision lives here
     * rather than inside [FieldSafety], so it is a deliberate choice at the call
     * site instead of something inherited by accident.
     */
    @Volatile private var safety: FieldSafety? = FieldSafety()

    /**
     * Philips' 12.5 Hz ceiling. Applied on every rung including Extreme — it is
     * a statement about what Zigbee can deliver, not a comfort setting, so
     * exceeding it produces no visible change and only more strobing.
     */
    private val rateLimiter = EffectRateLimiter()

    private var limiterMode: SyncMode? = null

    /**
     * Swap in the limiter this rung calls for, resetting the state so the new
     * one does not inherit the old one's flash history or anchor.
     */
    @Synchronized
    private fun selectLimiter(mode: SyncMode) {
        if (mode == limiterMode) return
        limiterMode = mode
        safety = when (mode) {
            SyncMode.EXTREME -> null
            SyncMode.INTENSE -> FieldSafety(RELAXED_MAX_FLASHES_PER_S, calmGated = false)
            else -> FieldSafety()
        }
        rateLimiter.reset()
    }

    /** The last frame actually put on the wire, for the unchanged check. */
    private var lastSent: Map<Int, Rgb>? = null

    /**
     * Is this frame visually identical to the one already on the bulbs?
     *
     * Quantised to the bridge's own resolution rather than compared exactly: xy
     * chromaticity is 12-bit at the lamp and brightness 11-bit, so differences
     * below those thresholds cannot be displayed and cost a datagram to send.
     * syncoV2 uses the same 1/2048 and 1/4096 and saves 20–40% of its DTLS
     * traffic by it.
     */
    private fun isUnchanged(next: Map<Int, Rgb>): Boolean {
        val prev = lastSent ?: return false
        if (prev.size != next.size) return false
        for ((id, rgb) in next) {
            val was = prev[id] ?: return false
            if (abs(rgb.first - was.first) >= COLOUR_QUANT) return false
            if (abs(rgb.second - was.second) >= COLOUR_QUANT) return false
            if (abs(rgb.third - was.third) >= COLOUR_QUANT) return false
        }
        return true
    }

    // ── Keepalive ───────────────────────────────────────────────────────────

    /**
     * Watches for bridge-side alerts, and resends only if the stream has actually
     * gone quiet. [renderLoop] sends continuously while a session is up, so the
     * resend is a backstop for a stalled loop rather than the normal path — firing
     * it regardless would inject a nine-second-old frame into a live stream.
     */
    private suspend fun keepaliveLoop() {
        while (running.get()) {
            kotlinx.coroutines.delay(KEEPALIVE_INTERVAL_MS)
            if (!running.get()) break

            // Check for bridge-side alerts (stream revocation).
            val client = dtls ?: break
            try {
                val alert = withContext(Dispatchers.IO) { client.pollAlert() }
                if (alert != null) {
                    val (_, desc) = alert
                    if (desc == 0 || desc == 90) {  // close_notify or user_canceled
                        Log.i(TAG, "Bridge closed the stream (alert $desc)")
                        // Bridge-initiated, so no reconnect — same reasoning as
                        // the send path.
                        revoked = true
                        running.set(false)
                        _error.value = "The bridge stopped the stream"
                        scope.launch { cleanup() }
                        break
                    }
                }
            } catch (e: Exception) {
                // Non-fatal: just log and continue.
            }

            // Only if the render loop has gone quiet — otherwise this would be a
            // stale frame cutting into a stream that is already flowing.
            if (System.nanoTime() - lastSendAt < KEEPALIVE_INTERVAL_MS * 1_000_000L) continue
            val frame = lastFrame ?: continue
            try {
                withContext(Dispatchers.IO) { client.send(frame) }
            } catch (e: Exception) {
                Log.w(TAG, "Keepalive send failed: ${e.message}")
            }
        }
    }

    // ── Live settings updates ───────────────────────────────────────────────

    /**
     * Mirror the stored Light Sync settings onto the running engine.
     *
     * The screen writes [AppSettings] and nothing else — it never touches the
     * engine. So a control moved mid-song lands on the next frame, and one moved
     * while nothing is playing is simply what [start] seeds the engine with. One
     * source of truth, and no path where the UI and the lights disagree.
     *
     * These collectors run for the life of the process. While the engine is null
     * every apply is a no-op, which costs nothing — and each flow emits only when
     * its own setting changes, which [AppSettings.pref] is what makes true. It was
     * not always: several of these do real work per emission, and the colour one
     * re-rolled the Song palette on a write to any key at all.
     */
    private fun observeSettings() {
        scope.launch {
            settings.lightSyncIntensity.collect { wire ->
                autoIntensity = wire == INTENSITY_AUTO
                if (autoIntensity) {
                    // Let the picker choose from the next frame rather than
                    // holding whatever rung was showing when Auto was selected.
                    picker.reset()
                } else {
                    val mode = SyncMode.fromWire(wire)
                    engine?.mode = mode
                    selectLimiter(mode)
                }
            }
        }
        scope.launch {
            settings.hueBridgeId.collect { pairedBridgeId = it }
        }
        // The bridge's entertainment areas: once at startup, and again only when the
        // bridge this app is paired with actually changes. See the fields above.
        scope.launch {
            combine(settings.hueBridgeIp, settings.hueAppKey) { ip, key -> ip to key }
                .distinctUntilChanged()
                .collect { (ip, key) -> loadEntertainmentConfigs(ip, key) }
        }
        scope.launch {
            settings.lightSyncAutoLevels.collect { wires ->
                autoLevels = wires.mapNotNull { w -> SyncMode.entries.firstOrNull { it.wire == w } }
                    .ifEmpty { DEFAULT_AUTO_LEVELS }
                // A checklist change should feel immediate, not wait out a dwell.
                picker.allowImmediateRepick()
            }
        }
        scope.launch {
            settings.lightSyncColor.collect { wire ->
                val scheme = ColorScheme.fromWire(wire)
                engine?.setScheme(scheme)
                // Switching between album_art (v1/even) and album_art_v2
                // (v2/weighted) changes the extraction algorithm. Re-extract
                // the cached cover with the right one so the room reads the
                // sleeve the way the selected option promises.
                if (scheme == ColorScheme.ALBUM_ART || scheme == ColorScheme.ALBUM_ART_V2) {
                    // The last URL that *worked*, not the last one seen. `lastArtUrl`
                    // tracks the feed, and the feed goes momentarily null on every
                    // backend handover — so guarding on it meant that after one of those
                    // there was nothing to re-extract from, `albumPalette` was already
                    // null, and switching to album art landed on Sunset and stayed
                    // there until a genuinely new cover arrived. That is the "change to
                    // another colour and come back and it's wrong" report.
                    lastGoodArtUrl?.let { applyAlbumArt(it) }
                }
            }
        }
        // A palette the user has just corrected has to reach the room now, not on
        // the next track. Nothing else re-reads the overrides — [applyAlbumArt] runs
        // when the artwork URL changes or the scheme does, and neither happens when
        // someone saves in the editor. Without this the save succeeded, the lights
        // carried on showing the old colours until the song ended, and the only
        // reading available to the user was that saving had not worked.
        //
        // Cheap to re-run: the extraction is cached by URL and scheme (see
        // [paletteCache]), so this costs a map lookup and a setAlbumColors rather
        // than a second decode of the cover.
        scope.launch {
            settings.coverPaletteOverrides
                .distinctUntilChanged()
                // The first emission is what is already stored, which the show has
                // either applied or had no artwork for. Only changes are news.
                .drop(1)
                // Unconditionally, not `lastGoodArtUrl?.let`: that field is only
                // written on a path that *succeeded* (see [applyAlbumArt]), so a
                // track whose cover is missing, is a `file://` that will not decode,
                // or simply never arrived left it null — and the save the user had
                // just made was a no-op for the show actually running. Those are
                // exactly the tracks a hand-picked palette exists for. A null URL is
                // fine here: `applyAlbumArt` checks the override *before* it needs a
                // cover, and `findOverride` still has the album, artist and track id
                // to look one up by.
                .collect { applyAlbumArt(lastGoodArtUrl) }
        }
        // Album artwork. Collected unconditionally rather than only while an
        // album-art scheme is selected, so switching to one mid-track picks up
        // the cover already on screen instead of waiting for the next song.
        //
        // Keyed on the track as well as the URL. A saved palette is looked up by
        // track, and two tracks can share an artwork URL — or have none at all, which
        // is one unchanging null — so keying on the URL alone meant moving from one
        // track to the next never re-checked for an override. On a library with no
        // covers that is *every* track change.
        scope.launch {
            @OptIn(kotlinx.coroutines.FlowPreview::class)
            activeSource.map { it.artUrl to scanKeyOf(it.scanTrack) }
                .distinctUntilChanged()
                // A backend handover emits null for a moment on its way from one cover
                // to the next. [applyAlbumArt] already refuses to act on a null, so this
                // is belt and braces — but it also collapses the null-then-URL pair into
                // one extraction rather than two, and stops a rapid run of skips
                // starting a fetch per track.
                .debounce(ART_SETTLE_MS)
                .collect { (url, _) ->
                    lastArtUrl = url
                    applyAlbumArt(url)
                }
        }
        // The track itself, for the scan.
        scope.launch {
            activeSource.map { it.scanTrack }.distinctUntilChanged { a, b -> scanKeyOf(a) == scanKeyOf(b) }
                .collect { track -> onTrackChanged(track) }
        }
        // Re-point the tap hookup whenever the active backend changes - a local
        // <-> MA switch, or MA reconnecting to a fresh SendspinNativeEngine (a new
        // AudioAnalysisTap instance every time). A no-op while nothing is
        // running: start() does its own initial rewireTap() call, and a source
        // change that arrives before that is exactly what wiredTap == null /
        // running == false already handles safely.
        scope.launch {
            activeSource.collect { source -> if (running.get()) rewireTap(source) }
        }
        // A scan that lands while its own track is still inside the adoption
        // window is adopted right away, which is what makes the very first play
        // of a local track already exact rather than only the second.
        scans?.let { repo ->
            scope.launch {
                repo.completed.collect { key ->
                    val track = currentTrack ?: return@collect
                    if (TrackScanRepository.keyFor(track) != key) return@collect
                    if (mapCommitted != null) return@collect  // regime already settled
                    adoptScan(repo.cached(track))
                }
            }
        }
        scope.launch {
            settings.lightSyncBrightness.collect { pct -> applyBrightness(pct.coerceIn(0, 100) / 100f) }
        }
        scope.launch {
            settings.lightSyncTunables.collect { tunables ->
                activeTunables = tunables
                engine?.setTunables(tunables)
            }
        }
        scope.launch {
            settings.lightSyncSpatial.collect { on ->
                spatialEnabled = on
                applySpatialGestures()
            }
        }
        scope.launch { settings.musicDnaEnabled.collect { setLayerEnabled(musicDnaLayer, it) } }
        scope.launch { settings.emotionalArcEnabled.collect { setLayerEnabled(emotionalArcLayer, it) } }
        scope.launch { settings.phantomStageEnabled.collect { setLayerEnabled(phantomStageLayer, it) } }
        scope.launch { settings.stemSeparation.collect { stemSeparationEnabled = it } }
        scope.launch {
            settings.phoneConductorEnabled.collect { on ->
                setLayerEnabled(phoneConductorLayer, on)
                // Battery consciousness: sensors only run while both this
                // setting and a stream are live. The stream-start case (the
                // setting already being on when start() runs) is handled
                // there, since this collector only fires on a *change*.
                if (on && running.get()) phoneConductorLayer.start() else phoneConductorLayer.stop()
            }
        }
        scope.launch {
            isPlaying.collect { playing ->
                playerPlaying = playing
                // Music starting ends an ambience show. Handled here rather than in the
                // Effects screen because most ways music starts never go near it: the
                // notification's play button, a headset button, Android Auto, a voice
                // assistant, or another phone joining a group. This is the one place all
                // of them arrive.
                if (playing && ambience != null) {
                    launch { stopAmbience() }
                }
            }
        }
    }

    /**
     * The user's preference and the system's accessibility setting, resolved
     * onto the engine. Either one saying no is a no.
     */
    private fun applySpatialGestures() {
        engine?.spatialGestures = spatialEnabled && !reducedMotion
    }

    /**
     * Push tunables straight at the running engine, without persisting them.
     *
     * For a slider that is still under the user's finger. Persisting on every pointer
     * move meant a DataStore write, a JSON serialisation and a re-emission of
     * `lightSyncTunables` per frame — which recomposed the whole screen *and* came
     * back round through [observeSettings] to set the same values again. The lights
     * still need to react live, so the live value goes here and only the released
     * value goes to storage; [observeSettings] then re-applies an identical map,
     * which is a no-op.
     */
    fun previewTunables(values: Map<String, Float>) {
        activeTunables = values
        engine?.setTunables(values)
    }

    /** As [previewTunables], for the brightness ceiling. [pct] is 0..100. */
    fun previewBrightness(pct: Int) {
        applyBrightness(pct.coerceIn(0, 100) / 100f)
    }

    /** Album-art colours, pushed by the player when the track changes. */
    fun setAlbumColors(colors: List<Rgb>) {
        engine?.setAlbumColors(colors)
    }

    /**
     * Hand the room to the rhythm game, or take it back.
     *
     * While this is on the show still runs in full — the beat grid, the scan, the
     * per-instrument reactions, the layer chain — and [applyGameGate] holds its
     * output down to a dim floor. A correctly-struck note opens the gate, and for
     * as long as it is open the room does exactly what it would have done anyway.
     *
     * That is the whole design, and it is a correction of the first attempt at it.
     * The first version replaced the render with a floor plus a coloured flash per
     * lane, which meant hitting a note lit a red lamp on the left rather than
     * *playing the kick's reaction*: it threw away everything the engine knows about
     * what the music is doing at that instant, which is the only reason the reward
     * is worth having. It also scaled its flashes by the brightness ceiling, so the
     * whole room — reactions included — came out dim. Nothing here touches the
     * ceiling now: the floor comes down, and a hit plays at the brightness the show
     * was always going to use.
     */
    fun setGameMode(on: Boolean) {
        if (gameMode == on) return
        gameMode = on
        closeGate()
        // Nothing else to do: the render loop picks the gate up on its next tick.
    }

    private fun closeGate() {
        gatePeak = 0f
        gateHoldMs = 0f
        gateOpenedAtMs = 0L
    }

    /**
     * A note was struck in the rhythm game: open the gate.
     *
     * [strength] is 0..1 for how well it was hit and becomes how far the gate opens,
     * so a Perfect plays the show at full and a scrappy Good plays it at rather
     * less. [combo] lengthens the hold — the better the run, the more continuous the
     * show becomes, which is a reward that costs nothing to explain because it is
     * simply more of the thing the player already wanted.
     *
     * [band] shapes *where* in the room the answer lands. A bass note favours the
     * floor lamps, a hat the ceiling, a snare the whole room, and a melody note is
     * a colour rotation with only a gentle brightness lift — the Hue guide book's
     * rule that a colour transition may be quicker than a brightness one, applied
     * to the reward. The previous hit's gate is never weakened: a later hit can
     * only widen the shape.
     *
     * Not scaled by the brightness ceiling. The engine has already applied that to
     * the frame this gate multiplies, and scaling here as well is what made the
     * first version dim the whole room instead of only its floor.
     *
     * Called from the main thread; the fields are volatile and the render loop only
     * reads them, so there is nothing to lock.
     */
    fun receiveGameHit(strength: Float, combo: Int, band: GameBand = GameBand.FULL) {
        val peak = strength.coerceIn(0f, 1f)
        if (peak <= 0f) return
        // Never let a new hit drop the room below where the last one has it. A weak
        // hit landing while a strong one is still ringing should extend the show,
        // not chop it — the player did not do anything wrong.
        gatePeak = maxOf(gameGate(), peak)
        gateHoldMs = GATE_HOLD_MS + (combo.coerceAtMost(GATE_HOLD_COMBO_CAP) * GATE_HOLD_PER_COMBO_MS)
        gateOpenedAtMs = System.currentTimeMillis()
        gateBand = band
    }

    /**
     * The sympathy floor: how much of the gate a lamp outside the hit's band still
     * gets. Not zero — a room that goes black except for one lamp reads as a fault,
     * and peripheral-vision flashes want company — but well under the band's own.
     */
    private val bandSympathy = 0.35f

    /** Melody hits lift brightness by this much at most; the reward is the colour. */
    private val colorBandBrightness = 0.6f

    /** How far a melody hit rotates the room's hue, as a fraction of a turn. */
    private val colorBandShift = 0.08f

    /** Reusable HSV buffer for the melody lane's colour rotation, render thread only. */
    private val gameHsv = FloatArray(3)

    /**
     * How strongly this lamp participates in [band]'s answer, 0..1.
     *
     * FULL is every lamp. BASS/TOP use the lamp's height-band blend computed once
     * per session ([gameBassLamps]/[gameTopLamps]) — the same stacking the engine
     * already performs, so a kick and the engine's own bass response agree on which
     * lamps are low. Flat Hue floor plans put every lamp at the same height, in
     * which case every lamp is an equal mix and the band degrades gracefully toward
     * "the whole room, gently shaped".
     */
    private fun bandWeight(id: Int, band: GameBand): Float = when (band) {
        GameBand.FULL -> 1f
        GameBand.BASS -> gameBassLamps[id] ?: 0.5f
        GameBand.TOP -> gameTopLamps[id] ?: 0.5f
        GameBand.COLOR -> 1f
    }

    /**
     * Hold the room at a dim floor, and open to the full show where the player has
     * earned it — shaped by the struck note's band.
     *
     * The floor is a *floor*, not a ceiling: it lifts whatever the show is doing up
     * to a dim minimum so the room is never black, and it never holds anything down.
     * The gate above it is a plain multiplier on the engine's own frame, so at full
     * open this returns the frame unchanged — the reaction is the show's, at the
     * show's brightness, with the user's ceiling already applied to it upstream.
     *
     * Componentwise `max` rather than a blend toward a floor colour: a blend would
     * wash the show's colours toward neutral in proportion to how dim they are,
     * which is exactly backwards for a deep-blue verse.
     */
    private fun applyGameGate(field: Map<Int, Rgb>): Map<Int, Rgb> {
        val gate = gameGate()
        // Kept under the user's ceiling: a room limited to 5% must not have a 6%
        // floor propping it up. Otherwise absolute, so the floor is the same dim
        // room whatever the show is allowed to peak at.
        val floor = minOf(GAME_FLOOR_LEVEL, layerBrightness)
        val band = gateBand
        val isColor = band == GameBand.COLOR
        // A melody hit rotates hue rather than punching brightness. The shift is
        // fixed (not scaled by the gate) so the sweep reads the same for a Good as
        // for a Perfect — what scales is only how bright it rides.
        val hueShift = if (isColor) colorBandShift else 0f
        val out = HashMap<Int, Rgb>(field.size)
        for ((id, c) in field) {
            val w = bandWeight(id, band)
            // Sympathy: lamps outside the band still get the gate, at a floor of
            // the band's weight — the room moves together, just less so.
            val shaped = if (isColor) {
                colorBandBrightness
            } else {
                maxOf(w, bandSympathy)
            }
            var r = c.first * gate * shaped
            var g = c.second * gate * shaped
            var b = c.third * gate * shaped
            if (hueShift > 0f && gate > 0.05f) {
                rgbToHsvInto(c, gameHsv)
                val shifted = hsvToRgb(wrap1(gameHsv[0] + hueShift), gameHsv[1], gameHsv[2])
                r = shifted.first * gate * shaped
                g = shifted.second * gate * shaped
                b = shifted.third * gate * shaped
            }
            out[id] = Rgb(
                maxOf(floor * FLOOR_TINT_R, r),
                maxOf(floor * FLOOR_TINT_G, g),
                maxOf(floor * FLOOR_TINT_B, b),
            )
        }
        return out
    }

    /**
     * How far open the gate is right now, 0..1.
     *
     * Attack, hold, release. The attack is short enough to read as the tap itself;
     * the release is long enough that a room of Zigbee bulbs relaying at 25 Hz
     * actually renders the fall rather than receiving one bright frame and one dark
     * one, and that a beat's own reaction has time to play out inside it.
     */
    private fun gameGate(): Float {
        val peak = gatePeak
        if (peak <= 0f) return 0f
        val age = (System.currentTimeMillis() - gateOpenedAtMs).toFloat()
        if (age < 0f) return 0f
        val hold = gateHoldMs
        return when {
            age < GATE_ATTACK_MS -> peak * (age / GATE_ATTACK_MS)
            age < GATE_ATTACK_MS + hold -> peak
            else -> {
                val d = (age - GATE_ATTACK_MS - hold) / GATE_RELEASE_MS
                if (d >= 1f) 0f else peak * (1f - d) * (1f - d)
            }
        }
    }


    // ── Track scans ─────────────────────────────────────────────────────────

    /** What is playing, for matching a completed scan against. */
    @Volatile private var currentTrack: LocalTrack? = null

    private fun scanKeyOf(track: LocalTrack?): String? =
        track?.let { TrackScanRepository.keyFor(it) }

    /**
     * A new song: drop everything the last one's scan told us, then look for
     * this one's.
     *
     * The clearing happens first and synchronously. The analysis thread may
     * already be a hop or two into the new audio by the time this runs, and a
     * grid from the previous track applied to it would be worse than no grid at
     * all — the beats would be confidently, precisely wrong.
     */
    private suspend fun onTrackChanged(track: LocalTrack?) {
        currentTrack = track
        activeScan = null
        activeProfile = null
        scannedSignal = null
        mapCommitted = null
        mapPrevPos = null
        mapSection = null
        picker.allowImmediateRepick()

        val repo = scans ?: return
        val next = track ?: return
        // Cached first, because that is the fast path and the one that lands
        // inside the adoption window.
        adoptScan(repo.cached(next))
        if (activeScan == null) repo.request(next, urgent = true)
    }

    private fun adoptScan(scan: TrackScan?) {
        if (scan == null) return
        activeScan = scan
        activeProfile = scan.intensity
    }

    /**
     * The stem energy for whichever [TrackScan.sections] entry [posS] falls in,
     * or null when the scan has no stems (unscanned, pre-stem-separation, or a
     * non-stereo source). [TrackScan.stems] is parallel to [TrackScan.sections]
     * by construction — see [com.engabd.sendpin.audio.buildStemProfile] — so the
     * same section search [TrackScan.sectionAt] does is enough to find the index.
     */
    private fun currentSectionStems(scan: TrackScan?, posS: Float): com.engabd.sendpin.audio.SectionStems? {
        val stems = scan?.stems ?: return null
        val index = scan.sections.indexOfFirst { posS >= it.startS && posS < it.endS }
        return stems.sections.getOrNull(index)
    }

    /**
     * Last in the class body, and that placement is load-bearing.
     *
     * Kotlin runs property initialisers and `init` blocks in declaration order,
     * and [observeSettings] launches collectors that fire immediately — the
     * settings flows have a value to give straight away. From an `init` block
     * near the top of the class those collectors reached `picker`,
     * `rateLimiter`, `safety` and `delayQueue` while their initialisers further
     * down had not run yet, and read them as null. Kotlin cannot warn about it:
     * the types are non-null, so the null-check is elided and the crash surfaces
     * as a bare NullPointerException on a field that "cannot" be null.
     *
     * Everything the collectors touch is declared above this point.
     */
    init {
        observeSettings()
    }
}
