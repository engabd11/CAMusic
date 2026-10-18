package com.engabd.sendpin.audio

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.pow

/**
 * Headphone crossfeed, after Boris Mikhaylov's bs2b.
 *
 * On speakers each ear hears both channels, the far one a little later, quieter and
 * with its treble shaded by the head. On headphones each ear hears only its own
 * channel, which is why a hard-panned instrument can feel like it is inside one ear.
 * Crossfeed mixes a low-passed, attenuated copy of each channel into the other, which
 * is most of what the head does to bass and low mids.
 *
 * The same structure as the reference implementation, so the presets mean what they
 * mean everywhere else:
 *
 *  - the **crossfed** path is a one-pole low-pass at `fcut`, several dB down at DC;
 *  - the **direct** path is a one-pole/one-zero high shelf that sits [Preset.feedDb]
 *    above the crossfed path at DC and rises to unity at the top, so treble is not shared;
 *  - one common gain brings a centred (mono) signal back to unity, so switching it on
 *    moves things around the head without changing how loud a typical mix is.
 *
 * Not thread safe on its own: [configure] and [process] belong to the audio thread.
 * Nothing here allocates once built.
 */
class Crossfeed {

    /** A named setting. [feedDb] is how far the direct path sits above the crossfed one. */
    enum class Preset(val id: String, val label: String, val fcutHz: Int, val feedDb: Float) {
        OFF("off", "Off", 0, 0f),
        DEFAULT("default", "Default", 700, 4.5f),
        CHU_MOY("chu_moy", "Chu Moy", 700, 6.0f),
        JAN_MEIER("jan_meier", "Jan Meier", 650, 9.5f);

        companion object {
            /** The preset stored as [id]; anything unknown, including a future one, is [OFF]. */
            fun of(id: String?): Preset = entries.firstOrNull { it.id == id } ?: OFF
        }
    }

    // One-pole low-pass: y = a0Lo * x + b1Lo * y1.
    private var a0Lo = 0.0
    private var b1Lo = 0.0

    // Direct path: y = a0Hi * x + a1Hi * x1 + b1Hi * y1.
    private var a0Hi = 1.0
    private var a1Hi = 0.0
    private var b1Hi = 0.0

    private var gain = 1.0

    private var loL = 0.0
    private var loR = 0.0
    private var hiL = 0.0
    private var hiR = 0.0
    private var asisL = 0.0
    private var asisR = 0.0

    /** Build the coefficients for [preset] at [sampleRate]. History is cleared. */
    fun configure(sampleRate: Int, preset: Preset) {
        reset()
        if (preset == Preset.OFF || sampleRate <= 0) return
        val fcLo = preset.fcutHz.toDouble()
        val feed = preset.feedDb.toDouble()

        val gbLo = feed * -5.0 / 6.0 - 3.0
        val gbHi = feed / 6.0 - 3.0
        val gLo = 10.0.pow(gbLo / 20.0)
        val gHi = 1.0 - 10.0.pow(gbHi / 20.0)
        val fcHi = fcLo * 2.0.pow((gbLo - 20.0 * log10(gHi)) / 12.0)

        var x = exp(-2.0 * PI * fcLo / sampleRate)
        a0Lo = gLo * (1.0 - x)
        b1Lo = x

        x = exp(-2.0 * PI * fcHi / sampleRate)
        a0Hi = 1.0 - gHi * (1.0 - x)
        a1Hi = -x
        b1Hi = x

        gain = 1.0 / (1.0 - gHi + gLo)
    }

    fun reset() {
        loL = 0.0; loR = 0.0
        hiL = 0.0; hiR = 0.0
        asisL = 0.0; asisR = 0.0
    }

    /** Crossfeed one stereo frame in place: `frame[0]` is left, `frame[1]` right. */
    fun process(frame: FloatArray) {
        val l = frame[0].toDouble()
        val r = frame[1].toDouble()

        loL = a0Lo * l + b1Lo * loL
        loR = a0Lo * r + b1Lo * loR
        hiL = a0Hi * l + a1Hi * asisL + b1Hi * hiL
        hiR = a0Hi * r + a1Hi * asisR + b1Hi * hiR
        asisL = l
        asisR = r

        frame[0] = ((hiL + loR) * gain).toFloat()
        frame[1] = ((hiR + loL) * gain).toFloat()
    }
}

/** Which outputs get crossfeed, and which setting an output has. */
object CrossfeedSelection {
    /**
     * Crossfeed is for headphones: wired, a USB headset or DAC, and Bluetooth. The
     * phone's own speaker and HDMI already put both channels in both ears.
     *
     * Android cannot tell a Bluetooth speaker from Bluetooth headphones over A2DP, so
     * a Bluetooth output counts, and the choice is kept per output: it stays Off for a
     * speaker until someone turns it on there.
     */
    fun isHeadphones(output: OutputKey?): Boolean =
        output != null && (output.id == "wired" || output.id.startsWith("bt:") || output.id.startsWith("usb:"))

    /** The preset that should run: Off unless [output] is a pair of headphones. */
    fun effective(profiles: EqProfiles, output: OutputKey?): Crossfeed.Preset {
        if (!isHeadphones(output)) return Crossfeed.Preset.OFF
        val id = if (profiles.perOutput && output != null) {
            profiles.crossfeedByOutput[output.id] ?: profiles.crossfeed
        } else {
            profiles.crossfeed
        }
        return Crossfeed.Preset.of(id)
    }
}
