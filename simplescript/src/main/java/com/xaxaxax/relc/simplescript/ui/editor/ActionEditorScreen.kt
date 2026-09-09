package com.xaxaxax.relc.simplescript.ui.editor

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.xaxaxax.relc.simplescript.domain.model.*

enum class ActionTypeEnum {
    CLICK, SWIPE, WAIT, SET_VARIABLE, SET_EVENT, SYSTEM_BTN, FOR_LOOP
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ActionEditorScreen(
    initialAction: Action?,
    availableVariables: List<Variable>,
    availableEvents: List<Event>,
    onSave: (Action) -> Unit,
    onBack: () -> Unit,
    onSelectPoint: () -> Unit = {}
) {
    var selectedType by remember {
        mutableStateOf(
            when (initialAction) {
                is ClickAction -> ActionTypeEnum.CLICK
                is SwipeAction -> ActionTypeEnum.SWIPE
                is WaitAction -> ActionTypeEnum.WAIT
                is SetVariableAction -> ActionTypeEnum.SET_VARIABLE
                is SetEventAction -> ActionTypeEnum.SET_EVENT
                is SystemBtnAction -> ActionTypeEnum.SYSTEM_BTN
                is ForLoopAction -> ActionTypeEnum.FOR_LOOP
                null -> ActionTypeEnum.CLICK
                else -> ActionTypeEnum.CLICK
            }
        )
    }

    var expandedTypeDropdown by remember { mutableStateOf(false) }

    // Click/Swipe State
    var p1x by remember { mutableStateOf((initialAction as? ClickAction)?.point?.x ?: (initialAction as? SwipeAction)?.point1?.x ?: "") }
    var p1y by remember { mutableStateOf((initialAction as? ClickAction)?.point?.y ?: (initialAction as? SwipeAction)?.point1?.y ?: "") }
    var p2x by remember { mutableStateOf((initialAction as? SwipeAction)?.point2?.x ?: "") }
    var p2y by remember { mutableStateOf((initialAction as? SwipeAction)?.point2?.y ?: "") }
    var durationMs by remember { mutableStateOf((initialAction as? ClickAction)?.duration?.toString() ?: (initialAction as? SwipeAction)?.duration?.toString() ?: "100") }

    // Wait State
    var waitDuration by remember { mutableStateOf((initialAction as? WaitAction)?.duration?.toString() ?: "1000") }
    var waitUnit by remember { mutableStateOf((initialAction as? WaitAction)?.unit ?: TimeUnit.MS) }

    // Set Variable State
    var varName by remember { mutableStateOf((initialAction as? SetVariableAction)?.name ?: "") }
    var varOp by remember { mutableStateOf((initialAction as? SetVariableAction)?.operator ?: AssignmentOperator.ASSIGN) }
    var varValue by remember { mutableStateOf((initialAction as? SetVariableAction)?.value ?: "") }

    // Set Event State
    var eventName by remember { mutableStateOf((initialAction as? SetEventAction)?.eventName ?: "") }
    var eventOp by remember { mutableStateOf((initialAction as? SetEventAction)?.operator ?: EventOperator.ON) }

    // System Btn State
    var sysBtnOp by remember { mutableStateOf((initialAction as? SystemBtnAction)?.op ?: SystemOperation.BACK) }

    val handleSave = {
        val action = when (selectedType) {
            ActionTypeEnum.CLICK -> ClickAction(PointConfig(p1x, p1y), durationMs.toLongOrNull() ?: 100)
            ActionTypeEnum.SWIPE -> SwipeAction(PointConfig(p1x, p1y), PointConfig(p2x, p2y), durationMs.toLongOrNull() ?: 100)
            ActionTypeEnum.WAIT -> WaitAction(waitDuration.toLongOrNull() ?: 1000, waitUnit)
            ActionTypeEnum.SET_VARIABLE -> SetVariableAction(varName, varOp, varValue)
            ActionTypeEnum.SET_EVENT -> SetEventAction(eventName, eventOp)
            ActionTypeEnum.SYSTEM_BTN -> SystemBtnAction(sysBtnOp)
            ActionTypeEnum.FOR_LOOP -> ForLoopAction("i", "1", "10", emptyList())
        }
        onSave(action)
    }

    BackHandler {
        onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (initialAction == null) "New Action" else "Edit Action") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = handleSave) {
                        Icon(Icons.Default.Save, contentDescription = "Save")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(8.dp))

            if (initialAction == null) {
                ExposedDropdownMenuBox(expanded = expandedTypeDropdown, onExpandedChange = { expandedTypeDropdown = it }) {
                    OutlinedTextField(
                        value = selectedType.name,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("Action Type") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expandedTypeDropdown) },
                        modifier = Modifier.menuAnchor().fillMaxWidth()
                    )
                    ExposedDropdownMenu(expanded = expandedTypeDropdown, onDismissRequest = { expandedTypeDropdown = false }) {
                        ActionTypeEnum.entries.forEach { t ->
                            DropdownMenuItem(
                                text = { Text(t.name) },
                                onClick = { selectedType = t; expandedTypeDropdown = false }
                            )
                        }
                    }
                }
            }

            when (selectedType) {
                ActionTypeEnum.CLICK -> {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = p1x, onValueChange = { p1x = it }, label = { Text("X") }, modifier = Modifier.weight(1f))
                        OutlinedTextField(value = p1y, onValueChange = { p1y = it }, label = { Text("Y") }, modifier = Modifier.weight(1f))
                    }
                    Button(onClick = onSelectPoint, modifier = Modifier.fillMaxWidth()) {
                        Text("Select Point on Screen")
                    }
                    OutlinedTextField(value = durationMs, onValueChange = { durationMs = it }, label = { Text("Duration (ms)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                }
                ActionTypeEnum.SWIPE -> {
                    Text("Start Point")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = p1x, onValueChange = { p1x = it }, label = { Text("X1") }, modifier = Modifier.weight(1f))
                        OutlinedTextField(value = p1y, onValueChange = { p1y = it }, label = { Text("Y1") }, modifier = Modifier.weight(1f))
                    }
                    Button(onClick = onSelectPoint, modifier = Modifier.fillMaxWidth()) { Text("Select Start Point") }
                    Text("End Point")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(value = p2x, onValueChange = { p2x = it }, label = { Text("X2") }, modifier = Modifier.weight(1f))
                        OutlinedTextField(value = p2y, onValueChange = { p2y = it }, label = { Text("Y2") }, modifier = Modifier.weight(1f))
                    }
                    Button(onClick = onSelectPoint, modifier = Modifier.fillMaxWidth()) { Text("Select End Point") }
                    OutlinedTextField(value = durationMs, onValueChange = { durationMs = it }, label = { Text("Duration (ms)") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                }
                ActionTypeEnum.WAIT -> {
                    OutlinedTextField(value = waitDuration, onValueChange = { waitDuration = it }, label = { Text("Duration") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                }
                ActionTypeEnum.SET_VARIABLE -> {
                    OutlinedTextField(value = varName, onValueChange = { varName = it }, label = { Text("Variable Name") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = varValue, onValueChange = { varValue = it }, label = { Text("Value") }, modifier = Modifier.fillMaxWidth())
                }
                ActionTypeEnum.SET_EVENT -> {
                    OutlinedTextField(value = eventName, onValueChange = { eventName = it }, label = { Text("Event Name") }, modifier = Modifier.fillMaxWidth())
                }
                ActionTypeEnum.SYSTEM_BTN -> {
                    var sysExpanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(expanded = sysExpanded, onExpandedChange = { sysExpanded = it }) {
                        OutlinedTextField(
                            value = sysBtnOp.name,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Button") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = sysExpanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(expanded = sysExpanded, onDismissRequest = { sysExpanded = false }) {
                            SystemOperation.entries.forEach { op ->
                                DropdownMenuItem(text = { Text(op.name) }, onClick = { sysBtnOp = op; sysExpanded = false })
                            }
                        }
                    }
                }
                ActionTypeEnum.FOR_LOOP -> {
                    Text("For Loop configuration not fully supported in simple editor yet.")
                }
            }
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Preview(showBackground = true)
@Composable
fun ActionEditorScreenPreview_Click() {
    MaterialTheme {
        ActionEditorScreen(
            initialAction = ClickAction(PointConfig("500", "btn_y"), 150),
            availableVariables = listOf(Variable("btn_y", VariableType.INT, 200)),
            availableEvents = emptyList(),
            onSave = {},
            onBack = {}
        )
    }
}
