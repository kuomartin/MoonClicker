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
 * `vision.wait` / `vision.wait_any` / `vision.wait_until` 的等待語意：畫面在腳本已經在等的時候才改變。
 * `vision.find_any`、`vision.find_all` 也在這裡：前者與 `wait_any` 共用多目標的 index 語意，
 * 後者與 `find` 共用最佳命中。
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

    /**
     * fn 回傳的值原樣帶出來，fn 也看得到並改得了外層的 local。鑑別力同 [vision_wait_blocks_until_the_marker_appears]：
     * 標記在延遲前不在畫面上；`calls > 1` 擋的是只呼叫一次就回傳的實作。
     */
    @Test
    fun vision_wait_until_returns_what_fn_returns_once_it_is_truthy() {
        val displayId = stageWithHiddenMarkers()
        PuppetActivity.showAfter(APPEAR_DELAY_MS)

        val started = SystemClock.uptimeMillis()
        val outcome = env.runScript(
            displayId,
            """
            local calls = 0
            local result = vision.wait_until(function()
                calls = calls + 1
                local hit = vision.find("marker.png")
                return hit and { x = hit.x, calls = calls }
            end, 15000)
            data.set("found", result ~= nil)
            data.set("calls", calls)
            data.set("returned_calls", result and result.calls)
            """.trimIndent(),
        )
        val elapsed = SystemClock.uptimeMillis() - started

        assertEquals(EngineRunState.Finished, outcome.runState)
        if (outcome.data["found"] != true) env.assumeFramesHaveContent(displayId)
        assertEquals(true, outcome.data["found"])
        assertEquals("wait_until must return fn's own value", outcome.data["calls"], outcome.data["returned_calls"])
        assertTrue("fn ran only ${outcome.data["calls"]} time(s) — it did not keep polling", (outcome.data["calls"] as Double) > 1)
        assertTrue("returned after ${elapsed}ms, before the marker was drawn at +${APPEAR_DELAY_MS}ms", elapsed >= APPEAR_DELAY_MS)
    }

    /** 逾時回傳 nil 而不是拋錯；畫面靜止時不重複呼叫 fn（每張新影格一次）。 */
    @Test
    fun vision_wait_until_returns_nil_on_timeout() {
        val displayId = stageWithHiddenMarkers()

        val started = SystemClock.uptimeMillis()
        val outcome = env.runScript(
            displayId,
            """
            local calls = 0
            local result = vision.wait_until(function()
                calls = calls + 1
                return false
            end, $TIMEOUT_MS)
            data.set("is_nil", result == nil)
            data.set("calls", calls)
            """.trimIndent(),
        )
        val elapsed = SystemClock.uptimeMillis() - started

        assertEquals(EngineRunState.Finished, outcome.runState)
        assertEquals(true, outcome.data["is_nil"])
        assertTrue("returned after only ${elapsed}ms for a ${TIMEOUT_MS}ms timeout", elapsed >= TIMEOUT_MS)
        // 靜止畫面幾乎不送新影格；每 200 ms 輪詢一次都重呼叫的話會遠超過這個數。
        assertTrue("fn ran ${outcome.data["calls"]} times on a still screen", (outcome.data["calls"] as Double) < 5)
    }

    /**
     * fn 拋的錯照常往外拋；fn 裡呼叫會等新影格的函式要報錯，否則固定的影格讓它永遠等不到。
     * 錯誤之後影格要解除固定：後面的 `vision.wait` 還能比中。
     */
    @Test
    fun vision_wait_until_propagates_errors_and_rejects_nested_waits() {
        val displayId = env.stage()

        val outcome = env.runScript(
            displayId,
            """
            local ok, e = pcall(vision.wait_until, function() error("boom") end, 1000)
            data.set("error", tostring(e))
            ok, e = pcall(vision.wait_until, function() return vision.wait("marker.png", 1000) end, 1000)
            data.set("nested", tostring(e))
            data.set("after", vision.wait("marker.png", 15000) ~= nil)
            """.trimIndent(),
        )

        assertEquals(EngineRunState.Finished, outcome.runState)
        assertTrue("got: ${outcome.data["error"]}", "boom" in outcome.data["error"] as String)
        assertTrue(
            "got: ${outcome.data["nested"]}",
            "vision.wait cannot be called inside vision.wait_until" in outcome.data["nested"] as String,
        )
        if (outcome.data["after"] != true) env.assumeFramesHaveContent(displayId)
        assertEquals("the frame stayed pinned after fn raised", true, outcome.data["after"])
    }

    /** puppet 只畫一個標記：find_all 回傳它一個，且與 find 相同；不在畫面上的回傳空表而不是 nil。 */
    @Test
    fun vision_find_all_returns_every_hit_and_an_empty_table_when_none() {
        val displayId = env.createDisplay()
        env.launchPuppet(displayId)
        PuppetActivity.setVisible(marker = true, glyph = false)
        env.awaitSettled(displayId)

        val outcome = env.runScript(
            displayId,
            """
            vision.wait("marker.png", 15000)
            local all = vision.find_all("marker.png")
            local one = vision.find("marker.png")
            data.set("count", #all)
            data.set("same", one ~= nil and all[1] ~= nil and all[1].x == one.x and all[1].y == one.y)
            local none = vision.find_all("glyph.png")
            data.set("none_is_table", type(none) == "table" and #none == 0)
            """.trimIndent(),
        )

        assertEquals(EngineRunState.Finished, outcome.runState)
        if (outcome.data["count"] != 1.0) env.assumeFramesHaveContent(displayId)
        assertEquals("one marker on screen, overlapping candidates must collapse into one hit", 1.0, outcome.data["count"])
        assertEquals("find_all's first hit must be find's hit", true, outcome.data["same"])
        assertEquals(true, outcome.data["none_is_table"])
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
            if (outcome.data["found"] != true) {
                // 先讓畫面變一次再量：這條擋的正是「新 sink 在靜止畫面上收到的那張是空的」，
                // 在靜止畫面上量會量到同一張空影格，把失敗誤判成 ATD 而跳過。
                PuppetActivity.showAfter(100, marker = true, glyph = false)
                env.assumeFramesHaveContent(displayId)
            }
            assertEquals("attempt ${attempt + 1}: the first vision.find saw no screen", true, outcome.data["found"])
        }
    }

    /**
     * 先走完 [Tier1Env.stage]，確定啟動轉場已經結束，再藏起標記。只等 awaitSettled 的話，
     * 轉場可能在之後才開始，腳本開跑時畫面還在動，「靜止畫面」的斷言就不成立。
     */
    private fun stageWithHiddenMarkers(): Int {
        val displayId = env.stage()
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
