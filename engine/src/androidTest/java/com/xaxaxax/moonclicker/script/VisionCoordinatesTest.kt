package com.xaxaxax.moonclicker.script

import android.graphics.Rect
import com.xaxaxax.moonclicker.engine.state.EngineRunState
import com.xaxaxax.moonclicker.engine.state.EngineStateRepository
import com.xaxaxax.moonclicker.script.puppet.PuppetRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * `vision.*` 回報的位置與 puppet 實際畫的位置一致，在每個顯示器方向下都成立。
 *
 * 影格在 distributor 就轉成邏輯空間（ADR-0017），所以腳本看到的尺寸、比中的座標、給的 roi
 * 都該直接等於 puppet 的 view 座標。rotation 0 時 distributor 沒有東西要轉，只有轉過的顯示器
 * 才驗得到換算。
 */
@RunWith(Parameterized::class)
class VisionCoordinatesTest(private val orientation: Orientation) {

    @get:Rule
    val env = Tier1Env()

    /** 腳本看到的尺寸由 `getDisplaySurfaceSize` 在啟動當下依 rotation 互換長寬（ADR-0017 的 B）。 */
    @Test
    fun screen_size_matches_the_puppet() {
        val displayId = env.stage(orientation)

        val outcome = env.runScript(
            displayId,
            """
            data.set("w", screen.width)
            data.set("h", screen.height)
            """.trimIndent(),
        )

        assertEquals(EngineRunState.Finished, outcome.runState)
        assertEquals(
            "the script and the puppet disagree about the display size",
            PuppetRecorder.current.contentSize,
            (outcome.data["w"] as Double).toInt() to (outcome.data["h"] as Double).toInt(),
        )
    }

    @Test
    fun the_marker_is_found_where_it_was_drawn() {
        val displayId = env.stage(orientation)

        assertFoundInside(displayId, "marker.png", PuppetRecorder.current.markerRect!!)
    }

    /**
     * 不對稱的 glyph：distributor 把方向轉錯時對稱標記照樣比中，只有它會漏。與標記分開斷言，
     * 對稱的中了、這個沒中，就是影格方向的問題。
     */
    @Test
    fun the_asymmetric_glyph_is_found_where_it_was_drawn() {
        val displayId = env.stage(orientation)

        assertFoundInside(displayId, "glyph.png", PuppetRecorder.current.glyphRect!!)
    }

    /**
     * 框住標記的 roi 比得中、框住 glyph 的 roi 比不中。
     *
     * 兩者放在同一個測試：只驗命中的話，一個完全忽略 roi 的實作也會通過。glyph 在離標記很遠的
     * 另一個象限，所以框住 glyph 的 roi 保證不含標記。
     */
    @Test
    fun a_roi_confines_the_match() {
        val displayId = env.stage(orientation)
        val puppet = PuppetRecorder.current
        val marker = puppet.markerRect!!
        val glyph = puppet.glyphRect!!
        val hitRoi = expandToRoi(marker, puppet.contentSize!!)
        val missRoi = expandToRoi(glyph, puppet.contentSize)

        // vision.wait 而非 vision.find：腳本剛啟動時 VisionMatcher 可能還沒收到第一張影格，
        // find 那時就返回未命中。miss 那句要等滿逾時才能確認真的沒中。
        val outcome = env.runScript(
            displayId,
            """
            local hit = vision.wait({ image = "marker.png", roi = $hitRoi }, 10000)
            data.set("hit_found", hit ~= nil)
            if hit ~= nil then
                data.set("hit_cx", hit.cx)
                data.set("hit_cy", hit.cy)
            end
            local miss = vision.wait({ image = "marker.png", roi = $missRoi }, 3000)
            data.set("miss_found", miss ~= nil)
            """.trimIndent(),
        )

        assertEquals(EngineRunState.Finished, outcome.runState)
        if (outcome.data["hit_found"] != true) env.assumeFramesHaveContent(displayId)
        assertEquals(
            "roi $hitRoi frames the marker at $marker but vision.wait never matched it, at " +
                    "display rotation ${env.displayRotation(displayId)}\n" +
                    "last match = ${EngineStateRepository.state.value.lastVisionResult}\n" +
                    env.logcat("VisionMatcher"),
            true,
            outcome.data["hit_found"],
        )
        val hx = (outcome.data["hit_cx"] as Double).toInt()
        val hy = (outcome.data["hit_cy"] as Double).toInt()
        assertTrue("roi $hitRoi matched at ($hx, $hy), outside the marker at $marker", marker.contains(hx, hy))
        assertEquals(
            "roi $missRoi frames the glyph at $glyph, which excludes the marker at $marker, but " +
                    "vision.wait matched anyway at display rotation ${env.displayRotation(displayId)} " +
                    "— the roi is either being ignored or applied at the wrong offset.\n" +
                    "last match = ${EngineStateRepository.state.value.lastVisionResult}",
            false,
            outcome.data["miss_found"],
        )
    }

    private fun assertFoundInside(displayId: Int, template: String, drawnAt: Rect) {
        val outcome = env.runScript(
            displayId,
            """
            local hit = vision.wait("$template", 20000)
            data.set("found", hit ~= nil)
            if hit ~= nil then
                data.set("cx", hit.cx)
                data.set("cy", hit.cy)
                data.set("confidence", hit.confidence)
            end
            """.trimIndent(),
        )

        assertEquals(EngineRunState.Finished, outcome.runState)
        if (outcome.data["found"] != true) env.assumeFramesHaveContent(displayId)
        assertEquals(
            "vision.wait never matched $template at display rotation " +
                    "${env.displayRotation(displayId)}; puppet ${PuppetRecorder.current}\n" +
                    // miss 時 confidence 一律是 0（低於門檻就 continue），所以這裡讀得到的是
                    // 「哪張圖、找到沒」，不是相關係數。
                    "last match = ${EngineStateRepository.state.value.lastVisionResult}\n" +
                    env.logcat("VisionMatcher", "NativeImageReader", "GlesDistributor", "LuaEngine"),
            true,
            outcome.data["found"],
        )
        val cx = (outcome.data["cx"] as Double).toInt()
        val cy = (outcome.data["cy"] as Double).toInt()
        assertTrue(
            "at display rotation ${env.displayRotation(displayId)} vision put $template at " +
                    "($cx, $cy) but it was drawn at $drawnAt (confidence ${outcome.data["confidence"]})",
            drawnAt.contains(cx, cy),
        )
    }

    /**
     * [rect] 加上邊界、裁進 [content]，再轉成一段 Lua 的 `roi` table literal。
     *
     * 邊界是為了 roi 剛好貼著模板邊緣時，縮放的浮點數截斷可能把寬高削到比模板還小一像素，
     * 讓比對失敗於截斷而不是於 roi 邏輯本身。
     */
    private fun expandToRoi(rect: Rect, content: Pair<Int, Int>): String {
        val (contentWidth, contentHeight) = content
        val left = (rect.left - ROI_MARGIN).coerceAtLeast(0)
        val top = (rect.top - ROI_MARGIN).coerceAtLeast(0)
        val right = (rect.right + ROI_MARGIN).coerceAtMost(contentWidth)
        val bottom = (rect.bottom + ROI_MARGIN).coerceAtMost(contentHeight)
        return "{ x = $left, y = $top, w = ${right - left}, h = ${bottom - top} }"
    }

    companion object {
        private const val ROI_MARGIN = 20

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun orientations() = Orientation.entries
    }
}
