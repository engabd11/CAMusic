package com.engabd.sendpin.service

import com.engabd.sendpin.SendpinApp
import com.engabd.sendpin.audio.LocalTrack
import com.engabd.sendpin.library.Capability
import com.engabd.sendpin.ma.MaItem
import com.engabd.sendpin.util.runCatchingCancellable

/**
 * The local player's current track as something that can be favourited, for the
 * heart in the system's media controls.
 *
 * The same rule Now Playing's heart uses: the track's library id and provider are on
 * the [LocalTrack], and the active library stars it — no heart for a library whose
 * heart would do nothing. Process-scoped because the notification outlives every
 * screen.
 */
object LocalFavourite {

    /** The track as a starrable item, or null when nothing can star it. */
    fun itemFor(track: LocalTrack?): MaItem? {
        track ?: return null
        val id = track.scrobbleId ?: return null
        val provider = track.scrobbleProvider ?: return null
        val source = SendpinApp.instance.musicSource.value?.takeIf { it.providerId == provider } ?: return null
        if (!source.has(Capability.STAR)) return null
        return MaItem(
            itemId = id, provider = provider, name = track.title, uri = null, mediaType = "track",
            subtitle = track.artist, image = track.artUrl,
            duration = (track.durationMs / 1000L).toInt().takeIf { it > 0 },
        )
    }

    /** Whether the library has it starred; null when it would not say. One request. */
    suspend fun isFavourite(item: MaItem): Boolean? {
        val source = SendpinApp.instance.musicSource.value?.takeIf { it.providerId == item.provider } ?: return null
        return runCatchingCancellable { source.song(item.itemId)?.favorite }.getOrNull()
    }

    /** Star or unstar it. False when the library refused or is not connected. */
    suspend fun set(item: MaItem, starred: Boolean): Boolean {
        val source = SendpinApp.instance.musicSource.value?.takeIf { it.providerId == item.provider } ?: return false
        return runCatchingCancellable { source.setStarred(item, starred) }.isSuccess
    }
}
