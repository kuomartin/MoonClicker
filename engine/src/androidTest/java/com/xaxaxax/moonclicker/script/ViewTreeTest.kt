package com.xaxaxax.moonclicker.script

import android.app.UiAutomation
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xaxaxax.moonclicker.engine.state.EngineRunState
import com.xaxaxax.moonclicker.script.puppet.PuppetActivity
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `ui.*` 對真的 system_server：服務自己註冊 UiAutomation，與 production 的 Shizuku 進程同一條路。
 *
 * 測試進程的 UiAutomation 要以 `FLAG_DONT_USE_ACCESSIBILITY` 讓出系統唯一的註冊位置，這個旗標是
 * API 33，所以只在 33 以上跑；產品本身支援到 API 30。
 */
@RunWith(AndroidJUnit4::class)
class ViewTreeTest {

    @get:Rule
    val env = Tier1Env(
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) UiAutomation.FLAG_DONT_USE_ACCESSIBILITY else 0,
    )

    @Before
    fun requireApi33() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
    }

    @Test
    fun ui_wait_finds_the_puppet_on_a_virtual_display() {
        val displayId = env.createDisplay()
        env.launchPuppet(displayId)

        val outcome = LuaScriptRunner(service = env.service, displayId = displayId).use {
            it.run(
                """
                local node = ui.wait({ desc = "${PuppetActivity.DESCRIPTION}" })
                data.set("node", node)
                local window = ui.dump()[1]
                data.set("window", window.type .. " " .. window.package)
                """.trimIndent(),
            )
        }

        assertEquals(outcome.error, EngineRunState.Finished, outcome.runState)
        val node = org.json.JSONObject(outcome.data["node"] as String)
        assertEquals(PuppetActivity.DESCRIPTION, node.getString("desc"))
        assertEquals(env.puppetPackage, node.getString("package"))
        assertEquals("android.view.View", node.getString("class"))
        // 內容區在顯示器範圍內，中心點落在框裡。
        val (x, y, w, h) = listOf("x", "y", "w", "h").map(node::getInt)
        assertTrue("bounds $x,$y ${w}x$h", x >= 0 && y >= 0 && w > 0 && h > 0)
        assertTrue("bounds $x,$y ${w}x$h", x + w <= Tier1Env.WIDTH && y + h <= Tier1Env.HEIGHT)
        assertEquals(x + w / 2, node.getInt("cx"))
        assertEquals("application ${env.puppetPackage}", outcome.data["window"])
    }

    @Test
    fun get_view_tree_lists_only_the_requested_display() {
        val displayId = env.createDisplay()
        env.launchPuppet(displayId)

        val onDisplay = JSONArray(env.service.getViewTree(displayId))
        val packages = (0 until onDisplay.length()).map { onDisplay.getJSONObject(it).optString("package") }
        assertTrue("windows on $displayId: $packages", env.puppetPackage in packages)

        val unknown = JSONArray(env.service.getViewTree(Int.MAX_VALUE))
        assertEquals(0, unknown.length())
    }
}
