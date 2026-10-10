package com.engabd.sendpin.widget

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

/** Which layout the home-screen widget draws at each size it is drawn for. */
class WidgetLayoutTest {

    @Test
    fun `one row high puts the song beside the buttons, cover or not`() {
        assertEquals(WidgetLayout.ROW, WidgetLayout.of(WidgetLayout.COMPACT, cover = false))
        assertEquals(WidgetLayout.ROW, WidgetLayout.of(WidgetLayout.COMPACT, cover = true))
        assertEquals(WidgetLayout.ROW, WidgetLayout.of(DpSize(400.dp, 60.dp), cover = true))
    }

    @Test
    fun `the cover shows only when it is on and the widget is wide enough`() {
        assertEquals(WidgetLayout.STACKED, WidgetLayout.of(WidgetLayout.REGULAR, cover = true))
        assertEquals(WidgetLayout.STACKED, WidgetLayout.of(WidgetLayout.WIDE, cover = false))
        assertEquals(WidgetLayout.STACKED_WITH_COVER, WidgetLayout.of(WidgetLayout.WIDE, cover = true))
    }

    @Test
    fun `every size drawn is a different layout when the cover is on`() {
        assertEquals(
            WidgetLayout.entries.toSet(),
            WidgetLayout.SIZES.map { WidgetLayout.of(it, cover = true) }.toSet(),
        )
    }
}
