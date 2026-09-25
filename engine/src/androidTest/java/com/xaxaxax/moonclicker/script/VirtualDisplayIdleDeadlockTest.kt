package com.xaxaxax.moonclicker.script

import android.os.Build
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * issue #6、#121 的 regression test：`FLAG_OWN_DISPLAY_GROUP` 的虛擬顯示所屬 power group
 * 睡著之後，注入的輸入要能把它喚醒——修法前這裡會卡死（見
 * `VirtualDisplayLifecycle.wakeDisplayGroupIfOwned`）。
 *
 * 用 [com.xaxaxax.moonclicker.MoonClickerService.sleepVirtualDisplay] 取代真的等待閒置逾時：兩者都讓這個
 * display group 走 `goToSleep`，這個場景因此在秒級內決定性重現。不用 `cmd power sleep --display-id`——
 * API 34 沒有這個 shell 指令。睡醒看 [PowerGroupLog]，不看 `Display.state`：36 以前 VD 的 state 不反映電源。
 */
@RunWith(AndroidJUnit4::class)
class VirtualDisplayIdleDeadlockTest {

    @get:Rule
    val env = Tier1Env()

    private val powerGroups = PowerGroupLog(env)

    @Test
    fun injectedTapWakesASleepingOwnDisplayGroup() {
        val displayId = env.createDisplay()

        // 前提而非斷言：舊版平台或 shell 沒有對應權限時拿不到這個旗標，也就沒有獨立的 power group。
        val precondition = env.displayDump(displayId)
        assumeTrue(
            "FLAG_OWN_DISPLAY_GROUP was not granted to this VD, so this run isn't " +
                    "exercising issue #6's precondition:\n$precondition",
            precondition.contains("FLAG_OWN_DISPLAY_GROUP"),
        )
        val group = powerGroups.groupOf(displayId)
        assertTrue("display $displayId owns a display group but none shows in dumpsys display", group != null)

        // 前提而非斷言：帶 displayId 的 goToSleep 從 API 34 起才有，更早的版本 sleepVirtualDisplay 一律拒絕。
        // 睡不著就沒有死結可驗；也不能拿掉這一步——group 從沒睡著的話，下面「被叫醒」恆真。
        var accepted = false
        val slept = powerGroups.await(group!!, awake = false) {
            accepted = env.service.sleepVirtualDisplay(displayId)
        }
        assumeTrue(
            "display group $group did not go to sleep on API ${Build.VERSION.SDK_INT} " +
                    "(sleepVirtualDisplay returned $accepted)\n${powerGroups.recent()}",
            slept,
        )

        assertTrue(
            "display group $group should wake from injected input once wakeDisplayGroupIfOwned runs " +
                    "before injection; if it stays asleep the deadlock from issue #6 is back\n" +
                    powerGroups.recent(),
            powerGroups.await(group, awake = true) { injectTap(displayId) },
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
}
