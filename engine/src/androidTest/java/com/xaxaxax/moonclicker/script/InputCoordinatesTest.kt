package com.xaxaxax.moonclicker.script

import com.xaxaxax.moonclicker.script.puppet.PuppetRecorder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * 注入的觸控落在瞄準的位置，在每個顯示器方向下都成立。
 *
 * 直接對服務注入而不經腳本：`ScriptHost` 把座標原封不動交給 `multiTouchSwipe`，那一段由
 * Tier 0 的 `LuaInputApiTest` 驗。方向寫反在維度上依然自洽，只有實際點下去、由 puppet 回報
 * 落點才分得出來。
 */
@RunWith(Parameterized::class)
class InputCoordinatesTest(private val orientation: Orientation) {

    @get:Rule
    val env = Tier1Env()

    @Test
    fun an_injected_tap_lands_where_it_was_aimed() {
        val displayId = env.createDisplay()
        env.launchPuppet(displayId)
        env.rotate(displayId, orientation)

        env.assertTapLandsInside(displayId, PuppetRecorder.current.markerRect!!)
    }

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun orientations() = Orientation.entries
    }
}
