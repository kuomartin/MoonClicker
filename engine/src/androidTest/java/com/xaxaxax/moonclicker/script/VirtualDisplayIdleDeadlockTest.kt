package com.xaxaxax.moonclicker.script

import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Display
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * issue #6 的 regression test：`FLAG_OWN_DISPLAY_GROUP` 的虛擬顯示所屬 power group
 * 睡著之後，注入的輸入要能把它喚醒——修法前這裡會卡死（見 [com.xaxaxax.moonclicker.MoonClickerService.wakeDisplayGroupIfOwned]）。
 *
 * 用 `cmd power sleep --display-id` 取代真的等待閒置逾時，讓這個場景在秒級內決定性重現。
 */
@RunWith(AndroidJUnit4::class)
class VirtualDisplayIdleDeadlockTest {

    @get:Rule
    val env = Tier1Env()

    @Test
    fun injectedTapWakesASleepingOwnDisplayGroup() {
        val displayId = env.createDisplay()

        val precondition = env.displayDump(displayId)
        assertTrue(
            "FLAG_OWN_DISPLAY_GROUP was not granted to this VD, so this run isn't " +
                    "exercising issue #6's precondition:\n$precondition",
            precondition.contains("FLAG_OWN_DISPLAY_GROUP"),
        )

        assertTrue(
            "display did not go OFF after cmd power sleep (state ${stateOf(displayId)})",
            awaitDisplayState(displayId, Display.STATE_OFF) {
                env.shell("cmd power sleep --display-id $displayId")
            },
        )

        assertTrue(
            "display should wake from injected input once wakeDisplayGroupIfOwned runs " +
                    "before injection; if it's still OFF the deadlock from issue #6 is back " +
                    "(state ${stateOf(displayId)})",
            awaitDisplayState(displayId, Display.STATE_ON) { injectTap(displayId) },
        )
    }

    private fun injectTap(displayId: Int) {
        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, 540f, 1200f, 0)
        val up = MotionEvent.obtain(down).apply { action = MotionEvent.ACTION_UP }
        try {
            env.service.injectMotionEvent(down, displayId)
            env.service.injectMotionEvent(up, displayId)
        } finally {
            down.recycle()
            up.recycle()
        }
    }

    private val displayManager get() = env.context.getSystemService(DisplayManager::class.java)

    private fun stateOf(displayId: Int): Int? = displayManager.getDisplay(displayId)?.state

    /** 先掛上 listener 再做 [action]，才不會漏掉 [action] 觸發的那次狀態變化。 */
    private fun awaitDisplayState(
        displayId: Int,
        state: Int,
        timeoutMs: Long = 5_000,
        action: () -> Unit,
    ): Boolean {
        val reached = CountDownLatch(1)
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayChanged(id: Int) {
                if (id == displayId && stateOf(id) == state) reached.countDown()
            }
            override fun onDisplayAdded(id: Int) {}
            override fun onDisplayRemoved(id: Int) {}
        }
        val thread = HandlerThread("display-state").apply { start() }
        displayManager.registerDisplayListener(listener, Handler(thread.looper))
        try {
            action()
            return stateOf(displayId) == state || reached.await(timeoutMs, TimeUnit.MILLISECONDS)
        } finally {
            displayManager.unregisterDisplayListener(listener)
            thread.quitSafely()
        }
    }
}
