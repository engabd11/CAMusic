package com.engabd.sendpin.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import com.engabd.sendpin.ui.screens.NowPlayingScreen
import com.engabd.sendpin.ui.screens.OnboardingScreen
import com.engabd.sendpin.ui.screens.SettingsScreen
import com.engabd.sendpin.ui.screens.SpeakersScreen
import com.engabd.sendpin.ui.theme.Ink
import com.engabd.sendpin.ui.theme.SendspinTheme
import com.engabd.sendpin.ui.viewmodel.PlayerViewModel

private val Tabs = listOf(
    NavTab("now_playing", "Playing", Icons.Default.PlayArrow),
    NavTab("library", "Library", Icons.Default.LibraryMusic),
    NavTab("speakers", "Speakers", Icons.Default.Speaker),
    NavTab("light_sync", "Lights", Icons.Default.Lightbulb),
    NavTab("settings", "Settings", Icons.Default.Settings),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    SendspinTheme {
        // One shared player VM at the app root so the album-derived accent flows to every screen.
        val playerVm: PlayerViewModel = viewModel()
        val art by playerVm.artworkUrl.collectAsState()
        val palette = rememberAlbumPalette(art)
        val accent = palette.accent

        // Onboarding is first-run only: once a server is saved the app auto-connects
        // on launch (see PlayerViewModel.init) and never shows onboarding again until
        // the user logs out. "Explore offline" skips it for the current launch.
        val connected by playerVm.connected.collectAsState()
        val hasSavedServer by playerVm.hasSavedServer.collectAsState()
        val bootChecked by playerVm.bootChecked.collectAsState()
        var skipped by rememberSaveable { mutableStateOf(false) }

        // Brief splash until we've read settings — avoids flashing onboarding on every launch.
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

        // Hoisted to the Activity, not the nav entry. Tab switches pop back to
        // now_playing, which would otherwise destroy each screen's ViewModel and
        // force it to reconnect — the library would flash its connect form and
        // Now Playing its idle state on every switch.
        val nowPlayingVm: NowPlayingViewModel = viewModel()
        val libraryVm: LibraryViewModel = viewModel()

        fun go(route: String) {
            if (currentRoute != route) {
                navController.navigate(route) {
                    popUpTo("now_playing") { inclusive = false }
                    launchSingleTop = true
                }
            }
        }

        CompositionLocalProvider(LocalAccent provides accent, LocalPalette provides palette) {
            // The tab bar floats over the content instead of displacing it, so the
            // album wash reaches the bottom of the panel. Screens leave room for it
            // with navBarInset().
            Box(Modifier.fillMaxSize().background(Ink)) {
                NavHost(
                    navController = navController,
                    startDestination = "now_playing",
                    modifier = Modifier.fillMaxSize(),
                ) {
                    composable("now_playing") {
                        NowPlayingScreen(
                            viewModel = nowPlayingVm,
                            onOpenSpeakers = { go("speakers") },
                            onBrowse = { go("library") },
                        )
                    }
                    composable("library") { LibraryScreen(libraryVm) }
                    composable("speakers") { SpeakersScreen(onBack = { navController.popBackStack() }) }
                    composable("light_sync") { LightSyncScreen(onBack = { navController.popBackStack() }) }
                    composable("settings") { SettingsScreen(viewModel = playerVm) }
                }
                SendspinNavBar(
                    tabs = Tabs,
                    currentRoute = currentRoute,
                    modifier = Modifier.align(Alignment.BottomCenter),
                    onSelect = ::go,
                )
            }
        }
    }
}
