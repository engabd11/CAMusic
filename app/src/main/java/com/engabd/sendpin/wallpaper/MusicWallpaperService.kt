package com.engabd.sendpin.wallpaper

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import com.engabd.sendpin.audio.AnalysisFrame
import com.engabd.sendpin.audio.MELBANK_BINS
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A live wallpaper that renders the music passing through the app's analysis
 * pipeline — the same 16-band melbank [AudioAnalysisTap][com.engabd.sendpin.audio.AudioAnalysisTap]
 * publishes for Light Sync and the on-screen visualizer.
 *
 * ## Why a wallpaper and not a widget
 *
 * A widget lives on the home screen but cannot draw at 60 fps — AppWidgetProvider
 * updates are throttled to minutes. A wallpaper owns a [Surface] and a render
 * loop, so the spectrum moves with the music the way the visualizer does, not
 * the way a clock widget ticks.
 *
 * ## What it draws
 *
 *  - **Playing:** a simplified spectrum visualiser — [BAR_COUNT] bars driven by
 *    the 16 melbank bins, interpolated with a Catmull-Rom spline the same way
 *    [AudioVisualizer][com.engabd.sendpin.ui.design.AudioVisualizer] does. Bars
 *    are coloured from the album palette so the wallpaper belongs to whatever
 *    is playing, not to a fixed scheme.
 *  - **Not playing:** a slow ambient drift — vertical bands that breathe in
 *    and out using sine waves at different phases, coloured from the same
 *    palette. The result is a living background that is never inert but never
 *    demands attention when there is no music.
 *
 * ## Where the data comes from
 *
 * [spectrum] is a [StateFlow] that [AudioAnalysisTap.frames] feeds. The engine
 * collects it on a background coroutine and snapshots the latest frame on each
 * render tick. When the flow is null or the frame's melbank is empty, the
 * wallpaper falls back to ambient drift.
 *
 * [palette] holds the current album colours as an array of ARGB ints. The
 * app's palette extraction ([AlbumPalette][com.engabd.sendpin.ui.design.AlbumPalette])
 * publishes to it; a default fallback is used until the first cover is seen.
 *
 * ## Threading
 *
 * The engine's render loop runs on the main thread (via [Handler]), as
 * [SurfaceHolder] callbacks require. The StateFlow collection runs on a
 * background [Dispatchers.Default] coroutine; it only stores the latest frame
 * in a `@Volatile` field, which the render loop reads. No locks: a torn read
 * means one frame's worth of slightly stale data, which is invisible.
 *
 * ## Config pattern
 *
 * The `@Volatile` + snapshot approach follows [LocalDsp][com.engabd.sendpin.audio.LocalDsp]:
 * settings that can change at runtime (bar count, colours, drift speed) are
 * written to `@Volatile` fields from any thread and read on the render thread.
 * No allocation on the render path — paints and gradients are reused.
 */
class MusicWallpaperService : WallpaperService() {

    /**
     * Spectrum data from the audio analysis pipeline.
     *
     * The app publishes [AnalysisFrame]s here from whichever player is making
     * sound (the same resolution [PlaybackOwner][com.engabd.sendpin.service.PlaybackOwner]
     * already does for the visualizer). The wallpaper subscribes to this flow;
     * when it emits null or a frame with an empty melbank, the wallpaper draws
     * ambient drift instead of a spectrum.
     */
    private val _spectrum = MutableStateFlow<AnalysisFrame?>(null)
    val spectrum: StateFlow<AnalysisFrame?> = _spectrum.asStateFlow()

    /**
     * The album colour palette as ARGB ints.
     *
     * Published by the app's palette extraction. The first entry is the accent,
     * followed by companion swatches. A default is used until the app sets one.
     */
    @Volatile
    var palette: IntArray = DEFAULT_PALETTE.copyOf()

    override fun onCreateEngine(): Engine = MusicWallpaperEngine()

    /**
     * The wallpaper engine. Owns the render loop and the surface.
     *
     * Extends [WallpaperService.Engine] — the system creates one per wallpaper
     * instance and notifies it of surface lifecycle changes. The render loop
     * is a [Handler] callback that schedules itself at ~60 fps while visible
     * and stops when the surface is destroyed.
     */
    inner class MusicWallpaperEngine : Engine() {

        private val handler = Handler(Looper.getMainLooper())
        private var visible = false
        private var width = 0
        private var height = 0

        // ── Spectrum data (updated by background collector, read by render) ──

        @Volatile
        private var latestFrame: AnalysisFrame? = null

        // ── Ballistics state (render thread only) ───────────────────────────

        /** Smoothed bar levels, 0..1, one per [BAR_COUNT] bar. */
        private val levels = FloatArray(BAR_COUNT)

        /** Ambient drift phases, one per drift band. */
        private val driftPhase = FloatArray(DRIFT_BANDS) { i -> i * 0.7f }

        /** Time accumulator for the drift, in seconds. */
        private var driftTime = 0f

        // ── Reusable paint objects (render thread only) ────────────────────

        private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        // ── Coroutine scope for StateFlow collection ──────────────────────

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        private var collectJob: Job? = null

        init {
            // Collect the spectrum flow on a background coroutine. Only the
            // @Volatile latestFrame field is touched — the render loop reads
            // it, so the collector must not touch any render-thread state.
            collectJob = scope.launch {
                _spectrum.collect { frame ->
                    latestFrame = frame
                }
            }
        }

        override fun onVisibilityChanged(visible: Boolean) {
            this.visible = visible
            if (visible) {
                handler.post(renderRunnable)
            } else {
                handler.removeCallbacks(renderRunnable)
            }
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            this.width = width
            this.height = height
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            visible = false
            handler.removeCallbacks(renderRunnable)
        }

        override fun onDestroy() {
            collectJob?.cancel()
            scope.cancel()
            handler.removeCallbacks(renderRunnable)
        }

        private val renderRunnable = object : Runnable {
            override fun run() {
                if (!visible) return
                val holder = surfaceHolder
                if (!holder.surface.isValid) return

                val canvas = holder.lockCanvas() ?: return
                try {
                    drawFrame(canvas)
                } finally {
                    holder.unlockCanvasAndPost(canvas)
                }

                // Schedule the next frame. ~16 ms for 60 fps.
                handler.postDelayed(this, FRAME_INTERVAL_MS)
            }
        }

        /**
         * Draw one frame.
         *
         * If the latest [AnalysisFrame] has a melbank, render the spectrum.
         * Otherwise, render the ambient drift. Both paths share the same
         * background gradient — a vertical blend of the first two palette
         * colours, darkened — so the transition between playing and not
         * playing is a shape change, not a colour flash.
         */
        private fun drawFrame(canvas: Canvas) {
            if (width <= 0 || height <= 0) return
            val pal = palette

            // Background: vertical gradient from the darkest palette colour
            // to black, so bars and drift sit on a surface that belongs to
            // the album rather than to a flat fill.
            drawBackground(canvas, pal)

            val frame = latestFrame
            val mel = frame?.melbank?.takeIf { it.size == MELBANK_BINS }

            if (mel != null) {
                drawSpectrum(canvas, mel, pal)
            } else {
                drawAmbientDrift(canvas, pal)
            }
        }

        /**
         * Draw a vertical gradient background using the palette's first two
         * colours, darkened so the foreground bars read clearly.
         */
        private fun drawBackground(canvas: Canvas, pal: IntArray) {
            val top = darken(pal.getOrElse(0) { Color.BLACK }, 0.4f)
            val bottom = darken(pal.getOrElse(1) { Color.BLACK }, 0.15f)
            bgPaint.shader = LinearGradient(
                0f, 0f, 0f, height.toFloat(),
                top, bottom,
                Shader.TileMode.CLAMP,
            )
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)
        }

        /**
         * Draw the spectrum bars.
         *
         * The 16 melbank bins are resampled to [BAR_COUNT] bars through a
         * Catmull-Rom spline, the same approach as the on-screen visualizer.
         * Bars have asymmetric ballistics — fast attack, slow release — so
         * transients reach their height and the eye can still read it.
         */
        private fun drawSpectrum(canvas: Canvas, mel: FloatArray, pal: IntArray) {
            val dt = FRAME_INTERVAL_S
            val attack = 1f - exp(-dt / ATTACK_TAU_S)
            val release = 1f - exp(-dt / RELEASE_TAU_S)
            val span = (MELBANK_BINS - 1).toFloat() / (BAR_COUNT - 1).toFloat()

            val barWidth = width.toFloat() / BAR_COUNT
            val headroom = height * HEADROOM
            val spanHeight = height - headroom
            val floorHeight = spanHeight * MIN_BAR_HEIGHT

            for (i in 0 until BAR_COUNT) {
                // Catmull-Rom interpolation of the 16 melbank bins.
                val t = i * span
                val target = splineSample(mel, t)

                // Asymmetric ballistics: fast up, slow down.
                val was = levels[i]
                val a = if (target > was) attack else release
                levels[i] = was + (target - was) * a

                val h = max(floorHeight, spanHeight * levels[i])
                val x = i * barWidth
                val w = barWidth * BAR_FILL
                val inset = (barWidth - w) / 2f

                // Colour cycles through the palette so the spectrum has a
                // sense of width, not a wall of one colour.
                val color = pal[i % pal.size]
                barPaint.color = color

                val radius = w / 2f
                canvas.drawRoundRect(
                    x + inset, height - h,
                    x + inset + w, height.toFloat(),
                    radius, radius,
                    barPaint,
                )
            }
        }

        /**
         * Draw the ambient drift — vertical bands that breathe using sine
         * waves at different phases, coloured from the palette.
         *
         * The effect is a living background: it moves slowly enough not to
         * distract but never sits still. Each band's height is a sine of
         * (time + phase), so the bands rise and fall out of sync, giving
         * the impression of a gentle wave rather than a pulse.
         */
        private fun drawAmbientDrift(canvas: Canvas, pal: IntArray) {
            driftTime += FRAME_INTERVAL_S

            val bandWidth = width.toFloat() / DRIFT_BANDS
            val maxBarHeight = height * DRIFT_MAX_HEIGHT

            for (i in 0 until DRIFT_BANDS) {
                val phase = driftPhase[i]
                // Slow sine, offset per band so they don't all move together.
                val wave = (sin(driftTime * DRIFT_FREQ + phase) + 1f) * 0.5f
                // Second harmonic for a more organic shape.
                val wave2 = (sin(driftTime * DRIFT_FREQ * 0.6f + phase * 1.3f) + 1f) * 0.5f
                val blend = wave * 0.6f + wave2 * 0.4f

                val h = maxBarHeight * blend
                val w = bandWidth * DRIFT_BAR_FILL
                val inset = (bandWidth - w) / 2f
                val x = i * bandWidth + inset

                val color = pal[i % pal.size]
                barPaint.color = darken(color, 0.35f + 0.4f * blend)

                val radius = w / 2f
                canvas.drawRoundRect(
                    x, height - h,
                    x + w, height.toFloat(),
                    radius, radius,
                    barPaint,
                )
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /**
     * Catmull-Rom spline sample of [b] at fractional index [t], clamped at
     * both ends. Same approach as [AudioVisualizer][com.engabd.sendpin.ui.design.AudioVisualizer]'s
     * `spline` function: the 16 melbank bins are a log-frequency axis, so
     * interpolating along it interpolates the spectrum on a log-f axis.
     */
    private fun splineSample(b: FloatArray, t: Float): Float {
        val n = b.size
        if (n == 0) return 0f
        if (n == 1) return b[0].coerceIn(0f, 1f)
        val i = kotlin.math.floor(t).toInt().coerceIn(0, n - 1)
        val f = t - i
        val p0 = b[(i - 1).coerceAtLeast(0)]
        val p1 = b[i]
        val p2 = b[(i + 1).coerceAtMost(n - 1)]
        val p3 = b[(i + 2).coerceAtMost(n - 1)]
        val v = 0.5f * (
            2f * p1 +
                (p2 - p0) * f +
                (2f * p0 - 5f * p1 + 4f * p2 - p3) * f * f +
                (3f * p1 - p0 - 3f * p2 + p3) * f * f * f
        )
        return v.coerceIn(0f, 1f)
    }

    /**
     * Darken an ARGB colour by [factor] (0 = black, 1 = unchanged).
     */
    private fun darken(color: Int, factor: Float): Int {
        val r = (Color.red(color) * factor).toInt().coerceIn(0, 255)
        val g = (Color.green(color) * factor).toInt().coerceIn(0, 255)
        val b = (Color.blue(color) * factor).toInt().coerceIn(0, 255)
        return Color.argb(Color.alpha(color), r, g, b)
    }

    companion object {
        /** How many bars the spectrum draws. */
        private const val BAR_COUNT = 48

        /** Share of each bar's slot the bar occupies. */
        private const val BAR_FILL = 0.72f

        /** Share of the canvas left empty above a full-scale bar. */
        private const val HEADROOM = 0.07f

        /** A faint resting height so silence reads as "ready". */
        private const val MIN_BAR_HEIGHT = 0.012f

        /** Attack time constant for bar rise, in seconds. */
        private const val ATTACK_TAU_S = 0.012f

        /** Release time constant for bar fall, in seconds. */
        private const val RELEASE_TAU_S = 0.19f

        /** Number of bands in the ambient drift. */
        private const val DRIFT_BANDS = 12

        /** Share of the screen height the drift bars can reach. */
        private const val DRIFT_MAX_HEIGHT = 0.35f

        /** Drift oscillation frequency in Hz. */
        private const val DRIFT_FREQ = 0.15f

        /** Share of each drift band's slot the bar occupies. */
        private const val DRIFT_BAR_FILL = 0.5f

        /** Frame interval in milliseconds (~60 fps). */
        private const val FRAME_INTERVAL_MS = 16L

        /** Frame interval in seconds. */
        private const val FRAME_INTERVAL_S = FRAME_INTERVAL_MS / 1000f

        /**
         * Default palette used until the app publishes album colours.
         *
         * A muted teal-to-indigo, so the wallpaper is not jarring before the
         * first track's artwork is extracted.
         */
        private val DEFAULT_PALETTE = intArrayOf(
            Color.parseColor("#1B5E6B"),
            Color.parseColor("#2D3561"),
            Color.parseColor("#3A4A5E"),
            Color.parseColor("#1A2238"),
        )
    }
}