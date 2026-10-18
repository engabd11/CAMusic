package com.engabd.sendpin.party

import com.engabd.sendpin.ma.MaItem

/**
 * What a party needs from the library layer, which lives in `LibraryViewModel` (the
 * connections to Music Assistant and the other servers are owned there).
 *
 * The view model registers itself in [PartyLibraries] while it exists and withdraws
 * when it is cleared. A party that outlives the screen (the foreground service keeps
 * it going) therefore keeps working for as long as the app's main screen does; once
 * Android has torn that down, searches say so politely until the app is opened again.
 */
interface PartyLibrary {
    /** Tracks matching [query] across every library, best first. */
    suspend fun searchTracks(query: String, limit: Int): List<MaItem>

    /**
     * Queue [item] [position] songs after the one playing. Returns the queue key the
     * song will have in [upNext], or null when it could not be queued.
     */
    suspend fun enqueue(item: MaItem, position: Int): String?

    /** The songs after the current one, whichever player owns the session. */
    suspend fun upNext(limit: Int): List<PartyUpNext>

    /** Skip to the next song on whichever player owns the session. */
    fun skip()
}

object PartyLibraries {
    @Volatile var current: PartyLibrary? = null
}
