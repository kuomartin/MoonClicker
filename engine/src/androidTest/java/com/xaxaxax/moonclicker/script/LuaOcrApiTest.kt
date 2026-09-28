package com.xaxaxax.moonclicker.script

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xaxaxax.moonclicker.engine.state.EngineRunState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * OCR 相關 Lua API 的參數檢查（Tier 0）。請求先解析才檢查影格來源，所以沒有影格也驗得到。
 * 實際辨識見 `OcrVisionTest`。
 */
@RunWith(AndroidJUnit4::class)
class LuaOcrApiTest {

    @Test
    fun malformed_text_requests_fail_with_a_message_that_names_the_problem() = LuaScriptRunner().use { runner ->
        val outcome = runner.run(
            """
            local function err(f, ...) local ok, e = pcall(f, ...); return tostring(e) end
            data.set("both", err(vision.find, { image = "a.png", text = "OK" }))
            data.set("neither", err(vision.find, { threshold = 0.9 }))
            data.set("empty", err(vision.find, { text = "" }))
            data.set("scale", err(vision.find, { text = "OK", scale = 0.5 }))
            data.set("gray", err(vision.wait, { text = "OK", gray = true }))
            data.set("exact", err(vision.find_any, { { image = "a.png", exact = true } }))
            data.set("read", err(vision.read))
            """.trimIndent(),
        )

        assertEquals(EngineRunState.Finished, outcome.runState)
        fun assertMentions(key: String, fragment: String) {
            val message = outcome.data[key] as String
            assertTrue("$key: expected a message mentioning '$fragment', got: $message", fragment in message)
        }
        assertMentions("both", "exactly one of `image`")
        assertMentions("neither", "exactly one of `image`")
        assertMentions("empty", "must not be empty")
        assertMentions("scale", "`scale` only applies to image requests")
        assertMentions("gray", "`gray` only applies to image requests")
        assertMentions("exact", "`exact` only applies to text requests")
        assertMentions("read", "roi")
    }
}
