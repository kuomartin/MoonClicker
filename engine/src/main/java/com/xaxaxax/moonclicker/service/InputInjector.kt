package com.xaxaxax.moonclicker.service

import android.hardware.input.InputManagerHidden
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.KeyEventHidden
import android.view.MotionEvent
import android.view.MotionEventHidden
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import dev.rikka.tools.refine.Refine
import timber.log.Timber

private fun MotionEvent.setDisplayId(displayId: Int): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        Refine.unsafeCast<MotionEventHidden>(this).setDisplayId(displayId)
        true
    } else {
        Timber.d("Cannot associate a display id to the input event")
        false
    }

private fun KeyEvent.setDisplayId(displayId: Int): Boolean =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        Refine.unsafeCast<KeyEventHidden>(this).setDisplayId(displayId)
        true
    } else {
        Timber.d("Cannot associate a display id to the input event")
        false
    }

/** 觸控與按鍵注入。pointer 狀態機在 [TouchPointers]，這裡只負責組 MotionEvent 與送進系統。 */
internal class InputInjector(
    private val inputManager: InputManagerHidden,
    private val virtualDisplayLifecycle: VirtualDisplayLifecycle,
) {
    private val touchPointers = TouchPointers(
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
        uptimeMillis = SystemClock::uptimeMillis,
        send = ::sendMultiTouchMotionEvent,
    )

    fun multiTouchSwipe(
        pointerId: Int,
        displayId: Int,
        points: IntArray,
        duration: Long,
        keep: Boolean,
    ) = touchPointers.swipe(pointerId, displayId, points, duration, keep)

    fun getPointers(displayId: Int): IntArray = touchPointers.get(displayId)

    private fun sendMultiTouchMotionEvent(
        action: Int,
        eventTime: Long,
        displayId: Int,
        pointers: List<PointerState>,
    ) {
        val pointerCount = pointers.size
        val properties = Array(pointerCount) { i ->
            MotionEvent.PointerProperties().apply {
                id = pointers[i].physicalId
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coords = Array(pointerCount) { i ->
            MotionEvent.PointerCoords().apply {
                x = pointers[i].x
                y = pointers[i].y
                pressure = 1.0f
                size = 1.0f
            }
        }

        val event = MotionEvent.obtain(
            pointers[0].downTime, eventTime, action, pointerCount,
            properties, coords, 0, 0, 1.0f, 1.0f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0
        )
        injectMotionEvent(event, displayId)
        event.recycle()
    }


    fun injectMotionEvent(event: MotionEvent, displayId: Int): Boolean {
        return try {
            if (displayId != 0 && !event.setDisplayId(displayId))
                return false
            virtualDisplayLifecycle.wakeDisplayGroupIfOwned(displayId)
            inputManager.injectInputEvent(event, 0)
        } catch (t: Throwable) {
            Timber.d(t, "Failed to inject $event on display#$displayId.")
            false
        }
    }

    /**
     * @return 系統拒絕注入時為 false。
     * @throws UnsupportedOperationException API 28 以下無法把按鍵送到主螢幕以外的顯示器。
     * @throws IllegalStateException 注入本身失敗（hidden API、權限），見 [toBinderException]。
     */
    fun injectKeyEvent(event: KeyEvent, displayId: Int): Boolean {
        if (displayId != 0 && !event.setDisplayId(displayId)) {
            throw UnsupportedOperationException("this device can't send key events to display#$displayId")
        }
        return try {
            virtualDisplayLifecycle.wakeDisplayGroupIfOwned(displayId)
            inputManager.injectInputEvent(event, 0)
        } catch (t: Throwable) {
            Timber.w(t, "Failed to inject $event on display#$displayId.")
            throw t.toBinderException("injectKeyEvent")
        }
    }
}
