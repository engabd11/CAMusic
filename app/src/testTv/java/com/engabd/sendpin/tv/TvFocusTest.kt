package com.engabd.sendpin.tv

import androidx.compose.ui.input.key.Key
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** When a rail that took the D-pad focus gives it back to the content. */
class TvFocusTest {

    @Test
    fun `pressing Left into the rail keeps the focus there`() {
        assertFalse(TvFocus.returnToContent(Key.DirectionLeft, tabChanged = false))
    }

    @Test
    fun `the first focus at launch stays on the rail`() {
        assertFalse(TvFocus.returnToContent(Key.Unknown, tabChanged = false))
    }

    @Test
    fun `OK or Back that removed the focused control sends it back into the content`() {
        assertTrue(TvFocus.returnToContent(Key.DirectionCenter, tabChanged = false))
        assertTrue(TvFocus.returnToContent(Key.Enter, tabChanged = false))
        assertTrue(TvFocus.returnToContent(Key.Back, tabChanged = false))
    }

    @Test
    fun `a key that changed the tab leaves the focus on the rail`() {
        assertFalse(TvFocus.returnToContent(Key.Back, tabChanged = true))
    }
}
