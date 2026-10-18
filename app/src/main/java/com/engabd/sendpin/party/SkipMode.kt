package com.engabd.sendpin.party

/** How many guests have to agree before a song is skipped. */
enum class SkipMode(val label: String) {
    OFF("Off"), TWO("2"), THREE("3"), FIVE("5"), HALF("Half the guests");

    /**
     * Votes needed with [guests] present, or null when voting is off. Never more than
     * the number of guests (so a party of two with "5" still works) and never fewer
     * than one.
     */
    fun required(guests: Int): Int? {
        val wanted = when (this) {
            OFF -> return null
            TWO -> 2
            THREE -> 3
            FIVE -> 5
            HALF -> (guests + 1) / 2
        }
        return wanted.coerceAtMost(guests).coerceAtLeast(1)
    }
}
