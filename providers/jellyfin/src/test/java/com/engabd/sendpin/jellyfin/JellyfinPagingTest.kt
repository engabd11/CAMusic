package com.engabd.sendpin.jellyfin

import kotlinx.coroutines.test.runTest
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A playlist longer than one page must come back whole.
 *
 * The regression: `playlistTracks` sent one request with the item query's default
 * `Limit=200`, so a 450-track playlist played — and downloaded — as its first 200,
 * with nothing on screen to say the rest existed.
 */
class JellyfinPagingTest {

    /** A fake server holding one playlist of [total] tracks, honouring StartIndex/Limit. */
    private fun fakeServer(total: Int, requests: MutableList<String>) = OkHttpClient.Builder()
        .addInterceptor(Interceptor { chain ->
            val url = chain.request().url
            requests += url.toString()
            val start = url.queryParameter("StartIndex")?.toInt() ?: 0
            val limit = url.queryParameter("Limit")?.toInt() ?: total
            val items = (start until minOf(start + limit, total)).joinToString(",") { i ->
                """{"Id":"t$i","Name":"Track $i","Type":"Audio","RunTimeTicks":1800000000}"""
            }
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(200).message("OK")
                .body("""{"Items":[$items],"TotalRecordCount":$total}""".toResponseBody("application/json".toMediaType()))
                .build()
        })
        .build()

    @Test
    fun `a 450 track playlist is fetched whole, a page at a time`() = runTest {
        val requests = mutableListOf<String>()
        val client = JellyfinClient("http://jf:8096", token = "tok", userId = "u1", http = fakeServer(450, requests))
        val tracks = client.playlistTracks("pl1")
        assertEquals(450, tracks.size)
        assertEquals("t449", tracks.last().itemId)
        assertEquals(3, requests.size, "200 + 200 + 50")
    }

    @Test
    fun `a short playlist is still a single request`() = runTest {
        val requests = mutableListOf<String>()
        val client = JellyfinClient("http://jf:8096", token = "tok", userId = "u1", http = fakeServer(37, requests))
        assertEquals(37, client.playlistTracks("pl1").size)
        assertEquals(1, requests.size)
    }
}
