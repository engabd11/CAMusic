package com.engabd.sendpin.hue.ambience

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
    AmbienceEffect.FIREWORKS_2 -> FireworksAmbienceScript(AmbienceEffect.FIREWORKS_2)
    AmbienceEffect.THUNDERSTORM -> ThunderstormScript()
    AmbienceEffect.UNDERWATER -> UnderwaterScript()
    AmbienceEffect.FIREPLACE -> FireplaceScript()
    AmbienceEffect.LIGHT_TRAIN -> LightTrainScript()
    AmbienceEffect.AURORA -> AuroraScript()
}
