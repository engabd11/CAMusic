package com.engabd.sendpin.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.engabd.sendpin.ui.screens.LibraryScreen
import com.engabd.sendpin.ui.screens.NowPlayingScreen
import com.engabd.sendpin.ui.screens.SettingsScreen
import com.engabd.sendpin.ui.theme.SendspinTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App() {
    SendspinTheme {
        val navController = rememberNavController()
        val navBackStackEntry by navController.currentBackStackEntryAsState()
        val currentRoute = navBackStackEntry?.destination?.route

        Scaffold(
            modifier = Modifier.fillMaxSize(),
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.MusicNote, contentDescription = "Now Playing") },
                        label = { Text("Now Playing") },
                        selected = currentRoute == "now_playing",
                        onClick = {
                            if (currentRoute != "now_playing") {
                                navController.navigate("now_playing") {
                                    popUpTo("now_playing") { inclusive = true }
                                }
                            }
                        }
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.LibraryMusic, contentDescription = "Library") },
                        label = { Text("Library") },
                        selected = currentRoute == "library",
                        onClick = {
                            if (currentRoute != "library") {
                                navController.navigate("library") {
                                    popUpTo("now_playing") { inclusive = false }
                                }
                            }
                        }
                    )
                    NavigationBarItem(
                        icon = { Icon(Icons.Default.Settings, contentDescription = "Settings") },
                        label = { Text("Settings") },
                        selected = currentRoute == "settings",
                        onClick = {
                            if (currentRoute != "settings") {
                                navController.navigate("settings") {
                                    popUpTo("now_playing") { inclusive = false }
                                }
                            }
                        }
                    )
                }
            }
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = "now_playing",
                modifier = Modifier.padding(innerPadding)
            ) {
                composable("now_playing") { NowPlayingScreen() }
                composable("library") { LibraryScreen() }
                composable("settings") { SettingsScreen() }
            }
        }
    }
}
