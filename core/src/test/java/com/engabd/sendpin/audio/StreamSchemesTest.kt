package com.engabd.sendpin.audio

import kotlinx.coroutines.runBlocking
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Streaming services whose stream URLs are *signed per request and expire* (Qobuz,
 * later Tidal) cannot hand the player the real URL at queue-build time the way every
 * self-hosted server does — the queue can sit for hours and the URL dies in minutes.
 *
 * The mechanism under test here is the escape hatch: [StreamSchemes]. A queue item
 * carries a cheap, stable scheme uri (`qobuz://track/123`), and the player resolves
 * it to the real, freshly-signed https url at the moment it opens the stream — see
 * [LocalPlayer]'s media source factory. The registry is the whole contract: sources
 * register a handler for their scheme, the resolver rewrites only what it knows, and
 * a known scheme with no handler is an honest load error rather than a garbage url.
 */
class StreamSchemesTest {

    @Test
    fun `a registered handler resolves its scheme`() = runBlocking {
        StreamSchemes.register("fake") { id -> "https://cdn.example.com/$id?sig=abc" }
        try {
            assertEquals(
                "https://cdn.example.com/123?sig=abc",
                StreamSchemes.resolve("fake", "123"),
            )
        } finally {
            StreamSchemes.unregister("fake")
        }
    }

    @Test
    fun `a re-registered handler replaces the old one`() = runBlocking {
        // Sources are rebuilt on every connect and backend switch, so the last one
        // created must be the one the player asks — a stale handler would resolve
        // with a client whose session has since been replaced.
        StreamSchemes.register("fake") { "first" }
        StreamSchemes.register("fake") { "second" }
        try {
            assertEquals("second", StreamSchemes.resolve("fake", "123"))
        } finally {
            StreamSchemes.unregister("fake")
        }
    }

    @Test
    fun `knows mirrors the registry`() {
        assertFalse(StreamSchemes.knows("fake2"))
        StreamSchemes.register("fake2") { "x" }
        try {
            assertTrue(StreamSchemes.knows("fake2"))
        } finally {
            StreamSchemes.unregister("fake2")
        }
        assertFalse(StreamSchemes.knows("fake2"))
    }

    @Test
    fun `a scheme with no handler is an honest failure`() {
        assertFailsWith<IOException> {
            runBlocking { StreamSchemes.resolve("nosuchscheme", "123") }
        }
    }

    @Test
    fun `unregistering removes the handler`() {
        StreamSchemes.register("fake") { "x" }
        StreamSchemes.unregister("fake")
        assertFailsWith<IOException> {
            runBlocking { StreamSchemes.resolve("fake", "123") }
        }
    }

    @Test
    fun `a handler that cannot resolve surfaces its error`() {
        // The player's load error path shows the resolver's message, so a refused
        // url ("track not streamable in your region") must not be flattened into a
        // generic failure.
        StreamSchemes.register("fake") { throw IllegalStateException("not streamable") }
        try {
            val e = assertFailsWith<IllegalStateException> {
                runBlocking { StreamSchemes.resolve("fake", "123") }
            }
            assertEquals("not streamable", e.message)
        } finally {
            StreamSchemes.unregister("fake")
        }
    }
}
