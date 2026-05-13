package com.xaxaxax.relc.input

import android.graphics.Matrix
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import com.xaxaxax.relc.IRelcShizukuService
import timber.log.Timber

/**
 * High-level input controller that uses IRelcShizukuService to inject events.
 */
class InputController(private val service: IRelcShizukuService) {

    /**
     * Helper for script-driven multitouch.
     */
    val script = MultiTouchScriptController(this)

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
        injectMotionEvent(event, displayId)
        event.recycle()
    }

    private fun injectMove(x: Int, y: Int, time: Long, displayId: Int) {
        val event = MotionEvent.obtain(
            time, time, MotionEvent.ACTION_MOVE,
            x.toFloat(), y.toFloat(), 1.0f, 1.0f, 0, 1.0f, 1.0f, 0, 0
        )
        injectMotionEvent(event, displayId)
        event.recycle()
    }

    private fun injectUp(x: Int, y: Int, time: Long, displayId: Int) {
        val event = MotionEvent.obtain(
            time, time, MotionEvent.ACTION_UP,
            x.toFloat(), y.toFloat(), 0.0f, 1.0f, 0, 1.0f, 1.0f, 0, 0
        )
        injectMotionEvent(event, displayId)
        event.recycle()
    }

    /**
     * Injects a MotionEvent into the specified display.
     * Supports optional transformation matrix for interactive forwarding (scaling, translation).
     */
    fun injectMotionEvent(
        event: MotionEvent,
        displayId: Int,
        transform: Matrix? = null
    ) {
        val eventCopy = MotionEvent.obtain(event)
        eventCopy.source = InputDevice.SOURCE_TOUCHSCREEN

        if (transform != null && !transform.isIdentity) {
            eventCopy.transform(transform)
            Timber.d("Touch Transform: (${event.x}, ${event.y}) -> (${eventCopy.x}, ${eventCopy.y}) on Display $displayId")
        }

        try {
            service.injectMotionEvent(eventCopy, displayId)
        } catch (e: Exception) {
            Timber.e(e, "Failed to inject motion event")
        } finally {
            eventCopy.recycle()
        }
    }
}

/**
 * Manages multitouch state for script-driven input.
 * Ensures correct ACTION_POINTER_DOWN/UP and ActionIndex calculation.
 */
class MultiTouchScriptController(private val controller: InputController) {
    private data class Pointer(val id: Int, var x: Float, var y: Float)

    private val activePointers = mutableListOf<Pointer>()
    private var gestureDownTime = 0L

    fun down(pointerId: Int, x: Float, y: Float, displayId: Int) {
        val now = SystemClock.uptimeMillis()
        if (activePointers.any { it.id == pointerId }) return

        activePointers.add(Pointer(pointerId, x, y))
        val actionIndex = activePointers.size - 1

        val action = if (activePointers.size == 1) {
            gestureDownTime = now
            MotionEvent.ACTION_DOWN
        } else {
            MotionEvent.ACTION_POINTER_DOWN or (actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        }
        sendEvent(action, now, displayId)
    }

    fun move(pointerId: Int, x: Float, y: Float, displayId: Int) {
        val pointer = activePointers.find { it.id == pointerId }
        if (pointer != null && (pointer.x != x || pointer.y != y)) {
            pointer.x = x
            pointer.y = y
            sendEvent(MotionEvent.ACTION_MOVE, SystemClock.uptimeMillis(), displayId)
        }
    }

    fun up(pointerId: Int, displayId: Int) {
        val now = SystemClock.uptimeMillis()
        val actionIndex = activePointers.indexOfFirst { it.id == pointerId }
        if (actionIndex == -1) return

        val action = if (activePointers.size == 1) {
            MotionEvent.ACTION_UP
        } else {
            MotionEvent.ACTION_POINTER_UP or (actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        }

        sendEvent(action, now, displayId)
        activePointers.removeAt(actionIndex)
        if (activePointers.isEmpty()) gestureDownTime = 0L
    }

    private fun sendEvent(action: Int, eventTime: Long, displayId: Int) {
        val pointerCount = activePointers.size
        if (pointerCount == 0) return

        val properties = Array(pointerCount) { MotionEvent.PointerProperties() }
        val coords = Array(pointerCount) { MotionEvent.PointerCoords() }

        for (i in 0 until pointerCount) {
            val p = activePointers[i]
            properties[i] = MotionEvent.PointerProperties().apply {
                id = p.id
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
            coords[i] = MotionEvent.PointerCoords().apply {
                this.x = p.x
                this.y = p.y
                pressure = 1.0f
                size = 1.0f
            }
        }

        val event = MotionEvent.obtain(
            gestureDownTime, eventTime, action, pointerCount,
            properties, coords, 0, 0, 1.0f, 1.0f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0
        )

        controller.injectMotionEvent(event, displayId)
        event.recycle()
    }
}
