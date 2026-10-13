package com.engabd.sendpin.audio

/**
 * One track the local player can play. [localPath] wins over [streamUrl] whenever
 * it is present, so a downloaded track plays from storage even with the server up
 * — and still plays with the server gone.
 */
data class LocalTrack(
    val id: String,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long = 0,
    /** Cover art: a remote URL, or a `file://` path for a downloaded cover. */
    val artUrl: String? = null,
    /**
     * The track's first genre tag, or null when the library gives none.
     *
     * Carried here rather than looked up when wanted because this is the only
     * description of the playing track that reaches the light-show side of the
     * app: `MaItem` has the tags, `LocalTrack` is what crosses over. Used by the
     * genre-to-preset rules in `SendpinApp`; nothing else reads it, and a null
     * simply means no rule can match.
     */
    val genre: String? = null,
    val streamUrl: String? = null,
    val localPath: String? = null,
    /**
     * The Navidrome song id to report plays against, when this track came from
     * there. Separate from [id] because "play at original quality" plays a Music
     * Assistant item through a Navidrome stream — same song, two different ids, and
     * scrobbling MA's would name a track Navidrome has never heard of.
     */
    val scrobbleId: String? = null,
    /**
     * Which library [scrobbleId] belongs to, as a `MusicSource.providerId`.
     *
     * An id alone is not enough to report a play against: a Jellyfin guid sent to
     * Navidrome names nothing, and there is now more than one library it could have
     * come from. Null when the track has no library behind it at all.
     */
    val scrobbleProvider: String? = null,
    /**
     * The file's own format, when the library reported one — the "Source" half of the
     * quality badge. Null on a server that doesn't say (plain Subsonic sends no
     * `samplingRate`), in which case the badge shows Playing alone.
     */
    val sourceQuality: StreamQuality? = null,
    /** Composer credit, for classical / jazz tracks. */
    val composer: String? = null,
) {
    /**
     * There is a downloaded copy to play from. Deliberately *not* a `File.exists()`
     * check: this is read on every state emission and every notification rebuild,
     * and hitting the disk there put I/O on the main thread several times a second.
     * The one place the file has to really be there is [LocalPlayer.sourceOf], which
     * checks once and falls back to the stream.
     */
    val offline: Boolean get() = localPath != null
}
