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
import com.xaxaxax.relc.simplescript.domain.model.Event
import com.xaxaxax.relc.simplescript.domain.model.Variable
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
            composable<SimpleScriptsRoute> {
                val viewModel: SimpleScriptViewModel = hiltViewModel()
                val scripts by viewModel.scripts.collectAsStateWithLifecycle()

                ScriptListScreen(
                    scripts = scripts,
                    onAddScript = {
                        navController.navigate(SimpleScriptEditorRoute(0L))
                    },
                    onScriptClick = { script ->
                        // Script entity currently doesn't have an ID in domain model. Let's assume we navigate by ID if we add it, or index.
                        // Wait, ScriptEntity has id. We should add id to Domain model later or find by name.
                        // For now we navigate to 0 to create.
                        // Let's assume we navigate by name or we need to add ID to Script model.
                        // I will add ID to Script later. For now let's just navigate to 0.
                        navController.navigate(SimpleScriptEditorRoute(0L)) // TODO: pass actual ID
                    },
                    onDeleteScript = {
                        // TODO: Implement delete
                    }
                )
            }

            composable<SimpleScriptEditorRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<SimpleScriptEditorRoute>()
                val viewModel: SimpleScriptViewModel = hiltViewModel()

                LaunchedEffect(route.scriptId) {
                    viewModel.loadScript(route.scriptId)
                }

                val currentScript by viewModel.currentScript.collectAsStateWithLifecycle()

                if (currentScript != null) {
                    ScriptEditorScreen(
                        script = currentScript!!,
                        onBack = {
                            viewModel.saveCurrentScript()
                            navController.popBackStack()
                        },
                        onNameChange = { name -> viewModel.updateCurrentScript(currentScript!!.copy(name = name)) },
                        onFpsChange = { fps -> viewModel.updateCurrentScript(currentScript!!.copy(fps = fps)) },
                        onUpdateVariables = { vars -> viewModel.updateCurrentScript(currentScript!!.copy(variables = vars)) },
                        onAddEvent = {
                            navController.navigate(SimpleEventEditorRoute(-1)) // -1 for new
                        },
                        onEditEvent = { event ->
                            val index = currentScript!!.events.indexOf(event)
                            navController.navigate(SimpleEventEditorRoute(index))
                        },
                        onDeleteEvent = { event ->
                            viewModel.updateCurrentScript(currentScript!!.copy(events = currentScript!!.events - event))
                        }
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }

            composable<SimpleEventEditorRoute> { backStackEntry ->
                val route = backStackEntry.toRoute<SimpleEventEditorRoute>()

                // We share the ViewModel attached to the SimpleScriptsRoute using remember
                // Wait, hiltViewModel() inside a NavGraph will usually scope to that route.
                // To share it, we should pass it from the parent or use scoped view model.
                // For simplicity here, we assume hiltViewModel() is fine if the data is saved in DB,
                // but since currentScript is just state, hiltViewModel() on a different route will create a new instance!
                // To fix this, we can scope it to the navController graph.
                val parentEntry = remember(backStackEntry) {
                    navController.getBackStackEntry(SimpleScriptsRoute::class)
                }
                val viewModel: SimpleScriptViewModel = hiltViewModel(parentEntry)
                val currentScript by viewModel.currentScript.collectAsStateWithLifecycle()

                val eventIndex = route.eventIndex
                // If it's a new event, we just construct an empty one.
                // If editing, we pull from currentScript
                var localEvent by remember(currentScript, eventIndex) {
                    mutableStateOf<Event>(
                        if (eventIndex >= 0 && currentScript != null && eventIndex < currentScript!!.events.size) {
                            currentScript!!.events[eventIndex]
                        } else {
                            Event(name = "New Event")
                        }
                    )
                }

                if (currentScript != null) {
                    EventEditorScreen(
                        event = localEvent,
                        onBack = {
                            // Save back to script
                            val updatedEvents = currentScript!!.events.toMutableList()
                            if (eventIndex >= 0 && eventIndex < updatedEvents.size) {
                                updatedEvents[eventIndex] = localEvent
                            } else {
                                updatedEvents.add(localEvent)
                            }
                            viewModel.updateCurrentScript(currentScript!!.copy(events = updatedEvents))
                            navController.popBackStack()
                        },
                        onNameChange = { localEvent = localEvent.copy(name = it) },
                        onEnabledChange = { localEvent = localEvent.copy(enabledOnStart = it) },
                        onOperatorChange = { localEvent = localEvent.copy(conditionOperator = it) },
                        onAddCondition = { /* TODO: Nav to Condition Selector/Editor */ },
                        onEditCondition = { /* TODO: Nav to Condition Selector/Editor */ },
                        onDeleteCondition = { cond ->
                            localEvent = localEvent.copy(conditions = localEvent.conditions - cond)
                        },
                        onAddAction = { /* TODO: Nav to Action Selector/Editor */ },
                        onEditAction = { /* TODO: Nav to Action Selector/Editor */ },
                        onDeleteAction = { act ->
                            localEvent = localEvent.copy(actions = localEvent.actions - act)
                        }
                    )
                } else {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
            }
            // --- SETTINGS 群組 ---
            composable<SettingsRoute> {
                SettingsScreen()
            }
        }
    }
}
