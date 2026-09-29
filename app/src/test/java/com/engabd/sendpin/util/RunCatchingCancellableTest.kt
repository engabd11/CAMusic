package com.engabd.sendpin.util

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RunCatchingCancellableTest {

    @Test
    fun `an ordinary failure is caught, like runCatching`() {
        val r = runCatchingCancellable { error("boom") }
        assertTrue(r.isFailure)
        assertEquals("boom", r.exceptionOrNull()?.message)
        assertEquals(3, runCatchingCancellable { 3 }.getOrNull())
    }

    @Test
    fun `cancellation is not caught`() {
        assertFailsWith<CancellationException> { runCatchingCancellable { throw CancellationException("stop") } }
    }

    @Test
    fun `a cancelled coroutine stops instead of falling back`() = runBlocking {
        var fellBack = false
        val job = launch {
            runCatchingCancellable { delay(10_000) }.getOrElse { fellBack = true }
            fellBack = true
        }
        delay(50)
        job.cancel()
        job.join()
        assertFalse(fellBack)
    }
}
