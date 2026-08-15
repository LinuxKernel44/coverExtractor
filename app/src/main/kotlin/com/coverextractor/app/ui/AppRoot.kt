package com.coverextractor.app.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.coverextractor.app.ResultItem
import com.coverextractor.app.library.CoverPreviewScreen
import com.coverextractor.app.library.LibraryScreen
import com.coverextractor.app.library.LibrarySettingsScreen
import com.coverextractor.app.library.LibraryViewModel

private const val ROUTE_QUICK_PICK = "quickPick"
private const val ROUTE_LIBRARY = "library"
private const val ROUTE_PREVIEW = "library/preview/{entryId}"
private const val ROUTE_LIBRARY_SETTINGS = "library/settings"

@Composable
fun AppRoot(results: List<ResultItem>, onPickFiles: () -> Unit) {
    val navController = rememberNavController()
    val libraryViewModel: LibraryViewModel = viewModel()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val showBottomBar = currentRoute == ROUTE_QUICK_PICK || currentRoute == ROUTE_LIBRARY

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    NavigationBarItem(
                        selected = currentRoute == ROUTE_QUICK_PICK,
                        onClick = {
                            navController.navigate(ROUTE_QUICK_PICK) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(Icons.Filled.MusicNote, contentDescription = null) },
                        label = { Text("Quick pick") }
                    )
                    NavigationBarItem(
                        selected = currentRoute == ROUTE_LIBRARY,
                        onClick = {
                            navController.navigate(ROUTE_LIBRARY) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(Icons.Filled.LibraryMusic, contentDescription = null) },
                        label = { Text("Library") }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = ROUTE_QUICK_PICK,
            modifier = Modifier.fillMaxSize()
        ) {
            composable(ROUTE_QUICK_PICK) {
                MainScreen(results = results, onPickFiles = onPickFiles, contentPadding = padding)
            }
            composable(ROUTE_LIBRARY) {
                LibraryScreen(
                    viewModel = libraryViewModel,
                    contentPadding = padding,
                    onOpenPreview = { entryId -> navController.navigate("library/preview/$entryId") },
                    onOpenSettings = { navController.navigate(ROUTE_LIBRARY_SETTINGS) }
                )
            }
            composable(ROUTE_PREVIEW) { entry ->
                val entryId = entry.arguments?.getString("entryId").orEmpty()
                CoverPreviewScreen(
                    viewModel = libraryViewModel,
                    entryId = entryId,
                    onBack = { navController.popBackStack() }
                )
            }
            composable(ROUTE_LIBRARY_SETTINGS) {
                LibrarySettingsScreen(
                    viewModel = libraryViewModel,
                    onBack = { navController.popBackStack() }
                )
            }
        }
    }
}
