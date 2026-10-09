package com.engabd.sendpin.car

import com.engabd.sendpin.ma.MaItem

/**
 * What loading one car folder came to.
 *
 * The browse tree used to fold every failure into an empty list, so a home server
 * out of Wi-Fi range - the ordinary case on a phone just driven away from the house
 * - drew "Nothing here yet / Recently added is empty", which reads as a library with
 * nothing in it rather than one that could not be reached.
 */
sealed interface CarLoad {
    data class Items(val items: List<MaItem>) : CarLoad
    data object Unreachable : CarLoad
    data object TimedOut : CarLoad

    companion object {
        /**
         * The line the car shows for a folder with nothing to list, or null when it
         * has items: (title, subtitle).
         */
        fun message(load: CarLoad, library: String?, folder: String?): Pair<String, String?>? = when (load) {
            is Items -> if (load.items.isEmpty()) "Nothing here yet" to folder?.let { "$it is empty" } else null
            Unreachable -> "Couldn't reach ${library ?: "your library"}" to "Check the connection, then go back and try again"
            TimedOut -> "${library ?: "Your library"} took too long" to "Go back and try again in a moment"
        }
    }
}
