package com.engabd.sendpin.service

/**
 * Which extra buttons the system's media controls carry for this phone's player.
 *
 * Android 13 and later draw the shade, lock-screen and watch controls from the media
 * session, with room for two buttons beside play, previous and next. They were
 * empty: the only way to favourite the song, or turn shuffle or repeat on, was to
 * open the app. Off by default — it changes what the shade looks like — and the
 * listener picks which two.
 */
enum class SessionButton { FAVOURITE, SHUFFLE, REPEAT }

object SessionButtons {
    const val OFF = "off"
    const val FAV_SHUFFLE = "fav_shuffle"
    const val FAV_REPEAT = "fav_repeat"
    const val SHUFFLE_REPEAT = "shuffle_repeat"

    val CHOICES = listOf(OFF, FAV_SHUFFLE, FAV_REPEAT, SHUFFLE_REPEAT)

    /** The buttons a setting asks for, in the order they are drawn. */
    fun chosen(setting: String): List<SessionButton> = when (setting) {
        FAV_SHUFFLE -> listOf(SessionButton.FAVOURITE, SessionButton.SHUFFLE)
        FAV_REPEAT -> listOf(SessionButton.FAVOURITE, SessionButton.REPEAT)
        SHUFFLE_REPEAT -> listOf(SessionButton.SHUFFLE, SessionButton.REPEAT)
        else -> emptyList()
    }

    /**
     * What is actually drawn: the chosen buttons, less a heart for a track that has
     * nothing to favourite it on (a bare stream, a library without stars). A heart
     * that does nothing is worse than no heart.
     */
    fun shown(setting: String, canFavourite: Boolean): List<SessionButton> =
        chosen(setting).filter { it != SessionButton.FAVOURITE || canFavourite }
}
