package com.xaxaxax.relc.input

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import com.xaxaxax.relc.IRelcShizukuService
import timber.log.Timber

/**
 * High-level input controller that uses IRelcShizukuService to inject events.
 */
class InputController(private val service: IRelcShizukuService) {

    fun tap(x: Int, y: Int, displayId: Int) {
        val now = SystemClock.uptimeMillis()
        injectDown(x, y, now, displayId)
        SystemClock.sleep(50)
        injectUp(x, y, SystemClock.uptimeMillis(), displayId)
    }

    fun swipe(x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Long, displayId: Int) {
        val startTime = SystemClock.uptimeMillis()
        injectDown(x1, y1, startTime, displayId)

        val steps = (durationMs / 16).toInt().coerceAtLeast(1)
        for (i in 1..steps) {
            val progress = i.toFloat() / steps
            val x = x1 + (x2 - x1) * progress
            val y = y1 + (y2 - y1) * progress
            SystemClock.sleep(16)
            injectMove(x.toInt(), y.toInt(), SystemClock.uptimeMillis(), displayId)
        }

        injectUp(x2, y2, SystemClock.uptimeMillis(), displayId)
    }

    private fun injectDown(x: Int, y: Int, time: Long, displayId: Int) {
        val event = MotionEvent.obtain(
            time, time, MotionEvent.ACTION_DOWN,
            x.toFloat(), y.toFloat(), 1.0f, 1.0f, 0, 1.0f, 1.0f, 0, 0
        )
        sendEvent(event, displayId)
    }

    private fun injectMove(x: Int, y: Int, time: Long, displayId: Int) {
        val event = MotionEvent.obtain(
            time, time, MotionEvent.ACTION_MOVE,
            x.toFloat(), y.toFloat(), 1.0f, 1.0f, 0, 1.0f, 1.0f, 0, 0
        )
        sendEvent(event, displayId)
    }

    private fun injectUp(x: Int, y: Int, time: Long, displayId: Int) {
        val event = MotionEvent.obtain(
            time, time, MotionEvent.ACTION_UP,
            x.toFloat(), y.toFloat(), 0.0f, 1.0f, 0, 1.0f, 1.0f, 0, 0
        )
        sendEvent(event, displayId)
    }

    private fun sendEvent(event: MotionEvent, displayId: Int) {
        event.source = InputDevice.SOURCE_TOUCHSCREEN
        try {
            service.injectMotionEvent(event, displayId)
        } catch (e: Exception) {
            Timber.e(e, "Failed to inject motion event")
        } finally {
            event.recycle()
        }
    }
}
