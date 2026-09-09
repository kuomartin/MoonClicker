package com.xaxaxax.relc.input

import android.graphics.Matrix
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import com.xaxaxax.relc.IRelcV2Service
import timber.log.Timber
import kotlin.math.abs
import kotlin.math.hypot

/**
 * High-level input controller that uses IRelcV2Service to inject events.
 */
class InputController(private val service: IRelcV2Service) {

    /**
     * Helper for script-driven multitouch.
     */
    val script = MultiTouchScriptController(this)

    /** Single tap with configurable press duration (down → up). */
    fun tap(durationMs: Long, x: Int, y: Int, displayId: Int) {
        val hold = durationMs.coerceAtLeast(0L)
        val now = SystemClock.uptimeMillis()
        injectDown(x, y, now, displayId)
        if (hold > 0L) SystemClock.sleep(hold)
        injectUp(x, y, SystemClock.uptimeMillis(), displayId)
    }

    /**
     * Drag through [points] over [durationMs]. Requires at least two points.
     * Progress along the polyline is uniform in **Euclidean (L2) arc length** per segment.
     */
    fun swipePolyline(durationMs: Long, points: List<Pair<Int, Int>>, displayId: Int) {
        swipePolylineInternal(durationMs, points, displayId, SegmentMetric.L2)
    }

    /**
     * Same geometry as [swipePolyline], but each segment's contribution to arc length is
     * **Manhattan (L1)**: |Δx| + |Δy|. Motion between vertices is still a straight line.
     */
    fun swipePolylineL1(durationMs: Long, points: List<Pair<Int, Int>>, displayId: Int) {
        swipePolylineInternal(durationMs, points, displayId, SegmentMetric.L1)
    }

    private enum class SegmentMetric { L1, L2 }

    private fun swipePolylineInternal(
        durationMs: Long,
        points: List<Pair<Int, Int>>,
        displayId: Int,
        metric: SegmentMetric,
    ) {
        require(points.size >= 2) { "swipe needs at least 2 points" }
        val segLen = ArrayList<Double>(points.lastIndex)
        var total = 0.0
        for (i in 0 until points.lastIndex) {
            val dx = (points[i + 1].first - points[i].first).toDouble()
            val dy = (points[i + 1].second - points[i].second).toDouble()
            val len = when (metric) {
                SegmentMetric.L2 -> hypot(dx, dy)
                SegmentMetric.L1 -> abs(dx) + abs(dy)
            }
            segLen.add(len)
            total += len
        }
        val startTime = SystemClock.uptimeMillis()
        val p0 = points[0]
        injectDown(p0.first, p0.second, startTime, displayId)

        val steps = (durationMs / 16L).toInt().coerceAtLeast(1)
        for (i in 1..steps) {
            val t = i.toFloat() / steps
            val (x, y) = pointOnPolyline(points, segLen, total, t)
            SystemClock.sleep(16)
            injectMove(x, y, SystemClock.uptimeMillis(), displayId)
        }
        val last = points.last()
        injectUp(last.first, last.second, SystemClock.uptimeMillis(), displayId)
    }

    private fun pointOnPolyline(
        points: List<Pair<Int, Int>>,
        segLen: List<Double>,
        totalLen: Double,
        t: Float,
    ): Pair<Int, Int> {
        if (totalLen <= 0.0) return points.last()
        var dist = t.coerceIn(0f, 1f) * totalLen
        if (dist >= totalLen) return points.last()
        var i = 0
        while (i < segLen.size && dist >= segLen[i]) {
            dist -= segLen[i]
            i++
        }
        if (i >= segLen.size) return points.last()
        val pA = points[i]
        val pB = points[i + 1]
        val s = segLen[i]
        val u = if (s <= 0.0) 0.0 else dist / s
        val x = (pA.first + (pB.first - pA.first) * u).toInt()
        val y = (pA.second + (pB.second - pA.second) * u).toInt()
        return x to y
    }

    fun injectPhysicalKey(key: SimplePhysicalKey, displayId: Int) {
        injectKey(key.keyCode, displayId)
    }

    private fun injectKey(keyCode: Int, displayId: Int) {
        val downTime = SystemClock.uptimeMillis()
        val down = KeyEvent(downTime, downTime, KeyEvent.ACTION_DOWN, keyCode, 0)
        val upTime = downTime + 50
        val up = KeyEvent(upTime, upTime, KeyEvent.ACTION_UP, keyCode, 0)
        try {
            service.injectKeyEvent(down, displayId)
            SystemClock.sleep(20)
            service.injectKeyEvent(up, displayId)
        } catch (e: Exception) {
            Timber.e(e, "Failed to inject key event")
        }
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
