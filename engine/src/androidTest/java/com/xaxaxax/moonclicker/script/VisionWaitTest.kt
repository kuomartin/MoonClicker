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

/**
 * `vision.wait` / `vision.wait_any` 的等待語意：畫面在腳本已經在等的時候才改變。
 * `vision.find_any` 也在這裡：它與 `wait_any` 共用多目標的 index 語意。
 */
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

    /**
     * step 讓比對只在 step 邊界發生：標記在 +2 秒出現，但下一次比對排在 +3 秒，
     * 所以命中不會早於 3 秒。不帶 step 時同一條件約 2 秒就返回，這條斷言因此有鑑別力。
     */
    @Test
    fun vision_wait_with_step_matches_only_on_step_boundaries() {
        val displayId = stageWithHiddenMarkers()
        PuppetActivity.showAfter(APPEAR_DELAY_MS)

        val started = SystemClock.uptimeMillis()
        val outcome = env.runScript(
            displayId,
            """
            local hit = vision.wait("marker.png", 15000, $STEP_MS)
            data.set("found", hit ~= nil)
            """.trimIndent(),
        )
        val elapsed = SystemClock.uptimeMillis() - started

        assertEquals(EngineRunState.Finished, outcome.runState)
        if (outcome.data["found"] != true) env.assumeFramesHaveContent(displayId)
        assertEquals(true, outcome.data["found"])
        assertTrue(
            "vision.wait with step ${STEP_MS}ms returned after ${elapsed}ms — it matched between " +
                    "step boundaries instead of waiting for the next one",
            elapsed >= STEP_MS,
        )
    }

    /** 同 wait_any：只顯示其中一個，index 才有意義；全部都不在畫面上時回傳 nil 而不是拋錯。 */
    @Test
    fun vision_find_any_reports_which_one_is_on_screen() {
        val displayId = env.createDisplay()
        env.launchPuppet(displayId)
        PuppetActivity.setVisible(marker = false, glyph = true)
        env.awaitSettled(displayId)

        val outcome = env.runScript(
            displayId,
            """
            -- find_any 只看當下那一張；先等到 glyph 確定已在影格裡，避免比到第一張影格之前。
            vision.wait("glyph.png", 15000)
            local i, hit = vision.find_any({ { image = "marker.png" }, "glyph.png" })
            data.set("index", i)
            data.set("found", hit ~= nil)
            local miss = vision.find_any({ "marker.png" })
            data.set("miss_is_nil", miss == nil)
            """.trimIndent(),
        )

        assertEquals(EngineRunState.Finished, outcome.runState)
        if (outcome.data["found"] != true) env.assumeFramesHaveContent(displayId)
        assertEquals(true, outcome.data["found"])
        assertEquals("only the glyph is on screen, so find_any must report index 2 (1-based)", 2.0, outcome.data["index"])
        assertEquals(true, outcome.data["miss_is_nil"])
    }

    /** 顯示器 + puppet 就緒、標記全部藏起來、畫面已經穩定。 */
    /**
     * #166：腳本第一行就 `vision.find`，比的是已經在畫面上的標記。影格管線剛接上時要
     * 50–135 ms 才有第一張，引擎不在第一次呼叫時等的話，這裡會拿到空影格而回傳 nil。重複數次，因為沒等時
     * 是否趕上第一張影格取決於執行速度。
     */
    @Test
    fun vision_find_on_the_first_line_sees_what_is_already_on_screen() {
        val displayId = env.stage()

        repeat(3) { attempt ->
            val outcome = env.runScript(displayId, """data.set("found", vision.find("marker.png") ~= nil)""")

            assertEquals(EngineRunState.Finished, outcome.runState)
            assertEquals("attempt ${attempt + 1}: the first vision.find saw no screen", true, outcome.data["found"])
        }
    }

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
        /** 比 [APPEAR_DELAY_MS] 大：第二次比對必須落在標記出現之後。 */
        const val STEP_MS = 3_000L
    }
}
