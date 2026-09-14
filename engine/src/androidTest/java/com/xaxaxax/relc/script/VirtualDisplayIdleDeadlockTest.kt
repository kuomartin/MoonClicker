package com.xaxaxax.relc.script

import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * issue #6 的 regression test：`FLAG_OWN_DISPLAY_GROUP` 的虛擬顯示所屬 power group
 * 睡著之後，注入的輸入要能把它喚醒——修法前這裡會卡死（見 [RelcV2Service.wakeDisplayGroupIfOwned]）。
 *
 * 用 `cmd power sleep --display-id` 取代真的等待閒置逾時，讓這個場景在秒級內決定性重現。
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class VirtualDisplayIdleDeadlockTest {

    private val env = Tier1Env()

    @Before
    fun setUp() {
        env.bootstrap()
    }

    @After
    fun tearDown() {
        env.close()
    }

    /**
     * `env.displayDump` 錨定的是 `mDisplayId=`（LogicalDisplay 那一段），不含電源狀態；
     * `state ON/OFF` 在更早的 `DisplayDeviceInfo{"relc-tier1"...}` 那一段裡，只能用建立時的
     * 顯示器名稱去找。
     */
    private fun powerState(): String {
        val all = env.shell("dumpsys display")
        val start = all.indexOf("DisplayDeviceInfo{\"relc-tier1\"")
        check(start >= 0) { "relc-tier1 not found in dumpsys display:\n$all" }
        val stateMatch = Regex("state [A-Z]+, committedState [A-Z]+").find(all, start)
        return stateMatch?.value ?: "(no state field found after offset $start)"
    }

    @Test
    fun injectedTapWakesASleepingOwnDisplayGroup() {
        val displayId = env.displayId

        val precondition = env.displayDump(displayId)
        assertTrue(
            "FLAG_OWN_DISPLAY_GROUP was not granted to this VD, so this run isn't " +
                    "exercising issue #6's precondition:\n$precondition",
            precondition.contains("FLAG_OWN_DISPLAY_GROUP"),
        )

        env.shell("cmd power sleep --display-id $displayId")
        SystemClock.sleep(500)
        assertTrue(
            "display did not go OFF after cmd power sleep:\n${powerState()}",
            powerState().contains("state OFF"),
        )

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
        SystemClock.sleep(1_000)

        assertTrue(
            "display should wake from injected input once wakeDisplayGroupIfOwned runs " +
                    "before injection; if it's still OFF the deadlock from issue #6 is back:\n" +
                    powerState(),
            powerState().contains("state ON"),
        )
    }
}
