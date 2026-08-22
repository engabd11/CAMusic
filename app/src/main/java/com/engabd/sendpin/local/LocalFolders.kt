package com.engabd.sendpin.local

/**
 * A picked music folder, expressed the way MediaStore can actually answer it.
 *
 * ## Why this exists
 *
 * The folder picker is `ACTION_OPEN_DOCUMENT_TREE`, which hands back a *SAF tree*
 * uri:
 *
 * ```
 * content://com.android.externalstorage.documents/tree/primary%3AMusic%2FRock
 * ```
 *
 * The scan is MediaStore, which describes the same file as:
 *
 * ```
 * content://media/external/audio/media/1234
 * ```
 *
 * These two namespaces share nothing. The reachability test used to be
 * `trackUri.startsWith(folderUri)`, which can never be true between them — so
 * picking *any* folder emptied the library, and the only configuration that returned
 * tracks was the one where no folder had been picked at all.
 *
 * The bridge is the tree uri's document id — `"primary:Music/Rock"` — which decomposes
 * into exactly the two columns MediaStore indexes by: `VOLUME_NAME` and
 * `RELATIVE_PATH`. That is what this holds.
 *
 * Pure, and separated from [LocalMediaSource] for that reason: the string handling
 * here is wrong in ten small ways if it is wrong at all, and none of it needs an
 * Android `Context` to exercise.
 */
data class FolderScope(
    /** MediaStore's `VOLUME_NAME`, e.g. `external_primary` or an SD card's serial. */
    val volumeName: String,
    /**
     * MediaStore's `RELATIVE_PATH` prefix, with a trailing slash, or empty for the
     * whole volume. E.g. `"Music/Rock/"`.
     */
    val relativePath: String,
)

internal object LocalFolders {

    /** MediaStore's name for the built-in shared storage. */
    const val PRIMARY_VOLUME = "external_primary"

    /**
     * Turn a tree uri's document id into something MediaStore can be asked about.
     *
     * [documentId] is what `DocumentsContract.getTreeDocumentId(treeUri)` returns:
     * `"<volume>:<path>"`, where the volume is `primary` for built-in storage and the
     * FAT serial (e.g. `1AEF-1D0F`) for a removable card.
     *
     * Null for anything that does not decompose — a tree from a cloud provider, say,
     * whose documents are not in MediaStore at all and cannot be matched by path. The
     * caller treats that as "cannot restrict" rather than as "matches nothing"; see
     * [LocalMediaSource].
     */
    fun scopeOf(documentId: String?): FolderScope? {
        if (documentId.isNullOrBlank()) return null
        val colon = documentId.indexOf(':')
        if (colon <= 0) return null
        val volume = documentId.substring(0, colon)
        val path = documentId.substring(colon + 1).trim('/')
        val volumeName =
            if (volume.equals("primary", ignoreCase = true)) PRIMARY_VOLUME
            // MediaStore lower-cases a removable volume's serial; the document id
            // does not.
            else volume.lowercase()
        return FolderScope(volumeName, if (path.isEmpty()) "" else "$path/")
    }

    /**
     * Whether a scanned track falls inside [scope].
     *
     * A null [relativePath] only matches a whole-volume scope: MediaStore has told us
     * nothing about where the file is, and guessing it is inside the folder would put
     * unplayable tracks in the library.
     */
    fun matches(scope: FolderScope, volumeName: String?, relativePath: String?): Boolean {
        if (volumeName != null && !volumeName.equals(scope.volumeName, ignoreCase = true)) return false
        if (scope.relativePath.isEmpty()) return true
        val rel = relativePath?.trimStart('/') ?: return false
        return rel.startsWith(scope.relativePath, ignoreCase = true)
    }

    /** True when the track is inside any of [scopes], or when there are none. */
    fun reachable(scopes: List<FolderScope>, volumeName: String?, relativePath: String?): Boolean =
        scopes.isEmpty() || scopes.any { matches(it, volumeName, relativePath) }

    /**
     * The stored form of the picked folders: a JSON array of uri strings.
     *
     * One encoder and one decoder, in one place, because there were two: the settings
     * screen wrote a JSON array and [com.engabd.sendpin.library.MusicSources] read it
     * back with `split("|")`. `Uri.parse` does not throw, so the whole JSON string
     * became a single nonsensical uri rather than an error — the folder list was
     * never empty and never right.
     */
    fun encode(uris: List<String>): String =
        uris.joinToString(prefix = "[", postfix = "]") { "\"${it.replace("\\", "\\\\").replace("\"", "\\\"")}\"" }

    fun decode(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        val trimmed = raw.trim()
        if (!trimmed.startsWith("[")) return emptyList()
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        var inString = false
        var escaped = false
        for (ch in trimmed.substring(1)) {
            when {
                escaped -> { sb.append(ch); escaped = false }
                ch == '\\' && inString -> escaped = true
                ch == '"' -> {
                    if (inString) { out += sb.toString(); sb.setLength(0) }
                    inString = !inString
                }
                inString -> sb.append(ch)
                ch == ']' -> return out
            }
        }
        return out
    }
}
