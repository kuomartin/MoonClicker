package com.xaxaxax.relc

import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
fun RelcNavGraph() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    val isDetailScreen = currentDestination?.hierarchy?.any {
        it.hasRoute(DisplayDetailRoute::class) || it.hasRoute(ScriptDetailRoute::class)
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
            // 這裡只會迭代 DISPLAYS, SCRIPTS, SETTINGS
            TopLevelDestination.entries.forEach { destination ->
                // 判斷當前路由是否屬於該頂層路由的層級
                val isSelected = currentDestination?.hierarchy?.any {
                    it.hasRoute(destination.route::class)
                } == true

                item(
                    icon = { Icon(destination.icon, contentDescription = destination.label) },
                    label = { Text(destination.label) },
                    selected = isSelected,
                    onClick = {
                        navController.navigate(destination.route) {
                            // 避免堆疊無限增長
                            popUpTo(navController.graph.findStartDestination().id) {
                                saveState = true
                            }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }
        }
    ) {

        // 子頁面的路由就放在這個 NavHost 裡面！
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
            // 子頁面放在這裡
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
            // 子頁面放在這裡
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
