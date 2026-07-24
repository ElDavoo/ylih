package it.eldavo.ylih.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController

private data class Destination(val route: String, val label: String, val icon: ImageVector)

private val destinations = listOf(
    Destination("devices", "Headphones", Icons.Default.Home),
    Destination("stats", "Stats", Icons.Default.DateRange),
    Destination("settings", "Settings", Icons.Default.Settings),
)

@Composable
fun YlihNavHost(viewModel: YlihViewModel = viewModel()) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.messages.collect { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            NavigationBar {
                destinations.forEach { destination ->
                    NavigationBarItem(
                        selected = currentRoute == destination.route,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = { Icon(destination.icon, contentDescription = null) },
                        label = { Text(destination.label) },
                    )
                }
            }
        },
    ) { padding ->
        NavHost(navController = navController, startDestination = "devices") {
            composable("devices") {
                DevicesScreen(
                    viewModel = viewModel,
                    contentPadding = padding,
                    onOpenPair = { navController.navigate("pair/$it") },
                )
            }
            composable("stats") {
                StatsScreen(viewModel = viewModel, contentPadding = padding)
            }
            composable("settings") {
                SettingsScreen(viewModel = viewModel, contentPadding = padding)
            }
            composable("pair/{pairId}") { entry ->
                val pairId = entry.arguments?.getString("pairId")?.toLongOrNull()
                if (pairId == null) {
                    navController.popBackStack()
                } else {
                    PairDetailScreen(
                        viewModel = viewModel,
                        pairId = pairId,
                        contentPadding = padding,
                        onBack = { navController.popBackStack() },
                    )
                }
            }
        }
    }
}
