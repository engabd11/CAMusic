package com.engabd.sendpin.audio

import androidx.media3.common.PlaybackException
import com.engabd.sendpin.audio.PlaybackErrorPolicy.Action
import kotlin.test.Test
import kotlin.test.assertEquals

class PlaybackErrorPolicyTest {

    @Test
    fun `a network dropout waits instead of skipping`() {
        assertEquals(Action.WAIT_FOR_NETWORK, PlaybackErrorPolicy.classify(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
        assertEquals(Action.WAIT_FOR_NETWORK, PlaybackErrorPolicy.classify(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT))
        assertEquals(Action.WAIT_FOR_NETWORK, PlaybackErrorPolicy.classify(PlaybackException.ERROR_CODE_IO_UNSPECIFIED))
    }

    @Test
    fun `a revoked token stops the queue rather than failing every track`() {
        assertEquals(Action.STOP, PlaybackErrorPolicy.classify(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 401))
        assertEquals(Action.STOP, PlaybackErrorPolicy.classify(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 403))
    }

    @Test
    fun `a struggling server is waited for`() {
        for (code in listOf(429, 500, 502, 503, 504)) {
            assertEquals(Action.WAIT_FOR_NETWORK, PlaybackErrorPolicy.classify(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, code))
        }
    }

    @Test
    fun `a missing or undecodable file is skipped`() {
        assertEquals(Action.SKIP, PlaybackErrorPolicy.classify(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 404))
        assertEquals(Action.SKIP, PlaybackErrorPolicy.classify(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND))
        assertEquals(Action.SKIP, PlaybackErrorPolicy.classify(PlaybackException.ERROR_CODE_DECODING_FAILED))
        assertEquals(Action.SKIP, PlaybackErrorPolicy.classify(PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED))
    }
}
