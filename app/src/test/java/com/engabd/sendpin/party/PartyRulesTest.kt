package com.engabd.sendpin.party

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PartyRulesTest {

    // --- token ---------------------------------------------------------------------

    @Test fun tokenIs128BitsOfHexAndDiffersEachTime() {
        val a = PartyAuth.newToken()
        assertEquals(32, a.length)
        assertTrue(a.all { it in "0123456789abcdef" })
        assertTrue(a != PartyAuth.newToken())
    }

    @Test fun tokenCheckAcceptsOnlyTheExactToken() {
        val t = PartyAuth.newToken()
        assertTrue(PartyAuth.matches(t, t))
        assertFalse(PartyAuth.matches(t, t.dropLast(1)))
        assertFalse(PartyAuth.matches(t, t + "0"))
        assertFalse(PartyAuth.matches(t, t.uppercase()))
        assertFalse(PartyAuth.matches(t, null))
        assertFalse(PartyAuth.matches(t, ""))
        assertFalse(PartyAuth.matches("", ""))
    }

    // --- rate limiting -------------------------------------------------------------

    @Test fun rateLimiterAllowsLimitThenRefusesThenRecovers() {
        var now = 0L
        val limiter = RateLimiter(3, 60_000, { now })
        repeat(3) { assertTrue(limiter.tryAcquire("a")) }
        assertFalse(limiter.tryAcquire("a"))
        assertTrue(limiter.tryAcquire("b"), "keys are independent")
        now += 59_999
        assertFalse(limiter.tryAcquire("a"))
        now += 1
        assertTrue(limiter.tryAcquire("a"))
    }

    // --- paths ---------------------------------------------------------------------

    private val token = "0123456789abcdef0123456789abcdef"

    @Test fun routesTheDocumentedPaths() {
        assertEquals(PartyRoute.Asset("index.html"), PartyPaths.route("/p/$token/", token))
        assertEquals(PartyRoute.Asset("app.js"), PartyPaths.route("/p/$token/app.js", token))
        assertEquals(PartyRoute.Api("state"), PartyPaths.route("/p/$token/api/state", token))
        assertEquals(PartyRoute.Api("search"), PartyPaths.route("/p/$token/api/search?q=abba", token))
        assertEquals(PartyRoute.Api("art", "abcdef0123456789"), PartyPaths.route("/p/$token/api/art/abcdef0123456789", token))
    }

    @Test fun refusesWrongTokenTraversalAndListings() {
        val bad = listOf(
            "/", "/p", "/p/", "/p/$token", "/p/wrong/", "/p/${token.dropLast(1)}/",
            "/p/$token/../secret", "/p/$token/..%2fsecret", "/p/$token/%2e%2e/x", "/p/$token/a\\b",
            "/p/$token/app.js/", "/p/$token/other.txt", "/p/$token/api/", "/p/$token/api/nope",
            "/p/$token/api/art/", "/p/$token/api/art/short", "/p/$token/api/art/abcdef0123456789/extra",
            "/p/$token//", "/p/$token/index.html/..", "/p/$token/AndroidManifest.xml",
            "/p/$token/" + "a".repeat(600),
        )
        bad.forEach { assertEquals(PartyRoute.NotFound, PartyPaths.route(it, token), it) }
    }

    @Test fun namesAreSanitisedAndLimited() {
        assertEquals("Guest", PartyPaths.cleanName(null))
        assertEquals("Guest", PartyPaths.cleanName("   "))
        assertEquals("Guest", PartyPaths.cleanName("<>\u0000"))
        assertEquals("scriptalert1script", PartyPaths.cleanName("<script>alert(1)</script>").replace(" ", "").replace("/", ""))
        assertEquals("Sam Jones", PartyPaths.cleanName("  Sam \n  Jones "))
        assertEquals(PartyPaths.MAX_NAME, PartyPaths.cleanName("x".repeat(100)).length)
        assertFalse(PartyPaths.cleanName("a<b>\"c'd").any { it in "<>\"" })
    }

    @Test fun queriesAreTrimmedAndLimited() {
        assertEquals("abba", PartyPaths.cleanQuery("  abba\u0007 "))
        assertEquals(PartyPaths.MAX_QUERY, PartyPaths.cleanQuery("q".repeat(500)).length)
    }

    // --- fair round-robin ----------------------------------------------------------

    @Test fun emptyQueueTakesTheFirstSong() {
        assertEquals(0, FairQueue.insertIndex(emptyList(), "a"))
    }

    @Test fun aSecondGuestJumpsAheadOfTheFirstGuestsExtras() {
        // a has added three; b's first song should play right after a's first.
        assertEquals(1, FairQueue.insertIndex(listOf("a", "a", "a"), "b"))
    }

    @Test fun roundsInterleaveGuestsInOrderOfArrival() {
        val queue = mutableListOf<String>()
        for (g in listOf("a", "a", "a", "b", "c", "b", "a")) queue.add(FairQueue.insertIndex(queue, g), g)
        assertEquals(listOf("a", "b", "c", "a", "b", "a", "a"), queue)
    }

    @Test fun oneGuestFloodingCannotPushOthersBack() {
        val queue = mutableListOf<String>()
        repeat(20) { queue.add(FairQueue.insertIndex(queue, "flood"), "flood") }
        queue.add(FairQueue.insertIndex(queue, "quiet"), "quiet")
        assertTrue(queue.indexOf("quiet") <= 1)
    }

    @Test fun reconcileDropsPlayedSongsFromTheFront() {
        val pending = listOf("k1", "k2", "k3")
        assertEquals(listOf("k2", "k3"), FairQueue.reconcile(pending, { it }, listOf("k2", "k3", "other")))
        assertEquals(pending, FairQueue.reconcile(pending, { it }, listOf("k1", "k2", "k3")))
        assertEquals(emptyList(), FairQueue.reconcile(pending, { it }, emptyList()))
        // The host reordered the queue: stop guessing.
        assertEquals(emptyList(), FairQueue.reconcile(pending, { it }, listOf("k2", "k1", "k3")))
    }

    // --- skip votes ----------------------------------------------------------------

    @Test fun skipThresholds() {
        assertNull(SkipMode.OFF.required(10))
        assertEquals(2, SkipMode.TWO.required(10))
        assertEquals(3, SkipMode.THREE.required(10))
        assertEquals(5, SkipMode.FIVE.required(10))
        assertEquals(1, SkipMode.TWO.required(1), "never more than the guests present")
        assertEquals(2, SkipMode.FIVE.required(2))
        assertEquals(1, SkipMode.HALF.required(1))
        assertEquals(2, SkipMode.HALF.required(3))
        assertEquals(3, SkipMode.HALF.required(6))
        assertEquals(4, SkipMode.HALF.required(7))
        assertEquals(1, SkipMode.HALF.required(0))
    }
}
