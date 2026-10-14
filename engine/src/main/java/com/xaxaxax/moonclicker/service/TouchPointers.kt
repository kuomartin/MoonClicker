package com.xaxaxax.moonclicker.service

import android.view.MotionEvent
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.hypot
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal class PointerState(
    val logicalId: Int,
    val physicalId: Int, // 0-9
    var x: Float,
    var y: Float,
    var downTime: Long = 0,
)

/**
 * 每個 display 各自一組 pointer：swipe 內插與 ACTION_(POINTER_)DOWN/MOVE/UP 的狀態機。
 * 腳本給的 pointer id 只在自己的 display 內有意義，所以 job 與狀態都以 (displayId, pointerId) 為 key。
 * 不碰 MotionEvent 實體，由 [send] 組事件，方便在 JVM 上測。
 */
internal class TouchPointers(
    private val scope: CoroutineScope,
    private val uptimeMillis: () -> Long,
    private val send: (action: Int, eventTime: Long, displayId: Int, pointers: List<PointerState>) -> Unit,
) {
    companion object {
        const val DELAY_MS = 16 // 60fps
    }

    private data class Key(val displayId: Int, val pointerId: Int)

    private val mutex = Mutex()
    private val nextInternalPointerId = AtomicInteger(-100)
    private val swipeJobs = ConcurrentHashMap<Key, Job>()
    private val activePointersByDisplay = ConcurrentHashMap<Int, MutableList<PointerState>>()

    fun swipe(
        pointerId: Int,
        displayId: Int,
        points: IntArray,
        duration: Long,
        keep: Boolean,
    ) {
        if (points.size < 2) return

        val logId = if (pointerId == -1) nextInternalPointerId.getAndDecrement() else pointerId
        val key = Key(displayId, logId)

        swipeJobs[key]?.cancel()

        val job = scope.launch {
            try {
                val state = mutex.withLock {
                    val pointers = activePointersByDisplay.getOrPut(displayId) { mutableListOf() }
                    var s = pointers.find { it.logicalId == logId }
                    val now = uptimeMillis()

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
                        pointers.add(s)

                        val actionIndex = pointers.indexOf(s)
                        val action = if (pointers.size == 1) MotionEvent.ACTION_DOWN
                        else MotionEvent.ACTION_POINTER_DOWN or (actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)

                        send(action, now, displayId, pointers)
                    }
                    s
                } ?: return@launch

                if (points.size >= 4 && duration > 0) {
                    interpolate(state, points, duration, displayId)
                } else {
                    mutex.withLock {
                        val pointers = activePointersByDisplay[displayId] ?: return@withLock
                        state.x = points[points.size - 2].toFloat()
                        state.y = points[points.size - 1].toFloat()
                        send(MotionEvent.ACTION_MOVE, uptimeMillis(), displayId, pointers)
                    }
                }

                if (!keep) {
                    mutex.withLock {
                        val pointers = activePointersByDisplay[displayId] ?: return@withLock
                        if (!pointers.contains(state)) return@withLock

                        val actionIndex = pointers.indexOf(state)
                        val action = if (pointers.size == 1) MotionEvent.ACTION_UP
                        else MotionEvent.ACTION_POINTER_UP or (actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)

                        send(action, uptimeMillis(), displayId, pointers)
                        pointers.remove(state)
                    }
                }
            } finally {
                swipeJobs.remove(key, coroutineContext[Job])
            }
        }
        swipeJobs[key] = job
    }

    private suspend fun interpolate(
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

                mutex.withLock {
                    val pointers = activePointersByDisplay[displayId] ?: return@withLock
                    state.x = newX
                    state.y = newY
                    send(MotionEvent.ACTION_MOVE, uptimeMillis(), displayId, pointers)
                }
            }
            delay(DELAY_MS.milliseconds)
        }
    }

    fun get(displayId: Int): IntArray = runBlocking {
        mutex.withLock {
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
}
