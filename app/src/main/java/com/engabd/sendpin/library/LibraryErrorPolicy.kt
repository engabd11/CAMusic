package com.engabd.sendpin.library

/**
 * Whether a failed library read means "the library is in trouble" — worth telling
 * someone about — or only "this library does not do that".
 *
 * The home shelves are best-effort: a server that lacks a shelf hides it rather than
 * erroring. That is right for the second kind and wrong for the first, which is what
 * every shelf caught: a server that had gone away emptied the whole home screen with
 * nothing on it to say why, and looked exactly like a library with nothing in it.
 */
object LibraryErrorPolicy {

    /** HTTP answers that mean the server does not offer this, rather than that it failed. */
    private val UNSUPPORTED_HTTP = setOf(400, 404, 405, 501)

    /** Subsonic codes for "not here" and "not this version": unsupported, not broken. */
    private val UNSUPPORTED_SUBSONIC = setOf(
        com.engabd.sendpin.subsonic.SubsonicError.CLIENT_TOO_OLD,
        com.engabd.sendpin.subsonic.SubsonicError.SERVER_TOO_OLD,
        70, // data not found
    )

    fun isFailure(e: Throwable): Boolean = when (e) {
        is com.engabd.sendpin.subsonic.SubsonicException -> e.code == null || e.code !in UNSUPPORTED_SUBSONIC
        is com.engabd.sendpin.jellyfin.JellyfinException -> httpFailure(e.httpCode)
        is com.engabd.sendpin.emby.EmbyException -> httpFailure(e.httpCode)
        is com.engabd.sendpin.plex.PlexException -> httpFailure(e.httpCode)
        is com.engabd.sendpin.ma.MaApiException -> e.isTransport || e.code == 401 || e.code == 403
        // Unreachable sockets, timeouts, refused passwords, and anything else: worth saying.
        else -> true
    }

    private fun httpFailure(code: Int?): Boolean = code == null || code !in UNSUPPORTED_HTTP
}
