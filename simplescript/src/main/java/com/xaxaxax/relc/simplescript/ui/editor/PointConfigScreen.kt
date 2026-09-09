package com.xaxaxax.relc.simplescript.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.xaxaxax.relc.simplescript.domain.model.PointConfig
import com.xaxaxax.relc.simplescript.domain.model.Variable
import com.xaxaxax.relc.simplescript.domain.model.VariableType

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PointConfigScreen(
    initialPoint: PointConfig?,
    availableVariables: List<Variable>,
    onSave: (PointConfig) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    // Current point coordinate on screen (null if not placed yet)
    var screenOffset by remember {
        mutableStateOf(
            initialPoint?.let {
                // If it's pure numbers, parse it to show on screen.
                // If it's a variable, we might just put it at 0,0 or center visually since we can't evaluate it now.
                val xFloat = it.x.toFloatOrNull()
                val yFloat = it.y.toFloatOrNull()
                if (xFloat != null && yFloat != null) Offset(xFloat, yFloat) else null
            }
        )
    }

    // Explicit X and Y configurations (string to support variables/formulas)
    var configX by remember { mutableStateOf(initialPoint?.x ?: "") }
    var configY by remember { mutableStateOf(initialPoint?.y ?: "") }

    // Update config strings when screen point moves, IF they are just numbers
    LaunchedEffect(screenOffset) {
        screenOffset?.let {
            // Only overwrite if the user isn't currently using a variable
            if (configX.isEmpty() || configX.toFloatOrNull() != null) {
                configX = it.x.toInt().toString()
            }
            if (configY.isEmpty() || configY.toFloatOrNull() != null) {
                configY = it.y.toInt().toString()
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.DarkGray) // Mocking background screenshot
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { offset ->
                        screenOffset = offset
                    }
                )
            }
            .pointerInput(Unit) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    screenOffset = screenOffset?.plus(dragAmount) ?: change.position
                }
            }
    ) {
        // Draw the point marker
        Canvas(modifier = Modifier.fillMaxSize()) {
            screenOffset?.let {
                // Draw a crosshair
                val lineLength = 15.dp.toPx()
                drawLine(
                    color = Color.Red,
                    start = Offset(it.x - lineLength, it.y),
                    end = Offset(it.x + lineLength, it.y),
                    strokeWidth = 2.dp.toPx()
                )
                drawLine(
                    color = Color.Red,
                    start = Offset(it.x, it.y - lineLength),
                    end = Offset(it.x, it.y + lineLength),
                    strokeWidth = 3.dp.toPx()
                )
                // Draw a circle in the center
                drawCircle(
                    color = Color.White,
                    radius = 4.dp.toPx(),
                    center = it,
                    style = Stroke(width = 2.dp.toPx())
                )
            }
        }

        // Instructional Hint
        if (screenOffset == null) {
            Text(
                text = "Tap anywhere to place a point",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 48.dp)
                    .background(Color.Black.copy(alpha = 0.5f), shape = MaterialTheme.shapes.small)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        // Bottom Configuration Panel
        Card(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(text = "Point Configuration", style = MaterialTheme.typography.titleMedium)

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // X Coordinate Config
                    CoordinateInput(
                        label = "X",
                        value = configX,
                        onValueChange = { configX = it },
                        availableVariables = availableVariables,
                        modifier = Modifier.weight(1f)
                    )

                    // Y Coordinate Config
                    CoordinateInput(
                        label = "Y",
                        value = configY,
                        onValueChange = { configY = it },
                        availableVariables = availableVariables,
                        modifier = Modifier.weight(1f)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onCancel) {
                        Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                        Text("Cancel")
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            if (configX.isNotEmpty() && configY.isNotEmpty()) {
                                onSave(PointConfig(configX, configY))
                            }
                        },
                        enabled = configX.isNotEmpty() && configY.isNotEmpty()
                    ) {
                        Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.padding(end = 4.dp))
                        Text("Save")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoordinateInput(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    availableVariables: List<Variable>,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = !expanded },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            trailingIcon = {
                ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded)
            },
            modifier = Modifier.menuAnchor(),
            singleLine = true
        )
        if (availableVariables.isNotEmpty()) {
            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                availableVariables.forEach { variable ->
                    DropdownMenuItem(
                        text = { Text(variable.name) },
                        onClick = {
                            onValueChange(variable.name)
                            expanded = false
                        }
                    )
                }
            }
        }
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
fun PointConfigScreenPreview() {
    MaterialTheme {
        PointConfigScreen(
            initialPoint = PointConfig("150", "300"),
            availableVariables = listOf(
                Variable("btn_x", VariableType.INT, 100),
                Variable("btn_y", VariableType.INT, 200)
            ),
            onSave = {},
            onCancel = {}
        )
    }
}
