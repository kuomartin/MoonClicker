package com.xaxaxax.relc.ui.displaydetail

import android.annotation.SuppressLint
import android.graphics.Matrix
import android.graphics.RectF
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.viewinterop.AndroidView
import com.xaxaxax.relc.core.DisplayConfig
import com.xaxaxax.relc.display.DirectSink
import com.xaxaxax.relc.display.NoOpSink
import com.xaxaxax.relc.display.VirtualDisplayController
import com.xaxaxax.relc.input.InputController
import timber.log.Timber

/**
 * 把 SurfaceView 的生命週期跟 VirtualDisplayController 串接起來。
 * 同時攔截觸控事件並透過 InputController 轉發。
 *
 * 使用 Matrix 處理座標變換（包含縮放與平移），確保座標對應精確。
 *
 * @param controller 已建立（State.CREATED）的 VirtualDisplayController
 * @param inputController 用於注入事件的控制器
 * @param config 虛擬螢幕的配置，用於計算座標縮放
 */
@SuppressLint("ClickableViewAccessibility")
@Composable
fun VirtualDisplaySurfaceView(
    controller: VirtualDisplayController,
    inputController: InputController,
    config: DisplayConfig,
    isReadOnly: Boolean,
    modifier: Modifier = Modifier,
) {
    // 預先準備好矩陣與座標矩形，避免在 onTouch 中頻繁分配記憶體
    val touchMatrix = remember { Matrix() }
    val srcRect = remember { RectF() }
    val dstRect = remember { RectF(0f, 0f, config.width.toFloat(), config.height.toFloat()) }

    // SurfaceHolder.Callback 的 instance 在 recomposition 間保持穩定

    val callback = remember(controller) {
        object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                Timber.d("VirtualDisplaySurfaceView: surfaceCreated")
                if (controller.state != VirtualDisplayController.State.CREATED) return
                runCatching {
                    controller.setPreviewSink(DirectSink(holder.surface))
                }.onFailure {
                    Timber.e(it, "replaceSink(DirectSink) failed")
                }
            }

            override fun surfaceChanged(
                holder: SurfaceHolder, format: Int, width: Int, height: Int
            ) {
                Timber.d("VirtualDisplaySurfaceView: surfaceChanged ${width}x${height}")
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                Timber.d("VirtualDisplaySurfaceView: surfaceDestroyed")
                if (controller.state != VirtualDisplayController.State.CREATED) return
                runCatching {
                    controller.setPreviewSink(NoOpSink)
                }.onFailure {
                    Timber.e(it, "replaceSink(NoOpSink) failed")
                }
            }
        }
    }

    Surface(modifier) {
        val dpWidth = with(LocalDensity.current) { config.width.toDp() }
        val dpHeight = with(LocalDensity.current) { config.height.toDp() }
        AndroidView(
            modifier = Modifier
                .width(dpWidth)
                .height(dpHeight),
            factory = { context ->
                SurfaceView(context).apply {
                    // 讓 Surface buffer 與 VirtualDisplay 邏輯解析度一致，避免 VD→較小 consumer
                    // 時系統縮放路徑出現類似 crop 的裁切；畫面改由 HWC 縮進 View 區域。
                    holder.setFixedSize(config.width, config.height)
                    holder.addCallback(callback)
                    srcRect.set(this.x, this.y, this.width.toFloat(), this.height.toFloat())
                    touchMatrix.setRectToRect(srcRect, dstRect, Matrix.ScaleToFit.FILL)

                    setOnTouchListener { _, event ->
                        if (isReadOnly) return@setOnTouchListener false
                        val displayId = controller.displayId
                        if (displayId != -1) {
                            inputController.injectMotionEvent(event, displayId, touchMatrix)
                        }
                        true
                    }
                }
            },
            update = { view ->
                view.setOnTouchListener { _, event ->
                    if (isReadOnly) return@setOnTouchListener false
                    val displayId = controller.displayId
                    if (displayId != -1) {
                        inputController.injectMotionEvent(event, displayId, touchMatrix)
                    }
                    true
                }
            }
        )
    }
}
