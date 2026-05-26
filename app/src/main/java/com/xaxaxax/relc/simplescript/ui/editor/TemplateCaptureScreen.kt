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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// Represents which part of the bounding box is being dragged
enum class DragTarget {
    NONE, CENTER, TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT, TOP, BOTTOM, LEFT, RIGHT
}

@Composable
fun TemplateCaptureScreen(
    onCapture: (Rect) -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    // We store the actual current rectangle
    var rectLeft by remember { mutableStateOf(-1f) }
    var rectTop by remember { mutableStateOf(-1f) }
    var rectRight by remember { mutableStateOf(-1f) }
    var rectBottom by remember { mutableStateOf(-1f) }

    var dragTarget by remember { mutableStateOf(DragTarget.NONE) }
    val touchSlop = with(LocalDensity.current) { 24.dp.toPx() } // Generous hit area for corners/edges

    Box(
        modifier = modifier
            .fillMaxSize()
            // In a real app, the background would be a screenshot image.
            .background(Color.DarkGray)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset ->
                        if (rectLeft == -1f) {
                            // Initial draw
                            rectLeft = offset.x
                            rectTop = offset.y
                            rectRight = offset.x
                            rectBottom = offset.y
                            dragTarget = DragTarget.BOTTOM_RIGHT // Conceptually pulling to the bottom right
                        } else {
                            // Determine what we are dragging based on proximity
                            val l = min(rectLeft, rectRight)
                            val r = max(rectLeft, rectRight)
                            val t = min(rectTop, rectBottom)
                            val b = max(rectTop, rectBottom)

                            val nearLeft = abs(offset.x - l) < touchSlop
                            val nearRight = abs(offset.x - r) < touchSlop
                            val nearTop = abs(offset.y - t) < touchSlop
                            val nearBottom = abs(offset.y - b) < touchSlop
                            val inside = offset.x in (l + touchSlop)..(r - touchSlop) && offset.y in (t + touchSlop)..(b - touchSlop)

                            dragTarget = when {
                                nearTop && nearLeft -> DragTarget.TOP_LEFT
                                nearTop && nearRight -> DragTarget.TOP_RIGHT
                                nearBottom && nearLeft -> DragTarget.BOTTOM_LEFT
                                nearBottom && nearRight -> DragTarget.BOTTOM_RIGHT
                                nearTop -> DragTarget.TOP
                                nearBottom -> DragTarget.BOTTOM
                                nearLeft -> DragTarget.LEFT
                                nearRight -> DragTarget.RIGHT
                                inside -> DragTarget.CENTER
                                else -> {
                                    // Start a new box if clicking way outside
                                    rectLeft = offset.x
                                    rectTop = offset.y
                                    rectRight = offset.x
                                    rectBottom = offset.y
                                    DragTarget.BOTTOM_RIGHT
                                }
                            }
                        }
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        when (dragTarget) {
                            DragTarget.CENTER -> {
                                rectLeft += dragAmount.x
                                rectRight += dragAmount.x
                                rectTop += dragAmount.y
                                rectBottom += dragAmount.y
                            }
                            DragTarget.TOP_LEFT -> {
                                rectLeft += dragAmount.x
                                rectTop += dragAmount.y
                            }
                            DragTarget.TOP_RIGHT -> {
                                rectRight += dragAmount.x
                                rectTop += dragAmount.y
                            }
                            DragTarget.BOTTOM_LEFT -> {
                                rectLeft += dragAmount.x
                                rectBottom += dragAmount.y
                            }
                            DragTarget.BOTTOM_RIGHT -> {
                                rectRight += dragAmount.x
                                rectBottom += dragAmount.y
                            }
                            DragTarget.TOP -> rectTop += dragAmount.y
                            DragTarget.BOTTOM -> rectBottom += dragAmount.y
                            DragTarget.LEFT -> rectLeft += dragAmount.x
                            DragTarget.RIGHT -> rectRight += dragAmount.x
                            DragTarget.NONE -> {}
                        }
                    },
                    onDragEnd = {
                        dragTarget = DragTarget.NONE
                        // Normalize coordinates so left < right and top < bottom
                        if (rectLeft != -1f) {
                            val l = min(rectLeft, rectRight)
                            val r = max(rectLeft, rectRight)
                            val t = min(rectTop, rectBottom)
                            val b = max(rectTop, rectBottom)
                            rectLeft = l
                            rectRight = r
                            rectTop = t
                            rectBottom = b
                        }
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
            drawRect(color = Color.Black.copy(alpha = 0.6f))

            if (rectLeft != -1f) {
                val left = min(rectLeft, rectRight)
                val top = min(rectTop, rectBottom)
                val right = max(rectLeft, rectRight)
                val bottom = max(rectTop, rectBottom)
                val width = right - left
                val height = bottom - top

                if (width > 0 && height > 0) {
                    drawRect(
                        color = Color.Transparent,
                        topLeft = Offset(left, top),
                        size = Size(width, height),
                        blendMode = BlendMode.Clear
                    )
                    
                    drawRect(
                        color = Color.Green,
                        topLeft = Offset(left, top),
                        size = Size(width, height),
                        style = Stroke(width = 2.dp.toPx())
                    )
                    
                    // Draw corner handles
                    val handleRadius = 6.dp.toPx()
                    drawCircle(Color.White, radius = handleRadius, center = Offset(left, top))
                    drawCircle(Color.White, radius = handleRadius, center = Offset(right, top))
                    drawCircle(Color.White, radius = handleRadius, center = Offset(left, bottom))
                    drawCircle(Color.White, radius = handleRadius, center = Offset(right, bottom))
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
                    if (rectLeft != -1f) {
                        val left = min(rectLeft, rectRight)
                        val top = min(rectTop, rectBottom)
                        val right = max(rectLeft, rectRight)
                        val bottom = max(rectTop, rectBottom)
                        val rect = Rect(left.toInt(), top.toInt(), right.toInt(), bottom.toInt())
                        onCapture(rect)
                    }
                },
                enabled = rectLeft != -1f && abs(rectLeft - rectRight) > 10 && abs(rectTop - rectBottom) > 10,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Icon(Icons.Default.Crop, contentDescription = "Capture", modifier = Modifier.padding(end = 8.dp))
                Text("Capture")
            }
        }
        
        // Instructional Hint
        if (rectLeft == -1f) {
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
