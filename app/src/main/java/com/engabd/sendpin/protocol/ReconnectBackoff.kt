package com.engabd.sendpin.protocol

/**
 * How long to wait before the next reconnect attempt, for the Music Assistant API
 * socket and the Sendspin player socket alike.
 *
 * Both used to double from half a second to fifteen and stay there, forever: a phone
 * that left home with the player switched on dialled two dead LAN addresses every
 * fifteen seconds all day, each dial a ten-second connect timeout, with the CPU held
 * awake to do it. Fifteen seconds is right while the server is restarting; it is not
 * right an hour later.
 *
 * So: the same quick ladder for the first [FAST_ATTEMPTS] (about a minute and a half
 * in all, which covers a server restart or a Wi-Fi blip), then once a minute for a
 * while, then every five minutes. A network appearing — arriving home — resets the
 * count and dials at once (see StreamNetwork.networkAvailable), so the slow tail costs
 * nothing in the case that matters.
 */
object ReconnectBackoff {

    const val FAST_ATTEMPTS = 10
    const val SLOW_ATTEMPTS = 20

    fun delayMs(attempt: Int): Long = when {
        attempt < FAST_ATTEMPTS -> (500L * (1L shl attempt.coerceIn(0, 5))).coerceAtMost(15_000L)
        attempt < SLOW_ATTEMPTS -> 60_000L
        else -> 5 * 60_000L
    }
}
