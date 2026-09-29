package com.engabd.sendpin.audio

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpreadShuffleTest {

    private fun keys(vararg artists: Pair<String, Int>): List<Pair<String?, String?>> =
        artists.flatMap { (a, n) -> List(n) { a to "$a album ${it % 2}" } }

    @Test
    fun `it is a permutation that starts with the playing track`() {
        val k = keys("A" to 5, "B" to 3, "C" to 2)
        for (seed in 0 until 50) {
            val o = SpreadShuffle.order(k, first = 4, random = Random(seed))
            assertEquals(4, o[0])
            assertEquals((0 until k.size).toSet(), o.toSet())
            assertEquals(k.size, o.size)
        }
    }

    @Test
    fun `an artist with a third of the queue is never played twice running`() {
        // 4 of 12 from one artist: a fair shuffle puts two together ~60% of the time.
        val k = keys("A" to 4, "B" to 2, "C" to 2, "D" to 2, "E" to 2)
        for (seed in 0 until 200) {
            val o = SpreadShuffle.order(k, first = 11, random = Random(seed)).drop(1)
            val artists = o.map { k[it].first }
            val adjacent = artists.zipWithNext().count { (x, y) -> x == "A" && y == "A" }
            assertEquals(0, adjacent, "seed $seed: $artists")
        }
    }

    @Test
    fun `one artist's albums alternate`() {
        val k = List(6) { "A" to "album ${it % 2}" }
        val o = SpreadShuffle.order(k, first = -1, random = Random(7))
        val albums = o.map { k[it].second }
        assertTrue(albums.zipWithNext().none { (x, y) -> x == y }, "$albums")
    }

    @Test
    fun `untagged tracks are not lumped together as one artist`() {
        val k = List(6) { null to null } + listOf("A" to "x")
        val o = SpreadShuffle.order(k, first = 0, random = Random(1))
        assertEquals(k.size, o.toSet().size)
    }

    @Test
    fun `the track after the playing one is by someone else`() {
        // The emulator queue: 7 by A over two albums, 5 by B, 5 by C.
        val k = List(4) { "Test Artist A" to "Sine Album" } + List(3) { "Test Artist A" to "Second Album" } +
            List(5) { "Test Artist B" to "Other Album" } + List(5) { "Test Artist C" to "Noise Album" }
        for (seed in 0 until 300) {
            for (first in k.indices) {
                val o = SpreadShuffle.order(k, first, Random(seed))
                val artists = o.map { k[it].first }
                assertTrue(artists.zipWithNext().none { (x, y) -> x == y }, "seed $seed first $first: $artists")
            }
        }
    }
}
