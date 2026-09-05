package com.engabd.sendpin.game

import android.content.Context
import com.engabd.sendpin.ui.viewmodel.GameRecord
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/**
 * Every track's best rhythm-game run, as one JSON map in SharedPreferences.
 *
 * Small by construction: one record per track actually played, four numbers each, so a
 * thousand tracks is about 100 KB and it only grows by what was played.
 *
 * Lifted out of `RhythmGameViewModel`, which owned it privately, because the Light Sync
 * page wants to show a personal best on the Rhythm Lights tile without starting the
 * game to find one out. Two copies of the same parse would be two places to get the
 * key or the format wrong, and the map is written from the view model and read from a
 * composable — exactly the pair that has to agree.
 *
 * Keyed by the scan store's track key (`TrackScanRepository.keyFor`), so a record
 * follows the track across backends rather than being tied to one server's ids.
 *
 * Every access swallows its own failure. A best score is an ornament: a corrupt or
 * unreadable store should cost the tile its number, never the game its run.
 */
object GameRecords {

    private const val PREFS = "game_records"
    private const val KEY = "records"

    private val serializer = MapSerializer(String.serializer(), GameRecord.serializer())

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** Every stored record, or an empty map if there are none or the store is unreadable. */
    fun load(context: Context): Map<String, GameRecord> = runCatching {
        val json = prefs(context).getString(KEY, null) ?: return emptyMap()
        Json.decodeFromString(serializer, json)
    }.getOrDefault(emptyMap())

    /** Replace the whole store. A no-op for an empty map, which would only ever be a wipe. */
    fun save(context: Context, records: Map<String, GameRecord>) {
        if (records.isEmpty()) return
        runCatching {
            prefs(context).edit().putString(KEY, Json.encodeToString(serializer, records)).apply()
        }
    }

    /** One track's record, or null — for a null key too, so callers need not check first. */
    fun bestFor(context: Context, trackKey: String?): GameRecord? =
        trackKey?.let { load(context)[it] }
}
