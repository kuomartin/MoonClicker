package com.xaxaxax.moonclicker.service

import android.hardware.input.InputManagerHidden
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.KeyEventHidden
import android.view.MotionEvent
import android.view.MotionEventHidden
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.hypot
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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

private data class PointerState(
    val logicalId: Int,
    val physicalId: Int, // 0-9
    var x: Float,
    var y: Float,
    var downTime: Long = 0,
)

/** 多點觸控注入：swipe 內插、pointer 狀態機，以及單點的 motion/key event 注入。 */
internal class InputInjector(
    private val inputManager: InputManagerHidden,
    private val virtualDisplayLifecycle: VirtualDisplayLifecycle,
) {
    companion object {
        const val DELAY_MS = 16 // 60fps
    }

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val inputMutex = Mutex()
    private val nextInternalPointerId = AtomicInteger(-100)
    private val swipeJobs = ConcurrentHashMap<Int, kotlinx.coroutines.Job>()
    private val activePointersByDisplay = ConcurrentHashMap<Int, MutableList<PointerState>>()

    fun multiTouchSwipe(
        pointerId: Int,
        displayId: Int,
        points: IntArray,
        duration: Long,
        keep: Boolean,
    ) {
        if (points.size < 2) return

        val logId = if (pointerId == -1) nextInternalPointerId.getAndDecrement() else pointerId

        swipeJobs[logId]?.cancel()

        val job = serviceScope.launch {
            try {
                val state = inputMutex.withLock {
                    val pointers = activePointersByDisplay.getOrPut(displayId) { mutableListOf() }
                    var s = pointers.find { it.logicalId == logId }
                    val now = SystemClock.uptimeMillis()

                    if (s == null) {
                        val usedPhysIds = pointers.map { it.physicalId }.toSet()
                        val physId =
                            (0..9).firstOrNull { it !in usedPhysIds } ?: return@withLock null

                        s = PointerState(
                            logId,
                            physId,
                            points[0].toFloat(),
                            points[1].toFloat(),
                            now
                        )
                        s.downTime = now
                        pointers.add(s)

                        val actionIndex = pointers.indexOf(s)
                        val action = if (pointers.size == 1) MotionEvent.ACTION_DOWN
                        else MotionEvent.ACTION_POINTER_DOWN or (actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)

                        sendMultiTouchMotionEvent(action, now, displayId, pointers)
                    }
                    s
                } ?: return@launch

                if (points.size >= 4 && duration > 0) {
                    performSwipeInterpolation(state, points, duration, displayId)
                } else {
                    inputMutex.withLock {
                        val pointers = activePointersByDisplay[displayId] ?: return@withLock
                        state.x = points[points.size - 2].toFloat()
                        state.y = points[points.size - 1].toFloat()
                        sendMultiTouchMotionEvent(
                            MotionEvent.ACTION_MOVE,
                            SystemClock.uptimeMillis(),
                            displayId,
                            pointers
                        )
                    }
                }

                if (!keep) {
                    inputMutex.withLock {
                        val pointers = activePointersByDisplay[displayId] ?: return@withLock
                        if (!pointers.contains(state)) return@withLock

                        val actionIndex = pointers.indexOf(state)
                        val action = if (pointers.size == 1) MotionEvent.ACTION_UP
                        else MotionEvent.ACTION_POINTER_UP or (actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)

                        sendMultiTouchMotionEvent(
                            action,
                            SystemClock.uptimeMillis(),
                            displayId,
                            pointers
                        )
                        pointers.remove(state)
                    }
                }
            } finally {
                swipeJobs.remove(logId, coroutineContext[kotlinx.coroutines.Job])
            }
        }
        swipeJobs[logId] = job
    }

    private suspend fun performSwipeInterpolation(
        state: PointerState,
        points: IntArray,
        duration: Long,
        displayId: Int,
    ) {
        val numPoints = points.size / 2
        val segLen = mutableListOf<Double>()
        var total = 0.0
        for (i in 0 until numPoints - 1) {
            val x1 = points[i * 2]
            val y1 = points[i * 2 + 1]
            val x2 = points[(i + 1) * 2]
            val y2 = points[(i + 1) * 2 + 1]
            val len = hypot((x2 - x1).toDouble(), (y2 - y1).toDouble())
            segLen.add(len)
            total += len
        }

        val steps = (duration / DELAY_MS).toInt().coerceAtLeast(1)
        for (i in 1..steps) {
            val t = i.toDouble() / steps
            var dist = t * total
            var segIdx = 0
            while (segIdx < segLen.size && dist > segLen[segIdx]) {
                dist -= segLen[segIdx]
                segIdx++
            }

            if (segIdx < numPoints - 1) {
                val xA = points[segIdx * 2]
                val yA = points[segIdx * 2 + 1]
                val xB = points[(segIdx + 1) * 2]
                val yB = points[(segIdx + 1) * 2 + 1]
                val u = if (segLen[segIdx] > 0) dist / segLen[segIdx] else 1.0

                val newX = (xA + (xB - xA) * u).toFloat()
                val newY = (yA + (yB - yA) * u).toFloat()

                inputMutex.withLock {
                    val pointers = activePointersByDisplay[displayId] ?: return@withLock
                    state.x = newX
                    state.y = newY
                    sendMultiTouchMotionEvent(
                        MotionEvent.ACTION_MOVE,
                        SystemClock.uptimeMillis(),
                        displayId,
                        pointers
                    )
                }
            }
            delay(DELAY_MS.milliseconds)
        }
    }

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

    fun getPointers(displayId: Int): IntArray = runBlocking {
        inputMutex.withLock {
            val pointers = activePointersByDisplay[displayId] ?: return@withLock intArrayOf()
            val result = IntArray(pointers.size * 3)
            for (i in pointers.indices) {
                result[i * 3] = pointers[i].logicalId
                result[i * 3 + 1] = pointers[i].x.toInt()
                result[i * 3 + 2] = pointers[i].y.toInt()
            }
            result
        }
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

    fun injectKeyEvent(event: KeyEvent, displayId: Int): Boolean {
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
}
