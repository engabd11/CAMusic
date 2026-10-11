package com.engabd.sendpin.ui.viewmodel

/** Which player Now Playing shows: this phone's own queue, or a Music Assistant player. */
internal enum class NowPlayingView {
    LOCAL, MA;

    companion object {
        /**
         * The local player while it has a session. Otherwise Music Assistant — unless the
         * active library is one this phone plays itself and Music Assistant is not
         * streaming here, in which case it is the (idle) local player, ready for that
         * library.
         *
         * [sendspinHere] outranks the library. The library and the player are separate
         * choices: a Music Assistant stream sent to this phone from Home Assistant or
         * MA's own app plays whichever library is open, and Now Playing has to show it.
         */
        fun of(localActive: Boolean, backend: String, sendspinHere: Boolean): NowPlayingView = when {
            localActive -> LOCAL
            backend != "subsonic" -> MA
            sendspinHere -> MA
            else -> LOCAL
        }
    }
}
