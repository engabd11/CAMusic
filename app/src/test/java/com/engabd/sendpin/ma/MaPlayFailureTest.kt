package com.engabd.sendpin.ma

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * Which of Music Assistant's two "media not found" failures a play error was.
 *
 * From 2.10 the server sends a localised message, and `player_queues/play_media`
 * uses the same `MediaNotFoundError` for a uri it couldn't resolve and for a track it
 * resolved and then couldn't get audio for. Only the second actually reaches a client
 * that way — the resolve loop swallows a bad uri and ends with "there is nothing to
 * play here" — so an item that still resolves means the library is fine and the
 * server log has the real reason.
 */
class MaPlayFailureTest {

    private fun err(code: Int, transport: Boolean = false) =
        MaApiException("The requested media item could not be found.", code, isTransport = transport)

    @Test
    fun `item still resolves - blame the stream, not the library`() {
        val out = MaRepository.playFailure(err(MaRepository.ERR_MEDIA_NOT_FOUND), itemFound = true)
        assertEquals(MaRepository.UNPLAYABLE_MESSAGE, out.message)
        assertEquals(MaRepository.ERR_MEDIA_NOT_FOUND, (out as MaApiException).code)
    }

    @Test
    fun `item really is gone - the server's own message stands`() {
        val e = err(MaRepository.ERR_MEDIA_NOT_FOUND)
        assertSame(e, MaRepository.playFailure(e, itemFound = false))
    }

    /** A probe that couldn't be made looks the same as a missing item, and must not overrule. */
    @Test
    fun `a dropped socket is not evidence of anything`() {
        val e = err(MaRepository.ERR_MEDIA_NOT_FOUND, transport = true)
        assertSame(e, MaRepository.playFailure(e, itemFound = true))
    }

    /** Every other rejection — no permission, bad argument — says what it means already. */
    @Test
    fun `other errors are passed through untouched`() {
        val e = err(code = 5)
        assertSame(e, MaRepository.playFailure(e, itemFound = true))
    }
}
