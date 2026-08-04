package com.engabd.sendpin.ui

import android.app.Activity
import androidx.activity.compose.BackHandler
import androidx.core.view.WindowCompat
import androidx.compose.ui.platform.LocalView
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.engabd.sendpin.ui.design.BottomChromeState
import com.engabd.sendpin.ui.design.LocalAccent
import com.engabd.sendpin.ui.design.LocalBottomChrome
import androidx.navigation.NavBackStackEntry
import com.engabd.sendpin.ui.design.LocalMiniBarInset
import com.engabd.sendpin.ui.design.Motion
import com.engabd.sendpin.ui.design.LocalNavAnimatedScope
import com.engabd.sendpin.ui.design.LocalSharedTransitionScope
import com.engabd.sendpin.ui.design.MiniBarHeight
import com.engabd.sendpin.ui.design.LocalPalette
import com.engabd.sendpin.ui.design.NavTab
import com.engabd.sendpin.ui.design.SendspinNavBar
import com.engabd.sendpin.ui.design.rememberAlbumPalette
import com.engabd.sendpin.ma.LibraryViewModel
import com.engabd.sendpin.ma.MaItem
import com.engabd.sendpin.subsonic.SubsonicClient
import com.engabd.sendpin.ui.viewmodel.NowPlayingViewModel
import com.engabd.sendpin.ui.screens.AlbumDetailScreen
import com.engabd.sendpin.ui.screens.ArtistDetailScreen
import com.engabd.sendpin.ui.screens.LibraryScreen
import com.engabd.sendpin.ui.screens.LightSyncScreen
import com.engabd.sendpin.ui.screens.MiniPlayerBar
import com.engabd.sendpin.ui.screens.NowPlayingOverlay
import com.engabd.sendpin.ui.screens.NowPlayingScreen
import com.engabd.sendpin.ui.screens.OnboardingScreen
import com.engabd.sendpin.ui.screens.PlaylistDetailScreen
import com.engabd.sendpin.ui.screens.SettingsScreen
import com.engabd.sendpin.ui.screens.SettingsSection
import com.engabd.sendpin.ui.screens.SpeakersScreen
import com.engabd.sendpin.ui.theme.AccentChoice
import com.engabd.sendpin.ui.theme.Ink
import com.engabd.sendpin.ui.theme.LocalSendspinColors
import com.engabd.sendpin.ui.theme.SendspinTheme
import com.engabd.sendpin.ui.theme.ThemeChoice
import com.engabd.sendpin.ui.theme.parseAccent
import com.engabd.sendpin.ui.viewmodel.PlayerViewModel
import com.engabd.sendpin.data.AppSettings
import kotlinx.coroutines.flow.first

private val TabTabs = listOf(
    NavTab("now_playing", "Playing", Icons.Default.PlayArrow),
    NavTab("library", "Library", Icons.Default.LibraryMusic),
    NavTab("speakers", "Speakers", Icons.Default.Speaker),
    NavTab("light_sync", "Lights", Icons.Default.Lightbulb),
    NavTab("settings", "Settings", Icons.Default.Settings),
)

// Detail screens arrive from the right and leave the way they came, so the back
// gesture undoes the push rather than dissolving it. The outgoing screen only moves a
// quarter of the way so it reads as staying put underneath rather than being shoved
// off — the parallax every platform's push uses.
//
// Springs rather than tweens, from the app's own motion tokens — see design/Motion.kt
// for why these are not MaterialTheme.motionScheme.
private val pushIn: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    slideInHorizontally(Motion.screenSlide()) { it } + fadeIn(Motion.effects())
}
private val pushOut: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    slideOutHorizontally(Motion.screenSlide()) { -it / 4 } + fadeOut(Motion.effects())
}
private val popIn: AnimatedContentTransitionScope<NavBackStackEntry>.() -> EnterTransition = {
    slideInHorizontally(Motion.screenSlide()) { -it / 4 } + fadeIn(Motion.effects())
}
private val popOut: AnimatedContentTransitionScope<NavBackStackEntry>.() -> ExitTransition = {
    slideOutHorizontally(Motion.screenSlide()) { it } + fadeOut(Motion.effects())
}

/**
 * Which tab a route belongs to, or null for one that belongs to none.
 *
 * The tab bar used to compare the route to the tab's own route, so opening an album
 * left every tab unlit — the app stopped saying where you were the moment you went
 * anywhere. A detail screen is still the Library tab as far as the user is concerned,
 * and this is what says so.
 */
private fun sectionOf(route: String?): String? = when {
    route == null -> null
    route.startsWith("album/") || route.startsWith("artist/") || route.startsWith("playlist/") -> "library"
    else -> route
}

/** In overlay mode, "Playing" is not a tab — the cover slides over the other four. */
private val OverlayTabs = listOf(
    NavTab("library", "Library", Icons.Default.LibraryMusic),
    NavTab("speakers", "Speakers", Icons.Default.Speaker),
    NavTab("light_sync", "Lights", Icons.Default.Lightbulb),
    NavTab("settings", "Settings", Icons.Default.Settings),
)

/**
 * Points the system bar icons at the current theme.
 *
 * `enableEdgeToEdge` in the Activity runs before any of this is known, and on a light
 * page its light icons are white-on-white — the clock and battery simply vanish. This
 * re-states them from inside the theme, so switching to Light in Settings fixes the
 * status bar in the same frame as the rest of the app.
 */
@Composable
private fun SystemBars() {
    val light = LocalSendspinColors.current.isLight
    val view = LocalView.current
    if (view.isInEditMode) return
    val window = (view.context as? Activity)?.window ?: return
    LaunchedEffect(light, window) {
        WindowCompat.getInsetsController(window, view).apply {
            isAppearanceLightStatusBars = light
            isAppearanceLightNavigationBars = light
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable
fun App() {
    // Appearance is read before the theme rather than inside it, because it *is* the
    // theme. Defaults match the app as designed, so the first frame of a fresh install
    // — drawn before DataStore has answered — is the same OLED black it settles on,
    // with no flash of another palette.
    val themeContext = LocalContext.current
    val themeSettings = remember { AppSettings(themeContext) }
    val themeKey by themeSettings.theme.collectAsState(initial = ThemeChoice.OLED.key)
    val accentKey by themeSettings.accentSource.collectAsState(initial = AccentChoice.ALBUM.key)
    val fixedAccentHex by themeSettings.fixedAccent.collectAsState(initial = "")
    val themeChoice = ThemeChoice.from(themeKey)
    val accentChoice = AccentChoice.from(accentKey)
    val fixedAccent = remember(fixedAccentHex) { parseAccent(fixedAccentHex) }

    SendspinTheme(
        theme = themeChoice,
        accentChoice = accentChoice,
        seedAccent = fixedAccent,
    ) {
        SystemBars()
        val playerVm: PlayerViewModel = viewModel()
        // Boot/onboarding run before the Now Playing VM exists, so they colour
        // themselves from this phone's own stream.
        val localArt by playerVm.artworkUrl.collectAsState()
        val bootPalette = rememberAlbumPalette(localArt)
        val accent = accentChoice.resolve(bootPalette.accent, fixedAccent, MaterialTheme.colorScheme.primary)

        val connected by playerVm.connected.collectAsState()
        val hasSavedServer by playerVm.hasSavedServer.collectAsState()
        val bootChecked by playerVm.bootChecked.collectAsState()
        var skipped by rememberSaveable { mutableStateOf(false) }

        if (!bootChecked) {
            Box(Modifier.fillMaxSize().background(Ink), contentAlignment = Alignment.Center) {
                Box(
                    Modifier.size(78.dp).clip(RoundedCornerShape(24.dp)).background(accent),
                    contentAlignment = Alignment.Center,
                ) { Text("S", color = Ink, fontWeight = FontWeight.Black, fontSize = 40.sp) }
            }
            return@SendspinTheme
        }

        if (!hasSavedServer && !connected && !skipped) {
            CompositionLocalProvider(LocalAccent provides accent, LocalPalette provides bootPalette) {
                OnboardingScreen(viewModel = playerVm, onSkip = { skipped = true })
            }
            return@SendspinTheme
        }

        val navController = rememberNavController()
        val navBackStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = navBackStackEntry?.destination?.route

        val nowPlayingVm: NowPlayingViewModel = viewModel()
        val libraryVm: LibraryViewModel = viewModel()

        // The whole app is tinted by whatever the *controlled player* is playing —
        // which is not necessarily this phone. Deriving the app palette from the
        // local Sendspin stream meant that casting to a speaker left every screen
        // but Now Playing on the default accent.
        val npState by nowPlayingVm.state.collectAsState()
        val appPalette = rememberAlbumPalette(npState.artworkUrl ?: localArt)
        // Only the album-art source lets the artwork drive the accent. On the other two
        // the user asked for one colour that does not move, so the palette is still
        // extracted (its companion swatches feed avatars and blooms) but the accent
        // itself is held.
        val appAccent = accentChoice.resolve(
            appPalette.accent, fixedAccent, MaterialTheme.colorScheme.primary,
        )

        // Read the now-playing layout preference.
        val context = LocalContext.current
        val settings = remember { AppSettings(context) }
        val layout by settings.nowPlayingLayout.collectAsState(initial = "tab")
        val isOverlay = layout == "overlay"

        // Speaker grouping and Light Sync are Music Assistant features — grouping is
        // an MA command and Light Sync drives an HA integration that follows MA
        // players. On the Navidrome backend the phone plays on its own, so both tabs
        // are shown greyed rather than offering controls that can't do anything.
        val backend by settings.backend.collectAsState(initial = "ma")
        val disabledRoutes = if (backend == "subsonic") setOf("speakers", "light_sync") else emptySet()

        // An album/artist/playlist detail screen belongs to the server it was opened
        // from, so switching libraries has to leave it.
        //
        // [LibraryViewModel.applyBackend] already empties the *browse stack*, which is
        // why this looked fixed and wasn't: a detail screen is not a browse node, it
        // is its own navigation destination with its own ViewModel, and no amount of
        // clearing library state pops it. Switching to Navidrome while standing on an
        // MA album left that album on screen, backed by a repository for a server the
        // library is no longer pointed at.
        /**
         * Does a detail screen opened from [itemProvider] still belong to the library
         * the user is now browsing?
         *
         * A detail screen is its own navigation destination, so clearing the library's
         * browse stack cannot pop it — switching to Navidrome while standing on a
         * Music Assistant album left that album on screen. The obvious fix, popping
         * the back stack from an effect when the backend changes, is worse than the
         * bug: the switch happens in *Settings*, so the Library tab is a **saved**
         * stack, and tearing entries out from under it crashes with "cannot access
         * the NavBackStackEntry's ViewModels" the moment it is restored and composed.
         *
         * So the screen leaves under its own power instead, while it is the current
         * destination and popping is ordinary navigation. The provider already
         * travels in the route, so no extra state is needed to know.
         */
        fun strandedOnOtherBackend(itemProvider: String): Boolean {
            val subsonicItem = itemProvider == SubsonicClient.PROVIDER || itemProvider == "download"
            return if (backend == "subsonic") !subsonicItem else subsonicItem
        }

        // Overlay expand/collapse state.
        var overlayExpanded by rememberSaveable { mutableStateOf(false) }

        // Which category Settings has open, held here rather than inside the screen so
        // that re-tapping the Settings tab can close it. See [SettingsScreen.section].
        var settingsSection by rememberSaveable { mutableStateOf<SettingsSection?>(null) }

        fun go(route: String) {
            if (route in disabledRoutes) return

            // Re-tapping the tab you are already on means "take me back to the top of
            // this section" — the convention every tabbed app has. Without it the only
            // way out of an album, or out of a settings category, was the system back
            // button or the arrow in the corner.
            //
            // Two different kinds of depth have to unwind, because a detail screen is a
            // navigation destination while a browse level and a settings category are
            // state inside one:
            if (sectionOf(currentRoute) == route) {
                // A detail screen sitting on top of the tab — pop back to the tab root.
                if (currentRoute != route) navController.popBackStack(route, inclusive = false)
                // …and then unwind whatever depth the tab root holds internally. Both
                // are no-ops when already at the top, so a stray tap costs nothing.
                when (route) {
                    "library" -> libraryVm.goToRoot()
                    "settings" -> settingsSection = null
                }
                return
            }

            navController.navigate(route) {
                // saveState/restoreState is what keeps each tab where the user left
                // it. The previous popUpTo destroyed the destination outright, so
                // every return re-ran the screen from scratch — the library lost its
                // search and scroll position, and the other tabs reloaded on sight.
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
        }

        // Switching the backend while standing on a tab that just went away would
        // leave the user looking at dead controls, so walk them back to the library.
        LaunchedEffect(disabledRoutes, currentRoute) {
            if (currentRoute != null && currentRoute in disabledRoutes) {
                navController.navigate("library") {
                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
            }
        }

        // The mini player bar is bottom chrome the same way the tab bar is, and only
        // exists in the overlay layout. Publishing its height here is what lets every
        // screen's `navBarInset()` reserve room for it without any of them knowing the
        // layout is in play.
        // A sheet is drawn inside its own screen, which is a NavHost sibling below the
        // bottom chrome — so the tab bar painted over it. The chrome stands down while
        // a sheet is open rather than every sheet being re-parented out of the screen
        // that provides its palette.
        val bottomChrome = remember { BottomChromeState() }

        CompositionLocalProvider(
            LocalAccent provides appAccent,
            LocalPalette provides appPalette,
            LocalMiniBarInset provides if (isOverlay) MiniBarHeight else 0.dp,
            LocalBottomChrome provides bottomChrome,
        ) {
            Box(Modifier.fillMaxSize().background(Ink)) {
                val tabs = if (isOverlay) OverlayTabs else TabTabs
                val startDest = if (isOverlay) "library" else "now_playing"

                SharedTransitionLayout {
                NavHost(
                    navController = navController,
                    startDestination = startDest,
                    modifier = Modifier.fillMaxSize(),
                    // Tabs cross-fade: they are siblings, and sliding between them
                    // would imply an order the tab bar doesn't have. Pushing into a
                    // detail screen slides, because that *is* a direction — see the
                    // detail destinations below, which override these.
                    enterTransition = { fadeIn(Motion.effects()) },
                    exitTransition = { fadeOut(Motion.effects()) },
                ) {
                    if (!isOverlay) {
                        composable("now_playing") {
                            NowPlayingScreen(
                                viewModel = nowPlayingVm,
                                onOpenSpeakers = { go("speakers") },
                                onBrowse = { go("library") },
                            )
                        }
                    }
                    composable("library") {
                        val navToDetail: (String, MaItem) -> Unit = { route, item ->
                            val n = android.net.Uri.encode(item.name)
                            val a = item.image?.let { android.net.Uri.encode(it) } ?: ""
                            navController.navigate("$route/${android.net.Uri.encode(item.itemId)}/${android.net.Uri.encode(item.provider)}?name=$n&art=$a")
                        }
                        CompositionLocalProvider(
                            LocalSharedTransitionScope provides this@SharedTransitionLayout,
                            LocalNavAnimatedScope provides this@composable,
                        ) {
                            LibraryScreen(
                                viewModel = libraryVm,
                                onAlbumClick = { navToDetail("album", it) },
                                onArtistClick = { navToDetail("artist", it) },
                                onPlaylistClick = { navToDetail("playlist", it) },
                            )
                        }
                    }
                    composable(
                        route = "album/{itemId}/{provider}?name={name}&art={art}",
                        arguments = listOf(
                            navArgument("itemId") { type = NavType.StringType },
                            navArgument("provider") { type = NavType.StringType },
                            navArgument("name") { type = NavType.StringType; defaultValue = "" },
                            navArgument("art") { type = NavType.StringType; defaultValue = "" },
                        ),
                        // No slide: the cover itself flies from the grid tile into the
                        // hero (see LocalSharedTransitionScope), and sliding the screen
                        // at the same time would drag the artwork along a second path.
                        enterTransition = { fadeIn(Motion.effects()) },
                        exitTransition = { fadeOut(Motion.effects()) },
                    ) { backStackEntry ->
                        val aItemId = backStackEntry.arguments?.getString("itemId") ?: ""
                        val aProvider = backStackEntry.arguments?.getString("provider") ?: ""
                        val aName = backStackEntry.arguments?.getString("name") ?: ""
                        val aArt = backStackEntry.arguments?.getString("art")?.takeIf { it.isNotBlank() }
                        val aStranded = strandedOnOtherBackend(aProvider)
                        LaunchedEffect(aStranded) { if (aStranded) navController.popBackStack() }
                        CompositionLocalProvider(
                            LocalSharedTransitionScope provides this@SharedTransitionLayout,
                            LocalNavAnimatedScope provides this@composable,
                        ) {
                        AlbumDetailScreen(
                            itemId = aItemId,
                            provider = aProvider,
                            name = aName,
                            artUrl = aArt,
                            onBack = { navController.popBackStack() },
                            // An album knows its artist's *name* and nothing else —
                            // MaItem carries no artist id — so the route travels with
                            // `byName`, and the artist screen looks the id up. The id
                            // slot is a placeholder: a name in a path segment breaks on
                            // the likes of "AC/DC".
                            onArtistClick = { artistName, artistProvider ->
                                val n = android.net.Uri.encode(artistName)
                                navController.navigate("artist/-/${android.net.Uri.encode(artistProvider)}?name=$n&byName=true")
                            },
                        )
                        }
                    }
                    composable(
                        route = "artist/{itemId}/{provider}?name={name}&art={art}&byName={byName}",
                        arguments = listOf(
                            navArgument("itemId") { type = NavType.StringType },
                            navArgument("provider") { type = NavType.StringType },
                            navArgument("name") { type = NavType.StringType; defaultValue = "" },
                            navArgument("art") { type = NavType.StringType; defaultValue = "" },
                            navArgument("byName") { type = NavType.BoolType; defaultValue = false },
                        ),
                        enterTransition = pushIn, exitTransition = pushOut,
                        popEnterTransition = popIn, popExitTransition = popOut,
                    ) { backStackEntry ->
                        val aItemId = backStackEntry.arguments?.getString("itemId") ?: ""
                        val aProvider = backStackEntry.arguments?.getString("provider") ?: ""
                        val aName = backStackEntry.arguments?.getString("name") ?: ""
                        val aArt = backStackEntry.arguments?.getString("art")?.takeIf { it.isNotBlank() }
                        val aByName = backStackEntry.arguments?.getBoolean("byName") ?: false
                        val artistStranded = strandedOnOtherBackend(aProvider)
                        LaunchedEffect(artistStranded) { if (artistStranded) navController.popBackStack() }
                        ArtistDetailScreen(
                            itemId = aItemId,
                            provider = aProvider,
                            name = aName,
                            artUrl = aArt,
                            resolveByName = aByName,
                            onBack = { navController.popBackStack() },
                            onAlbumClick = { album ->
                                val n = android.net.Uri.encode(album.name)
                                val a = album.image?.let { android.net.Uri.encode(it) } ?: ""
                                navController.navigate("album/${android.net.Uri.encode(album.itemId)}/${android.net.Uri.encode(album.provider)}?name=$n&art=$a")
                            },
                        )
                    }
                    composable(
                        route = "playlist/{itemId}/{provider}?name={name}&art={art}",
                        arguments = listOf(
                            navArgument("itemId") { type = NavType.StringType },
                            navArgument("provider") { type = NavType.StringType },
                            navArgument("name") { type = NavType.StringType; defaultValue = "" },
                            navArgument("art") { type = NavType.StringType; defaultValue = "" },
                        ),
                        enterTransition = pushIn, exitTransition = pushOut,
                        popEnterTransition = popIn, popExitTransition = popOut,
                    ) { backStackEntry ->
                        val pItemId = backStackEntry.arguments?.getString("itemId") ?: ""
                        val pProvider = backStackEntry.arguments?.getString("provider") ?: ""
                        val pName = backStackEntry.arguments?.getString("name") ?: ""
                        val pArt = backStackEntry.arguments?.getString("art")?.takeIf { it.isNotBlank() }
                        val pStranded = strandedOnOtherBackend(pProvider)
                        LaunchedEffect(pStranded) { if (pStranded) navController.popBackStack() }
                        PlaylistDetailScreen(
                            itemId = pItemId,
                            provider = pProvider,
                            name = pName,
                            artUrl = pArt,
                            onBack = { navController.popBackStack() },
                        )
                    }
                    composable("speakers") { SpeakersScreen(onBack = { navController.popBackStack() }) }
                    composable("light_sync") { LightSyncScreen(onBack = { navController.popBackStack() }) }
                    composable("settings") {
                        SettingsScreen(
                            viewModel = playerVm,
                            section = settingsSection,
                            onSection = { settingsSection = it },
                        )
                    }
                }
                }

                // In overlay mode, the mini player bar sits above the nav bar.
                // Tapping it expands the full-screen cover.
                if (isOverlay) {
                    BottomChrome(bottomChrome) {
                    Column(
                        Modifier
                            .align(Alignment.BottomCenter)
                    ) {
                        // Mini player bar above the nav bar. Pinned to [MiniBarHeight]
                        // rather than measured, so the space `navBarInset()` reserves
                        // for it can't drift out of step with what it actually takes.
                        Box(
                            Modifier
                                .height(MiniBarHeight)
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            MiniPlayerBar(viewModel = nowPlayingVm, onExpand = { overlayExpanded = true })
                        }
                        SendspinNavBar(
                            tabs = tabs,
                            currentRoute = sectionOf(currentRoute),
                            disabledRoutes = disabledRoutes,
                            onSelect = ::go,
                        )
                    }
                    }

                    // The full-screen cover overlay — slides over everything when expanded.
                    if (overlayExpanded) {
                        BackHandler { overlayExpanded = false }
                        NowPlayingOverlay(
                            viewModel = nowPlayingVm,
                            onOpenSpeakers = { overlayExpanded = false; go("speakers") },
                            onBrowse = { overlayExpanded = false; go("library") },
                            expanded = overlayExpanded,
                            onExpand = { overlayExpanded = true },
                            onCollapse = { overlayExpanded = false },
                        )
                    }
                } else {
                    BottomChrome(bottomChrome) {
                        SendspinNavBar(
                            tabs = tabs,
                            currentRoute = sectionOf(currentRoute),
                            modifier = Modifier.align(Alignment.BottomCenter),
                            disabledRoutes = disabledRoutes,
                            onSelect = ::go,
                        )
                    }
                }
            }
        }
    }
}

/**
 * The bottom chrome — mini player and tab bar — unless a sheet is up.
 *
 * Its own composable so the `openSheets` read lands in this recompose scope rather
 * than the whole app's.
 */
@Composable
private fun BoxScope.BottomChrome(
    state: BottomChromeState,
    content: @Composable BoxScope.() -> Unit,
) {
    if (state.openSheets == 0) content()
}