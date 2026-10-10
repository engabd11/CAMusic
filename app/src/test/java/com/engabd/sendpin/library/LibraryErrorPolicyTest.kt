package com.engabd.sendpin.library

import com.engabd.sendpin.jellyfin.JellyfinException
import com.engabd.sendpin.ma.MaApiException
import com.engabd.sendpin.subsonic.SubsonicException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A library in trouble is said so; a library without a feature is not. */
class LibraryErrorPolicyTest {

    @Test
    fun `an unreachable or slow server is a failure`() {
        assertTrue(LibraryErrorPolicy.isFailure(UnknownHostException("nav.local")))
        assertTrue(LibraryErrorPolicy.isFailure(SocketTimeoutException("timeout")))
        assertTrue(LibraryErrorPolicy.isFailure(SubsonicException("no route")))
        assertTrue(LibraryErrorPolicy.isFailure(MaApiException("Not connected", -1, isTransport = true)))
    }

    @Test
    fun `refused credentials and server errors are failures`() {
        assertTrue(LibraryErrorPolicy.isFailure(JellyfinException("unauthorised", 401)))
        assertTrue(LibraryErrorPolicy.isFailure(JellyfinException("server error", 503)))
        assertTrue(LibraryErrorPolicy.isFailure(SubsonicException("wrong password", 40)))
    }

    @Test
    fun `a feature the server lacks is not`() {
        assertFalse(LibraryErrorPolicy.isFailure(JellyfinException("not found", 404)))
        assertFalse(LibraryErrorPolicy.isFailure(SubsonicException("not found", 70)))
        assertFalse(LibraryErrorPolicy.isFailure(SubsonicException("too old", 30)))
        assertFalse(LibraryErrorPolicy.isFailure(MaApiException("unknown command", 0)))
    }
}
