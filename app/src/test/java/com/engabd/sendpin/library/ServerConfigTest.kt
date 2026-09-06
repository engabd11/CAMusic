package com.engabd.sendpin.library

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The server list is persisted as JSON in DataStore, which makes its serialised shape
 * a compatibility surface: a field added carelessly, or a rename, silently drops
 * every configured server on the next launch and drops the user back at the connect
 * form with no explanation.
 */
class ServerConfigTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private fun roundTrip(list: List<ServerConfig>): List<ServerConfig> =
        json.decodeFromString(
            ListSerializer(ServerConfig.serializer()),
            json.encodeToString(ListSerializer(ServerConfig.serializer()), list),
        )

    @Test
    fun `a configured server survives a round trip intact`() {
        val original = ServerConfig(
            id = "abc",
            kind = ServerKind.JELLYFIN,
            label = "Attic box",
            url = "http://192.168.0.10:8096",
            username = "abdullah",
            password = "hunter2",
            token = "tok",
            options = mapOf(ServerConfig.OPT_USER_ID to "u1"),
        )
        assertEquals(listOf(original), roundTrip(listOf(original)))
    }

    /**
     * A store written by an older build has to keep loading. Every field but [kind]
     * carries a default for exactly this reason, so a config missing everything else
     * still decodes rather than taking the whole list down with it.
     */
    @Test
    fun `a config written before the later fields existed still decodes`() {
        val old = """[{"id":"abc","kind":"NAVIDROME","url":"http://nas.local:4533"}]"""
        val list = json.decodeFromString(ListSerializer(ServerConfig.serializer()), old)
        assertEquals(1, list.size)
        assertEquals(ServerKind.NAVIDROME, list[0].kind)
        assertEquals("", list[0].token)
        assertEquals(emptyMap(), list[0].options)
    }

    // ─── Display ─────────────────────────────────────────────────────────────

    @Test
    fun `an unnamed server is called after its kind rather than left blank`() {
        assertEquals("Jellyfin", ServerConfig(kind = ServerKind.JELLYFIN).displayName)
        assertEquals("Attic box", ServerConfig(kind = ServerKind.JELLYFIN, label = "Attic box").displayName)
    }

    @Test
    fun `the host line drops the scheme and the trailing slash`() {
        assertEquals(
            "192.168.0.10:4533",
            ServerConfig(kind = ServerKind.NAVIDROME, url = "http://192.168.0.10:4533/").host,
        )
        assertEquals(
            "music.example.com",
            ServerConfig(kind = ServerKind.NAVIDROME, url = "https://music.example.com").host,
        )
    }

    @Test
    fun `a server with no address says so rather than showing an empty line`() {
        assertEquals("Not set up", ServerConfig(kind = ServerKind.NAVIDROME).host)
    }

    @Test
    fun `a blank option reads as absent`() {
        val c = ServerConfig(kind = ServerKind.NAVIDROME, options = mapOf("a" to "", "b" to "x"))
        assertNull(c.option("a"))
        assertEquals("x", c.option("b"))
        assertNull(c.option("missing"))
    }

    // ─── The MA / local-library split ────────────────────────────────────────

    /**
     * The one distinction the whole app hangs off: Music Assistant owns a
     * server-side queue and plays to speakers this phone never decodes for;
     * everything else is played here. It decides which tabs are alive, which Light
     * Sync transport is used, and whether a source can be built at all.
     */
    @Test
    fun `Music Assistant is the only kind this phone does not play itself`() {
        assertFalse(ServerKind.MUSIC_ASSISTANT.playsLocally)
        ServerKind.entries.filter { it != ServerKind.MUSIC_ASSISTANT }.forEach {
            assertTrue(it.playsLocally, "${it.name} should play locally")
        }
    }

    @Test
    fun `the picker splits what exists from what is planned`() {
        assertEquals(
            listOf(
                ServerKind.MUSIC_ASSISTANT,
                ServerKind.NAVIDROME,
                ServerKind.SUBSONIC,
                ServerKind.JELLYFIN,
                ServerKind.EMBY,
                ServerKind.PLEX,
                ServerKind.MPD,
                ServerKind.LOCAL,
                ServerKind.DOWNLOADS,
            ),
            ServerKind.available,
        )
        assertTrue(ServerKind.planned.isNotEmpty())
        assertTrue(ServerKind.planned.none { it.supported })
    }

    @Test
    fun `downloads is a library but not one you can add`() {
        // It exists on every install and is created by AppSettings, not by the user,
        // so offering it in the "add a library" picker would be an action that either
        // does nothing or produces a duplicate.
        assertTrue(ServerKind.DOWNLOADS in ServerKind.available)
        assertTrue(ServerKind.DOWNLOADS !in ServerKind.addable)
        assertTrue(ServerKind.addable.all { it.supported })
    }

    @Test
    fun `device libraries need neither credentials nor an address`() {
        assertTrue(ServerKind.NAVIDROME.needsAddress)
        assertTrue(ServerKind.MUSIC_ASSISTANT.needsAddress)
        // The two that read what is already on the phone. This is what stops the
        // library screen demanding a server URL for a library that has no server.
        assertFalse(ServerKind.LOCAL.needsAddress)
        assertFalse(ServerKind.DOWNLOADS.needsAddress)
    }

    // ─── Direct streaming kinds (Spotify / Qobuz / Tidal) ────────────────────

    /**
     * The streaming services are *accounts*, not servers: there is no host to
     * point at, but there are credentials to ask for. `needsAddress` is what stops
     * the connect forms demanding a URL a cloud library can never give, and it
     * must not be the same question as "this kind has a form" — Spotify's form
     * exists and holds a username and password and not one field more.
     */
    @Test
    fun `streaming accounts need credentials but no address`() {
        for (kind in listOf(ServerKind.SPOTIFY, ServerKind.QOBUZ, ServerKind.TIDAL)) {
            assertTrue(kind.needsCredentials, "${kind.name} has a form to fill in")
            assertFalse(kind.needsAddress, "${kind.name} has no host to type")
            assertTrue(kind.playsLocally, "${kind.name} is played by this phone, not by a server")
            assertFalse(kind.supported, "${kind.name} stays roadmap greyed until its source lands")
        }
    }

    /**
     * `ServerKind.from` feeds the source wiring, not just the picker: a stored
     * config's kind name has to resolve for a streaming source to be built from
     * it in the phase that makes it playable.
     */
    @Test
    fun `the streaming kinds resolve by name`() {
        assertEquals(ServerKind.SPOTIFY, ServerKind.from("SPOTIFY"))
        assertEquals(ServerKind.QOBUZ, ServerKind.from("QOBUZ"))
        assertEquals(ServerKind.TIDAL, ServerKind.from("TIDAL"))
    }

    @Test
    fun `a streaming account config survives a round trip`() {
        val original = ServerConfig(
            kind = ServerKind.QOBUZ,
            username = "abdullah@example.com",
            password = "hunter2",
        )
        assertEquals(listOf(original), roundTrip(listOf(original)))
    }

    /**
     * Experimental is a visible status, not a third word for unsupported: these
     * kinds render in their own picker section with the tag on them, so nobody
     * reads a polished brand row as "works today". Everything experimental is
     * also planned (no source behind it yet); the flag only changes how the
     * roadmap row is presented and what the docs claim.
     */
    @Test
    fun `the streaming kinds are flagged experimental and stay unplanned-plus-unaddable`() {
        assertEquals(
            listOf(ServerKind.SPOTIFY, ServerKind.QOBUZ, ServerKind.TIDAL),
            ServerKind.entries.filter { it.experimental },
        )
        ServerKind.entries.filter { it.experimental }.forEach {
            assertTrue(it in ServerKind.planned, "${it.name} has no source yet, so it is still planned")
            assertTrue(it !in ServerKind.addable, "${it.name} cannot be added yet")
            assertFalse(it.supported, "${it.name} is not supported yet")
        }
        // The "not yet supported" section is for genuinely unstarted ideas; the
        // experimental ones are shown separately, so the planned list the picker
        // renders there must not contain them twice.
        assertEquals(
            ServerKind.planned.filterNot { it.experimental },
            ServerKind.plannedNotExperimental,
        )
    }

    @Test
    fun `an unknown kind name resolves to null rather than throwing`() {
        assertEquals(ServerKind.NAVIDROME, ServerKind.from("NAVIDROME"))
        assertNull(ServerKind.from("NOSUCHKIND"))
        assertNull(ServerKind.from(null))
    }
}

/**
 * Which provider tags mean "this phone plays it".
 *
 * This test exists because the check it covers used to be `provider ==
 * SubsonicClient.PROVIDER`, written in a dozen places when Navidrome was the only
 * answer. Every one of those sites would have answered "no, that's a Music Assistant
 * item" for a Jellyfin track — popping detail screens as they opened, refusing
 * downloads, and routing playback to a server that had never heard of the id.
 */
class LocalProviderTest {

    @Test
    fun `every source provider counts as local, and so does the download index`() {
        assertTrue(MusicSources.isLocalProvider("subsonic"))
        assertTrue(MusicSources.isLocalProvider("jellyfin"))
        assertTrue(MusicSources.isLocalProvider("emby"))
        assertTrue(MusicSources.isLocalProvider("plex"))
        assertTrue(MusicSources.isLocalProvider(MusicSources.DOWNLOAD_PROVIDER))
    }

    @Test
    fun `Music Assistant's own tags do not`() {
        // MA files everything under "library"; the provider domains are what it
        // streamed *from*, and neither is something this phone plays on its own.
        assertFalse(MusicSources.isLocalProvider("library"))
        assertFalse(MusicSources.isLocalProvider("spotify"))
        assertFalse(MusicSources.isLocalProvider(""))
    }
}
