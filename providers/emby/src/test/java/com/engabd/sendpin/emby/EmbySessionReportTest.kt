package com.engabd.sendpin.emby

import com.engabd.sendpin.jellyfin.JellyfinClient
import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Emby's session reports follow Jellyfin's since PR #195: the halfway "listened to"
 * mark must not stop the session (that ended "Now Playing" mid-song), and the stop
 * goes out when the track really ends.
 */
class EmbySessionReportTest {

    private data class Call(val method: String, val path: String, val body: String)

    private fun client(calls: MutableList<Call>) = EmbyClient(
        baseUrl = "http://emby:8096", token = "tok", userId = "u1",
        http = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val req = chain.request()
            val body = req.body?.let { b -> Buffer().also { b.writeTo(it) }.readUtf8() }.orEmpty()
            calls += Call(req.method, req.url.encodedPath, body)
            Response.Builder().request(req).protocol(Protocol.HTTP_1_1).code(204).message("No Content")
                .body("".toResponseBody("application/json".toMediaType())).build()
        }).build(),
    )

    @Test
    fun `the halfway mark does not stop the session, the real end does`() = runTest {
        val calls = mutableListOf<Call>()
        val c = client(calls)
        c.reportPlayback("t1", completed = false, positionMs = 0)
        c.markCounted("t1")
        c.reportProgress("t1", positionMs = 120_000, paused = false, repeatMode = "all", shuffle = true)
        assertFalse(calls.any { it.path.endsWith("/Sessions/Playing/Stopped") }, "halfway must not stop the session")

        c.reportStopped("t1", positionMs = 150_000, durationMs = 180_000)
        assertTrue(calls.any { it.path.endsWith("/Sessions/Playing/Stopped") })
        // Counted, but stopped before Emby's own 90 % mark: marked played explicitly.
        assertTrue(calls.any { it.method == "POST" && it.path == "/Users/u1/PlayedItems/t1" })
    }

    @Test
    fun `progress carries the session, repeat mode and play order`() = runTest {
        val calls = mutableListOf<Call>()
        val c = client(calls)
        c.reportPlayback("t1", completed = false)
        c.reportProgress("t1", positionMs = 5_000, paused = false, repeatMode = "one", shuffle = true)
        val progress = calls.last { it.path.endsWith("/Sessions/Playing/Progress") }.body
        assertTrue("\"RepeatMode\":\"RepeatOne\"" in progress, progress)
        assertTrue("\"PlaybackOrder\":\"Shuffle\"" in progress, progress)
        assertTrue("\"PlaySessionId\"" in progress, progress)
        assertTrue("\"PositionTicks\":50000000" in progress, progress)
    }

    @Test
    fun `nothing is sent for a session that never started`() = runTest {
        val calls = mutableListOf<Call>()
        val c = client(calls)
        c.reportProgress("t1", positionMs = 5_000, paused = false)
        c.reportStopped("t1", positionMs = 5_000, durationMs = 180_000)
        assertEquals(emptyList(), calls)
    }

    @Test
    fun `repeat modes map to the server's enum`() {
        assertEquals("RepeatNone", JellyfinClient.jellyfinRepeatMode("off"))
        assertEquals("RepeatAll", JellyfinClient.jellyfinRepeatMode("all"))
        assertEquals("RepeatOne", JellyfinClient.jellyfinRepeatMode("one"))
    }
}
