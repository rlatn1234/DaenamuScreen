package dev.kimsu.daenamutouchphone

import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.ViewModule
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.kimsu.daenamutouchphone.ui.screens.AmsScreen
import dev.kimsu.daenamutouchphone.ui.screens.ControlScreen
import dev.kimsu.daenamutouchphone.ui.screens.HomeScreen
import dev.kimsu.daenamutouchphone.ui.screens.SettingsScreen
import dev.kimsu.daenamutouchphone.ui.theme.DaenamutouchphoneTheme
import dev.kimsu.daenamutouchphone.viewmodel.PrinterViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DaenamutouchphoneTheme {
                DaenamutouchphoneApp()
            }
        }
    }
}

private sealed class Screen(val route: String, val label: String, val icon: ImageVector) {
    data object Home     : Screen("home",     "Home",     Icons.Default.Home)
    data object Ams      : Screen("ams",      "AMS",      Icons.Outlined.ViewModule)
    data object Control  : Screen("control",  "Control",  Icons.Default.Tune)
    data object Settings : Screen("settings", "Settings", Icons.Default.Settings)
}

private val screens = listOf(Screen.Home, Screen.Ams, Screen.Control, Screen.Settings)

@Composable
fun DaenamutouchphoneApp() {
    val navController = rememberNavController()
    val printerViewModel: PrinterViewModel = viewModel()
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    if (isLandscape) {
        // ── Landscape: NavigationRail on left, content on right ──────────────
        Row(modifier = Modifier.fillMaxSize()) {
            val navBackStackEntry by navController.currentBackStackEntryAsState()
            val currentDest = navBackStackEntry?.destination

            NavigationRail(modifier = Modifier.fillMaxHeight()) {
                screens.forEach { screen ->
                    NavigationRailItem(
                        icon = { Icon(screen.icon, contentDescription = screen.label) },
                        label = { Text(screen.label) },
                        selected = currentDest?.hierarchy?.any { it.route == screen.route } == true,
                        onClick = {
                            navController.navigate(screen.route) {
                                popUpTo(navController.graph.findStartDestination().id) {
                                    saveState = true
                                }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                    )
                }
            }

            NavHost(
                navController = navController,
                startDestination = Screen.Home.route,
                modifier = Modifier.fillMaxSize(),
            ) {
                composable(Screen.Home.route)     { HomeScreen(printerViewModel) }
                composable(Screen.Ams.route)      { AmsScreen(printerViewModel) }
                composable(Screen.Control.route)  { ControlScreen(printerViewModel) }
                composable(Screen.Settings.route) { SettingsScreen(printerViewModel) }
            }
        }
    } else {
        // ── Portrait: NavigationBar at bottom (original layout) ──────────────
        Scaffold(
            bottomBar = {
                NavigationBar {
                    val navBackStackEntry by navController.currentBackStackEntryAsState()
                    val currentDest = navBackStackEntry?.destination
                    screens.forEach { screen ->
                        NavigationBarItem(
                            icon = { Icon(screen.icon, contentDescription = screen.label) },
                            label = { Text(screen.label) },
                            selected = currentDest?.hierarchy?.any { it.route == screen.route } == true,
                            onClick = {
                                navController.navigate(screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                        )
                    }
                }
            }
        ) { innerPadding ->
            NavHost(
                navController = navController,
                startDestination = Screen.Home.route,
                modifier = Modifier.padding(innerPadding),
            ) {
                composable(Screen.Home.route)     { HomeScreen(printerViewModel) }
                composable(Screen.Ams.route)      { AmsScreen(printerViewModel) }
                composable(Screen.Control.route)  { ControlScreen(printerViewModel) }
                composable(Screen.Settings.route) { SettingsScreen(printerViewModel) }
            }
        }
    }
}
