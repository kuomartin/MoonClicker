package com.xaxaxax.moonclicker.script

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xaxaxax.moonclicker.script.puppet.PuppetActivity
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * #164：distributor 只在有新影格時才分發，但新掛上的 sink 要立刻收到目前這張。
 * 前者讓靜止畫面不再消耗 CPU 與編碼位元，後者是 `vision` 在腳本開始時拿得到畫面的前提。
 */
@RunWith(AndroidJUnit4::class)
class DistributorDeliveryTest {

    @get:Rule
    val env = Tier1Env()

    /**
     * 修正前，畫面靜止時一秒會收到約 85 張相同的影格。上限不設成 1：有些裝置的虛擬顯示即使
     * 畫面不變也會偶爾送出新緩衝區（Galaxy A21s 約每秒 0.6 張），那些是真的新影格。
     */
    @Test
    fun static_screen_delivers_no_repeated_frames() {
        val displayId = env.stage()

        val frames = env.countFrames(displayId, 1_000)

        assertTrue("a static screen delivered $frames frames in 1 s; repeated frames are being redrawn", frames <= 10)
    }

    @Test
    fun new_sink_on_a_static_screen_gets_the_current_frame() {
        val displayId = env.stage()

        val started = SystemClock.uptimeMillis()
        val arrived = env.awaitFrame(displayId, timeoutMs = 1_000)
        val elapsed = SystemClock.uptimeMillis() - started

        assertTrue("a sink added to a static screen never received a frame", arrived)
        assertTrue("the first frame took ${elapsed}ms", elapsed < 200)
    }

    /** 畫面在變的時候照常分發，鑑別「什麼都不送」的錯誤實作。 */
    @Test
    fun changing_screen_keeps_delivering_frames() {
        val displayId = env.stage()
        val toggler = Thread {
            var on = false
            while (!Thread.currentThread().isInterrupted) {
                PuppetActivity.setVisible(marker = on, glyph = !on)
                on = !on
                try { Thread.sleep(50) } catch (_: InterruptedException) { return@Thread }
            }
        }.apply { start() }
        try {
            val frames = env.countFrames(displayId, 1_000)
            assertTrue("only $frames frames in 1 s while the screen changed every 50 ms", frames >= 8)
        } finally {
            toggler.interrupt()
            toggler.join()
            PuppetActivity.setVisible(marker = true, glyph = true)
        }
    }
}
