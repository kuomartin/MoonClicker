package com.xaxaxax.relc.ui

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.xaxaxax.relc.display.DirectSink
import com.xaxaxax.relc.display.NoOpSink
import com.xaxaxax.relc.display.VirtualDisplayController
import timber.log.Timber

/**
 * 把 SurfaceView 的生命週期跟 VirtualDisplayController 串接起來。
 *
 * Surface 出現  → replaceSink(DirectSink(surface))
 * Surface 消失  → replaceSink(NoOpSink())  ← VD 繼續存在，只是暫時不輸出
 *
 * @param controller 已建立（State.CREATED）的 VirtualDisplayController
 */
@Composable
fun VirtualDisplaySurfaceView(
    controller: VirtualDisplayController,
    modifier: Modifier = Modifier,
) {
    // SurfaceHolder.Callback 的 instance 在 recomposition 間保持穩定
    val callback = remember(controller) {
        object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                Timber.d("VirtualDisplaySurfaceView: surfaceCreated")
                if (controller.state != VirtualDisplayController.State.CREATED) return
                runCatching {
                    controller.replaceSink(DirectSink(holder.surface))
                }.onFailure {
                    Timber.e(it, "replaceSink(DirectSink) failed")
                }
            }

            override fun surfaceChanged(
                holder: SurfaceHolder, format: Int, width: Int, height: Int
            ) {
                // VirtualDisplay 的解析度是建立時決定的，這裡不需要處理
                Timber.d("VirtualDisplaySurfaceView: surfaceChanged ${width}x${height}")
            }

            override fun surfaceDestroyed(holder: SurfaceHolder) {
                Timber.d("VirtualDisplaySurfaceView: surfaceDestroyed")
                if (controller.state != VirtualDisplayController.State.CREATED) return
                runCatching {
                    controller.replaceSink(NoOpSink())
                }.onFailure {
                    Timber.e(it, "replaceSink(NoOpSink) failed")
                }
            }
        }
    }
    Surface {
        AndroidView(
            factory = { context ->
                SurfaceView(context).apply {
                    holder.addCallback(callback)
                }
            },
            modifier = modifier,
        )
    }
}
