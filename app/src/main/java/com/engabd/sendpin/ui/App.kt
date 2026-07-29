package com.engabd.sendpin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.engabd.sendpin.ui.design.LocalAccent
import com.engabd.sendpin.ui.design.LocalPalette
import com.engabd.sendpin.ui.design.NavTab
import com.engabd.sendpin.ui.design.SendspinNavBar
import com.engabd.sendpin.ui.design.rememberAlbumPalette
import com.engabd.sendpin.ma.LibraryViewModel
import com.engabd.sendpin.ui.viewmodel.NowPlayingViewModel
import com.engabd.sendpin.ui.screens.LibraryScreen
import com.engabd.sendpin.ui.screens.LightSyncScreen
import com.engabd.sendpin.ui.screens.MiniPlayerBar
import com.engabd.sendpin.ui.screens.NowPlayingOverlay
import com.engabd.sendpin.ui.screens.NowPlayingScreen
import com.engabd.sendpin.ui.screens.OnboardingScreen
import com.engabd.sendpin.ui.screens.SettingsScreen
import com.engabd.sendpin.ui.screens.SpeakersScreen
import com.engabd.sendpin.ui.theme.Ink
import com.engabd.sendpin.ui.theme.SendspinTheme
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

/** In overlay mode, "Playing" is not a tab — the cover slides over the other four. */
private val OverlayTabs = listOf(
    NavTab("library", "Library", Icons.Default.LibraryMusic),
    NavTab("speakers", "Speakers", Icons.Default.Speaker),
    NavTab("light_sync", "Lights", Icons.Default.Lightbulb),
    NavTab("settings", "Settings", Icons.Default.Settings),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    SendspinTheme {
        val playerVm: PlayerViewModel = viewModel()
        val art by playerVm.artworkUrl.collectAsState()
        val palette = rememberAlbumPalette(art)
        val accent = palette.accent

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
            CompositionLocalProvider(LocalAccent provides accent, LocalPalette provides palette) {
                OnboardingScreen(viewModel = playerVm, onSkip = { skipped = true })
            }
            return@SendspinTheme
        }

        val navController = rememberNavController()
        val navBackStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = navBackStackEntry?.destination?.route

        val nowPlayingVm: NowPlayingViewModel = viewModel()
        val libraryVm: LibraryViewModel = viewModel()

        // Read the now-playing layout preference.
        val context = LocalContext.current
        val settings = remember { AppSettings(context) }
        val layout by settings.nowPlayingLayout.collectAsState(initial = "tab")
        val isOverlay = layout == "overlay"

        // Overlay expand/collapse state.
        var overlayExpanded by rememberSaveable { mutableStateOf(false) }

        fun go(route: String) {
            if (currentRoute != route) {
                navController.navigate(route) {
                    popUpTo(if (isOverlay) "library" else "now_playing") { inclusive = false }
                    launchSingleTop = true
                }
            }
        }

        CompositionLocalProvider(LocalAccent provides accent, LocalPalette provides palette) {
            Box(Modifier.fillMaxSize().background(Ink)) {
                val tabs = if (isOverlay) OverlayTabs else TabTabs
                val startDest = if (isOverlay) "library" else "now_playing"

                NavHost(
                    navController = navController,
                    startDestination = startDest,
                    modifier = Modifier.fillMaxSize(),
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
                    composable("library") { LibraryScreen(libraryVm) }
                    composable("speakers") { SpeakersScreen(onBack = { navController.popBackStack() }) }
                    composable("light_sync") { LightSyncScreen(onBack = { navController.popBackStack() }) }
                    composable("settings") { SettingsScreen(viewModel = playerVm) }
                }

                // In overlay mode, the mini player bar sits above the nav bar.
                // Tapping it expands the full-screen cover.
                if (isOverlay) {
                    Column(
                        Modifier
                            .align(Alignment.BottomCenter)
                    ) {
                        // Mini player bar above the nav bar.
                        Box(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                            MiniPlayerBar(viewModel = nowPlayingVm, onExpand = { overlayExpanded = true })
                        }
                        SendspinNavBar(
                            tabs = tabs,
                            currentRoute = currentRoute,
                            onSelect = ::go,
                        )
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
                    SendspinNavBar(
                        tabs = tabs,
                        currentRoute = currentRoute,
                        modifier = Modifier.align(Alignment.BottomCenter),
                        onSelect = ::go,
                    )
                }
            }
        }
    }
}