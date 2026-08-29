package com.engabd.sendpin.hue.ambience.scripts

import com.engabd.sendpin.hue.Rgb
import com.engabd.sendpin.hue.Vec3
import com.engabd.sendpin.hue.ambience.AmbienceEffect
import com.engabd.sendpin.hue.ambience.AmbienceEvent
import com.engabd.sendpin.hue.ambience.AmbienceParams
import com.engabd.sendpin.hue.ambience.AmbienceScript
import com.engabd.sendpin.hue.ambience.BrownNoise
import com.engabd.sendpin.hue.ambience.Envelope
import com.engabd.sendpin.hue.ambience.GrainCloud
import com.engabd.sendpin.hue.ambience.OnePole
import com.engabd.sendpin.hue.ambience.RoomModel
import com.engabd.sendpin.hue.ambience.Rng
import com.engabd.sendpin.hue.ambience.Svf
import com.engabd.sendpin.hue.ambience.VoicePool
import com.engabd.sendpin.hue.ambience.panGains
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin

/**
 * Shells climbing, bursting overhead, and crackling away into the dark.
 *
 * The burst model is the one `FireworksEffect` already uses for the music-reactive
 * effect — an expanding Gaussian shell, `EXPAND` metres a second, fading on its own
 * lifetime — but sourced from scheduled events rather than from beat detection, and in
 * real 3D rather than the (xrank, height) plane the music version works in.
 *
 * The launch is the interesting part. One event carries the whole shell: the whistle on
 * the way up, the burst, and the crackle after. The light side shows nothing until the
 * burst, the audio side starts whistling `LAUNCH_S` earlier — and because both read the
 * same `startS`, the whistle always ends exactly when the sky lights up.
 */
class FireworksAmbienceScript : AmbienceScript {

    override val effect = AmbienceEffect.FIREWORKS

    private lateinit var room: RoomModel
    @Volatile private var params = AmbienceParams()

    private val rng = Rng(0xF1_2E_00_2AL)
    private var nextBurstS = 0.8

    /**
     * Everything one shell needs to make its own sound.
     *
     * Per shell, not per script: two shells overlapping is the normal case, and shared
     * filters would make the render order-dependent — see [VoicePool].
     */
    private class ShellVoice(sampleRate: Int) {
        val whistle = Svf()
        val boom = BrownNoise(0x3131)
        val boomLp = OnePole()
        val crackle = Rng(0x4242)
        val sparkle = GrainCloud(0x5A5A, sampleRate)
        fun reset() { whistle.reset(); boomLp.reset() }
    }

    private var voices: VoicePool<ShellVoice>? = null
    private val pan = FloatArray(2)

    override fun bind(room: RoomModel, params: AmbienceParams) {
        this.room = room
        this.params = params
    }

    override fun retune(params: AmbienceParams) { this.params = params }

    override fun schedule(fromS: Double, toS: Double, emit: (AmbienceEvent) -> Unit) {
        if (nextBurstS < fromS) nextBurstS = fromS
        val intensity = params.intensity.coerceIn(0f, 1f)
        val lambda = 0.25f + 1.15f * intensity          // bursts per second
        while (nextBurstS < toS) {
            val az = rng.f01()
            // Overhead and out: a firework is not in the room with you.
            val radius = 0.55f + 0.45f * rng.f01()
            val origin = Vec3(
                0.5f + radius * kotlin.math.cos(2f * PI.toFloat() * az),
                0.5f + radius * sin(2f * PI.toFloat() * az),
                0.75f + 0.25f * rng.f01(),
            )
            emit(
                AmbienceEvent(
                    kind = AmbienceEvent.BURST,
                    startS = nextBurstS,
                    // Long enough to expand and fade; matches FireworksEffect's BURST_LIFE.
                    env = Envelope(attackS = 0.05f, holdS = 0.05f, decayTauS = 0.55f),
                    gain = 0.45f + 0.55f * rng.f01(),
                    origin = origin,
                    azimuth = az,
                    colour = SHELL_COLOURS[rng.nextInt(SHELL_COLOURS.size)],
                    // Shell size: drives both the light shell's radius and the boom's depth.
                    timbre = 0.5f + 0.5f * rng.f01(),
                    seed = rng.nextLong(),
                ),
            )
            nextBurstS += (-ln(rng.f01().coerceAtLeast(1e-6f)) / lambda).toDouble()
        }
    }

    override fun renderLights(
        tS: Double,
        live: Array<AmbienceEvent?>,
        n: Int,
        out: MutableMap<Int, Rgb>,
    ) {
        val intensity = params.intensity.coerceIn(0f, 1f)
        for (id in room.ids) {
            // Night sky between bursts — not black, or the room looks switched off.
            var r = NIGHT.first
            var g = NIGHT.second
            var b = NIGHT.third
            var k = 0
            while (k < n) {
                val e = live[k]
                k++
                if (e == null || e.kind != AmbienceEvent.BURST) continue
                val age = e.ageAt(tS)
                if (age < 0f) continue        // still climbing; nothing to see yet
                val d = room.distanceTo(id, e.origin)
                // The shell expands, so a lamp lights when the front reaches it and
                // dims as it passes — a ring travelling outward, not a flash.
                val shellR = EXPAND * age * e.timbre
                val x = (d - shellR) / SHELL_WIDTH
                val ring = exp(-x * x)
                val lvl = e.levelAt(tS) * ring
                if (lvl <= 0f) continue
                r = minOf(1f, r + e.colour.first * lvl)
                g = minOf(1f, g + e.colour.second * lvl)
                b = minOf(1f, b + e.colour.third * lvl)
            }
            val trim = 0.6f + 0.4f * intensity
            out[id] = Rgb(r * trim, g * trim, b * trim)
        }
    }

    override fun renderAudio(
        out: FloatArray,
        frames: Int,
        startS: Double,
        sampleRate: Int,
        live: Array<AmbienceEvent?>,
        n: Int,
    ) {
        // Built on the first block, when the sample rate is finally known.
        val pool = voices ?: VoicePool(MAX_VOICES) { ShellVoice(sampleRate) }.also { voices = it }
        // Deliberately no block-boundary skip here.
        //
        // The obvious optimisation — bail out when a shell's whole span falls outside
        // this block — makes *which* events are rendered depend on where the block
        // boundaries happen to fall. The voices below are shared between shells and
        // carry state, so a shell skipped in one block layout and rendered in another
        // leaves the filters in a different place, and the output stops being a function
        // of the show alone: it becomes a function of the audio buffer size. Every
        // sample is gated on absolute time inside [renderShell] instead, which costs a
        // few comparisons and makes the result reproducible.
        var k = 0
        while (k < n) {
            val e = live[k]
            k++
            if (e == null || e.kind != AmbienceEvent.BURST) continue
            panGains(sin(2f * PI.toFloat() * e.azimuth), pan)
            renderShell(e, pool.voiceFor(e) { it.reset() }, out, frames, startS, sampleRate)
        }
    }

    private fun renderShell(
        e: AmbienceEvent,
        v: ShellVoice,
        out: FloatArray,
        frames: Int,
        startS: Double,
        sampleRate: Int,
    ) {
        val boomEnv = Envelope(0.006f, 0.02f, 0.28f + 0.35f * e.timbre)
        v.boomLp.setCutoff(160f + 240f * (1f - e.timbre), sampleRate)
        var i = 0
        while (i < frames) {
            val t = startS + i.toDouble() / sampleRate
            val rel = (t - e.startS).toFloat()
            var s = 0f

            // 1. Whistle on the way up: a rising band-pass, ending exactly at the burst.
            if (rel < 0f && rel > -LAUNCH_S.toFloat()) {
                val climb = 1f + rel / LAUNCH_S.toFloat()   // 0 at launch, 1 at burst
                v.whistle.set(500f + 1500f * climb, 9f, sampleRate)
                val fade = climb * climb
                s += v.whistle.bp(v.crackle.bipolar()) * fade * 0.10f * e.gain
            }

            // 2. The burst itself.
            if (rel >= 0f) {
                val be = boomEnv.at(rel)
                if (be > 0f) s += v.boomLp.lp(v.boom.next()) * be * e.gain * 0.85f
            }

            // 3. The crackle that falls out of it, thinning as it goes.
            if (rel > 0.05f && rel < CRACKLE_S.toFloat()) {
                val decay = exp(-(rel - 0.05f) / 0.7f)
                s += v.sparkle.next(70f * decay + 4f, 4200f, 3.5f) * decay * e.gain * 0.30f
            }

            if (s != 0f) {
                out[i * 2] += s * pan[0]
                out[i * 2 + 1] += s * pan[1]
            }
            i++
        }
    }

    private companion object {
        /** Concurrent shells that can each keep their own voice. Beyond this the oldest recycles. */
        const val MAX_VOICES = 12

        /** Shell radius per second, in normalised room units. Matches FireworksEffect. */
        const val EXPAND = 0.9f
        const val SHELL_WIDTH = 0.34f

        /** How long a shell whistles before it bursts. */
        const val LAUNCH_S = 0.9

        /** How long the crackle carries on after. */
        const val CRACKLE_S = 2.4

        val NIGHT = Rgb(0.02f, 0.02f, 0.06f)
        val SHELL_COLOURS = arrayOf(
            Rgb(1.00f, 0.30f, 0.25f),
            Rgb(1.00f, 0.78f, 0.25f),
            Rgb(0.35f, 0.85f, 1.00f),
            Rgb(0.65f, 0.40f, 1.00f),
            Rgb(0.40f, 1.00f, 0.55f),
            Rgb(1.00f, 0.55f, 0.85f),
        )
    }
}
