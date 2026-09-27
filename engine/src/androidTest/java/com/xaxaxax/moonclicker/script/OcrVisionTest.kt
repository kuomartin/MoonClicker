package com.xaxaxax.moonclicker.script

import android.graphics.Rect
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xaxaxax.moonclicker.engine.state.EngineRunState
import com.xaxaxax.moonclicker.ocr.OcrRuntime
import com.xaxaxax.moonclicker.ocr.OcrTestPack
import com.xaxaxax.moonclicker.script.puppet.PuppetActivity
import com.xaxaxax.moonclicker.script.puppet.PuppetRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * `vision.*` 的文字請求與 `vision.read`／`read_lines`，對照 puppet 實際把字畫在哪。
 *
 * 需要 OCR 套件（推送方式見 [OcrTestPack]），沒有就跳過；影格全黑（ATD 映像檔）時比照其他
 * vision 測試跳過。
 */
@RunWith(AndroidJUnit4::class)
class OcrVisionTest {

    @get:Rule
    val env = Tier1Env()

    private val ocr: OcrRuntime by lazy {
        val dir: File? = OcrTestPack.install()
        assumeTrue("push ocr-pack-<abi>.zip into ${OcrTestPack.SHELL_DIR} to run this test", dir != null)
        OcrRuntime(dir!!, threads = 2)
    }

    /**
     * 同一行有好幾個字，找其中一個要點到那個字而不是整行中央。鑑別力來自 `START` 在行尾：
     * 整行中心離它的左緣超過一個字高（容許誤差），退回整行框的實作會紅。
     */
    @Test
    fun find_text_points_at_the_word_within_its_line() {
        val displayId = stage(LINE)
        val outcome = env.runScript(
            displayId,
            """
            local hit = vision.wait({ text = "START" }, 20000)
            data.set("found", hit ~= nil)
            if hit then
                data.set("cx", hit.cx); data.set("cy", hit.cy)
                data.set("line", hit.text); data.set("confidence", hit.confidence)
            end
            """.trimIndent(),
            ocr = ocr,
        )

        assertEquals(EngineRunState.Finished, outcome.runState)
        if (outcome.data["found"] != true) env.assumeFramesHaveContent(displayId)
        assertEquals("vision never found START in '$LINE'", true, outcome.data["found"])
        val drawn = word("START")
        val cx = (outcome.data["cx"] as Double).toInt()
        val cy = (outcome.data["cy"] as Double).toInt()
        // CTC 的字元位置約準到正負一個字寬：中心要落在字的框內，左右各放寬一個字高。
        val tolerance = drawn.height()
        assertTrue(
            "START was drawn at $drawn but the hit centre is ($cx, $cy); line read as '${outcome.data["line"]}'",
            cx in (drawn.left - tolerance)..(drawn.right + tolerance) && cy in drawn.top..drawn.bottom,
        )
        assertEquals(1.0, outcome.data["confidence"] as Double, 0.0)
        val line = PuppetRecorder.current.wordRects.values.reduce { a, b -> Rect(a).apply { union(b) } }
        assertTrue(
            "test lost its discrimination: the line centre ${line.centerX()} is within tolerance of START $drawn",
            line.centerX() < drawn.left - tolerance,
        )
    }

    @Test
    fun read_returns_the_number_inside_a_roi() {
        val displayId = stage(LINE)
        val number = word("12345")
        val roi = with(number) { "{ ${left - 8}, ${top - 8}, ${width() + 16}, ${height() + 16} }" }
        val outcome = env.runScript(
            displayId,
            """
            vision.wait({ text = "START" }, 20000)
            local line = vision.read($roi)
            data.set("text", line and line.text or "<nil>")
            """.trimIndent(),
            ocr = ocr,
        )

        assertEquals(EngineRunState.Finished, outcome.runState)
        if (outcome.data["text"] != "12345") env.assumeFramesHaveContent(displayId)
        assertEquals("12345", outcome.data["text"])
    }

    @Test
    fun read_lines_returns_the_whole_line() {
        val displayId = stage(LINE)
        val outcome = env.runScript(
            displayId,
            """
            vision.wait({ text = "START" }, 20000)
            local lines = vision.read_lines()
            local all = {}
            for _, l in ipairs(lines) do all[#all + 1] = l.text end
            data.set("lines", table.concat(all, "|"))
            """.trimIndent(),
            ocr = ocr,
        )

        assertEquals(EngineRunState.Finished, outcome.runState)
        val lines = outcome.data["lines"] as String
        if ("START" !in lines) env.assumeFramesHaveContent(displayId)
        assertTrue("read_lines returned '$lines'", lines.replace(" ", "").contains(LINE.replace(" ", "")))
    }

    /** 文字延後出現：比中就蘊含有等到；時間斷言擋的是比中了畫面上別的東西。 */
    @Test
    fun wait_text_blocks_until_the_text_appears() {
        val displayId = stage(null)
        PuppetActivity.showTextAfter(APPEAR_DELAY_MS, LINE)

        val started = SystemClock.uptimeMillis()
        val outcome = env.runScript(
            displayId,
            """
            local hit = vision.wait({ text = "START" }, 20000)
            data.set("found", hit ~= nil)
            """.trimIndent(),
            ocr = ocr,
        )
        val elapsed = SystemClock.uptimeMillis() - started

        assertEquals(EngineRunState.Finished, outcome.runState)
        if (outcome.data["found"] != true) env.assumeFramesHaveContent(displayId)
        assertEquals(true, outcome.data["found"])
        assertTrue("returned after ${elapsed}ms, before the text was drawn at +${APPEAR_DELAY_MS}ms", elapsed >= APPEAR_DELAY_MS)
    }

    /** 反向對照：永遠回傳命中的實作會讓上面幾條全綠。 */
    @Test
    fun text_that_is_not_on_screen_is_not_found() {
        val displayId = stage(LINE)
        val outcome = env.runScript(
            displayId,
            """
            vision.wait({ text = "START" }, 20000)
            data.set("missing", vision.find({ text = "EXIT" }) == nil)
            data.set("partial_exact", vision.find({ text = "START", exact = true }) == nil)
            """.trimIndent(),
            ocr = ocr,
        )

        assertEquals(EngineRunState.Finished, outcome.runState)
        assertEquals(true, outcome.data["missing"])
        assertEquals("exact compares the whole line, which also holds OK, 12345 and SCORE", true, outcome.data["partial_exact"])
    }

    @Test
    fun a_text_request_without_the_ocr_pack_fails_with_a_pointer_to_settings() {
        val displayId = stage(LINE)
        val outcome = env.runScript(displayId, """vision.find({ text = "START" })""", ocr = null)

        val error = (outcome.runState as? EngineRunState.Error)?.message
        assertTrue("expected an error about the missing OCR pack, got ${outcome.runState}", error?.contains("OCR is not installed") == true)
    }

    private fun stage(text: String?): Int {
        val displayId = env.createDisplay()
        env.launchPuppet(displayId)
        PuppetActivity.setVisible(marker = false, glyph = false)
        PuppetActivity.setText(text)
        if (text != null) {
            val state = PuppetRecorder.await(5_000) { it.wordRects.isNotEmpty() }
            val right = state?.wordRects?.values?.maxOf { it.right } ?: 0
            val width = state?.contentSize?.first ?: 0
            assertTrue("'$text' runs off the puppet (right edge $right, content width $width)", right < width)
        }
        env.awaitSettled(displayId)
        return displayId
    }

    private fun word(word: String): Rect {
        val rect = PuppetRecorder.current.wordRects[word]
        assertTrue("puppet did not draw '$word'", rect != null)
        return rect!!
    }

    private companion object {
        const val LINE = "OK 12345 SCORE START"
        const val APPEAR_DELAY_MS = 2_000L
    }
}
