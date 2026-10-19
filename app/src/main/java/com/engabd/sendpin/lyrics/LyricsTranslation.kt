package com.engabd.sendpin.lyrics

/** What the lyrics pane shows for translation. */
sealed interface LyricsTranslation {
    /** Nothing to show: switched off, or the song is already in the target language. */
    data object Off : LyricsTranslation

    /**
     * On its way: [message] says which step (fetching the language the first time, or
     * which line), and [partial] holds the lines finished so far, same order as the
     * lyrics, so they can show while the rest are translated.
     */
    data class Working(val message: String, val partial: List<String?>? = null) : LyricsTranslation

    /** One entry per lyric line, same order as `MaLyrics.lines`. */
    data class Ready(val lines: List<String?>) : LyricsTranslation

    /** Why there is no translation, in words for the listener. */
    data class Note(val message: String) : LyricsTranslation
}
