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
import com.xaxaxax.relc.simplescript.ui.simpleScriptNavGraph
import com.xaxaxax.relc.simplescript.ui.SimpleEventEditorRoute
import com.xaxaxax.relc.simplescript.ui.SimpleScriptEditorRoute
import com.xaxaxax.relc.simplescript.domain.model.Event
import com.xaxaxax.relc.simplescript.domain.model.Variable
import com.xaxaxax.relc.simplescript.domain.model.Condition
import com.xaxaxax.relc.simplescript.domain.model.Action
import com.xaxaxax.relc.simplescript.ui.editor.EventEditorScreen
import com.xaxaxax.relc.simplescript.ui.editor.ScriptEditorScreen
import com.xaxaxax.relc.simplescript.ui.editor.VariableEditorDialog
import com.xaxaxax.relc.simplescript.ui.list.ScriptListScreen
import com.xaxaxax.relc.simplescript.ui.viewmodel.SimpleScriptViewModel
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
        it.hasRoute(DisplayDetailRoute::class) ||
                it.hasRoute(ScriptDetailRoute::class) ||
                it.hasRoute(SimpleScriptEditorRoute::class) ||
                it.hasRoute(SimpleEventEditorRoute::class)
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
                    onClick = {
                        navController.navigate(destination.route) {
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

            // --- SIMPLE SCRIPTS V2 群組 ---
            // SimpleScriptsRoute is now handled within FullscreenDisplayActivity,
            // or if we want to enter it from the main nav graph, we can use simpleScriptNavGraph here too!
            simpleScriptNavGraph(
                navController = navController,
                onStartPointSelecting = {},
                onStartCropping = {},
                onCloseEditor = { navController.popBackStack() }
            )

            // --- SETTINGS 群組 ---
            composable<SettingsRoute> {
                SettingsScreen()
            }
        }
    }
}
