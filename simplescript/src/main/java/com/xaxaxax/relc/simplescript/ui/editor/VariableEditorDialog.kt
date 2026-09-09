package com.xaxaxax.relc.simplescript.ui.editor

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.xaxaxax.relc.simplescript.domain.model.Variable
import com.xaxaxax.relc.simplescript.domain.model.VariableType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VariableEditorDialog(
    initialVariable: Variable?,
    onSave: (Variable) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initialVariable?.name ?: "") }
    var type by remember { mutableStateOf(initialVariable?.type ?: VariableType.INT) }
    var valueStr by remember { 
        mutableStateOf(
            if (initialVariable != null) {
                if (initialVariable.type == VariableType.INT) initialVariable.initialValue.toInt().toString()
                else initialVariable.initialValue.toFloat().toString()
            } else ""
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initialVariable == null) "New Variable" else "Edit Variable") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Variable Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text("Type", style = MaterialTheme.typography.labelLarge)
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        RadioButton(
                            selected = type == VariableType.INT,
                            onClick = { type = VariableType.INT }
                        )
                        Text("Integer")
                    }
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        RadioButton(
                            selected = type == VariableType.FLOAT,
                            onClick = { type = VariableType.FLOAT }
                        )
                        Text("Float")
                    }
                }

                OutlinedTextField(
                    value = valueStr,
                    onValueChange = { valueStr = it },
                    label = { Text("Initial Value") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val finalValue = if (type == VariableType.INT) valueStr.toIntOrNull() ?: 0 else valueStr.toFloatOrNull() ?: 0f
                    onSave(Variable(name, type, finalValue))
                },
                enabled = name.isNotBlank() && valueStr.isNotBlank()
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Preview(showBackground = true)
@Composable
fun VariableEditorDialogPreview() {
    MaterialTheme {
        VariableEditorDialog(
            initialVariable = null,
            onSave = {},
            onDismiss = {}
        )
    }
}
