package com.xaxaxax.relc.ui.displaydetail

import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Display
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import timber.log.Timber

/**
 * ADR-0014 第 0 步：量測 `(d, v)` 的 transient 是否存在、長度是否符合推論，動手改
 * [VirtualDisplayMirror] 的旋轉依據之前的暫時診斷。只讀 [DisplayManager] 的公開 API 並打
 * log，不影響任何既有行為；量測確認後整檔刪除。
 */
@Composable
fun LogRotationTransient(targetDisplayId: Int) {
    val context = LocalContext.current
    DisposableEffect(context, targetDisplayId) {
        val displayManager = context.getSystemService(DisplayManager::class.java)
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(id: Int) = Unit
            override fun onDisplayRemoved(id: Int) = Unit
            override fun onDisplayChanged(id: Int) {
                val label = when (id) {
                    Display.DEFAULT_DISPLAY -> "d"
                    targetDisplayId -> "v"
                    else -> return
                }
                val rotation = displayManager.getDisplay(id)?.rotation ?: return
                Timber.tag("RotationTransient").i(
                    "%s displayId=%d rotation=%d t=%d",
                    label, id, rotation, SystemClock.elapsedRealtime(),
                )
            }
        }
        displayManager.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        onDispose { displayManager.unregisterDisplayListener(listener) }
    }
}
