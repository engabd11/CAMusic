package com.engabd.sendpin.party

/**
 * Where a guest's next song goes among the songs guests have already queued.
 *
 * Round-robin by guest: every guest's first pending song plays before anyone's
 * second, and so on, so one guest adding twenty songs cannot make the others wait
 * for all of them. Within a round, guests keep the order they first appeared in.
 */
object FairQueue {

    /**
     * [pendingGuests] is the guest id of each pending song, in play order. Returns the
     * index at which a new song from [guestId] belongs in that list (0 = straight after
     * what is playing).
     *
     * A song's round is how many songs the same guest has ahead of it. The new song
     * joins the round equal to the guest's current pending count, after every song in
     * that round or any earlier one.
     */
    fun insertIndex(pendingGuests: List<String>, guestId: String): Int {
        val round = pendingGuests.count { it == guestId }
        val seen = HashMap<String, Int>()
        var at = 0
        for ((i, g) in pendingGuests.withIndex()) {
            val r = (seen[g] ?: 0)
            seen[g] = r + 1
            if (r <= round) at = i + 1
        }
        return at
    }

    /**
     * Drop the entries that have played. [pending] is what the party remembers guests
     * adding, in order; [upcomingKeys] is the real queue after the current song. Played
     * songs leave from the front, so what remains must line up with the start of the
     * upcoming queue. If the host has reshuffled things so that it no longer does, the
     * party stops tracking them (the songs still play) rather than guess.
     */
    fun <T> reconcile(pending: List<T>, keyOf: (T) -> String, upcomingKeys: List<String>): List<T> {
        for (drop in 0..pending.size) {
            val rest = pending.drop(drop)
            if (rest.size <= upcomingKeys.size && rest.indices.all { keyOf(rest[it]) == upcomingKeys[it] }) return rest
        }
        return emptyList()
    }
}
