package com.xaxaxax.relc.ui.displaydetail

import android.graphics.Matrix
import android.view.InputDevice
import android.view.MotionEvent
import com.xaxaxax.relc.IRelcV2Service
import timber.log.Timber

/**
 * 把鏡像 view 收到的觸控轉發到它所映射的虛擬顯示。
 *
 * 住在 `ui/displaydetail` 而不是 `:engine`，是因為 [transform] 的生產者 [Viewport] 就在
 * 這裡：它同時算出畫面呈現的幾何與觸控的反向映射（view 座標 → 邏輯座標），兩者不可能不
 * 一致。消費者放在生產者隔壁，那個保證才完整。服務端只認邏輯座標，view 被縮放到多大不是
 * 它該知道的事。
 *
 * 三個容易漏、漏了很難查的細節，所以留一個有名字的地方而不是 inline 進 composable：
 *
 *  - **複製事件**。傳進來的那個屬於 view 的派送流程，不能就地改。
 *  - **蓋上 `SOURCE_TOUCHSCREEN`**。注入端會看 source，`pointerInteropFilter` 給的未必是它。
 *  - **`recycle()`**。複製出來的那份要還回去，包含失敗路徑。
 *
 * @param transform view 座標 → 邏輯座標；`null` 或單位矩陣時不做轉換。
 */
fun IRelcV2Service.forwardMirrorTouch(
    event: MotionEvent,
    displayId: Int,
    transform: Matrix? = null,
) {
    val eventCopy = MotionEvent.obtain(event)
    eventCopy.source = InputDevice.SOURCE_TOUCHSCREEN

    if (transform != null && !transform.isIdentity) {
        eventCopy.transform(transform)
    }

    try {
        injectMotionEvent(eventCopy, displayId)
    } catch (e: Exception) {
        Timber.e(e, "Failed to inject motion event")
    } finally {
        eventCopy.recycle()
    }
}
