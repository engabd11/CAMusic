package com.engabd.sendpin.ma

/**
 * Build the `media` list for "play this album/playlist/shelf, starting at the track
 * the user tapped".
 *
 * ## Why this is done here and not by the server
 *
 * Music Assistant's `play_media` takes a `start_item` parameter, and its own docs
 * describe it as "Optional item to start the playlist or album from". That reads like
 * it would work on any `media` list, and between PR #97 and this change the app relied
 * on exactly that: it sent every track of the album as `media` and named the tapped one
 * as `start_item`.
 *
 * It never worked, and it could not have. MA resolves `media` through
 * `MediaResolver._resolve_media_items`, which dispatches on the item's `media_type` and
 * only threads `start_item` into the PLAYLIST, ALBUM, GENRE and AUDIOBOOK branches — the
 * *container* types, where there is a track listing to slice. A TRACK falls through to
 * the bottom of that function and is returned as a one-item list; `start_item` is never
 * consulted. So a flat list of track URIs plus `start_item` enqueues the tracks in list
 * order and starts at index 0, whatever was tapped. That is the reported bug: "browsing
 * to an album and playing any song will always play the first song."
 *
 * Slicing here instead is one command, needs no particular server version, and cannot
 * develop an index/queue mismatch because the list *is* the queue. It also reproduces
 * MA's own documented semantics for the container case: `test_start_item_rotation.py`
 * states that playing a playlist or album from a chosen track "normally drops everything
 * before that track".
 *
 * [MaRepository.playMedia]'s `startItem` parameter is still correct and still used — for
 * plays that really do send a container URI, such as the album screen's "Play all".
 *
 * ## Deduplication
 *
 * `music/albums/album_tracks` concatenates the listing per provider mapping, so an album
 * available from two providers comes back with every track twice — the same reason
 * [ArtistDetailViewModel] has to `distinctBy` its album shelf. Nothing deduplicated the
 * *track* list, so the queue silently held each track twice and "next" replayed the song
 * that had just finished. Deduplicating by URI fixes that, and has to happen before the
 * index is taken or the slice would start in the wrong copy.
 *
 * @param tracks the list as shown on screen, in screen order
 * @param tapped the track the user tapped, or null to start from the beginning
 * @return the URIs to send as `media`, tapped-track-first; empty if nothing is playable
 */
fun queueFrom(tracks: List<MaItem>, tapped: MaItem?): List<String> {
    val uris = tracks.mapNotNull { it.uri }.distinct()
    if (uris.isEmpty()) return emptyList()
    // indexOf, not indexOfFirst-by-id: the URI is what the server will act on, and it is
    // the same key the list was deduplicated by, so the two cannot disagree. A tapped
    // track that somehow is not in the list starts the queue at the top rather than
    // playing nothing.
    val from = uris.indexOf(tapped?.uri).coerceAtLeast(0)
    return uris.subList(from, uris.size)
}
