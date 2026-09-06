package com.engabd.sendpin.car

import android.os.Bundle

/**
 * Android Auto's content style hints, and the one rule about them worth writing down.
 *
 * **The hints on a browsable node describe that node's children, not the node.**
 * `CONTENT_STYLE_BROWSABLE` says how the folders inside it should look and
 * `CONTENT_STYLE_PLAYABLE` how the tracks inside it should look; the hint that
 * describes a row itself is `CONTENT_STYLE_SINGLE_ITEM`, and it exists for the case
 * where one row genuinely differs from its siblings. Set on the browse root — via
 * both the root item's extras and the `LibraryParams` media3 turns into the legacy
 * `BrowserRoot` extras — the same two keys are the app-wide default for everything
 * below that overrides nothing.
 *
 * Getting that backwards is silent: the car draws *something*, just not the thing
 * that was meant. This app had an album row telling Auto to lay the album's tracks
 * out as a grid of covers, because the album itself was a grid item.
 *
 * The wire values are spelled out here rather than read from
 * `androidx.media3.session.MediaConstants`. They belong to the platform's media
 * browser protocol — `MediaBrowserCompat`'s own `android.media.browse.*` extras,
 * which Android Auto reads and every media3 release simply mirrors — and pinning
 * them makes this file the one place to check a hint against Google's documentation,
 * rather than three call sites and a library upgrade. media3's constants carry the
 * identical strings and ints; if that ever stops being true, it is these that are
 * correct, because these are what the car reads.
 */
internal object CarContentStyle {

    /** How the browsable children of this node are drawn. */
    const val KEY_BROWSABLE = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"

    /** How the playable children of this node are drawn. */
    const val KEY_PLAYABLE = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"

    /** How *this* row is drawn, overriding whatever its parent said. */
    const val KEY_SINGLE_ITEM = "android.media.browse.CONTENT_STYLE_SINGLE_ITEM_HINT"

    /** The heading this row sits under. Rows sharing one are drawn as a section. */
    const val KEY_GROUP_TITLE = "android.media.browse.CONTENT_STYLE_GROUP_TITLE_HINT"

    private const val LIST_ITEM = 1
    private const val GRID_ITEM = 2
    private const val CATEGORY_LIST_ITEM = 3
    private const val CATEGORY_GRID_ITEM = 4

    fun value(style: CarRowStyle): Int = when (style) {
        CarRowStyle.LIST -> LIST_ITEM
        CarRowStyle.GRID -> GRID_ITEM
        CarRowStyle.CATEGORY_LIST -> CATEGORY_LIST_ITEM
        CarRowStyle.CATEGORY_GRID -> CATEGORY_GRID_ITEM
    }

    /**
     * The extras for one node.
     *
     * @param browsableChildren how the folders inside this node should be drawn.
     * @param playableChildren how the tracks inside it should be drawn.
     * @param self this row's own shape, for a row whose siblings differ from it.
     * @param group the heading this row belongs under, or null for no grouping.
     */
    fun extras(
        browsableChildren: CarRowStyle? = null,
        playableChildren: CarRowStyle? = null,
        self: CarRowStyle? = null,
        group: String? = null,
    ): Bundle = Bundle().apply {
        browsableChildren?.let { putInt(KEY_BROWSABLE, value(it)) }
        playableChildren?.let { putInt(KEY_PLAYABLE, value(it)) }
        self?.let { putInt(KEY_SINGLE_ITEM, value(it)) }
        group?.let { putString(KEY_GROUP_TITLE, it) }
    }
}
