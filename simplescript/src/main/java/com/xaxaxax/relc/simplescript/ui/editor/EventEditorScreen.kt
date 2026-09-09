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
fun EventEditorScreen(
    event: Event,
    onBack: () -> Unit,
    onNameChange: (String) -> Unit,
    onEnabledChange: (Boolean) -> Unit,
    onOperatorChange: (LogicalOperator) -> Unit,
    onAddCondition: () -> Unit,
    onEditCondition: (Condition) -> Unit,
    onDeleteCondition: (Condition) -> Unit,
    onAddAction: () -> Unit,
    onEditAction: (Action) -> Unit,
    onDeleteAction: (Action) -> Unit,
    modifier: Modifier = Modifier
) {
    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text("Edit Event") },
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

            // Event Header (Name, Enable, Operator)
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = event.name,
                            onValueChange = onNameChange,
                            label = { Text("Event Name") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Enabled on Start")
                            Switch(
                                checked = event.enabledOnStart,
                                onCheckedChange = onEnabledChange
                            )
                        }
                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text("Conditions Operator", fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Determine how multiple conditions trigger the event",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            SingleChoiceSegmentedButtonRow {
                                SegmentedButton(
                                    selected = event.conditionOperator == LogicalOperator.AND,
                                    onClick = { onOperatorChange(LogicalOperator.AND) },
                                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2)
                                ) {
                                    Text("AND")
                                }
                                SegmentedButton(
                                    selected = event.conditionOperator == LogicalOperator.OR,
                                    onClick = { onOperatorChange(LogicalOperator.OR) },
                                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2)
                                ) {
                                    Text("OR")
                                }
                            }
                        }
                    }
                }
            }

            // Conditions Section
            item {
                SectionHeader(title = "Conditions", onAddClick = onAddCondition)
            }
            items(event.conditions) { condition ->
                ConditionListItem(
                    condition = condition,
                    onEdit = { onEditCondition(condition) },
                    onDelete = { onDeleteCondition(condition) }
                )
            }
            if (event.conditions.isEmpty()) {
                item {
                    Text("No conditions defined.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // Actions Section
            item {
                SectionHeader(title = "Actions", onAddClick = onAddAction)
            }
            items(event.actions) { action ->
                ActionListItem(
                    action = action,
                    onEdit = { onEditAction(action) },
                    onDelete = { onDeleteAction(action) }
                )
            }
            if (event.actions.isEmpty()) {
                item {
                    Text("No actions defined.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            item { Spacer(modifier = Modifier.height(32.dp)) }
        }
    }
}

@Composable
fun ConditionListItem(
    condition: Condition,
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
                when (condition) {
                    is TemplateMatchCondition -> {
                        Text(text = "Template Match", fontWeight = FontWeight.Bold)
                        Text(text = "Path: ${condition.imgPath.substringAfterLast("/")} | Thr: ${condition.threshold}", style = MaterialTheme.typography.bodySmall)
                    }
                    is VariableCondition -> {
                        Text(text = "Variable Condition", fontWeight = FontWeight.Bold)
                        Text(text = "${condition.variableA} ${condition.operator.symbol} ${condition.target}", style = MaterialTheme.typography.bodySmall)
                    }
                    is TimerCondition -> {
                        Text(text = "Timer", fontWeight = FontWeight.Bold)
                        Text(text = "After ${condition.duration} ${condition.unit}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Row {
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = "Edit Condition")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete Condition", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
fun ActionListItem(
    action: Action,
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
                Text(text = action.actionName, fontWeight = FontWeight.Bold)
                val desc = when (action) {
                    is ClickAction -> "Point(${action.point.x}, ${action.point.y}) | ${action.duration}ms"
                    is SwipeAction -> "From(${action.point1.x}, ${action.point1.y}) to (${action.point2.x}, ${action.point2.y}) | ${action.duration}ms"
                    is WaitAction -> "Delay ${action.duration} ${action.unit}"
                    is SetVariableAction -> "Var ${action.name} ${action.operator.symbol} ${action.value}"
                    is SetEventAction -> "Event ${action.eventName} -> ${action.operator.name}"
                    is SystemBtnAction -> "Press ${action.op.name}"
                    is ForLoopAction -> "Var ${action.variableName} from ${action.from} to ${action.to} (${action.actions.size} acts)"
                }
                Text(text = desc, style = MaterialTheme.typography.bodySmall)
            }
            Row {
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = "Edit Action")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Delete Action", tint = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun EventEditorScreenPreview() {
    MaterialTheme {
        EventEditorScreen(
            event = Event(
                name = "Auto Heal",
                enabledOnStart = true,
                conditionOperator = LogicalOperator.AND,
                conditions = listOf(
                    TemplateMatchCondition(imgPath = "/sdcard/hp_low.png", threshold = 0.85f),
                    TimerCondition(duration = 2f, unit = TimeUnit.S)
                ),
                actions = listOf(
                    ClickAction(point = PointConfig("500", "800"), duration = 50),
                    WaitAction(duration = 500, unit = TimeUnit.MS)
                )
            ),
            onBack = {},
            onNameChange = {},
            onEnabledChange = {},
            onOperatorChange = {},
            onAddCondition = {},
            onEditCondition = {},
            onDeleteCondition = {},
            onAddAction = {},
            onEditAction = {},
            onDeleteAction = {}
        )
    }
}
