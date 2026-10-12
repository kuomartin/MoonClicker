package com.xaxaxax.moonclicker.script

import android.graphics.PixelFormat
import android.media.ImageReader
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xaxaxax.moonclicker.DisplaySink
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 掛著 sink 的虛擬顯示，它的 display group 不會閒置逾時；sink 拿掉之後照常逾時（issue #121）。
 *
 * 要真的等過 `screen_off_timeout`，一條就要半分鐘以上，所以預設跳過，要跑時加
 * `-Pandroid.testInstrumentationRunnerArguments.slow=true`。見 docs/lua-api-testing.md。
 */
@RunWith(AndroidJUnit4::class)
class VirtualDisplayKeepAwakeSlowTest {

    @get:Rule
    val env = Tier1Env()

    private val powerGroups = PowerGroupLog(env)

    @Test
    fun attachedSurfaceKeepsTheOwnDisplayGroupAwake() {
        assumeTrue(
            "slow test; pass -Pandroid.testInstrumentationRunnerArguments.slow=true to run it",
            InstrumentationRegistry.getArguments().getString("slow") == "true",
        )
        val displayId = env.createDisplay()
        assumeTrue(
            "FLAG_OWN_DISPLAY_GROUP was not granted to this VD, so it shares the default group",
            env.displayDump(displayId).contains("FLAG_OWN_DISPLAY_GROUP"),
        )
        val group = powerGroups.groupOf(displayId)
        assertTrue("display $displayId has no display group in dumpsys display", group != null)
        // 前提而非斷言：要了旗標，平台仍可能把它歸進預設 group（Android 17 的分開逾時）。
        assumeTrue(
            "display $displayId was put in the default display group despite FLAG_OWN_DISPLAY_GROUP",
            group != 0,
        )

        val oldTimeout = env.shell("settings get system screen_off_timeout").trim()
        val oldStayOn = env.shell("settings get global stay_on_while_plugged_in").trim()
        val reader = ImageReader.newInstance(Tier1Env.WIDTH, Tier1Env.HEIGHT, PixelFormat.RGBA_8888, 2)
        val sink = DisplaySink(displayId, reader.surface)
        try {
            env.shell("settings put global stay_on_while_plugged_in 0")
            env.shell("settings put system screen_off_timeout $TIMEOUT_MS")
            assertTrue("attachDisplaySink was refused", sink.attach(env.service))

            val since = powerGroups.mark()
            Thread.sleep(TIMEOUT_MS * 2 + 5_000)
            assertFalse(
                "display group $group timed out while a sink was attached\n${powerGroups.recent()}",
                powerGroups.lastTransition(group!!, since) == false,
            )

            assertTrue(
                "display group $group should time out once the last sink is gone\n${powerGroups.recent()}",
                powerGroups.await(group, awake = false, timeoutMs = TIMEOUT_MS * 2 + 5_000) {
                    sink.close()
                },
            )
        } finally {
            sink.close()
            reader.close()
            env.shell("settings put system screen_off_timeout $oldTimeout")
            env.shell("settings put global stay_on_while_plugged_in $oldStayOn")
        }
    }

    private companion object {
        const val TIMEOUT_MS = 10_000L
    }
}
