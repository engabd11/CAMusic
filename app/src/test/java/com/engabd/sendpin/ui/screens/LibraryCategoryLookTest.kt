package com.engabd.sendpin.ui.screens

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The library-look arithmetic.
 *
 * Worth pinning because this project has no Compose tests and no `@Preview`s, so the
 * only thing standing between a bad number and a broken library page is this file.
 * Five styles times three sizes times four corners is sixty combinations, and the two
 * that would actually break — a span outside the grid, which crashes a `LazyGrid`,
 * and a height under the touch target — are invisible in a screenshot of the other
 * fifty-eight.
 */
class LibraryCategoryLookTest {

    @Test
    fun `the default look is the row exactly as it shipped`() {
        // The whole promise of this feature: an install that never opens the Library
        // look page is unchanged. 96dp tall, half the grid (two per row), 18dp
        // corners, 14sp label — CategoryCard's own literals before it was made
        // configurable.
        val m = categoryMetrics(CategoryStyle.CARDS, CategorySize.REGULAR, CategoryShape.SOFT)
        assertEquals(96, m.heightDp)
        assertEquals(3, m.span)
        assertEquals(18, m.cornerDp)
        assertEquals(14, m.labelSp)
        assertTrue(!m.wrapping)
    }

    @Test
    fun `a span always fits the grid it is drawn in`() {
        // The one failure that is a crash rather than a cosmetic problem: a
        // GridItemSpan wider than the grid throws. Checked across every style, size
        // and the three column counts the app actually uses (phone 6, medium 8,
        // expanded 12 — see App.kt).
        for (style in CategoryStyle.entries) {
            for (size in CategorySize.entries) {
                for (cols in listOf(6, 8, 12)) {
                    val m = categoryMetrics(style, size, CategoryShape.SOFT, cols)
                    assertTrue(
                        m.span in 1..cols,
                        "span ${m.span} out of 1..$cols for $style/$size",
                    )
                }
            }
        }
    }

    @Test
    fun `a degenerate column count cannot produce a zero span`() {
        // Not expected from the app, but a zero or negative span is a crash and the
        // clamp is cheap.
        assertTrue(categoryMetrics(CategoryStyle.GRID, CategorySize.COMPACT, CategoryShape.SOFT, 0).span >= 1)
        assertTrue(categoryMetrics(CategoryStyle.GRID, CategorySize.COMPACT, CategoryShape.SOFT, 1).span >= 1)
    }

    @Test
    fun `nothing can be shrunk below the touch target`() {
        // Compact scales by 0.78, so a 38dp chip would come out at 29dp — under the
        // 48dp guidance and genuinely hard to hit, on a phone and worse on the car
        // pane, which reuses this screen whole.
        for (style in CategoryStyle.entries) {
            val m = categoryMetrics(style, CategorySize.COMPACT, CategoryShape.SOFT)
            assertTrue(m.heightDp >= MIN_HEIGHT_DP, "$style is only ${m.heightDp}dp tall")
        }
    }

    @Test
    fun `a pill never over-rounds`() {
        // Past half the height the two ends meet and the straight middle reads as a
        // mistake. The enum stores 999 precisely so it has to be clamped.
        for (size in CategorySize.entries) {
            val m = categoryMetrics(CategoryStyle.CARDS, size, CategoryShape.PILL)
            assertEquals(m.heightDp / 2, m.cornerDp)
        }
    }

    @Test
    fun `a corner never exceeds half the height`() {
        // Soft is 18dp and the shortest entry is 44dp, so this holds today — but the
        // relationship is what matters, not today's numbers.
        for (style in CategoryStyle.entries) {
            for (shape in CategoryShape.entries) {
                val m = categoryMetrics(style, CategorySize.COMPACT, shape)
                assertTrue(
                    m.cornerDp <= m.heightDp / 2,
                    "$style/$shape rounds to ${m.cornerDp} of ${m.heightDp}",
                )
            }
        }
    }

    @Test
    fun `size changes the height and only chips wrap`() {
        val compact = categoryMetrics(CategoryStyle.CARDS, CategorySize.COMPACT, CategoryShape.SOFT)
        val regular = categoryMetrics(CategoryStyle.CARDS, CategorySize.REGULAR, CategoryShape.SOFT)
        val large = categoryMetrics(CategoryStyle.CARDS, CategorySize.LARGE, CategoryShape.SOFT)
        assertTrue(compact.heightDp < regular.heightDp)
        assertTrue(regular.heightDp < large.heightDp)
        // The screen branches on this to choose a FlowRow over grid cells, so it is
        // load-bearing rather than descriptive.
        assertTrue(categoryMetrics(CategoryStyle.CHIPS, CategorySize.REGULAR, CategoryShape.SOFT).wrapping)
        for (style in CategoryStyle.entries - CategoryStyle.CHIPS) {
            assertTrue(!categoryMetrics(style, CategorySize.REGULAR, CategoryShape.SOFT).wrapping)
        }
    }

    @Test
    fun `an icon grid fits more per row than cards do`() {
        // The reason to pick it. Cards are half the grid, the icon grid a third.
        val cards = categoryMetrics(CategoryStyle.CARDS, CategorySize.REGULAR, CategoryShape.SOFT)
        val grid = categoryMetrics(CategoryStyle.GRID, CategorySize.REGULAR, CategoryShape.SOFT)
        assertTrue(grid.span < cards.span)
    }

    // ── categoryOrder ────────────────────────────────────────────────────

    @Test
    fun `no stored order means the library's own order`() {
        val available = listOf("artists", "albums", "tracks")
        assertEquals(available, categoryOrder(available, emptyList(), emptySet()))
    }

    @Test
    fun `a stored order is a preference applied to what exists`() {
        val available = listOf("artists", "albums", "tracks")
        assertEquals(
            listOf("tracks", "artists", "albums"),
            categoryOrder(available, listOf("tracks", "artists", "albums"), emptySet()),
        )
    }

    @Test
    fun `an order naming categories this library lacks is not a problem`() {
        // Reorder on Navidrome, switch to Music Assistant: "genres" is in the stored
        // order and nowhere in the library. It must be ignored, not rendered.
        val available = listOf("artists", "albums")
        assertEquals(
            listOf("albums", "artists"),
            categoryOrder(available, listOf("genres", "albums", "starred", "artists"), emptySet()),
        )
    }

    @Test
    fun `a category the stored order has never seen still appears`() {
        // The other direction, and the one that would silently hide a *new* feature:
        // a category added in a later version of the app is not in anyone's saved
        // order, and must land on the end rather than vanish.
        val available = listOf("artists", "albums", "brandnew")
        assertEquals(
            listOf("albums", "artists", "brandnew"),
            categoryOrder(available, listOf("albums", "artists"), emptySet()),
        )
    }

    @Test
    fun `hidden categories are dropped`() {
        val available = listOf("artists", "albums", "tracks")
        assertEquals(
            listOf("artists", "tracks"),
            categoryOrder(available, emptyList(), setOf("albums")),
        )
    }

    @Test
    fun `hiding everything is refused rather than obeyed`() {
        // An empty selection and no selection at all are stored identically, so an
        // all-hidden state would read back as "show everything" on the next launch —
        // and in the meantime the library page would have no way into it. The same
        // trap CarBrowseOptions documents.
        val available = listOf("artists", "albums")
        assertEquals(available, categoryOrder(available, emptyList(), setOf("artists", "albums")))
    }

    @Test
    fun `an empty library yields nothing rather than throwing`() {
        assertEquals(emptyList(), categoryOrder(emptyList(), listOf("artists"), setOf("albums")))
    }
}
