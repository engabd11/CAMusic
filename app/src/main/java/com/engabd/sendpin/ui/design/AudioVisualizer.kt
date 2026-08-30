package com.engabd.sendpin.ui.design

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.engabd.sendpin.SendpinApp
import com.engabd.sendpin.audio.AnalysisFrame
import com.engabd.sendpin.audio.AudioAnalysisTap
import com.engabd.sendpin.audio.MELBANK_BINS
import com.engabd.sendpin.service.PlaybackOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.pow

/**
 * The latest [AnalysisFrame] from whichever player is actually making sound right now.
 *
 * [com.engabd.sendpin.audio.AudioAnalysisTap.frames] is per-tap, and each player
 * ([com.engabd.sendpin.audio.LocalPlayer], the Sendspin engine) owns its own tap.
 * This resolves "which tap" the same way [PlaybackOwner] already resolves "which
 * player" for transport and Light Sync, so a visualizer doesn't have to re-derive
 * that answer a third time — and it follows the *sound* owner, not the session
 * owner, since a paused player isn't producing frames to show.
 */
@Composable
fun rememberActiveAnalysisFrame(): State<AnalysisFrame?> {
    val tap = rememberActiveTap()
    val frameFlow = remember(tap) { tap?.frames ?: MutableStateFlow(null) }
    return frameFlow.collectAsStateWithLifecycle()
}

/** The tap owned by whichever player is actually making sound right now. */
@Composable
private fun rememberActiveTap(): AudioAnalysisTap? {
    val app = SendpinApp.instance
    val owner by app.playbackOwner.state.collectAsStateWithLifecycle()
    return remember(owner.soundOwner, owner.sendspinTap) {
        when (owner.soundOwner) {
            PlaybackOwner.Who.LOCAL -> app.localPlayer.audioAnalysisTap
            PlaybackOwner.Who.SENDSPIN -> owner.sendspinTap?.first
            PlaybackOwner.Who.NONE -> null
        }
    }
}

/**
 * A live spectrum analyser, reading the 16-band log-spaced melbank
 * [AudioAnalysisTap][com.engabd.sendpin.audio.AudioAnalysisTap] already produces at
 * ~50 Hz for Light Sync. No second FFT, no analysis of its own — just a render of
 * data that already exists.
 *
 * Fills whatever slot it's given (the Now Playing cover's, when the listener taps
 * it) rather than sitting as a small bar over the artwork — so it's drawn with no
 * background of its own, letting `MeltBackdrop` show through around and behind the
 * bars exactly as it does behind `LyricsPane`.
 *
 * ## Why it now reads as an analyser rather than as an ornament
 *
 * Three things were making it move to the music without ever showing the music:
 *
 *  1. **[AnalysisFrame.melbank] is per-bin AGC'd.** Each of the 16 bins is divided
 *     by its *own* ~100 s running peak, which is right for Light Sync — a lamp on
 *     the hi-hats should react to hi-hats — and exactly wrong for a spectrum,
 *     because it divides the spectrum out. Every band filled 0..1 on its own terms,
 *     so a ticking hi-hat stood as tall as a kick and the bars had no shape to
 *     follow. [SpectrumBallistics.advance] multiplies the level back by the frame's
 *     absolute per-band reference ([AnalysisFrame.melbankRef] from a track scan
 *     where there is one, [AnalysisFrame.melbankRefLive] otherwise) — the quantity
 *     those fields exist to carry, taken in the same order of preference as
 *     `SyncoEngine.melLoudWeights`. What comes out is the real spectral tilt: bass
 *     tall and slow, the top end small and quick.
 *  2. **The bars mirrored out from the centre**, so 16 values were drawn as 32 bars
 *     and every frame was left-right symmetrical. Nothing in music is. They now run
 *     low to high, left to right, which is what a spectrum analyser is.
 *  3. **One symmetric time constant** eased rises and falls alike. Analyser
 *     ballistics are asymmetric — near-instant attack so a transient reaches its
 *     true height, slow release so the eye can still read it — and carry peak-hold
 *     caps that fall away under gravity. Both are here now.
 *
 * The 16 bands are resampled up to [BAR_COUNT] bars through a Catmull-Rom spline.
 * Band index *is* the log-frequency axis (the melbank's edges are geometrically
 * spaced from 40 Hz to 11 kHz), so interpolating along it interpolates the spectrum
 * on a log-f axis — the axis an analyser is drawn on anyway.
 *
 * A second, blurred pass of the same bars underneath gives them a soft glow
 * consistent with the glass surfaces elsewhere in the app (see `Backdrop.kt`).
 */
@Composable
fun AudioVisualizer(modifier: Modifier = Modifier, color: Color = LocalAccent.current) {
    val tap = rememberActiveTap()
    val frames = rememberActiveAnalysisFrame()

    // Analysis only ran while Light Sync was on, so on a phone with no bridge
    // configured this drew a flat line for ever — and the visualizer is not an
    // opt-in strip any more but a tap on the cover, where that dead state is far
    // more visible. Ask for frames while it is on screen and give them back after.
    DisposableEffect(tap) {
        tap?.setUiActive(true)
        onDispose { tap?.setUiActive(false) }
    }

    val bars = remember { SpectrumBallistics() }
    // The bars are plain data; this is what the draw phase actually depends on.
    // Reading it *inside* the draw lambda below — rather than easing the levels
    // during composition — is the whole point: a FloatArray is not snapshot state,
    // so a draw that only read it had nothing to invalidate on and painted its
    // first frame for ever. `AmbientRain` in this same package is the pattern.
    var tick by remember { mutableIntStateOf(0) }

    // Keyed on the frame State, not on Unit. `frames` is a fresh State object every
    // time the sound owner changes tap, and a loop started once would go on reading
    // the State belonging to the player that has stopped — frozen bars from the
    // moment of the handover for the rest of the session.
    LaunchedEffect(frames) {
        var lastNanos = 0L
        while (true) {
            withFrameNanos { nanos ->
                val dt = if (lastNanos == 0L) 0f
                else ((nanos - lastNanos) / 1_000_000_000f).coerceIn(0f, MAX_STEP_S)
                lastNanos = nanos
                if (bars.advance(frames.value, dt)) tick++
            }
        }
    }

    Box(modifier) {
        Canvas(Modifier.fillMaxSize().blur(GLOW_RADIUS)) {
            tick
            drawSpectrum(bars, color, glow = true)
        }
        Canvas(Modifier.fillMaxSize()) {
            tick
            drawSpectrum(bars, color, glow = false)
        }
    }
}

/**
 * What each bar is doing, and what the peak cap above it is doing.
 *
 * Plain arrays rather than snapshot state, mutated once per display frame from the
 * `withFrameNanos` loop and read from the draw scope. See [AudioVisualizer] for why
 * that split exists and how the draw is invalidated.
 */
private class SpectrumBallistics {
    /** Bar heights, 0..1 of the pane, lowest frequency first. */
    val levels = FloatArray(BAR_COUNT)

    /** Peak-hold caps, 0..1, never below the bar they sit over. */
    val peaks = FloatArray(BAR_COUNT)

    /** Downward speed of each cap once its hold has expired. */
    private val peakFall = FloatArray(BAR_COUNT)

    /** Seconds each cap still has at its high-water mark before it falls. */
    private val peakHold = FloatArray(BAR_COUNT)

    /** The frame's 16 bands, weighted back to absolute loudness. Reused. */
    private val bands = FloatArray(MELBANK_BINS)

    /**
     * How much of the absolute-loudness correction is applied, 0..1.
     *
     * The reference arrives about eight seconds into a track — the analyzer will not
     * publish one until its slow envelope has settled, since a reference taken from
     * an intro is a reference to the intro. Easing it in over [TILT_FADE_TAU_S]
     * rather than switching it on means the spectrum finds its shape instead of
     * lurching into it mid-verse.
     */
    private var tilt = 0f

    /**
     * Advance one display frame. Returns whether anything moved far enough to be
     * worth a repaint — silence with the caps already down costs nothing.
     */
    fun advance(frame: AnalysisFrame?, dt: Float): Boolean {
        val mel = frame?.melbank?.takeIf { it.size == MELBANK_BINS }
        val ref = frame?.melbankRef?.takeIf { it.size == MELBANK_BINS }
            ?: frame?.melbankRefLive?.takeIf { it.size == MELBANK_BINS }

        if (dt > 0f) {
            val want = if (ref != null) 1f else 0f
            tilt += (want - tilt) * (1f - exp(-dt / TILT_FADE_TAU_S))
        }

        for (i in bands.indices) {
            val raw = mel?.get(i)?.coerceIn(0f, 1f) ?: 0f
            val weight = if (ref == null) 1f else 1f + (loudWeight(ref[i]) - 1f) * tilt
            bands[i] = (raw * weight).coerceIn(0f, 1f)
        }

        val attack = if (dt <= 0f) 0f else 1f - exp(-dt / ATTACK_TAU_S)
        val release = if (dt <= 0f) 0f else 1f - exp(-dt / RELEASE_TAU_S)
        val span = (MELBANK_BINS - 1).toFloat() / (BAR_COUNT - 1).toFloat()
        var moved = false

        for (i in levels.indices) {
            val want = spline(bands, i * span).pow(DISPLAY_GAMMA)
            val was = levels[i]
            levels[i] = was + (want - was) * (if (want > was) attack else release)
            if (abs(levels[i] - was) > MOVED_EPSILON) moved = true

            // Classic peak hold: the cap is carried up instantly, rests, then falls
            // away under a constant acceleration. It is what keeps a transient
            // legible after the bar under it has already dropped.
            val wasPeak = peaks[i]
            if (levels[i] >= peaks[i]) {
                peaks[i] = levels[i]
                peakFall[i] = 0f
                peakHold[i] = PEAK_HOLD_S
            } else if (peakHold[i] > 0f) {
                peakHold[i] -= dt
            } else {
                peakFall[i] += PEAK_GRAVITY * dt
                peaks[i] = (peaks[i] - peakFall[i] * dt).coerceAtLeast(levels[i])
            }
            if (abs(peaks[i] - wasPeak) > MOVED_EPSILON) moved = true
        }
        return moved
    }
}

/**
 * What one band's absolute reference is worth as a height multiplier.
 *
 * [AnalysisFrame.melbankRef] is a linear amplitude ratio against the loudest band,
 * and on most material the top octave sits a long way below the bass — applied
 * straight it would erase everything above the mids. [LOUD_COMPRESS] is the
 * perceptual compression that turns that into a readable tilt, and [LOUD_FLOOR] the
 * share of full height a band keeps however quiet it is, so a real hi-hat still
 * registers as a hi-hat.
 */
private fun loudWeight(ref: Float): Float =
    LOUD_FLOOR + (1f - LOUD_FLOOR) * ref.coerceIn(0f, 1f).pow(LOUD_COMPRESS)

/**
 * Catmull-Rom sample of [b] at fractional index [t], clamped at both ends.
 *
 * A spline rather than a linear blend because a linear one between 16 points is
 * visibly faceted at this width — the eye reads the 16 corners, and the corners
 * belong to the filterbank rather than to anything in the music. Overshoot is
 * clamped away: a cubic through four samples can dip below zero on a steep edge,
 * which would draw as a bar of negative height.
 */
private fun spline(b: FloatArray, t: Float): Float {
    val i = floor(t).toInt().coerceIn(0, b.size - 1)
    val f = t - i
    val p0 = b[(i - 1).coerceAtLeast(0)]
    val p1 = b[i]
    val p2 = b[(i + 1).coerceAtMost(b.size - 1)]
    val p3 = b[(i + 2).coerceAtMost(b.size - 1)]
    val v = 0.5f * (
        2f * p1 +
            (p2 - p0) * f +
            (2f * p0 - 5f * p1 + 4f * p2 - p3) * f * f +
            (3f * p1 - p0 - 3f * p2 + p3) * f * f * f
        )
    return v.coerceIn(0f, 1f)
}

/**
 * Bars low-to-high left-to-right, with their peak caps.
 *
 * [glow] is the blurred pass underneath: it draws the same geometry a little wider
 * and without the caps, since a blurred cap is a smudge rather than a highlight.
 */
private fun DrawScope.drawSpectrum(bars: SpectrumBallistics, color: Color, glow: Boolean) {
    if (size.width <= 0f || size.height <= 0f) return
    val levels = bars.levels
    val slot = size.width / levels.size
    val width = slot * (if (glow) BAR_FILL_GLOW else BAR_FILL)
    val inset = (slot - width) / 2f
    val corner = CornerRadius(width / 2f)
    val capHeight = PEAK_CAP_HEIGHT.toPx()
    // Full scale stops short of the top edge. The melbank is AGC'd to peak at 1, so
    // the bass reaches full height on most bars of most tracks; without headroom it
    // would sit flat against the top of the pane with its cap clamped on to it.
    val span = size.height * (1f - HEADROOM)
    val floorHeight = span * MIN_HEIGHT_FRACTION

    for (i in levels.indices) {
        val l = levels[i]
        val h = (span * l).coerceAtLeast(floorHeight)
        val x = i * slot + inset
        // Louder bars are brighter as well as taller, so a busy spectrum still has a
        // reading order instead of being a flat wall of accent.
        val alpha = (BAR_ALPHA_MIN + (1f - BAR_ALPHA_MIN) * l) * color.alpha
        drawRoundRect(
            color = color.copy(alpha = if (glow) alpha * GLOW_ALPHA else alpha),
            topLeft = Offset(x, size.height - h),
            size = Size(width, h),
            cornerRadius = corner,
        )

        if (glow) continue
        // A cap sitting on the bar it came from is just a brighter tip; leave it out
        // until it has actually separated, or every resting bar wears one.
        val p = bars.peaks[i]
        if (p <= l + PEAK_SHOW_GAP) continue
        drawRoundRect(
            color = color.copy(alpha = PEAK_ALPHA * color.alpha),
            topLeft = Offset(x, size.height - span * p - capHeight),
            size = Size(width, capHeight),
            cornerRadius = CornerRadius(capHeight / 2f),
        )
    }
}

/**
 * How many bars are drawn.
 *
 * Well above the 16 bands behind them: at the size this fills — the whole album
 * cover — 16 blocks read as a level meter, and a spectrum wants enough resolution
 * for the eye to follow a *curve*. See [spline] for what fills the gaps.
 */
private const val BAR_COUNT = 48

/** Share of each bar's slot the bar occupies; the rest is the gap beside it. */
private const val BAR_FILL = 0.72f

/** The glow pass runs wider, so the blur reads as light around the bars. */
private const val BAR_FILL_GLOW = 0.9f

private val GLOW_RADIUS = 22.dp
private const val GLOW_ALPHA = 0.75f

/**
 * Attack and release time constants for the bars.
 *
 * Asymmetric on purpose, and that asymmetry is most of what makes a meter look like
 * a meter: the attack is fast enough to be transparent, so a snare reaches its real
 * height on the frame it happens, while the release is slow enough that the eye can
 * still read that height a moment later. One shared constant — which is what this
 * replaced — either smears the attack or makes the decay flicker.
 *
 * Time constants rather than per-frame fractions, so the bars move at the same speed
 * on a 60 Hz panel and a 120 Hz one.
 */
private const val ATTACK_TAU_S = 0.012f
private const val RELEASE_TAU_S = 0.19f

/** Longest step the ballistics will integrate, so a stalled frame is not a jump. */
private const val MAX_STEP_S = 0.1f

/** How long a peak cap rests at its high-water mark before it starts to fall. */
private const val PEAK_HOLD_S = 0.55f

/** Downward acceleration of a released cap, in pane-heights per second squared. */
private const val PEAK_GRAVITY = 0.85f

/** Share of the pane left empty above a full-scale bar. */
private const val HEADROOM = 0.07f

private val PEAK_CAP_HEIGHT = 2.5.dp
private const val PEAK_ALPHA = 0.85f

/** How far a cap must clear its bar before it is worth drawing separately. */
private const val PEAK_SHOW_GAP = 0.02f

/**
 * Perceptual compression on the absolute per-band reference. See [loudWeight].
 *
 * Roughly a square root — close enough to a loudness law over the range that
 * survives the reference's own 2% floor, and cheaper to reason about.
 */
private const val LOUD_COMPRESS = 0.45f

/** Share of full height the quietest band keeps. See [loudWeight]. */
private const val LOUD_FLOOR = 0.18f

/** How quickly the absolute-loudness tilt eases in once a reference exists. */
private const val TILT_FADE_TAU_S = 0.6f

/**
 * Display curve on the finished band level.
 *
 * Slightly below 1, which lifts quiet detail the way a dB axis would without
 * flattening the tilt [loudWeight] has just restored.
 */
private const val DISPLAY_GAMMA = 0.85f

/** Alpha of a bar at rest; it brightens with height from here. */
private const val BAR_ALPHA_MIN = 0.3f

/** A faint resting height so silence reads as "ready", not as missing bars. */
private const val MIN_HEIGHT_FRACTION = 0.012f

/** Movement below this is not worth a repaint. */
private const val MOVED_EPSILON = 1e-4f
