package com.engabd.sendpin.ui.screens

import com.engabd.sendpin.ma.MaItem

/**
 * Splitting an album into its discs, on servers that say so and on servers that don't.
 *
 * The album screen used to do this inline with a `groupBy { it.discNumber ?: 1 }`, which
 * is right only when every backend fills the field. Music Assistant does
 * (`disc_number`); Navidrome and Jellyfin have the field too — `discNumber` and
 * `ParentIndexNumber` — but both drop it whenever the tag is missing on the file, and a
 * two-disc rip with no disc tags is common enough that "the headers never appear" was
 * the whole of the bug report.
 *
 * So the field is used when it is there, and *derived* when it is not: a track list that
 * counts 1..12 and then starts again at 1 is two discs, whatever the server neglected to
 * say. The derivation is deliberately timid — see [deriveFromRestarts] — because a wrong
 * split invents a record that does not exist, which is worse than the flat list the
 * screen showed before.
 */

/** One disc's tracks, in the order they should be played. */
data class DiscGroup(
    val number: Int,
    /** The disc's own name, where the server has one ("Live at the Fillmore"). */
    val title: String?,
    val tracks: List<MaItem>,
    /** The disc numbers were derived from restarting track numbers, not given. */
    val derived: Boolean = false,
)

/**
 * [tracks] split into discs.
 *
 * Always returns at least one group for a non-empty list, so the caller's test for
 * "is this a multi-disc album" is simply `size > 1`. [titles] is the album's own disc
 * naming where a server offers one (OpenSubsonic `discTitles`).
 */
fun discGroups(tracks: List<MaItem>, titles: Map<Int, String> = emptyMap()): List<DiscGroup> {
    if (tracks.isEmpty()) return emptyList()

    val tagged = tracks.any { (it.discNumber ?: 0) > 0 }
    if (tagged) {
        // A disc number of 0 or null on *some* tracks of an otherwise tagged album is a
        // gap in the tags, not a disc zero — those tracks belong to the first disc.
        return tracks
            .groupBy { (it.discNumber ?: 0).takeIf { n -> n > 0 } ?: 1 }
            .toSortedMap()
            .map { (number, discTracks) ->
                DiscGroup(number, titles[number], inTrackOrder(discTracks))
            }
    }

    val derived = deriveFromRestarts(tracks)
    if (derived != null) {
        return derived.mapIndexed { i, discTracks ->
            DiscGroup(i + 1, titles[i + 1], discTracks, derived = true)
        }
    }

    return listOf(DiscGroup(1, titles[1], inTrackOrder(tracks)))
}

/**
 * Track order within one disc.
 *
 * Sorted only when every track carries a number. A server that returns its own
 * considered order and no track numbers — a playlist-shaped response, a library with
 * untagged files — is left exactly as it came: an arbitrary re-sort of an album is a
 * regression, and there is nothing here to sort it *by*.
 */
private fun inTrackOrder(tracks: List<MaItem>): List<MaItem> =
    if (tracks.all { (it.trackNumber ?: 0) > 0 }) tracks.sortedBy { it.trackNumber } else tracks

/**
 * Discs inferred from track numbers that start over.
 *
 * Null unless the evidence is unambiguous, because the cost of being wrong is a "Disc 2"
 * header on a record that has one disc. The bar:
 *
 *  - every track carries a number,
 *  - the numbers rise within each run and restart at most a handful of times,
 *  - every run begins at 1 — a genuine second disc starts at track 1, whereas a
 *    compilation whose numbering merely dips does not,
 *  - and no run is a single track, which is what a stray mis-tag looks like.
 */
private fun deriveFromRestarts(tracks: List<MaItem>): List<List<MaItem>>? {
    if (tracks.any { (it.trackNumber ?: 0) <= 0 }) return null

    val runs = mutableListOf<MutableList<MaItem>>()
    var previous = 0
    for (track in tracks) {
        val number = track.trackNumber ?: return null
        if (runs.isEmpty() || number <= previous) runs.add(mutableListOf())
        runs.last().add(track)
        previous = number
    }

    if (runs.size < 2 || runs.size > MAX_DERIVED_DISCS) return null
    if (runs.any { it.size < 2 }) return null
    if (runs.any { it.first().trackNumber != 1 }) return null
    return runs
}

/**
 * Beyond this, a "restart" is not a disc.
 *
 * A list where the numbering resets ten times is a shelf of singles filed under one
 * name, or tags that mean something else entirely — either way, not a box set this
 * screen should invent headers for.
 */
private const val MAX_DERIVED_DISCS = 8
