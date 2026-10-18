package com.engabd.sendpin.lyrics

/** What the lyrics pane shows for translation. */
sealed interface LyricsTranslation {
    /** Nothing to show: switched off, or the song is already in the target language. */
    data object Off : LyricsTranslation

    data object Working : LyricsTranslation

    /** One entry per lyric line, same order as `MaLyrics.lines`. */
    data class Ready(val lines: List<String?>) : LyricsTranslation

    /** Why there is no translation, in words for the listener. */
    data class Note(val message: String) : LyricsTranslation
}
