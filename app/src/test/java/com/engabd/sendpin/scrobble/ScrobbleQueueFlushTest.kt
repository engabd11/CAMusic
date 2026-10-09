package com.engabd.sendpin.scrobble

import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

/** A flush must not lose listens queued while it was sending. */
class ScrobbleQueueFlushTest {

    private fun play(n: Int) = Play(title = "Track $n", artist = "Artist", album = "Album", durationMs = 200_000, startedAtMs = 1_700_000_000_000L + n)

    @Test
    fun `a listen queued during a flush survives it`() {
        val q = ScrobbleQueue(File(Files.createTempDirectory("scrobble").toFile(), "q.json"))
        val a = PendingScrobble("listenbrainz", play(1))
        val b = PendingScrobble("listenbrainz", play(2))
        q.add(a)
        q.add(b)
        val snapshot = q.load()
        // The flush is out on the network; meanwhile a track ends and is queued.
        val late = PendingScrobble("listenbrainz", play(3))
        q.add(late)
        // The flush delivered a, failed b once.
        q.finishFlush(snapshot, keep = listOf(b.copy(attempts = 1)))
        assertEquals(listOf(b.copy(attempts = 1), late), q.load())
    }

    @Test
    fun `a flush with nothing new behaves like a replace`() {
        val q = ScrobbleQueue(File(Files.createTempDirectory("scrobble").toFile(), "q.json"))
        val a = PendingScrobble("lastfm", play(1))
        q.add(a)
        q.finishFlush(q.load(), keep = emptyList())
        assertEquals(emptyList(), q.load())
    }
}
