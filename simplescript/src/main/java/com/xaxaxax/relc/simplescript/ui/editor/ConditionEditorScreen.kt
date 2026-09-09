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

enum class ConditionTypeEnum {
    TEMPLATE_MATCH, VARIABLE, TIMER
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConditionEditorScreen(
    initialCondition: Condition?,
    availableVariables: List<Variable>,
    onSave: (Condition) -> Unit,
    onBack: () -> Unit,
    onCaptureArea: () -> Unit = {}
) {
    var selectedType by remember {
        mutableStateOf(
            when (initialCondition) {
                is TemplateMatchCondition -> ConditionTypeEnum.TEMPLATE_MATCH
                is VariableCondition -> ConditionTypeEnum.VARIABLE
                is TimerCondition -> ConditionTypeEnum.TIMER
                null -> ConditionTypeEnum.TEMPLATE_MATCH
            }
        )
    }

    // Template Match State
    var imgPath by remember { mutableStateOf((initialCondition as? TemplateMatchCondition)?.imgPath ?: "") }
    var threshold by remember { mutableStateOf((initialCondition as? TemplateMatchCondition)?.threshold?.toString() ?: "0.9") }

    // Variable Condition State
    var variableA by remember { mutableStateOf((initialCondition as? VariableCondition)?.variableA ?: "") }
    var compareOperator by remember { mutableStateOf((initialCondition as? VariableCondition)?.operator ?: CompareOperator.EQUAL) }
    var target by remember { mutableStateOf((initialCondition as? VariableCondition)?.target ?: "") }

    // Timer State
    var duration by remember { mutableStateOf((initialCondition as? TimerCondition)?.duration?.toString() ?: "1000") }
    var timeUnit by remember { mutableStateOf((initialCondition as? TimerCondition)?.unit ?: TimeUnit.MS) }

    val handleSave = {
        val condition = when (selectedType) {
            ConditionTypeEnum.TEMPLATE_MATCH -> TemplateMatchCondition(
                imgPath = imgPath,
                threshold = threshold.toFloatOrNull() ?: 0.9f
            )
            ConditionTypeEnum.VARIABLE -> VariableCondition(
                variableA = variableA,
                operator = compareOperator,
                target = target
            )
            ConditionTypeEnum.TIMER -> TimerCondition(
                duration = duration.toFloatOrNull() ?: 0f,
                unit = timeUnit
            )
        }
        onSave(condition)
    }

    BackHandler {
        onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (initialCondition == null) "New Condition" else "Edit Condition") },
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

            if (initialCondition == null) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = selectedType == ConditionTypeEnum.TEMPLATE_MATCH,
                        onClick = { selectedType = ConditionTypeEnum.TEMPLATE_MATCH },
                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 3)
                    ) { Text("Image") }
                    SegmentedButton(
                        selected = selectedType == ConditionTypeEnum.VARIABLE,
                        onClick = { selectedType = ConditionTypeEnum.VARIABLE },
                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 3)
                    ) { Text("Var") }
                    SegmentedButton(
                        selected = selectedType == ConditionTypeEnum.TIMER,
                        onClick = { selectedType = ConditionTypeEnum.TIMER },
                        shape = SegmentedButtonDefaults.itemShape(index = 2, count = 3)
                    ) { Text("Timer") }
                }
            }

            when (selectedType) {
                ConditionTypeEnum.TEMPLATE_MATCH -> {
                    OutlinedTextField(
                        value = imgPath,
                        onValueChange = { imgPath = it },
                        label = { Text("Image Path") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(onClick = onCaptureArea, modifier = Modifier.fillMaxWidth()) {
                        Text("Capture Area / Select Image")
                    }
                    OutlinedTextField(
                        value = threshold,
                        onValueChange = { threshold = it },
                        label = { Text("Threshold (0.0 - 1.0)") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                ConditionTypeEnum.VARIABLE -> {
                    OutlinedTextField(
                        value = variableA,
                        onValueChange = { variableA = it },
                        label = { Text("Variable Name") },
                        modifier = Modifier.fillMaxWidth()
                    )

                    var opExpanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(expanded = opExpanded, onExpandedChange = { opExpanded = it }) {
                        OutlinedTextField(
                            value = compareOperator.symbol,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Operator") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = opExpanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(expanded = opExpanded, onDismissRequest = { opExpanded = false }) {
                            CompareOperator.entries.forEach { op ->
                                DropdownMenuItem(
                                    text = { Text(op.symbol) },
                                    onClick = { compareOperator = op; opExpanded = false }
                                )
                            }
                        }
                    }

                    OutlinedTextField(
                        value = target,
                        onValueChange = { target = it },
                        label = { Text("Target Value or Variable") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                ConditionTypeEnum.TIMER -> {
                    OutlinedTextField(
                        value = duration,
                        onValueChange = { duration = it },
                        label = { Text("Duration") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    var unitExpanded by remember { mutableStateOf(false) }
                    ExposedDropdownMenuBox(expanded = unitExpanded, onExpandedChange = { unitExpanded = it }) {
                        OutlinedTextField(
                            value = timeUnit.name,
                            onValueChange = {},
                            readOnly = true,
                            label = { Text("Unit") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = unitExpanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth()
                        )
                        ExposedDropdownMenu(expanded = unitExpanded, onDismissRequest = { unitExpanded = false }) {
                            TimeUnit.entries.forEach { unit ->
                                DropdownMenuItem(
                                    text = { Text(unit.name) },
                                    onClick = { timeUnit = unit; unitExpanded = false }
                                )
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Preview(showBackground = true)
@Composable
fun ConditionEditorScreenPreview_Image() {
    MaterialTheme {
        ConditionEditorScreen(
            initialCondition = TemplateMatchCondition("/sdcard/test.png", threshold = 0.85f),
            availableVariables = emptyList(),
            onSave = {},
            onBack = {}
        )
    }
}
