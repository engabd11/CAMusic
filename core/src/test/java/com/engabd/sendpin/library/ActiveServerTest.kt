package com.engabd.sendpin.library

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Which server is active, and what the legacy `library_backend` key is mirrored to.
 *
 * These two rules are small and were both wrong in a way no compiler could catch, so
 * they are pinned here rather than left to be found on a device:
 *
 *  - an upgrade must land on the library the user was already browsing, not on
 *    whichever server happens to be first in the list;
 *  - the mirrored backend key must follow the *active* server, because half the app —
 *    which tabs are alive, which Light Sync transport runs, whether playback stops —
 *    still reads it.
 *
 * The logic is duplicated here rather than reached into `AppSettings`, which needs an
 * Android `Context` and a DataStore to exist at all. It is four lines; the value is in
 * stating the rules where they can be read, and a change to one without the other
 * shows up as a failure here.
 */
class ActiveServerTest {

    private val ma = ServerConfig(id = "ma", kind = ServerKind.MUSIC_ASSISTANT, url = "http://ma:8095")
    private val nav = ServerConfig(id = "nav", kind = ServerKind.NAVIDROME, url = "http://nav:4533")
    private val jelly = ServerConfig(id = "jf", kind = ServerKind.JELLYFIN, url = "http://jf:8096")

    /** Mirrors `AppSettings.resolveActiveId`. */
    private fun resolveActiveId(stored: String?, backend: String?, list: List<ServerConfig>): String =
        stored?.takeIf { id -> list.any { it.id == id } }
            ?: list.firstOrNull { it.kind.playsLocally == (backend == "subsonic") }?.id
            ?: list.firstOrNull()?.id
            ?: ""

    /** Mirrors the backend half of `AppSettings.mirrorLegacyKeys`. */
    private fun mirroredBackend(list: List<ServerConfig>, activeId: String?): String {
        val active = list.firstOrNull { it.id == activeId } ?: list.firstOrNull()
        return if (active?.kind?.playsLocally == false) "ma" else "subsonic"
    }

    // ─── Picking the active server ───────────────────────────────────────────

    @Test
    fun `a stored id wins when it still names a server`() {
        assertEquals("nav", resolveActiveId("nav", "ma", listOf(ma, nav)))
    }

    /**
     * The bug this exists for. `active_server_id` is empty on an install that predates
     * the server list, and the list is built MA-first — so defaulting to "the first
     * one" moved every existing Navidrome user to Music Assistant on first launch.
     */
    @Test
    fun `with nothing stored, the old backend key decides`() {
        assertEquals("nav", resolveActiveId(null, "subsonic", listOf(ma, nav)))
        assertEquals("ma", resolveActiveId(null, "ma", listOf(ma, nav)))
    }

    @Test
    fun `an id naming a removed server falls through rather than stranding the tab`() {
        assertEquals("ma", resolveActiveId("deleted", "ma", listOf(ma, nav)))
    }

    @Test
    fun `an empty list has no active server`() {
        assertEquals("", resolveActiveId("nav", "subsonic", emptyList()))
    }

    // ─── Mirroring it back to the legacy key ─────────────────────────────────

    @Test
    fun `the mirrored backend follows the active server, not the list order`() {
        // The list is MA-first, so reading `list.first()` here — which is what the
        // mirror used to fall back to — reported "ma" while Navidrome was active, and
        // that flips tabs off and stops playback.
        assertEquals("subsonic", mirroredBackend(listOf(ma, nav), "nav"))
        assertEquals("ma", mirroredBackend(listOf(ma, nav), "ma"))
    }

    /**
     * Every kind but Music Assistant mirrors as "subsonic", because what the old key
     * really encoded was "this phone plays it itself" — which is exactly what the rest
     * of the app uses it to decide.
     */
    @Test
    fun `a provider with no legacy equivalent still mirrors as locally played`() {
        assertEquals("subsonic", mirroredBackend(listOf(ma, jelly), "jf"))
    }
}
