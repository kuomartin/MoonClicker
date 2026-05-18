package com.xaxaxax.relc.overlay.ui.simple

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.xaxaxax.relc.R
import com.xaxaxax.relc.script.simple.SimpleSwipePayload

@Composable
fun FloatingSwipe(
    index: Int,
    payload: SimpleSwipePayload,
    offsetX: Int, // The x-coordinate of the window's top-left
    offsetY: Int, // The y-coordinate of the window's top-left
    onPanDrag: (dx: Float, dy: Float) -> Unit,
    modifier: Modifier = Modifier
) {
    val swipeStartColor = colorResource(R.color.click_assist_swipe_start)
    val swipeEndColor = colorResource(R.color.click_assist_swipe_end)

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(payload) {
                // We can also allow panning by dragging the space between points if we want,
                // but usually, dragging the markers is more intuitive.
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onPanDrag(dragAmount.x, dragAmount.y)
                }
            }
    ) {
        // Draw lines
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (payload.points.size >= 2) {
                val path = Path().apply {
                    val first = payload.points.first()
                    moveTo((first.first - offsetX).toFloat(), (first.second - offsetY).toFloat())
                    for (i in 1 until payload.points.size) {
                        val p = payload.points[i]
                        lineTo((p.first - offsetX).toFloat(), (p.second - offsetY).toFloat())
                    }
                }
                drawPath(
                    path = path,
                    color = swipeStartColor.copy(alpha = 0.5f),
                    style = Stroke(width = 4.dp.toPx())
                )
            }
        }

        // Draw points (Markers are now just labels that also trigger the pan drag of the whole box)
        payload.points.forEachIndexed { pIndex, point ->
            val isStart = pIndex == 0
            val isEnd = pIndex == payload.points.lastIndex
            if (!isStart && !isEnd) return@forEachIndexed // Only show start and end markers

            val color = if (isStart) swipeStartColor else swipeEndColor
            val label = if (isStart) "${index + 1}s" else "${index + 1}e"

            Box(
                modifier = Modifier
                    .offset {
                        val radius = (32.dp.toPx() / 2).toInt()
                        IntOffset(point.first - offsetX - radius, point.second - offsetY - radius)
                    }
                    .size(32.dp)
                    .background(color.copy(alpha = 0.7f), CircleShape)
                    .border(2.dp, Color.White, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = label,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }
        }
    }
}
