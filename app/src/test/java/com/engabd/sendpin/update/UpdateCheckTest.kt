package com.engabd.sendpin.update

import com.engabd.sendpin.update.UpdateCheck.Outcome
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** When the background check tells someone about a release, and when it keeps quiet. */
class UpdateCheckTest {

    private fun should(
        latest: String,
        current: String = "0.16.2",
        enabled: Boolean = true,
        supported: Boolean = true,
        last: String? = null,
    ) = UpdateCheck.shouldNotify(enabled, supported, latest, current, last)

    @Test fun `notifies for a newer version not yet announced`() = assertTrue(should("0.17.0"))

    @Test fun `not for the same or an older version`() {
        assertFalse(should("0.16.2"))
        assertFalse(should("0.16.1"))
        assertFalse(should("0.9.9"))
    }

    @Test fun `numeric compare, so 0_10 is after 0_9`() = assertTrue(should("0.10.0", current = "0.9.9"))

    @Test fun `not twice for the same version`() {
        assertFalse(should("0.17.0", last = "0.17.0"))
        assertFalse(should("v0.17.0", last = "0.17.0"))
    }

    @Test fun `again for a newer one`() = assertTrue(should("0.18.0", last = "0.17.0"))

    @Test fun `not when switched off`() = assertFalse(should("0.17.0", enabled = false))

    @Test fun `not for a side-by-side copy`() = assertFalse(should("0.17.0", supported = false))

    @Test fun `a build label on the running version is ignored`() {
        assertTrue(should("0.17.0", current = "0.16.2-freshtest"))
        assertFalse(should("0.16.2", current = "0.16.2-freshtest"))
    }

    @Test fun `a label on the release is ignored and an unreadable tag never notifies`() {
        assertFalse(should("0.16.2-rc1"))
        assertTrue(should("0.17.0-rc1"))
        assertFalse(should("nightly"))
    }

    // ── The run around the decision ──────────────────────────────────────

    private fun release(version: String) = ReleaseInfo(version, "Faster everything.", "page", "apk", 1L, null)

    private class Spy {
        var fetches = 0
        var offered: ReleaseInfo? = null
        var notified = mutableListOf<String>()
        var remembered: String? = null
        var errors = 0
    }

    private fun run(
        spy: Spy,
        fetch: suspend () -> ReleaseInfo?,
        enabled: Boolean = true,
        supported: Boolean = true,
        last: String? = null,
        canNotify: Boolean = true,
    ): Outcome = runBlocking {
        UpdateCheck.run(
            enabled = enabled,
            supported = supported,
            current = "0.16.2",
            lastNotified = { last },
            fetch = { spy.fetches++; fetch() },
            offer = { spy.offered = it },
            notify = { spy.notified += it.version; canNotify },
            remember = { spy.remembered = it },
            onError = { spy.errors++ },
        )
    }

    @Test fun `a new release is offered, announced and remembered`() {
        val spy = Spy()
        assertEquals(Outcome.NOTIFIED, run(spy, { release("0.17.0") }))
        assertEquals("0.17.0", spy.offered?.version)
        assertEquals(listOf("0.17.0"), spy.notified)
        assertEquals("0.17.0", spy.remembered)
    }

    @Test fun `an announced release is offered on the page but not announced again`() {
        val spy = Spy()
        assertEquals(Outcome.ALREADY_TOLD, run(spy, { release("0.17.0") }, last = "0.17.0"))
        assertEquals("0.17.0", spy.offered?.version)
        assertTrue(spy.notified.isEmpty())
    }

    @Test fun `without the notification permission nothing is remembered, so it can still be told later`() {
        val spy = Spy()
        run(spy, { release("0.17.0") }, canNotify = false)
        assertEquals(null, spy.remembered)
    }

    @Test fun `up to date does nothing`() {
        val spy = Spy()
        assertEquals(Outcome.UP_TO_DATE, run(spy, { release("0.16.2") }))
        assertEquals(null, spy.offered)
        assertTrue(spy.notified.isEmpty())
    }

    @Test fun `disabled or unsupported never reaches the network`() {
        val off = Spy()
        assertEquals(Outcome.SKIPPED, run(off, { release("0.17.0") }, enabled = false))
        assertEquals(0, off.fetches)
        val copy = Spy()
        assertEquals(Outcome.SKIPPED, run(copy, { release("0.17.0") }, supported = false))
        assertEquals(0, copy.fetches)
    }

    @Test fun `an error such as a rate limit is swallowed and not retried`() {
        val spy = Spy()
        assertEquals(Outcome.FAILED, run(spy, { throw IllegalStateException("GitHub is rate-limiting this network") }))
        assertEquals(1, spy.fetches)
        assertEquals(1, spy.errors)
        assertTrue(spy.notified.isEmpty())
    }

    @Test fun `a latest release with no apk for this flavour does nothing`() {
        val spy = Spy()
        assertEquals(Outcome.NO_RELEASE, run(spy, { null }))
    }
}
