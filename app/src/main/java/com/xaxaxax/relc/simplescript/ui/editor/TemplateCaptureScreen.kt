package com.xaxaxax.relc.simplescript.ui.editor

import android.graphics.Rect
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Crop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import kotlin.math.max
import kotlin.math.min

@Composable
fun TemplateCaptureScreen(
    onCapture: (Rect) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    var startOffset by remember { mutableStateOf<Offset?>(null) }
    var endOffset by remember { mutableStateOf<Offset?>(null) }

    val density = LocalDensity.current

    Box(
        modifier = modifier
            .fillMaxSize()
            // In a real app, the background would be a screenshot image.
            // For preview, we just use a dark gradient or color.
            .background(Color.DarkGray)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        startOffset = offset
                        endOffset = offset
                    },
                    onDrag = { change, _ ->
                        change.consume()
                        endOffset = change.position
                    },
                    onDragEnd = {
                        // Optional: Handle drag end
                    }
                )
            }
    ) {
        // Dim overlay with clear hole
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
        ) {
            // Draw semi-transparent dark overlay over the whole screen
            drawRect(color = Color.Black.copy(alpha = 0.6f))

            // If we have a selection box, "cut out" the hole
            if (startOffset != null && endOffset != null) {
                val left = min(startOffset!!.x, endOffset!!.x)
                val top = min(startOffset!!.y, endOffset!!.y)
                val right = max(startOffset!!.x, endOffset!!.x)
                val bottom = max(startOffset!!.y, endOffset!!.y)
                val width = right - left
                val height = bottom - top

                if (width > 0 && height > 0) {
                    // Cut out the hole
                    drawRect(
                        color = Color.Transparent,
                        topLeft = Offset(left, top),
                        size = Size(width, height),
                        blendMode = BlendMode.Clear
                    )

                    // Draw a highly visible border around the crop area
                    drawRect(
                        color = Color.Green,
                        topLeft = Offset(left, top),
                        size = Size(width, height),
                        style = Stroke(width = 2.dp.toPx())
                    )
                }
            }
        }

        // Bottom Action Bar
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.7f))
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(
                onClick = onCancel,
                colors = ButtonDefaults.textButtonColors(contentColor = Color.White)
            ) {
                Icon(Icons.Default.Close, contentDescription = "Cancel", modifier = Modifier.padding(end = 8.dp))
                Text("Cancel")
            }

            Button(
                onClick = {
                    if (startOffset != null && endOffset != null) {
                        val left = min(startOffset!!.x, endOffset!!.x)
                        val top = min(startOffset!!.y, endOffset!!.y)
                        val right = max(startOffset!!.x, endOffset!!.x)
                        val bottom = max(startOffset!!.y, endOffset!!.y)

                        // Convert Compose Float coordinates to Android Rect (Integers)
                        val rect = Rect(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())
                        onCapture(rect)
                    }
                },
                enabled = startOffset != null && endOffset != null &&
                          Math.abs(startOffset!!.x - endOffset!!.x) > 10 &&
                          Math.abs(startOffset!!.y - endOffset!!.y) > 10,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Icon(Icons.Default.Crop, contentDescription = "Capture", modifier = Modifier.padding(end = 8.dp))
                Text("Capture")
            }
        }

        // Instructional Hint (Fades out or stays at top)
        if (startOffset == null) {
            Text(
                text = "Drag to select an area",
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 48.dp)
                    .background(Color.Black.copy(alpha = 0.5f), shape = MaterialTheme.shapes.small)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
    }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 640)
@Composable
fun TemplateCaptureScreenInitialPreview() {
    MaterialTheme {
        TemplateCaptureScreen(
            onCapture = {},
            onCancel = {}
        )
    }
}

// In a real app we'd mock the drag state, but for a simple preview
// we can wrap it to inject state or just show the initial state.
