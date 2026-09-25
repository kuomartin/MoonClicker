package com.xaxaxax.moonclicker.script

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xaxaxax.moonclicker.engine.state.EngineRunState
import com.xaxaxax.moonclicker.script.puppet.PuppetActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** `vision.wait` / `vision.wait_any` 的等待語意：畫面在腳本已經在等的時候才改變。 */
@RunWith(AndroidJUnit4::class)
class VisionWaitTest {

    @get:Rule
    val env = Tier1Env()

    /**
     * 鑑別力來自 `found`——標記在延遲前不在畫面上，比中就蘊含有等到；經過時間那條擋的是
     * 「比中了畫面上別的東西」。反向對照見 [vision_wait_returns_nil_on_timeout_without_erroring]。
     */
    @Test
    fun vision_wait_blocks_until_the_marker_appears() {
        val displayId = stageWithHiddenMarkers()
        PuppetActivity.showAfter(APPEAR_DELAY_MS)

        val started = SystemClock.uptimeMillis()
        val outcome = env.runScript(
            displayId,
            """
            local hit = vision.wait("marker.png", 15000)
            data.set("found", hit ~= nil)
            """.trimIndent(),
        )
        val elapsed = SystemClock.uptimeMillis() - started

        assertEquals(EngineRunState.Finished, outcome.runState)
        if (outcome.data["found"] != true) env.assumeFramesHaveContent(displayId)
        assertEquals("vision.wait never matched even after the marker appeared", true, outcome.data["found"])
        assertTrue(
            "vision.wait returned after ${elapsed}ms but the marker was not drawn until " +
                    "+${APPEAR_DELAY_MS}ms — it matched something already on screen instead of " +
                    "waiting for it to appear",
            elapsed >= APPEAR_DELAY_MS,
        )
    }

    /** `docs/lua-api.md` 承諾逾時回傳 `nil` 而不是拋錯——腳本的錯誤處理建立在這上面。 */
    @Test
    fun vision_wait_returns_nil_on_timeout_without_erroring() {
        val displayId = stageWithHiddenMarkers()

        val started = SystemClock.uptimeMillis()
        val outcome = env.runScript(
            displayId,
            """
            local hit = vision.wait("marker.png", $TIMEOUT_MS)
            data.set("found", hit ~= nil)
            data.set("reached_the_end", true)
            """.trimIndent(),
        )
        val elapsed = SystemClock.uptimeMillis() - started

        assertEquals("timing out must unwind as a normal return, not an error", EngineRunState.Finished, outcome.runState)
        assertEquals(false, outcome.data["found"])
        assertEquals(true, outcome.data["reached_the_end"])
        assertTrue("returned after only ${elapsed}ms for a ${TIMEOUT_MS}ms timeout — it gave up early", elapsed >= TIMEOUT_MS)
    }

    /** 只讓其中一個出現：兩個都出現的話 index 只反映呼叫順序，什麼都沒驗到。 */
    @Test
    fun vision_wait_any_reports_which_one_appeared() {
        val displayId = stageWithHiddenMarkers()
        PuppetActivity.showAfter(APPEAR_DELAY_MS, marker = false, glyph = true)

        val outcome = env.runScript(
            displayId,
            """
            local i, hit = vision.wait_any({
                { image = "marker.png" },
                { image = "glyph.png" },
            }, 15000)
            data.set("index", i)
            data.set("found", hit ~= nil)
            """.trimIndent(),
        )

        assertEquals(EngineRunState.Finished, outcome.runState)
        if (outcome.data["found"] != true) env.assumeFramesHaveContent(displayId)
        assertEquals(true, outcome.data["found"])
        assertEquals("only the glyph was ever drawn, so wait_any must report index 2 (1-based)", 2.0, outcome.data["index"])
    }

    /** 顯示器 + puppet 就緒、標記全部藏起來、畫面已經穩定。 */
    private fun stageWithHiddenMarkers(): Int {
        val displayId = env.createDisplay()
        env.launchPuppet(displayId)
        PuppetActivity.setVisible(marker = false, glyph = false)
        env.awaitSettled(displayId)
        return displayId
    }

    private companion object {
        /** 排程改變畫面的延遲，要明顯大於「第一幀就比中」的時間尺度。 */
        const val APPEAR_DELAY_MS = 2_000L
        const val TIMEOUT_MS = 2_000L
    }
}
