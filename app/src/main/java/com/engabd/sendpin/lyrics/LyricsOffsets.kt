package com.engabd.sendpin.lyrics

import android.content.Context

/**
 * A timing nudge remembered for one song.
 *
 * The global offset in Settings fixes a provider that is late across the board;
 * this fixes the one song whose lyrics were stamped against a different edit —
 * the radio cut, the album version with a longer intro. Kept per song (artist and
 * title, not a server id), so the same song from another library or from Downloads
 * keeps its correction.
 *
 * SharedPreferences, like [com.engabd.sendpin.library.LocalFavourites]: small, read
 * once per song, and nothing to migrate.
 */
object LyricsOffsets {
    private const val PREFS = "lyrics_offsets"

    /** Limits of the nudge, and the step one tap moves it. */
    const val MAX_MS = 5_000
    const val STEP_MS = 250

    fun key(artist: String?, title: String): String =
        "${artist.orEmpty().trim().lowercase()}\u0000${title.trim().lowercase()}"

    fun get(context: Context, key: String): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(key, 0)

    fun set(context: Context, key: String, ms: Int) {
        val v = ms.coerceIn(-MAX_MS, MAX_MS)
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (v == 0) remove(key) else putInt(key, v)
        }.apply()
    }
}
