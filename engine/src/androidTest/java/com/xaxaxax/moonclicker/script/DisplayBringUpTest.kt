package com.xaxaxax.moonclicker.script

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xaxaxax.moonclicker.MoonClickerService
import com.xaxaxax.moonclicker.script.puppet.PuppetRecorder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** 真的 [MoonClickerService] 建出來的虛擬顯示，本身能不能用：旗標、影格、app 啟動。 */
@RunWith(AndroidJUnit4::class)
class DisplayBringUpTest {

    @get:Rule
    val env = Tier1Env()

    /**
     * 每個特權旗標要跟它自己的權限一致：有權限就該帶上，沒有就不該帶。
     *
     * 斷言一致而非特定值，所以兩種裝置上都有意義。旗標給錯的代價是整台裝置建不出顯示器
     * （見 docs/virtual-display-pitfalls.md），而其餘測試全綠也分辨不出來。
     */
    @Test
    fun each_privileged_flag_follows_its_own_permission() {
        val displayId = env.createDisplay()
        val trusted = env.isGranted(MoonClickerService.ADD_TRUSTED_DISPLAY)
        val alwaysUnlocked = env.isGranted(MoonClickerService.ADD_ALWAYS_UNLOCKED_DISPLAY)
        val dump = env.displayDump(displayId)

        assertEquals(
            "ADD_TRUSTED_DISPLAY granted=$trusted but the display's FLAG_TRUSTED disagrees.\n$dump",
            trusted,
            "FLAG_TRUSTED" in dump,
        )
        // ALWAYS_UNLOCKED 走另一個權限，決定裝置鎖定時虛擬顯示還收不收得到注入的觸控。
        assertEquals(
            "ADD_ALWAYS_UNLOCKED_DISPLAY granted=$alwaysUnlocked (ADD_TRUSTED_DISPLAY=$trusted) " +
                    "but FLAG_ALWAYS_UNLOCKED disagrees.\n$dump",
            trusted && alwaysUnlocked,
            "FLAG_ALWAYS_UNLOCKED" in dump,
        )
    }

    /** GLES 分發器要真的產出影格，`vision.*` 才有東西可看。ATD 映像檔上這是唯一驗得到它的測試。 */
    @Test
    fun the_display_produces_frames() {
        val displayId = env.createDisplay()

        assertTrue("no frame arrived within 10s — the distributor produced nothing", env.awaitFrame(displayId))
    }

    /** 啟動 activity 到次要顯示器；缺 `INTERNAL_SYSTEM_WINDOW` 或顯示器擁有權會被擋掉。 */
    @Test
    fun the_puppet_launches_onto_the_display_and_fills_it() {
        val displayId = env.createDisplay()

        env.launchPuppet(displayId)

        assertEquals(
            "the puppet did not fill the display",
            Tier1Env.WIDTH to Tier1Env.HEIGHT,
            PuppetRecorder.current.contentSize,
        )
    }
}
