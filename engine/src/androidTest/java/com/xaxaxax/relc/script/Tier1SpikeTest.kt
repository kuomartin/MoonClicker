package com.xaxaxax.relc.script

import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xaxaxax.relc.engine.state.EngineRunState
import com.xaxaxax.relc.engine.state.EngineStateRepository
import com.xaxaxax.relc.script.puppet.PuppetMarker
import com.xaxaxax.relc.script.puppet.PuppetRecorder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Tier 1 spike —— 見 `docs/lua-api-testing.md`。
 *
 * 每個 step 回答一個我們目前**不知道答案**的問題，而且刻意各自獨立、依名稱排序執行，
 * 所以一次跑完就能知道是在哪一步斷掉的，而不是只知道「Tier 1 不行」。
 *
 * 這裡不用 `Assume` 跳過：spike 的目的就是把不成立的假設吵出來。等知道哪些 API level
 * 撐得住之後，再把它換成 `@SdkSuppress` 或 assumption。
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class Tier1SpikeTest {

    private lateinit var env: Tier1Env

    @Before
    fun setUp() {
        PuppetRecorder.reset()
        env = Tier1Env()
        env.adoptShellIdentity()
        env.exemptHiddenApis()
        env.startService()
    }

    @After
    fun tearDown() {
        env.close()
    }

    /**
     * Q: instrumentation 進程 adopt 之後，真的拿得到 Shizuku 那些權限嗎？
     *
     * 這是整個 Tier 1 的前提。拿不到的話後面每一步都會以難解讀的方式失敗。
     *
     * `ADD_TRUSTED_DISPLAY` **不在必要清單裡**——實測 Samsung SM-A217F / Android 12 的
     * shell 就沒有它，而 Pixel / API 37 有。所以它是一個要量、不是要求的事實：production
     * 現在也會在被擋下來時退回非 trusted 顯示器（見 `RelcV2Service.createDisplay`）。
     */
    @Test
    fun step1_shell_permissions_are_adoptable() {
        val required = listOf(
            "android.permission.INJECT_EVENTS",
            "android.permission.INTERNAL_SYSTEM_WINDOW",
        )
        fun granted(name: String) =
            env.context.checkSelfPermission(name) == PackageManager.PERMISSION_GRANTED

        val missing = required.filterNot(::granted)
        assertEquals(
            "shell identity did not carry these; Tier 1 cannot work here " +
                    "(trusted displays available = ${granted("android.permission.ADD_TRUSTED_DISPLAY")})",
            emptyList<String>(),
            missing,
        )
    }

    /**
     * Q: `RelcV2Service` 的 `DisplayManager` 是用一個自稱 `com.android.shell` 的 Context
     * 反射出來的。在 Shizuku 進程裡那是實話，在測試進程裡不是——系統會不會擋？
     */
    @Test
    fun step2_the_real_service_creates_a_virtual_display() {
        val displayId = env.createDisplay()

        assertTrue("createVirtualDisplay returned $displayId", displayId > 0)
    }

    /** Q: GLES 分發器在這個進程裡起得來嗎——`vision.*` 有沒有影格可看？ */
    @Test
    fun step3_the_display_produces_frames() {
        val displayId = requireDisplay()
        val thread = HandlerThread("frames").apply { start() }
        val reader = ImageReader.newInstance(env.width, env.height, PixelFormat.RGBA_8888, 2)
        val gotFrame = CountDownLatch(1)
        reader.setOnImageAvailableListener({ r ->
            r.acquireLatestImage()?.close()
            gotFrame.countDown()
        }, Handler(thread.looper))

        val handle = env.service.addVirtualDisplaySurface(displayId, reader.surface)
        try {
            assertTrue("addVirtualDisplaySurface returned $handle", handle >= 0)
            assertTrue(
                "no frame arrived within 10s — the distributor produced nothing",
                gotFrame.await(10, TimeUnit.SECONDS),
            )
        } finally {
            env.service.removeVirtualDisplaySurface(displayId, handle)
            reader.close()
            thread.quitSafely()
        }
    }

    /**
     * Q: 啟動一個 activity 到次要顯示器上——這一步在沒有 `INTERNAL_SYSTEM_WINDOW`
     * 或顯示器擁有權時會被 `ActivityStarter` 擋掉。
     */
    @Test
    fun step4_the_puppet_launches_onto_the_display() {
        val displayId = requireDisplay()

        val launched = env.service.launchInDisplay(env.puppetPackage, displayId)

        assertTrue("launchInDisplay(${env.puppetPackage}, $displayId) returned false", launched)
        assertTrue(
            "the puppet never resumed on display $displayId " +
                    "(resumedOnDisplay=${PuppetRecorder.resumedOnDisplay})",
            PuppetRecorder.awaitReady(displayId),
        )
        assertEquals(
            "the puppet did not fill the display",
            env.width to env.height,
            PuppetRecorder.contentSize,
        )
    }

    /**
     * Q: 注入的觸控會不會真的送到那個顯示器上的視窗？這是 Tier 1 最大的未知——
     * 非 trusted 的虛擬顯示在不同 API level 上行為不一樣。
     */
    @Test
    fun step5_an_injected_tap_reaches_the_puppet() {
        val displayId = requireDisplay()
        env.service.launchInDisplay(env.puppetPackage, displayId)
        assertTrue("the puppet never came up", PuppetRecorder.awaitReady(displayId))

        val target = PuppetRecorder.markerRect!!

        // 重試而不是點一次：要分辨「這台裝置根本不派送」與「只是還沒輪到 puppet 的視窗」
        // （Android 12 的 splash screen 會在 activity 都 resume、畫完之後還壓在上面一陣子）。
        // 撐過 10 秒還一次都沒到，才叫做不會到。
        val down = tapUntilReceived(displayId, target.centerX(), target.centerY())

        assertNotNull(
            "no ACTION_DOWN reached the puppet on display $displayId after 10s of retries\n" +
                    "all touches = ${PuppetRecorder.touches}\n" +
                    "--- windows on this display ---\n" + env.windowsOnDisplay(displayId) +
                    "\n--- logcat ---\n" +
                    env.logcat("RelcV2Service", "InputManager", "InputDispatcher", "InputReader"),
            down,
        )
        assertTrue(
            "tap landed at (${down!!.x}, ${down.y}) on display ${down.displayId}, " +
                    "expected inside ${PuppetRecorder.markerRect} " +
                    "(content ${PuppetRecorder.contentSize})",
            PuppetRecorder.markerRect!!.contains(down.x.toInt(), down.y.toInt()),
        )
    }

    /**
     * 全部串起來：腳本啟動 puppet、用 `vision.wait` 找到畫在上面的標記、點它，
     * 而 puppet 確認被點到的位置就在標記裡。
     *
     * 這個斷言是**自洽**的——vision 說它在哪，input 就打去哪，puppet 回報打到哪。中間任何
     * 一段的座標換算錯了都會露出來，而且不依賴 letterbox、density、insets 的任何假設。
     */
    @Test
    fun step6_a_script_finds_the_marker_and_taps_it() {
        val displayId = requireDisplay()

        val outcome = LuaScriptRunner(
            service = env.service,
            displayId = displayId,
            hasVision = true,
        ).use { runner ->
            runner.run(
                main = """
                    app.launch("${env.puppetPackage}")
                    local hit = vision.wait("marker.png", 20000)
                    data.set("found", hit ~= nil)
                    if hit == nil then return end
                    data.set("cx", hit.cx)
                    data.set("cy", hit.cy)
                    data.set("confidence", hit.confidence)
                    input.tap(hit.cx, hit.cy)
                """.trimIndent(),
                assets = mapOf("marker.png" to PuppetMarker.png()),
                timeoutMs = 40_000,
            )
        }

        assertEquals(EngineRunState.Finished, outcome.runState)
        assertEquals(
            "vision.wait never matched the marker\n" +
                    "puppet ready on ${PuppetRecorder.resumedOnDisplay}, " +
                    "marker at ${PuppetRecorder.markerRect}, content ${PuppetRecorder.contentSize}\n" +
                    // 最後一次比對的實際分數。接近 0 表示影格跟模板毫無關係（多半是空白/黑畫面，
                    // 也就是根本沒截到內容）；0.5~0.8 表示截到了但有縮放或色彩差異。這兩種原因
                    // 要查的地方完全不同，光看「沒中」分不出來。
                    "last match = ${EngineStateRepository.state.value.lastVisionResult}\n" +
                    env.logcat("VisionMatcher", "NativeImageReader", "GlesDistributor", "LuaEngine"),
            true,
            outcome.data["found"],
        )

        val target = PuppetRecorder.markerRect!!
        val cx = (outcome.data["cx"] as Double).toInt()
        val cy = (outcome.data["cy"] as Double).toInt()
        assertTrue(
            "vision put the marker at ($cx, $cy) but it was drawn at $target " +
                    "(confidence ${outcome.data["confidence"]})",
            target.contains(cx, cy),
        )

        val down = PuppetRecorder.awaitTouch { it.action == MotionEvent.ACTION_DOWN }
        assertNotNull("the script's tap never reached the puppet", down)
        assertTrue(
            "the script tapped (${down!!.x}, ${down.y}), outside the marker at $target",
            target.contains(down.x.toInt(), down.y.toInt()),
        )
    }

    /** 反覆注入同一個 tap，直到 puppet 收到 ACTION_DOWN 或 [timeoutMs] 到期。 */
    private fun tapUntilReceived(
        displayId: Int,
        x: Int,
        y: Int,
        timeoutMs: Long = 10_000,
    ): PuppetRecorder.Touch? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            // 每一輪都先清掉：不清的話回傳的會是**最早**擠進來的那一下，而那一下很可能發生在
            // 啟動動畫還沒結束、視窗幾何還在變的時候——座標於是對不上，看起來像座標換算錯了，
            // 其實只是量到了一個過渡狀態。
            PuppetRecorder.touches.clear()
            env.service.multiTouchSwipe(-1, displayId, intArrayOf(x, y, x, y), 50L, false)
            PuppetRecorder.awaitTouch(700) { it.action == MotionEvent.ACTION_DOWN }
                ?.let { return it }
        }
        return null
    }

    private fun requireDisplay(): Int {
        val displayId = env.createDisplay()
        assertTrue("could not create a virtual display (got $displayId) — see step2", displayId > 0)
        return displayId
    }
}
