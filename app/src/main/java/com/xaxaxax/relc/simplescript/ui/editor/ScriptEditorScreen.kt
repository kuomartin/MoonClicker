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
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.xaxaxax.relc.simplescript.domain.model.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScriptEditorScreen(
    script: Script,
    onBack: () -> Unit,
    onNameChange: (String) -> Unit,
    onFpsChange: (Int) -> Unit,
    onAddVariable: () -> Unit,
    onEditVariable: (Variable) -> Unit,
    onDeleteVariable: (Variable) -> Unit,
    onAddEvent: () -> Unit,
    onEditEvent: (Event) -> Unit,
    onDeleteEvent: (Event) -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Edit Script") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                SectionHeader(title = "Variables", onAddClick = onAddVariable)
            }
            items(script.variables) { variable ->
                VariableListItem(
                    variable = variable,
                    onEdit = { onEditVariable(variable) },
                    onDelete = { onDeleteVariable(variable) }
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
            onBack = {},
            onNameChange = {},
            onFpsChange = {},
            onAddVariable = {},
            onEditVariable = {},
            onDeleteVariable = {},
            onAddEvent = {},
            onEditEvent = {},
            onDeleteEvent = {}
        )
    }
}
