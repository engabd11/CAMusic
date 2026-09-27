package com.engabd.sendpin.library

/**
 * Every item a paged endpoint has, fetched a page at a time.
 *
 * Jellyfin, Emby and Plex all answer a list request with at most the `Limit` asked
 * for, and say nothing when there is more. Asking for one fixed-size page is therefore
 * a silent truncation: a 250-track playlist came back as its first 200 — and was
 * downloaded as 200 — and an artist list stopped at the 500th name with nothing on
 * screen to say so. This keeps asking until a page comes back short.
 *
 * [cap] is a guard against a server that ignores the offset and returns the same
 * full page forever, not a limit anyone should reach: at the default it is far past
 * any real music library's playlist or artist count.
 */
suspend fun <T> fetchAllPages(
    pageSize: Int,
    cap: Int = 50_000,
    fetch: suspend (offset: Int, limit: Int) -> List<T>,
): List<T> {
    require(pageSize > 0)
    val all = ArrayList<T>()
    var offset = 0
    while (all.size < cap) {
        val page = fetch(offset, pageSize)
        all.addAll(page)
        if (page.size < pageSize) break
        offset += page.size
    }
    return if (all.size > cap) all.subList(0, cap).toList() else all
}
