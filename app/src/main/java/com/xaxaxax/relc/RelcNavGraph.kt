package com.xaxaxax.relc

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.xaxaxax.relc.ui.displaydetail.DisplayDetailScreen
import com.xaxaxax.relc.ui.displays.DisplaysScreen
import com.xaxaxax.relc.ui.scriptdetail.ScriptDetailScreen
import com.xaxaxax.relc.ui.scripts.ScriptsScreen
import com.xaxaxax.relc.ui.setting.SettingsScreen

@Composable
fun RelcNavGraph(
    openScriptsPage: Boolean = false,
    onScriptsPageOpened: () -> Unit = {},
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    /** Shared by the navigation bar and the notification deep link below. */
    fun navigateToTopLevel(route: Any) {
        navController.navigate(route) {
            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
        }
    }

    // Deep link from the script status notification ("查看狀態" / tapping the notification).
    LaunchedEffect(openScriptsPage) {
        if (!openScriptsPage) return@LaunchedEffect
        navigateToTopLevel(ScriptsRoute)
        onScriptsPageOpened()
    }

    val isDetailScreen = currentDestination?.hierarchy?.any {
        it.hasRoute(DisplayDetailRoute::class) ||
                it.hasRoute(ScriptDetailRoute::class)
    } == true

    val layoutType = if (isDetailScreen) {
        NavigationSuiteType.None
    } else {
        NavigationSuiteScaffoldDefaults.calculateFromAdaptiveInfo(
            currentWindowAdaptiveInfo()
        )
    }

    NavigationSuiteScaffold(
        layoutType = layoutType,
        navigationSuiteItems = {
            TopLevelDestination.entries.forEach { destination ->
                val isSelected = currentDestination?.hierarchy?.any {
                    it.hasRoute(destination.route::class)
                } == true

                item(
                    icon = { Icon(destination.icon, contentDescription = destination.label) },
                    label = { Text(destination.label) },
                    selected = isSelected,
                    onClick = { navigateToTopLevel(destination.route) }
                )
            }
        }
    ) {

        NavHost(
            navController = navController,
            startDestination = DisplaysRoute
        ) {
            // --- DISPLAYS 群組 ---
            composable<DisplaysRoute> {
                DisplaysScreen(
                    onNavigateToDetail = { id ->
                        navController.navigate(DisplayDetailRoute(id))
                    }
                )
            }
            composable<DisplayDetailRoute> { backStackEntry ->
                val detail = backStackEntry.toRoute<DisplayDetailRoute>()
                DisplayDetailScreen(
                    id = detail.id,
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            // --- SCRIPTS 群組 ---
            composable<ScriptsRoute> {
                ScriptsScreen(
                    onNavigateToDetail = { id ->
                        navController.navigate(ScriptDetailRoute(id))
                    }
                )
            }
            composable<ScriptDetailRoute> { backStackEntry ->
                val detail = backStackEntry.toRoute<ScriptDetailRoute>()
                ScriptDetailScreen(
                    id = detail.id,
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            // --- SETTINGS 群組 ---
            composable<SettingsRoute> {
                SettingsScreen()
            }
        }
    }
}
