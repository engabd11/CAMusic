package com.engabd.sendpin.car

/**
 * What the car's screen shows, and how it is laid out — the whole of Android Auto's
 * appearance, as data.
 *
 * Deliberately free of every `android.*` import. Two reasons, and the second is the
 * one that matters: the browse tree is built inside a service that only a real car
 * (or the Desktop Head Unit) ever binds, so the *only* place this logic can be
 * checked before a drive is a JVM unit test — see `CarBrowseOptionsTest`. Turning a
 * [CarRowStyle] into the `MediaConstants` int that media3 wants, and a URL into a
 * `content://` artwork URI, both stay in [CarLibraryBridge] where a `Bundle` is
 * available and nothing needs testing.
 *
 * The defaults here are the tree exactly as it shipped: adaptive styling, every
 * shelf, every library, 50 items. An untouched install sees no change from turning
 * these into settings — see [CarBrowseOptions] for what each one loosens.
 */

/**
 * The heading a shelf sits under inside a library folder.
 *
 * Android Auto draws these as section dividers when a browsable child carries
 * `EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE`. Nine shelves in one flat column is a list
 * to read at a junction; three headings of three is a shape to recognise.
 */
enum class CarShelfGroup(val title: String) {
    FOR_YOU("For you"),
    FAVOURITES("Favourites"),
    LIBRARY("Library"),
}

/**
 * One shelf inside a library folder, and how it prefers to be drawn.
 *
 * [key] is the wire value — it goes into a `cmid://shelf` media id and into the
 * stored settings list, so it is fixed forever and keeps the American spelling the
 * first version shipped with. [title] is what the driver reads, and follows the rest
 * of the app ("Favourite albums", as the Library tab already says).
 */
enum class CarShelf(
    val key: String,
    val title: String,
    val group: CarShelfGroup,
    /** Covers worth showing large, or a list of names worth showing many of. */
    val grid: Boolean,
    /** People, not records — drawn as circles when the setting allows it. */
    val people: Boolean = false,
) {
    RECENTLY_ADDED("recentlyAdded", "Recently added", CarShelfGroup.FOR_YOU, grid = true),
    RECENTLY_PLAYED("recentlyPlayed", "Recently played", CarShelfGroup.FOR_YOU, grid = true),
    FAVOURITE_ALBUMS("favoriteAlbums", "Favourite albums", CarShelfGroup.FAVOURITES, grid = true),
    FAVOURITE_ARTISTS("favoriteArtists", "Favourite artists", CarShelfGroup.FAVOURITES, grid = true, people = true),
    FAVOURITE_PLAYLISTS("favoritePlaylists", "Favourite playlists", CarShelfGroup.FAVOURITES, grid = true),
    FAVOURITE_TRACKS("favoriteTracks", "Favourite tracks", CarShelfGroup.FAVOURITES, grid = false),
    ARTISTS("artists", "Artists", CarShelfGroup.LIBRARY, grid = false, people = true),
    ALBUMS("albums", "Albums", CarShelfGroup.LIBRARY, grid = true),
    PLAYLISTS("playlists", "Playlists", CarShelfGroup.LIBRARY, grid = true),
    ;

    companion object {
        fun byKey(key: String): CarShelf? = entries.firstOrNull { it.key == key }

        /**
         * The shelves a given library can actually fill.
         *
         * Music Assistant answers "recently played" and its four favourite lists but
         * has no flat artists/albums/playlists browse in this app; a direct server
         * (Navidrome, Jellyfin, Plex, Emby, MPD, Downloads) is the other way round and
         * only offers favourites when its adapter says so. Offering a shelf the server
         * cannot fill is how a folder ends up empty in a car, which is the one place
         * there is nothing to be done about it.
         */
        fun offeredBy(abilities: CarLibraryAbilities): List<CarShelf> = buildList {
            add(RECENTLY_ADDED)
            if (abilities.musicAssistant) {
                add(RECENTLY_PLAYED)
                add(FAVOURITE_ALBUMS)
                add(FAVOURITE_ARTISTS)
                add(FAVOURITE_PLAYLISTS)
                add(FAVOURITE_TRACKS)
            } else {
                if (abilities.favourites) {
                    add(FAVOURITE_ALBUMS)
                    add(FAVOURITE_ARTISTS)
                    add(FAVOURITE_PLAYLISTS)
                    add(FAVOURITE_TRACKS)
                }
                add(ARTISTS)
                add(ALBUMS)
                if (abilities.playlists) add(PLAYLISTS)
            }
        }
    }
}

/** What a configured library can fill a shelf from. See [CarShelf.offeredBy]. */
data class CarLibraryAbilities(
    val musicAssistant: Boolean,
    val favourites: Boolean,
    val playlists: Boolean,
)

/**
 * How the driver wants rows drawn, before the per-shelf question of what suits the
 * content.
 *
 * [ADAPTIVE] is the default and the one this app shipped with: covers get a grid,
 * names get a list. The other two exist because the right answer depends on the car
 * as much as on the content — a small 6" head unit fits four list rows and one grid
 * row, and a driver who wants the biggest possible targets should be able to say so
 * without the app arguing.
 */
enum class CarBrowseStyle(val key: String, val label: String) {
    ADAPTIVE("adaptive", "Adaptive"),
    GRID("grid", "Big covers"),
    LIST("list", "Compact list"),
    ;

    companion object {
        fun byKey(key: String?): CarBrowseStyle = entries.firstOrNull { it.key == key } ?: ADAPTIVE
    }
}

/**
 * The four shapes Android Auto can draw a browse row in.
 *
 * Maps one-to-one onto the platform's `CONTENT_STYLE_*` values — see
 * [CarContentStyle], which is also where the rule about *what* a style hint
 * describes is written down. The "category" pair is the one worth
 * knowing about: it is what makes artwork round, which is the car's own signal for
 * "this is a person or a genre, not a record".
 */
enum class CarRowStyle { LIST, GRID, CATEGORY_LIST, CATEGORY_GRID }

/**
 * Every Android Auto appearance setting, resolved.
 *
 * Read fresh on each browse request rather than cached in the service: DataStore
 * answers from memory, the browse call is already suspending, and a change made on
 * the phone while the car is plugged in should be visible in the car — see
 * [CarMediaLibraryService], which nudges the browser when one of these changes.
 */
data class CarBrowseOptions(
    val style: CarBrowseStyle = CarBrowseStyle.ADAPTIVE,
    /** Draw artists and genres as circles. Auto's own convention for people. */
    val peopleAsCircles: Boolean = true,
    /** Head the shelves with "For you" / "Favourites" / "Library". */
    val groupTitles: Boolean = true,
    /**
     * Cover art on browse rows, served through [CarArtworkProvider].
     *
     * Off means the tree the app shipped with: titles alone. On costs one image
     * fetch per visible row the first time a folder is opened, and hands Android
     * Auto a `content://` URI that carries no credential of any kind.
     */
    val artwork: Boolean = true,
    /**
     * With one library configured, put its shelves at the root instead of a folder
     * containing them.
     *
     * A tap saved on every single trip, for the setup almost everybody has. Turned
     * off by anyone who prefers the folder to stay where it is once they add a
     * second library and it comes back anyway.
     */
    val flattenSingleLibrary: Boolean = true,
    /** How many items a shelf loads. Fewer is less to scroll past at a light. */
    val shelfItemLimit: Int = DEFAULT_SHELF_ITEMS,
    /** Enabled shelf keys, in the driver's order. Empty means "all, as offered". */
    val shelfKeys: List<String> = emptyList(),
    /** Enabled library ids, in the driver's order. Empty means "all, as configured". */
    val libraryIds: List<String> = emptyList(),
    /**
     * Seconds a rewind / fast-forward press moves, or 0 for no such buttons.
     *
     * Off by default: on a three-minute song the car's own previous/next are the
     * right controls, and two more buttons on a screen glanced at from the driver's
     * seat is a cost. It earns its place on a two-hour DJ set or a podcast.
     */
    val seekSeconds: Int = 0,
) {

    /** Tracks are one line each, so twice as many fit the same scroll as covers do. */
    val trackItemLimit: Int get() = (shelfItemLimit * 2).coerceAtMost(MAX_SHELF_ITEMS)

    val seekMs: Long get() = seekSeconds * 1000L

    /**
     * The shelves to show for a library, in order.
     *
     * Falls back to everything [offered] whenever the stored selection leaves
     * nothing — a driver who picked only Music Assistant's shelves and then browsed
     * a Navidrome server would otherwise open an empty folder, which is the one
     * outcome no setting should be able to produce.
     */
    fun shelves(offered: List<CarShelf>): List<CarShelf> {
        if (shelfKeys.isEmpty()) return offered
        val chosen = shelfKeys.mapNotNull { CarShelf.byKey(it) }.filter { it in offered }
        return chosen.ifEmpty { offered }
    }

    /**
     * The libraries to show at the root, in order.
     *
     * Generic over the config type for the same reason this file imports no Android:
     * it keeps the ordering rule — the one thing here that can silently hide a
     * library — testable without a `ServerConfig` and everything it drags in.
     */
    fun <T> libraries(all: List<T>, id: (T) -> String): List<T> {
        if (libraryIds.isEmpty()) return all
        val byId = all.associateBy(id)
        val chosen = libraryIds.mapNotNull { byId[it] }
        return chosen.ifEmpty { all }
    }

    /**
     * What fits on the car's root screen, and what goes behind "More libraries".
     *
     * [limit] is the browser's own figure — Android Auto sends it as a root hint and
     * it is usually four. One slot has to be given up to the "More" folder itself the
     * moment there is an overflow, which is the `cap - 1` below: five libraries into
     * four slots is three plus More, not four plus a fifth nobody can reach.
     *
     * A driver with more libraries than slots decides which ones are one tap away by
     * ordering them; see [libraries].
     */
    fun <T> splitForRoot(all: List<T>, limit: Int): Pair<List<T>, List<T>> {
        val cap = limit.coerceAtLeast(1)
        if (all.size <= cap) return all to emptyList()
        val shown = all.take((cap - 1).coerceAtLeast(1))
        return shown to all.drop(shown.size)
    }

    // A note on what these mean, because it is the thing about Android Auto's content
    // styles that is easiest to get backwards — and this app had it backwards.
    //
    // The `CONTENT_STYLE_BROWSABLE` / `CONTENT_STYLE_PLAYABLE` hints on a browsable
    // node describe **that node's children**, not the node. The hint that describes a
    // row itself is `CONTENT_STYLE_SINGLE_ITEM`, and it is only worth setting where a
    // row's siblings differ from it — search results, where a track, an album and an
    // artist arrive in one list. So most of what follows answers "what is *inside*
    // this?", which is why an album's answer is about tracks.

    /**
     * How the children of a shelf are drawn — the albums in "Recently added", the
     * names in "Artists".
     *
     * This is what [CarShelf.grid] and [CarShelf.people] have always described: a
     * shelf row itself is a line of text either way.
     */
    fun shelfChildStyle(shelf: CarShelf): CarRowStyle = rowStyle(prefersGrid = shelf.grid, people = shelf.people)

    /**
     * How the children of a library item are drawn.
     *
     * An artist opens onto albums, which are covers worth a grid; everything else
     * here — an album, a playlist, a podcast — opens onto tracks or episodes, which
     * are lines of text. The old code asked the wrong question and told the car to
     * draw an album's *tracks* as a grid, because the album itself was a grid item.
     */
    fun childStyleFor(mediaType: String): CarRowStyle =
        rowStyle(prefersGrid = mediaType == "artist", people = false)

    /**
     * How a single row is drawn, whatever its siblings are.
     *
     * Only used where a list is genuinely mixed — see [CarLibraryBridge]'s search
     * results. Setting it on an ordinary browse row would override the folder's own
     * hint, which is the thing that makes a whole shelf agree with itself.
     */
    fun itemStyle(mediaType: String): CarRowStyle =
        rowStyle(prefersGrid = mediaType in GRID_TYPES, people = mediaType in PEOPLE_TYPES)

    /**
     * How folder rows are drawn — the libraries at the root, the shelves in a library.
     *
     * A list unless the driver asked for covers everywhere: neither has artwork to
     * make a grid worth the height, and the root is the one screen that must fit
     * every library without scrolling.
     */
    fun folderStyle(): CarRowStyle = if (style == CarBrowseStyle.GRID) CarRowStyle.GRID else CarRowStyle.LIST

    private fun rowStyle(prefersGrid: Boolean, people: Boolean): CarRowStyle {
        val grid = when (style) {
            CarBrowseStyle.GRID -> true
            CarBrowseStyle.LIST -> false
            CarBrowseStyle.ADAPTIVE -> prefersGrid
        }
        val category = peopleAsCircles && people
        return when {
            category && grid -> CarRowStyle.CATEGORY_GRID
            category -> CarRowStyle.CATEGORY_LIST
            grid -> CarRowStyle.GRID
            else -> CarRowStyle.LIST
        }
    }

    companion object {
        const val DEFAULT_SHELF_ITEMS = 50
        const val MIN_SHELF_ITEMS = 10
        const val MAX_SHELF_ITEMS = 300

        /**
         * What the "items per shelf" picker offers.
         *
         * Four, not five or six. The picker is a segmented row in a settings page
         * being read by somebody standing beside a car, and a fifth segment is a
         * target narrow enough to miss — the same reasoning that decides how many
         * rows the car itself should show.
         */
        val SHELF_ITEM_CHOICES = listOf(10, 25, 50, 100)

        /** What the rewind / fast-forward picker offers. 0 is "no such buttons". */
        val SEEK_CHOICES = listOf(0, 10, 15, 30)

        private val GRID_TYPES = setOf("artist", "album", "playlist", "genre", "podcast", "audiobook")
        private val PEOPLE_TYPES = setOf("artist", "genre")

        /**
         * A stored key list, as text.
         *
         * A comma-joined string rather than a JSON array or a `stringSetPreferencesKey`:
         * the order *is* the setting here, and a `Set` has none.
         */
        fun encodeKeys(keys: List<String>): String = keys.filter { it.isNotBlank() }.joinToString(",")

        fun decodeKeys(csv: String?): List<String> =
            csv?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty().distinct()

        // ── Editing a stored list ───────────────────────────────────────────
        //
        // The three below are what the settings page's pickers are made of, and they
        // live here rather than beside the Compose that calls them because the rule
        // they encode is this file's: an *empty* stored list means "everything, in
        // the natural order", and it has to stay empty as long as that is still
        // true. A page that wrote out all nine shelf keys the first time somebody
        // dragged one would freeze the set of shelves at whatever this version
        // happens to offer, and a shelf added later would never appear for them.

        /** The enabled keys, in the driver's order, filtered to what exists. */
        fun enabledOrder(stored: List<String>, all: List<String>): List<String> =
            if (stored.isEmpty()) all else stored.filter { it in all }.ifEmpty { all }

        /** Enabled first in their own order, then everything switched off. */
        fun displayOrder(stored: List<String>, all: List<String>): List<String> {
            val enabled = enabledOrder(stored, all)
            return enabled + all.filterNot { it in enabled }
        }

        /**
         * Switch one entry on or off.
         *
         * Switching off the last one is refused rather than obeyed: an empty
         * selection is indistinguishable from the default, and a car showing nothing
         * at all is not a state any setting should be able to reach. The page greys
         * the last checkbox for the same reason, so this is the backstop.
         */
        fun toggle(stored: List<String>, all: List<String>, key: String): List<String> {
            if (key !in all) return stored
            val enabled = enabledOrder(stored, all)
            if (key in enabled) {
                if (enabled.size <= 1) return stored
                return normalise(enabled - key, all)
            }
            // Put it back where it belongs rather than on the end: after the last
            // entry that comes before it naturally. On an untouched list that restores
            // the natural order exactly — which is what lets switching something off
            // and straight back on go back to meaning "the default" — and on a
            // reordered one it lands beside its neighbours instead of at the bottom.
            val naturalIndex = all.indexOf(key)
            val insertAt = enabled.indexOfLast { all.indexOf(it) < naturalIndex } + 1
            return normalise(enabled.toMutableList().apply { add(insertAt, key) }, all)
        }

        /** Move one enabled entry earlier ([delta] < 0) or later. */
        fun move(stored: List<String>, all: List<String>, key: String, delta: Int): List<String> {
            val enabled = enabledOrder(stored, all).toMutableList()
            val from = enabled.indexOf(key)
            if (from < 0) return stored
            val to = (from + delta).coerceIn(0, enabled.size - 1)
            if (to == from) return stored
            enabled.removeAt(from)
            enabled.add(to, key)
            return normalise(enabled, all)
        }

        /** A selection that is exactly the default is stored as the default. */
        private fun normalise(next: List<String>, all: List<String>): List<String> =
            if (next == all) emptyList() else next
    }
}
