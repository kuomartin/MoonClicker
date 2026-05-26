package com.xaxaxax.relc.simplescript.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraphBuilder
import androidx.navigation.NavHostController
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.xaxaxax.relc.SimpleEventEditorRoute
import com.xaxaxax.relc.SimpleScriptEditorRoute
import com.xaxaxax.relc.SimpleScriptsRoute
import com.xaxaxax.relc.simplescript.domain.model.Action
import com.xaxaxax.relc.simplescript.domain.model.Condition
import com.xaxaxax.relc.simplescript.domain.model.Event
import com.xaxaxax.relc.simplescript.ui.editor.ActionEditorDialog
import com.xaxaxax.relc.simplescript.ui.editor.ConditionEditorDialog
import com.xaxaxax.relc.simplescript.ui.editor.EventEditorScreen
import com.xaxaxax.relc.simplescript.ui.editor.ScriptEditorScreen
import com.xaxaxax.relc.simplescript.ui.list.ScriptListScreen
import com.xaxaxax.relc.simplescript.ui.viewmodel.SimpleScriptViewModel

fun NavGraphBuilder.simpleScriptNavGraph(
    navController: NavHostController,
    onStartPointSelecting: () -> Unit = {},
    onStartCropping: () -> Unit = {}
) {
    composable<SimpleScriptsRoute> {
        val viewModel: SimpleScriptViewModel = hiltViewModel()
        val scripts by viewModel.scripts.collectAsStateWithLifecycle()

        ScriptListScreen(
            scripts = scripts,
            onAddScript = {
                navController.navigate(SimpleScriptEditorRoute(0L))
            },
            onScriptClick = { script ->
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
            BackHandler {
                viewModel.saveCurrentScript()
                navController.popBackStack()
            }

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

        val parentEntry = remember(backStackEntry) {
            navController.getBackStackEntry(SimpleScriptEditorRoute::class)
        }
        val viewModel: SimpleScriptViewModel = hiltViewModel(parentEntry)
        val currentScript by viewModel.currentScript.collectAsStateWithLifecycle()

        val eventIndex = route.eventIndex
        var localEvent by remember(currentScript, eventIndex) {
            mutableStateOf<Event>(
                if (eventIndex >= 0 && currentScript != null && eventIndex < currentScript!!.events.size) {
                    currentScript!!.events[eventIndex]
                } else {
                    Event(name = "New Event")
                }
            )
        }

        var showConditionDialog by remember { mutableStateOf(false) }
        var conditionToEdit by remember { mutableStateOf<Condition?>(null) }
        var conditionIndexToEdit by remember { mutableStateOf(-1) }

        var showActionDialog by remember { mutableStateOf(false) }
        var actionToEdit by remember { mutableStateOf<Action?>(null) }
        var actionIndexToEdit by remember { mutableStateOf(-1) }

        if (currentScript != null) {
            BackHandler {
                val updatedEvents = currentScript!!.events.toMutableList()
                if (eventIndex >= 0 && eventIndex < updatedEvents.size) {
                    updatedEvents[eventIndex] = localEvent
                } else {
                    updatedEvents.add(localEvent)
                }
                viewModel.updateCurrentScript(currentScript!!.copy(events = updatedEvents))
                navController.popBackStack()
            }

            EventEditorScreen(
                event = localEvent,
                onBack = {
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
                onAddCondition = {
                    conditionToEdit = null
                    conditionIndexToEdit = -1
                    showConditionDialog = true
                },
                onEditCondition = { cond ->
                    conditionToEdit = cond
                    conditionIndexToEdit = localEvent.conditions.indexOf(cond)
                    showConditionDialog = true
                },
                onDeleteCondition = { cond -> localEvent = localEvent.copy(conditions = localEvent.conditions - cond) },
                onAddAction = {
                    actionToEdit = null
                    actionIndexToEdit = -1
                    showActionDialog = true
                },
                onEditAction = { act ->
                    actionToEdit = act
                    actionIndexToEdit = localEvent.actions.indexOf(act)
                    showActionDialog = true
                },
                onDeleteAction = { act -> localEvent = localEvent.copy(actions = localEvent.actions - act) }
            )

            if (showConditionDialog) {
                ConditionEditorDialog(
                    initialCondition = conditionToEdit,
                    availableVariables = currentScript!!.variables,
                    onSave = { newCond ->
                        val updatedConditions = localEvent.conditions.toMutableList()
                        if (conditionIndexToEdit >= 0) {
                            updatedConditions[conditionIndexToEdit] = newCond
                        } else {
                            updatedConditions.add(newCond)
                        }
                        localEvent = localEvent.copy(conditions = updatedConditions)
                        showConditionDialog = false
                    },
                    onDismiss = { showConditionDialog = false },
                    onCaptureArea = {
                        showConditionDialog = false
                        onStartCropping()
                    }
                )
            }

            if (showActionDialog) {
                ActionEditorDialog(
                    initialAction = actionToEdit,
                    availableVariables = currentScript!!.variables,
                    availableEvents = currentScript!!.events,
                    onSave = { newAct ->
                        val updatedActions = localEvent.actions.toMutableList()
                        if (actionIndexToEdit >= 0) {
                            updatedActions[actionIndexToEdit] = newAct
                        } else {
                            updatedActions.add(newAct)
                        }
                        localEvent = localEvent.copy(actions = updatedActions)
                        showActionDialog = false
                    },
                    onDismiss = { showActionDialog = false },
                    onSelectPoint = {
                        showActionDialog = false
                        onStartPointSelecting()
                    }
                )
            }
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
    }
}
