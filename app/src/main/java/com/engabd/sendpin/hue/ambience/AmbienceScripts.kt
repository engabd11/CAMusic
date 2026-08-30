package com.engabd.sendpin.hue.ambience

import com.engabd.sendpin.hue.Rgb
import com.engabd.sendpin.hue.ambience.scripts.AuroraScript
import com.engabd.sendpin.hue.ambience.scripts.FireplaceScript
import com.engabd.sendpin.hue.ambience.scripts.FireworksAmbienceScript
import com.engabd.sendpin.hue.ambience.scripts.LightTrainScript
import com.engabd.sendpin.hue.ambience.scripts.ThunderstormScript
import com.engabd.sendpin.hue.ambience.scripts.UnderwaterScript

/**
 * A fresh script for [effect].
 *
 * New every time rather than pooled: a script carries filter state, RNG position and a
 * scheduling cursor, and restarting a show is supposed to start it over rather than
 * resume it mid-storm.
 */
fun scriptFor(effect: AmbienceEffect): AmbienceScript = when (effect) {
    AmbienceEffect.FIREWORKS -> FireworksAmbienceScript(AmbienceEffect.FIREWORKS)
    // A different seed and palette, or the second tile is the first one again:
    // the script is deterministic in its seed, so sharing one shares the show.
    AmbienceEffect.FIREWORKS_2 -> FireworksAmbienceScript(
        AmbienceEffect.FIREWORKS_2,
        seed = 0x2B_17_C0_5EL,
        shellColours = FireworksAmbienceScript.WHITE_HOT_SHELLS,
    )
    AmbienceEffect.THUNDERSTORM -> ThunderstormScript()
    AmbienceEffect.THUNDERSTORM_2 -> ThunderstormScript(
        AmbienceEffect.THUNDERSTORM_2,
        seed = 0x9C_44_1E_07L,
        nearBolt = Rgb(0.95f, 0.86f, 0.72f),
        farBolt = Rgb(0.72f, 0.62f, 0.85f),
    )
    AmbienceEffect.UNDERWATER -> UnderwaterScript()
    AmbienceEffect.FIREPLACE -> FireplaceScript()
    AmbienceEffect.LIGHT_TRAIN -> LightTrainScript()
    AmbienceEffect.AURORA -> AuroraScript()
}
