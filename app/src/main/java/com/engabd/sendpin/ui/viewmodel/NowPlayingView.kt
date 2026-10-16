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

        /**
         * One step of "has a held Music Assistant stream on this phone given way to the
         * library the listener switched to".
         *
         * [sendspinHere] used to be "Sendspin holds a session", playing *or paused* — and
         * a paused one keeps its session for as long as its track is loaded. So: play on
         * Music Assistant, pause, switch the library to Navidrome, and the mini-player
         * went on showing the paused MA player under the Navidrome library until a
         * Navidrome song was played. Switching library is the listener saying which
         * player they want, and the switch stops Music Assistant on the way out, so
         * the stream it leaves behind yields to it.
         *
         * Edge-triggered both ways. Switching to another library yields; a stream
         * *starting* to play here afterwards (sent from Home Assistant, or resumed)
         * takes the screen back, which is the case [of] puts it first for. The first
         * library seen is not a switch ([prevBackend] null), so opening the app does
         * not hide a stream that was already held.
         */
        fun yieldStep(
            yielded: Boolean,
            prevPlaying: Boolean,
            prevBackend: String?,
            playing: Boolean,
            backend: String,
        ): Boolean = when {
            playing && !prevPlaying -> false
            backend == "subsonic" && prevBackend != null && prevBackend != "subsonic" -> true
            else -> yielded
        }
    }
}
