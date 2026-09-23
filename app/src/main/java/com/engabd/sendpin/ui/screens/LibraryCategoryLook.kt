package com.engabd.sendpin.ui.screens

/**
 * What the library's category buttons look like — the pure half.
 *
 * The row of Artists / Albums / Tracks / Playlists / Genres / Starred / Recently
 * added / Shuffle all was one fixed shape: a 96dp gradient tile, two to a row, 18dp
 * corners, hard-coded in `CategoryCard` with no setting anywhere near it. It is the
 * first thing on the library page and the most-looked-at furniture in the app, and
 * people want different things from it — some want it smaller so the album shelves
 * start higher, some want it calmer, some want it to carry real artwork.
 *
 * Everything here is data and arithmetic, deliberately. There are no Compose tests
 * and no `@Preview`s anywhere in this project, so the part that can be pinned is the
 * part that decides *sizes* — and a look that computes a zero height or a span wider
 * than the grid is a blank screen or a crash, neither of which a screenshot review
 * would reliably catch across five styles times three sizes.
 *
 * The renderers live in `LibraryCategoryLooks.kt`.
 */

/** The shape of a category entry. [CARDS] is what shipped and stays the default. */
enum class CategoryStyle(val key: String, val label: String, val blurb: String) {
    /** The original: a tall gradient tile with a ghosted glyph. Two per row. */
    CARDS("cards", "Cards", "Big gradient tiles, two per row"),

    /** A wrapping run of small pills. All eight fit in about three rows. */
    CHIPS("chips", "Chips", "Small pills — fits them all in a few rows"),

    /** Full-width rows, icon then name. Calm, and the best of these at large fonts. */
    LIST("list", "List", "Full-width rows, text first"),

    /** Small squares with a big centred glyph and the label under it. */
    GRID("grid", "Icon grid", "Compact squares, three or four per row"),

    /** Tiles filled with a collage of real covers from that category. */
    MOSAIC("mosaic", "Artwork", "Tiles filled with covers from your library");

    companion object {
        fun byKey(key: String?): CategoryStyle = entries.firstOrNull { it.key == key } ?: CARDS
    }
}

/** How much room a category entry takes. */
enum class CategorySize(val key: String, val label: String, val scale: Float) {
    COMPACT("compact", "Compact", 0.78f),
    REGULAR("regular", "Regular", 1.0f),
    LARGE("large", "Large", 1.26f);

    companion object {
        fun byKey(key: String?): CategorySize = entries.firstOrNull { it.key == key } ?: REGULAR
    }
}

/** How round the corners are. [SOFT] is the 18dp the cards shipped with. */
enum class CategoryShape(val key: String, val label: String, val corner: Int) {
    SQUARE("square", "Square", 4),
    ROUNDED("rounded", "Rounded", 12),
    SOFT("soft", "Soft", 18),
    /** Fully round — clamped to half the height at use, so it never over-rounds. */
    PILL("pill", "Pill", 999);

    companion object {
        fun byKey(key: String?): CategoryShape = entries.firstOrNull { it.key == key } ?: SOFT
    }
}

/**
 * The numbers one look needs, resolved from the three choices.
 *
 * @param heightDp the entry's height. Zero would be an invisible row, so it is
 *   floored — see [categoryMetrics].
 * @param span how many of the grid's [GRID_COLUMNS] base columns one entry takes.
 *   Must divide into the grid or the last row wraps oddly, and must never exceed it.
 * @param cornerDp corner radius, already clamped so a "pill" cannot exceed half the
 *   height and turn a wide tile into a lozenge with straight sides.
 * @param glyphDp the icon inside the entry.
 * @param labelSp the label's text size.
 * @param wrapping true for the looks that are laid out as a wrapping run of
 *   intrinsically-sized items rather than as fixed grid cells — only [CategoryStyle.CHIPS].
 */
data class CategoryMetrics(
    val heightDp: Int,
    val span: Int,
    val cornerDp: Int,
    val glyphDp: Int,
    val labelSp: Int,
    val wrapping: Boolean,
)

/** The base column count the library grid is built on. See `LibraryScreen`. */
const val GRID_COLUMNS = 6

/**
 * Resolve a look to its numbers.
 *
 * [columns] is the grid's own column count, which is 6 on a phone and 8 or 12 on
 * larger windows — so a span computed against a hard-coded 6 would be a third of a
 * tablet screen. Spans are expressed as a fraction of the grid and then clamped into
 * `1..columns`, which is the invariant that keeps a look from crashing a `LazyGrid`.
 *
 * Sizes scale by [CategorySize.scale] and are floored at [MIN_HEIGHT_DP]: at 0.78 of
 * a 40dp row an entry would be 31dp, which is under the 48dp touch target and hard
 * to hit in a moving car — the car pane reuses this screen whole.
 */
fun categoryMetrics(
    style: CategoryStyle,
    size: CategorySize,
    shape: CategoryShape,
    columns: Int = GRID_COLUMNS,
): CategoryMetrics {
    val cols = columns.coerceAtLeast(1)
    val s = size.scale

    val baseHeight: Int
    val fraction: Float
    val baseGlyph: Int
    val baseLabel: Int
    when (style) {
        CategoryStyle.CARDS -> {
            baseHeight = 96; fraction = 0.5f; baseGlyph = 16; baseLabel = 14
        }
        CategoryStyle.CHIPS -> {
            // Height is the pill's own; the span is unused because chips wrap.
            baseHeight = 38; fraction = 1f; baseGlyph = 15; baseLabel = 13
        }
        CategoryStyle.LIST -> {
            baseHeight = 52; fraction = 1f; baseGlyph = 19; baseLabel = 14
        }
        CategoryStyle.GRID -> {
            // A third of the grid on a phone, so three per row rather than the
            // cards' two — which is the whole point of choosing it.
            baseHeight = 84; fraction = 1f / 3f; baseGlyph = 26; baseLabel = 12
        }
        CategoryStyle.MOSAIC -> {
            baseHeight = 104; fraction = 0.5f; baseGlyph = 16; baseLabel = 14
        }
    }

    val height = (baseHeight * s).toInt().coerceAtLeast(MIN_HEIGHT_DP)
    val span = when (style) {
        // A wrapping run is not laid out in grid cells; it takes the whole width and
        // arranges itself inside. Reported as the full grid so a caller that spans
        // blindly still gets a valid cell.
        CategoryStyle.CHIPS -> cols
        else -> Math.round(cols * fraction).coerceIn(1, cols)
    }

    // A pill is half the height, never more — past that the two ends meet and the
    // straight middle looks like a mistake rather than a choice.
    val corner = if (shape == CategoryShape.PILL) height / 2 else shape.corner.coerceAtMost(height / 2)

    return CategoryMetrics(
        heightDp = height,
        span = span,
        cornerDp = corner,
        glyphDp = (baseGlyph * s).toInt().coerceAtLeast(12),
        labelSp = (baseLabel * s).toInt().coerceIn(10, 20),
        wrapping = style == CategoryStyle.CHIPS,
    )
}

/**
 * The smallest an entry may get.
 *
 * Android's own guidance is a 48dp touch target; this is a touch under because the
 * grid's 12dp gaps sit around every entry and count towards the reachable area. The
 * floor exists at all because Compact scales everything by 0.78 and a short row would
 * otherwise fall to about 31dp — a control that is hard to hit on a phone and worse
 * on the car pane, which reuses this screen whole.
 */
const val MIN_HEIGHT_DP = 44

/**
 * The categories to draw, in the user's order, with the hidden ones removed.
 *
 * Both halves matter and both are easy to get wrong. [order] is a stored list of ids
 * that may name categories this library does not have (the user reordered on
 * Navidrome, then switched to Music Assistant, which has no Genres) and may be
 * missing ones it does have (a category added in a later version of the app). So it
 * is a *preference* applied to [available], never a replacement for it: known ids
 * first in the stored order, then anything new on the end, then the hidden ones
 * dropped.
 *
 * An empty or absent [order] means "the default order", not "hide everything" —
 * which is also why hiding the last visible category is refused: an empty selection
 * and no selection at all would be stored identically. The same trap
 * `CarBrowseOptions` documents.
 */
fun categoryOrder(available: List<String>, order: List<String>, hidden: Set<String>): List<String> {
    if (available.isEmpty()) return emptyList()
    val known = available.toSet()
    val preferred = order.filter { it in known }
    val rest = available.filterNot { it in preferred.toSet() }
    val ordered = preferred + rest
    val visible = ordered.filterNot { it in hidden }
    // Never everything-hidden: a library page with no way into it is not a look.
    return visible.ifEmpty { ordered }
}
