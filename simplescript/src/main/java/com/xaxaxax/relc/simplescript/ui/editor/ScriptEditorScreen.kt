package com.xaxaxax.relc.simplescript.ui.editor

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.xaxaxax.relc.simplescript.domain.model.*

import androidx.compose.material.icons.filled.Save
import androidx.activity.compose.BackHandler

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScriptEditorScreen(
    script: Script,
    isDirty: Boolean,
    onClose: () -> Unit,
    onSave: () -> Unit,
    onNameChange: (String) -> Unit,
    onFpsChange: (Int) -> Unit,
    onUpdateVariables: (List<Variable>) -> Unit,
    onAddEvent: () -> Unit,
    onEditEvent: (Event) -> Unit,
    onDeleteEvent: (Event) -> Unit,
    modifier: Modifier = Modifier
) {
    var showVariableDialog by remember { mutableStateOf(false) }
    var variableToEdit by remember { mutableStateOf<Variable?>(null) }
    var variableIndexToEdit by remember { mutableStateOf(-1) }
    var showDiscardDialog by remember { mutableStateOf(false) }

    val handleBackPress = {
        if (isDirty) {
            showDiscardDialog = true
        } else {
            onClose()
        }
    }

    BackHandler(enabled = true) {
        handleBackPress()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Edit Script") },
                navigationIcon = {
                    IconButton(onClick = handleBackPress) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Close")
                    }
                },
                actions = {
                    IconButton(onClick = onSave, enabled = isDirty) {
                        Icon(Icons.Default.Save, contentDescription = "Save")
                    }
                }
            )
        }
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item { Spacer(modifier = Modifier.height(8.dp)) }

            // Script Header (Name & FPS)
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = script.name,
                            onValueChange = onNameChange,
                            label = { Text("Script Name") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Execution FPS: ${script.fps}")
                            // We can use a Slider or a simpler TextField for FPS. Using Slider for ease in this preview.
                            Slider(
                                value = script.fps.toFloat(),
                                onValueChange = { onFpsChange(it.toInt()) },
                                valueRange = 1f..60f,
                                modifier = Modifier.weight(1f).padding(start = 16.dp)
                            )
                        }
                    }
                }
            }

            // Variables Section
            item {
                SectionHeader(title = "Variables", onAddClick = {
                    variableToEdit = null
                    variableIndexToEdit = -1
                    showVariableDialog = true
                })
            }
            items(script.variables) { variable ->
                VariableListItem(
                    variable = variable,
                    onEdit = {
                        variableToEdit = variable
                        variableIndexToEdit = script.variables.indexOf(variable)
                        showVariableDialog = true
                    },
                    onDelete = { onUpdateVariables(script.variables - variable) }
                )
            }
            if (script.variables.isEmpty()) {
                item {
                    Text("No variables defined.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // Events Section
            item {
                SectionHeader(title = "Events", onAddClick = onAddEvent)
            }
            items(script.events) { event ->
                EventListItem(
                    event = event,
                    onEdit = { onEditEvent(event) },
                    onDelete = { onDeleteEvent(event) }
                )
            }
            if (script.events.isEmpty()) {
                item {
                    Text("No events defined.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            item { Spacer(modifier = Modifier.height(32.dp)) }
        }

        if (showVariableDialog) {
            VariableEditorDialog(
                initialVariable = variableToEdit,
                onSave = { newVar ->
                    val updatedVars = script.variables.toMutableList()
                    if (variableIndexToEdit >= 0) {
                        updatedVars[variableIndexToEdit] = newVar
                    } else {
                        updatedVars.add(newVar)
                    }
                    onUpdateVariables(updatedVars)
                    showVariableDialog = false
                },
                onDismiss = { showVariableDialog = false }
            )
        }

        if (showDiscardDialog) {
            AlertDialog(
                onDismissRequest = { showDiscardDialog = false },
                title = { Text("Discard Changes?") },
                text = { Text("You have unsaved changes. Are you sure you want to discard them and leave?") },
                confirmButton = {
                    TextButton(onClick = {
                        showDiscardDialog = false
                        onClose()
                    }) {
                        Text("Discard", color = MaterialTheme.colorScheme.error)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showDiscardDialog = false }) {
                        Text("Cancel")
                    }
                }
            )
        }
    }
}
@Composable
fun SectionHeader(title: String, onAddClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        IconButton(onClick = onAddClick) {
            Icon(Icons.Default.Add, contentDescription = "Add $title")
        }
    }
}

@Composable
fun VariableListItem(
    variable: Variable,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = variable.name, fontWeight = FontWeight.Bold)
                Text(text = "Type: ${variable.type} | Initial: ${variable.initialValue}", style = MaterialTheme.typography.bodySmall)
            }
            Row {
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = "Edit Variable")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete Variable", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
fun EventListItem(
    event: Event,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable { onEdit() },
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = event.name.ifEmpty { "Unnamed Event" }, fontWeight = FontWeight.Bold)
                Text(text = "Enabled on start: ${event.enabledOnStart}", style = MaterialTheme.typography.bodySmall)
                Text(
                    text = "${event.conditions.size} Conditions | ${event.actions.size} Actions",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = "Delete Event", tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun ScriptEditorScreenPreview() {
    MaterialTheme {
        ScriptEditorScreen(
            script = Script(
                name = "Farm Loop",
                fps = 15,
                variables = listOf(
                    Variable("loop_count", VariableType.INT, 0),
                    Variable("offset_y", VariableType.FLOAT, 10.5)
                ),
                events = listOf(
                    Event(
                        name = "Check HP",
                        enabledOnStart = true,
                        conditions = listOf(TimerCondition(5f, TimeUnit.S)),
                        actions = listOf(SystemBtnAction(SystemOperation.HOME))
                    )
                )
            ),
            isDirty = false,
            onClose = {},
            onSave = {},
            onNameChange = {},
            onFpsChange = {},
            onUpdateVariables = {},
            onAddEvent = {},
            onEditEvent = {},
            onDeleteEvent = {}
        )
    }
}
