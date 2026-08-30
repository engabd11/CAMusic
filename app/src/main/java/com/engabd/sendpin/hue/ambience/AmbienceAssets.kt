package com.engabd.sendpin.hue.ambience

import android.content.Context

/**
 * A bundled, real-recording sound for an ambience effect, shipped as an app asset.
 *
 * Looked up by [AmbienceEffect.wire] under `assets/ambience/<wire>/bed.*` — a single
 * looping bed per effect, the same shape a user's own "My Clip" file plays as (see
 * `EffectsViewModel`). Absent for any effect with nothing bundled, which is a normal,
 * fully-supported state: the caller falls back to the synthesised sound.
 */
object AmbienceAssets {
    /** `"ambience/<wire>/<filename>"`, or null if this effect ships no bundled bed. */
    fun bedAssetPath(context: Context, effect: AmbienceEffect): String? {
        val dir = "ambience/${effect.wire}"
        val names = runCatching { context.assets.list(dir) }.getOrNull() ?: return null
        val bed = names.firstOrNull { it.startsWith("bed.") } ?: return null
        return "$dir/$bed"
    }
}
