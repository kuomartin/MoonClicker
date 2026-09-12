package com.xaxaxax.relc.script

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import com.xaxaxax.relc.lua.LuaNative
import timber.log.Timber

/**
 * 把目標顯示器當前的 rotation 推進 native，好讓 `vision.*` 回傳的是**邏輯座標**。
 *
 * 影格在 surface 空間、`injectMotionEvent` 用的是邏輯空間，兩者差一個旋轉（見 issue #19）。
 * rotation 由公開的 Display API 取得——比對跑在 app 進程，不需要經過 RelcV2Service。
 */
internal class DisplayRotationTracker(context: Context) {

    private val displayManager =
        context.applicationContext.getSystemService(DisplayManager::class.java)

    private var listener: DisplayManager.DisplayListener? = null

    fun start(displayId: Int) {
        stop()
        val manager = displayManager
        if (manager == null) {
            Timber.w("No DisplayManager; vision results will not be rotation-corrected")
            return
        }

        LuaNative.nativeSetDisplayRotation(manager.getDisplay(displayId)?.rotation ?: 0)

        val displayListener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(id: Int) = Unit
            override fun onDisplayRemoved(id: Int) = Unit
            override fun onDisplayChanged(id: Int) {
                if (id != displayId) return
                LuaNative.nativeSetDisplayRotation(manager.getDisplay(displayId)?.rotation ?: 0)
            }
        }
        manager.registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        listener = displayListener
    }

    fun stop() {
        val displayListener = listener ?: return
        listener = null
        displayManager?.unregisterDisplayListener(displayListener)
    }
}
