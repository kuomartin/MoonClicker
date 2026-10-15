package com.xaxaxax.moonclicker.script

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xaxaxax.moonclicker.engine.state.EngineRunState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** `ui.*` 的選擇器與等待（Tier 0）：樹由 [RecordingMoonClickerService.viewTree] 給。真的樹見 `ViewTreeTest`。 */
@RunWith(AndroidJUnit4::class)
class LuaUiApiTest {

    @Test
    fun dump_returns_windows_with_integer_coordinates_and_the_package_on_every_node() = withTree { runner ->
        val outcome = runner.run(
            """
            local windows = ui.dump()
            local ok = windows[2].root.children[1]
            data.set("count", #windows)
            data.set("type", windows[2].type)
            data.set("cx", math.type(ok.cx) .. " " .. ok.cx)
            data.set("package", ok.package)
            data.set("text", ok.text)
            """.trimIndent(),
        )

        assertEquals(outcome.error, EngineRunState.Finished, outcome.runState)
        assertEquals(2.0, outcome.data["count"])
        assertEquals("application", outcome.data["type"])
        assertEquals("integer 140", outcome.data["cx"])
        assertEquals("com.example", outcome.data["package"])
        assertEquals("確定 😀", outcome.data["text"])
        assertEquals(listOf(DISPLAY), runner.recorded.viewTreeDisplays)
    }

    @Test
    fun selectors_match_by_text_id_suffix_class_suffix_and_flags() = withTree { runner ->
        val outcome = runner.run(
            """
            local function id(sel) local n = ui.find(sel); return n and n.id or "nil" end
            data.set("text", id("確定 😀"))
            data.set("id", id({ id = "ok" }))
            data.set("full_id", id({ id = "com.example:id/ok" }))
            data.set("class", id({ class = "Button" }))
            data.set("partial_class", id({ class = "utton" }))
            data.set("contains", id({ text_contains = "確定" }))
            data.set("desc", id({ desc_contains = "los", enabled = false }))
            data.set("package", ui.find({ package = "com.android.systemui" }).class)
            data.set("hidden", id({ id = "hidden" }))
            data.set("hidden_explicit", id({ id = "hidden", visible = false }))
            data.set("all", #ui.find_all({ clickable = true }))
            data.set("none", #ui.find_all("nothing"))
            """.trimIndent(),
        )

        assertEquals(outcome.error, EngineRunState.Finished, outcome.runState)
        assertEquals("com.example:id/ok", outcome.data["text"])
        assertEquals("com.example:id/ok", outcome.data["id"])
        assertEquals("com.example:id/ok", outcome.data["full_id"])
        assertEquals("com.example:id/close", outcome.data["class"])
        assertEquals("nil", outcome.data["partial_class"])
        assertEquals("com.example:id/ok", outcome.data["contains"])
        assertEquals("com.example:id/close", outcome.data["desc"])
        assertEquals("android.widget.FrameLayout", outcome.data["package"])
        assertEquals("nil", outcome.data["hidden"])
        assertEquals("com.example:id/hidden", outcome.data["hidden_explicit"])
        assertEquals(2.0, outcome.data["all"])
        assertEquals(0.0, outcome.data["none"])
    }

    @Test
    fun wait_polls_until_the_timeout() = withTree { runner ->
        val outcome = runner.run(
            """
            data.set("found", ui.wait("確定 😀").id)
            data.set("missing", ui.wait("nothing", 300, 100) == nil)
            """.trimIndent(),
        )

        assertEquals(outcome.error, EngineRunState.Finished, outcome.runState)
        assertEquals("com.example:id/ok", outcome.data["found"])
        assertEquals(true, outcome.data["missing"])
        // found 取 1 次；missing 在 0、100、200、300 ms 各取一次，排程慢時可能多一次。
        assertTrue("polled ${runner.recorded.viewTreeDisplays.size} times", runner.recorded.viewTreeDisplays.size in 4..7)
    }

    @Test
    fun malformed_selectors_fail_with_a_message_that_names_the_problem() = withTree { runner ->
        val outcome = runner.run(
            """
            local function err(f, ...) local ok, e = pcall(f, ...); return tostring(e) end
            data.set("field", err(ui.find, { txt = "OK" }))
            data.set("type", err(ui.find_all, { text = 1 }))
            data.set("flag", err(ui.find, { clickable = "yes" }))
            data.set("selector", err(ui.wait, 3))
            data.set("timeout", err(ui.wait, "OK", -1))
            """.trimIndent(),
        )

        assertEquals(outcome.error, EngineRunState.Finished, outcome.runState)
        fun assertMentions(key: String, fragment: String) {
            val message = outcome.data[key] as String
            assertTrue("$key: expected a message mentioning '$fragment', got: $message", fragment in message)
        }
        assertMentions("field", "ui.find: unknown selector field 'txt'")
        assertMentions("type", "ui.find_all: selector field 'text' must be a string")
        assertMentions("flag", "ui.find: selector field 'clickable' must be a boolean")
        assertMentions("selector", "ui.wait: selector must be a string or a table")
        assertMentions("timeout", "ui.wait: timeout_ms must be a non-negative integer")
    }

    @Test
    fun a_script_that_overrides_globals_does_not_break_ui() = withTree { runner ->
        val outcome = runner.run("ipairs = nil; pairs = nil; string = nil; data.set('id', ui.find('確定 😀').id)")

        assertEquals(outcome.error, EngineRunState.Finished, outcome.runState)
        assertEquals("com.example:id/ok", outcome.data["id"])
    }

    private inline fun withTree(block: (LuaScriptRunner) -> Unit) =
        LuaScriptRunner(displayId = DISPLAY).use { runner ->
            runner.recorded.viewTree = TREE
            block(runner)
        }

    private companion object {
        const val DISPLAY = 7

        fun node(fields: String, children: String = "") =
            """{$fields,"x":0,"y":0,"w":10,"h":10,"cx":5,"cy":5,"clickable":false,"enabled":true,""" +
                    """"visible":true,"children":[$children]}"""

        val TREE = """[
            {"type":"system","package":"com.android.systemui","root":${node(""""class":"android.widget.FrameLayout"""")}},
            {"type":"application","package":"com.example","title":"Example","root":${
                node(
                    """"class":"android.widget.FrameLayout"""",
                    listOf(
                        """{"class":"android.widget.TextView","text":"確定 😀","id":"com.example:id/ok",""" +
                                """"x":100,"y":200,"w":80,"h":40,"cx":140,"cy":220,"clickable":true,"enabled":true,""" +
                                """"visible":true,"children":[]}""",
                        """{"class":"android.widget.TextView","text":"確定 😀","id":"com.example:id/hidden",""" +
                                """"x":100,"y":2000,"w":80,"h":40,"cx":140,"cy":2020,"clickable":true,"enabled":true,""" +
                                """"visible":false,"children":[]}""",
                        """{"class":"android.widget.Button","desc":"Close","id":"com.example:id/close",""" +
                                """"x":0,"y":0,"w":10,"h":10,"cx":5,"cy":5,"clickable":true,"enabled":false,""" +
                                """"visible":true,"children":[]}""",
                    ).joinToString(","),
                )
            }}
        ]"""
    }
}
