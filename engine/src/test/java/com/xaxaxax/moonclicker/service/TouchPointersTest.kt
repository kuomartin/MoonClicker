package com.xaxaxax.moonclicker.service

import android.view.MotionEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TouchPointersTest {

    private data class Sent(val display: Int, val action: Int, val pointers: List<Int>)

    private val scope = TestScope()
    private val sent = mutableListOf<Sent>()
    private val touch = TouchPointers(
        scope = scope,
        uptimeMillis = { scope.testScheduler.currentTime },
        send = { action, _, display, pointers -> sent += Sent(display, action, pointers.map { it.physicalId }) },
    )

    private fun actionsOn(display: Int) = sent.filter { it.display == display }.map { it.action and MotionEvent.ACTION_MASK }

    @Test
    fun samePointerIdOnTwoDisplaysDoesNotCancelEachOther() {
        touch.swipe(1, displayId = 1, points = intArrayOf(0, 0, 100, 100), duration = 160, keep = false)
        scope.runCurrent()
        scope.advanceTimeBy(50)

        touch.swipe(1, displayId = 2, points = intArrayOf(10, 10), duration = 0, keep = false)
        scope.advanceUntilIdle()

        assertEquals(MotionEvent.ACTION_DOWN, actionsOn(1).first())
        assertEquals(MotionEvent.ACTION_UP, actionsOn(1).last())
        assertEquals(List(10) { MotionEvent.ACTION_MOVE }, actionsOn(1).drop(1).dropLast(1))
        assertEquals(listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP), actionsOn(2))
    }

    @Test
    fun samePointerIdOnSameDisplayRestartsTheSwipe() {
        touch.swipe(1, displayId = 1, points = intArrayOf(0, 0, 100, 0), duration = 160, keep = false)
        scope.runCurrent()
        scope.advanceTimeBy(50)

        touch.swipe(1, displayId = 1, points = intArrayOf(30, 30), duration = 0, keep = false)
        scope.advanceUntilIdle()

        // 第二次接手同一根手指：不再 DOWN，最後只 UP 一次。
        val actions = actionsOn(1)
        assertEquals(1, actions.count { it == MotionEvent.ACTION_DOWN })
        assertEquals(1, actions.count { it == MotionEvent.ACTION_UP })
        assertEquals(MotionEvent.ACTION_UP, actions.last())
    }

    @Test
    fun keptPointersOnOneDisplayUsePointerDownForTheSecondFinger() {
        touch.swipe(1, displayId = 1, points = intArrayOf(0, 0), duration = 0, keep = true)
        touch.swipe(2, displayId = 1, points = intArrayOf(5, 5), duration = 0, keep = true)
        touch.swipe(1, displayId = 2, points = intArrayOf(7, 7), duration = 0, keep = true)
        scope.advanceUntilIdle()

        assertEquals(MotionEvent.ACTION_DOWN, sent[0].action)
        assertEquals(MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), sent[2].action)
        assertEquals(listOf(0, 1), sent[2].pointers)
        // 另一個 display 的手指從自己的 physical id 0 開始，是 ACTION_DOWN。
        assertEquals(Sent(2, MotionEvent.ACTION_DOWN, listOf(0)), sent[4])
    }
}
