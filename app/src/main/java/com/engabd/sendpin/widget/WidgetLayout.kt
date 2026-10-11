package com.engabd.sendpin.widget

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp

/**
 * Which layout the home-screen widget draws at a size.
 *
 * Glance draws one per [SIZES] entry ahead of time and the launcher shows the largest
 * that fits, so this is asked about those sizes, not the widget's exact one.
 */
enum class WidgetLayout {
    /** One row high: the song beside play and next. */
    ROW,
    /** The song over previous, play and next. */
    STACKED,
    /** The cover beside the stacked layout. */
    STACKED_WITH_COVER;

    companion object {
        val COMPACT = DpSize(160.dp, 48.dp)
        val REGULAR = DpSize(160.dp, 100.dp)
        /** Wide enough for a 72 dp cover beside the song and the three buttons. */
        val WIDE = DpSize(270.dp, 100.dp)
        val SIZES = setOf(COMPACT, REGULAR, WIDE)

        fun of(size: DpSize, cover: Boolean): WidgetLayout = when {
            size.height < REGULAR.height -> ROW
            cover && size.width >= WIDE.width -> STACKED_WITH_COVER
            else -> STACKED
        }
    }
}
