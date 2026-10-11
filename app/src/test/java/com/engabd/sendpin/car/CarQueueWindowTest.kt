package com.engabd.sendpin.car

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The slice of the queue the car shows, and a tapped row back to a queue index. */
class CarQueueWindowTest {

    @Test
    fun `a short queue is shown whole`() {
        val w = CarQueueWindow.of(queueSize = 5, current = 2)!!
        assertEquals(0, w.start)
        assertEquals(5, w.endExclusive)
        assertEquals(2, w.currentRow)
        assertFalse(w.trailingNext)
        assertEquals(5, w.rows)
        assertEquals(4, w.queueIndexOf(4))
    }

    @Test
    fun `a long queue is cut around the playing track`() {
        val w = CarQueueWindow.of(queueSize = 2_000, current = 1_000, max = 50)!!
        assertEquals(950, w.start)
        assertEquals(1_051, w.endExclusive)
        assertEquals(50, w.currentRow)
        assertEquals(1_000, w.queueIndexOf(w.currentRow))
        assertEquals(1_010, w.queueIndexOf(60))
    }

    @Test
    fun `the last track gets a stand-in next row that maps to nothing`() {
        val w = CarQueueWindow.of(queueSize = 3, current = 2)!!
        assertTrue(w.trailingNext)
        assertEquals(4, w.rows)
        assertEquals(2, w.queueIndexOf(2))
        assertNull(w.queueIndexOf(3))
    }

    @Test
    fun `nothing playing is no window`() {
        assertNull(CarQueueWindow.of(queueSize = 0, current = 0))
        assertNull(CarQueueWindow.of(queueSize = 3, current = -1))
        assertNull(CarQueueWindow.of(queueSize = 3, current = 3))
    }
}
