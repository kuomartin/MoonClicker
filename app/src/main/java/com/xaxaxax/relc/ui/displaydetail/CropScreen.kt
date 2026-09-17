package com.xaxaxax.relc.ui.displaydetail

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.xaxaxax.relc.R
import kotlin.math.roundToInt

/**
 * 裁切畫面：唯一的呼叫端是 [CropSession]，狀態與座標換算全部在那邊，這裡只轉發手勢、畫圖、
 * 收存檔名稱這個純 UI 的輸入。
 */
@Composable
fun CropScreen(session: CropSession, scriptDir: String) {
    val state by session.state.collectAsState()
    val density = LocalDensity.current
    val handleRadius = with(density) { 24.dp.toPx() }
    var showSaveDialog by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        val bitmap = state.bitmap
        if (bitmap == null) {
            Text(
                text = stringResource(R.string.crop_capturing),
                color = Color.White,
                modifier = Modifier.align(Alignment.Center)
            )
            TextButton(
                onClick = { session.cancel() },
                modifier = Modifier.align(Alignment.BottomCenter).padding(32.dp)
            ) {
                Text(stringResource(R.string.common_cancel), color = Color.White)
            }
            return@Box
        }

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { offset -> session.onDragStart(offset.x, offset.y, handleRadius) },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            session.onDrag(dragAmount.x, dragAmount.y)
                        },
                        onDragEnd = { session.onDragEnd() },
                    )
                }
        ) {
            session.onCanvasMeasured(size.width.toInt(), size.height.toInt())
            val viewport = state.viewport

            drawImage(
                image = bitmap.asImageBitmap(),
                dstOffset = IntOffset(viewport.contentLeft.roundToInt(), viewport.contentTop.roundToInt()),
                dstSize = IntSize(viewport.contentWidth.roundToInt(), viewport.contentHeight.roundToInt()),
            )
            drawRect(Color.Black.copy(alpha = 0.5f))

            state.cropRect?.let { rect ->
                val topLeft = Offset(rect.left, rect.top)
                val rectSize = Size(rect.width, rect.height)
                drawRect(color = Color.Transparent, topLeft = topLeft, size = rectSize, blendMode = BlendMode.Clear)
                drawRect(color = Color.Red, topLeft = topLeft, size = rectSize, style = Stroke(width = 4f))
                drawCircle(Color.Red, radius = 10f, center = Offset(rect.left, rect.top))
                drawCircle(Color.Red, radius = 10f, center = Offset(rect.right, rect.top))
                drawCircle(Color.Red, radius = 10f, center = Offset(rect.left, rect.bottom))
                drawCircle(Color.Red, radius = 10f, center = Offset(rect.right, rect.bottom))
            }
        }

        if (showSaveDialog && state.cropRect != null) {
            var templateName by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = { showSaveDialog = false },
                title = { Text(stringResource(R.string.crop_save_template_title)) },
                text = {
                    OutlinedTextField(
                        value = templateName,
                        onValueChange = { templateName = it },
                        label = { Text(stringResource(R.string.crop_template_name)) }
                    )
                },
                confirmButton = {
                    TextButton(onClick = {
                        session.confirmSave(scriptDir, templateName)
                        showSaveDialog = false
                    }) { Text(stringResource(R.string.common_save)) }
                },
                dismissButton = {
                    TextButton(onClick = { showSaveDialog = false }) { Text(stringResource(R.string.common_cancel)) }
                }
            )
        }

        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(32.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            TextButton(
                onClick = { session.cancel() },
                colors = ButtonDefaults.textButtonColors(contentColor = Color.White)
            ) {
                Text(stringResource(R.string.common_cancel))
            }
            if (state.canSave) {
                Button(onClick = { showSaveDialog = true }) {
                    Text(stringResource(R.string.crop_save_button))
                }
            }
        }
    }
}
