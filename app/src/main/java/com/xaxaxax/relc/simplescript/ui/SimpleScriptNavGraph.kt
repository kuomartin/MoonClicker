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
import com.xaxaxax.relc.simplescript.ui.editor.ConditionEditorScreen
import com.xaxaxax.relc.simplescript.ui.editor.ActionEditorScreen
import com.xaxaxax.relc.SimpleConditionEditorRoute
import com.xaxaxax.relc.SimpleActionEditorRoute
import com.xaxaxax.relc.simplescript.ui.editor.EventEditorScreen
import com.xaxaxax.relc.simplescript.ui.editor.ScriptEditorScreen
import com.xaxaxax.relc.simplescript.ui.list.ScriptListScreen
import com.xaxaxax.relc.simplescript.ui.viewmodel.SimpleScriptViewModel

fun NavGraphBuilder.simpleScriptNavGraph(
    navController: NavHostController,
    onStartPointSelecting: () -> Unit = {},
    onStartCropping: () -> Unit = {},
    onCloseEditor: () -> Unit = {}
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
        val isDirty by viewModel.isDirty.collectAsStateWithLifecycle()

        if (currentScript != null) {
            ScriptEditorScreen(
                script = currentScript!!,
                isDirty = isDirty,
                onClose = {
                    onCloseEditor()
                },
                onSave = {
                    viewModel.saveCurrentScript()
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
        } else {            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
    }

    composable<SimpleEventEditorRoute> { backStackEntry ->
        val route = backStackEntry.toRoute<SimpleEventEditorRoute>()
        val parentEntry = remember(backStackEntry) { navController.getBackStackEntry(SimpleScriptEditorRoute::class) }
        val viewModel: SimpleScriptViewModel = hiltViewModel(parentEntry)
        val currentScript by viewModel.currentScript.collectAsStateWithLifecycle()

        val eventIndex = route.eventIndex

        if (currentScript != null) {
            val localEvent = if (eventIndex >= 0 && eventIndex < currentScript!!.events.size) {
                currentScript!!.events[eventIndex]
            } else {
                Event(name = "New Event")
            }

            val updateEventInScript = { updatedEvent: Event ->
                val updatedEvents = currentScript!!.events.toMutableList()
                if (eventIndex >= 0 && eventIndex < updatedEvents.size) {
                    updatedEvents[eventIndex] = updatedEvent
                } else {
                    updatedEvents.add(updatedEvent)
                }
                viewModel.updateCurrentScript(currentScript!!.copy(events = updatedEvents))
            }

            BackHandler {
                updateEventInScript(localEvent)
                navController.popBackStack()
            }

            EventEditorScreen(
                event = localEvent,
                onBack = {
                    updateEventInScript(localEvent)
                    navController.popBackStack()
                },
                onNameChange = { updateEventInScript(localEvent.copy(name = it)) },
                onEnabledChange = { updateEventInScript(localEvent.copy(enabledOnStart = it)) },
                onOperatorChange = { updateEventInScript(localEvent.copy(conditionOperator = it)) },
                onAddCondition = {
                    // Need to ensure the event is saved to the script list before navigating to a child index.
                    updateEventInScript(localEvent)
                    val targetEventIndex = if (eventIndex >= 0) eventIndex else currentScript!!.events.size - 1
                    navController.navigate(SimpleConditionEditorRoute(targetEventIndex, -1))
                },
                onEditCondition = { cond ->
                    updateEventInScript(localEvent)
                    val targetEventIndex = if (eventIndex >= 0) eventIndex else currentScript!!.events.size - 1
                    navController.navigate(SimpleConditionEditorRoute(targetEventIndex, localEvent.conditions.indexOf(cond)))
                },
                onDeleteCondition = { cond -> updateEventInScript(localEvent.copy(conditions = localEvent.conditions - cond)) },
                onAddAction = {
                    updateEventInScript(localEvent)
                    val targetEventIndex = if (eventIndex >= 0) eventIndex else currentScript!!.events.size - 1
                    navController.navigate(SimpleActionEditorRoute(targetEventIndex, -1))
                },
                onEditAction = { act ->
                    updateEventInScript(localEvent)
                    val targetEventIndex = if (eventIndex >= 0) eventIndex else currentScript!!.events.size - 1
                    navController.navigate(SimpleActionEditorRoute(targetEventIndex, localEvent.actions.indexOf(act)))
                },
                onDeleteAction = { act -> updateEventInScript(localEvent.copy(actions = localEvent.actions - act)) }
            )
        } else {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
    }

    composable<SimpleConditionEditorRoute> { backStackEntry ->
        val route = backStackEntry.toRoute<SimpleConditionEditorRoute>()
        val parentEntry = remember(backStackEntry) { navController.getBackStackEntry(SimpleScriptEditorRoute::class) }
        val viewModel: SimpleScriptViewModel = hiltViewModel(parentEntry)
        val currentScript by viewModel.currentScript.collectAsStateWithLifecycle()

        if (currentScript != null && route.eventIndex >= 0 && route.eventIndex < currentScript!!.events.size) {
            val event = currentScript!!.events[route.eventIndex]
            val initialCondition = if (route.conditionIndex >= 0 && route.conditionIndex < event.conditions.size) {
                event.conditions[route.conditionIndex]
            } else null

            ConditionEditorScreen(
                initialCondition = initialCondition,
                availableVariables = currentScript!!.variables,
                onBack = { navController.popBackStack() },
                onSave = { newCond ->
                    val updatedConditions = event.conditions.toMutableList()
                    if (route.conditionIndex >= 0 && route.conditionIndex < updatedConditions.size) {
                        updatedConditions[route.conditionIndex] = newCond
                    } else {
                        updatedConditions.add(newCond)
                    }
                    val updatedEvent = event.copy(conditions = updatedConditions)
                    val updatedEvents = currentScript!!.events.toMutableList()
                    updatedEvents[route.eventIndex] = updatedEvent
                    viewModel.updateCurrentScript(currentScript!!.copy(events = updatedEvents))
                    navController.popBackStack()
                },
                onCaptureArea = {
                    // For now we just trigger capture. We might need to remember that we were capturing a condition.
                    // Ideally we pass a callback or save state, but triggering cropping hides the NavHost, we will come back here.
                    onStartCropping()
                }
            )
        } else {
            navController.popBackStack()
        }
    }

    composable<SimpleActionEditorRoute> { backStackEntry ->
        val route = backStackEntry.toRoute<SimpleActionEditorRoute>()
        val parentEntry = remember(backStackEntry) { navController.getBackStackEntry(SimpleScriptEditorRoute::class) }
        val viewModel: SimpleScriptViewModel = hiltViewModel(parentEntry)
        val currentScript by viewModel.currentScript.collectAsStateWithLifecycle()

        if (currentScript != null && route.eventIndex >= 0 && route.eventIndex < currentScript!!.events.size) {
            val event = currentScript!!.events[route.eventIndex]
            val initialAction = if (route.actionIndex >= 0 && route.actionIndex < event.actions.size) {
                event.actions[route.actionIndex]
            } else null

            ActionEditorScreen(
                initialAction = initialAction,
                availableVariables = currentScript!!.variables,
                availableEvents = currentScript!!.events,
                onBack = { navController.popBackStack() },
                onSave = { newAct ->
                    val updatedActions = event.actions.toMutableList()
                    if (route.actionIndex >= 0 && route.actionIndex < updatedActions.size) {
                        updatedActions[route.actionIndex] = newAct
                    } else {
                        updatedActions.add(newAct)
                    }
                    val updatedEvent = event.copy(actions = updatedActions)
                    val updatedEvents = currentScript!!.events.toMutableList()
                    updatedEvents[route.eventIndex] = updatedEvent
                    viewModel.updateCurrentScript(currentScript!!.copy(events = updatedEvents))
                    navController.popBackStack()
                },
                onSelectPoint = {
                    onStartPointSelecting()
                }
            )
        } else {
            navController.popBackStack()
        }
    }
}
