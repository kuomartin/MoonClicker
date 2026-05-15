package com.xaxaxax.relc.overlay

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import com.xaxaxax.relc.script.simple.SimpleSwipePayload

@Composable
fun RecordingOverlay(
    onRecordingFinished: (SimpleSwipePayload) -> Unit,
    onCancel: () -> Unit
) {
    val points = remember { mutableStateListOf<Offset>() }
    var startTime by remember { mutableLongStateOf(0L) }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.3f))
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    startTime = SystemClock.elapsedRealtime()
                    points.clear()
                    points.add(down.position)

                    var result: SimpleSwipePayload? = null

                    do {
                        val event = awaitPointerEvent()
                        event.changes.forEach {
                            if (it.pressed) {
                                points.add(it.position)
                                it.consume()
                            }
                        }

                        if (event.changes.all { !it.pressed }) {
                            // Up
                            val duration = SystemClock.elapsedRealtime() - startTime
                            if (points.size >= 2) {
                                result = SimpleSwipePayload(
                                    durationMs = duration,
                                    points = points.map { it.x.toInt() to it.y.toInt() }
                                )
                            }
                        }
                    } while (event.changes.any { it.pressed })

                    if (result != null) {
                        onRecordingFinished(result)
                    } else {
                        onCancel()
                    }
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (points.size >= 2) {
                val path = Path().apply {
                    moveTo(points[0].x, points[0].y)
                    for (i in 1 until points.size) {
                        lineTo(points[i].x, points[i].y)
                    }
                }
                drawPath(
                    path = path,
                    color = Color.Red,
                    style = Stroke(width = 12f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            }
        }

        Text(
            text = "錄製中：請在畫面上滑動\n放開即完成",
            color = Color.White,
            modifier = Modifier.align(Alignment.Center),
            style = MaterialTheme.typography.titleMedium,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
    }
}
