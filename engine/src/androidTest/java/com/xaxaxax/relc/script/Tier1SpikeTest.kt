package com.xaxaxax.relc.script

import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.graphics.PixelFormat
import android.graphics.Rect
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xaxaxax.relc.RelcV2Service
import com.xaxaxax.relc.engine.state.EngineRunState
import com.xaxaxax.relc.engine.state.EngineStateRepository
import com.xaxaxax.relc.script.puppet.PuppetActivity
import com.xaxaxax.relc.script.puppet.PuppetControl
import com.xaxaxax.relc.script.puppet.PuppetGlyph
import com.xaxaxax.relc.script.puppet.PuppetMarker
import com.xaxaxax.relc.script.puppet.PuppetRecorder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Tier 1 —— 見 `docs/lua-api-testing.md`。
 *
 * 每個 step 各自獨立、依名稱排序執行，失敗時直接指出是哪一環斷掉。
 * `Assume` 只用於環境不提供被測物的情況（如 ATD 映像檔無圖形堆疊）。
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class Tier1SpikeTest {

    private lateinit var env: Tier1Env

    @Before
    fun setUp() {
        // Tier 1 的下限是 API 29（adoptShellPermissionIdentity）；這是測試框架的限制，
        // 不是產品的——production 跑在 Shizuku 的 shell 進程裡，minSdk 仍是 27。
        assumeTrue(
            "UiAutomation.adoptShellPermissionIdentity does not exist below API 29, so this " +
                    "harness cannot stand in for Shizuku here. Says nothing about whether ReLC " +
                    "works there — Tier 0 still covers the Lua layer.",
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q,
        )
        PuppetRecorder.reset()
        PuppetControl.reset()
        env = Tier1Env()
        env.adoptShellIdentity()
        env.wakeAndUnlock()
        env.exemptHiddenApis()
        env.startService()
    }

    @After
    fun tearDown() {
        // setUp 的 assumption 不成立時 env 沒建起來，但 @After 照樣會跑。
        if (::env.isInitialized) env.close()
    }

    /**
     * Tier 1 的前提：instrumentation 進程 adopt 之後拿得到 Shizuku 那些權限。
     *
     * `ADD_TRUSTED_DISPLAY` 不在必要清單裡——不是每台裝置的 shell 都有，缺了就退回
     * 非 trusted 顯示器（`RelcV2Service.createDisplay`）。
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

    /** `RelcV2Service` 用自稱 `com.android.shell` 的 Context 建顯示器，在測試進程裡也要成立。 */
    @Test
    fun step2_the_real_service_creates_a_virtual_display() {
        val displayId = env.createDisplay()

        assertTrue("createVirtualDisplay returned $displayId", displayId > 0)
    }

    /**
     * 每個特權旗標要跟它自己的權限一致：有權限就該帶上，沒有就不該帶。
     *
     * 斷言一致而非特定值，所以兩種裝置上都有意義。旗標給錯的代價是整台裝置建不出顯示器
     * （見 docs/virtual-display-pitfalls.md），而其餘測試全綠也分辨不出來。
     */
    @Test
    fun step2b_each_privileged_flag_follows_its_own_permission() {
        val displayId = requireDisplay()
        val granted = env.context.checkSelfPermission(RelcV2Service.ADD_TRUSTED_DISPLAY) ==
                PackageManager.PERMISSION_GRANTED
        val dump = env.displayDump(displayId)

        assertEquals(
            "ADD_TRUSTED_DISPLAY granted=$granted but the display's FLAG_TRUSTED disagrees.\n$dump",
            granted,
            "FLAG_TRUSTED" in dump,
        )

        // ALWAYS_UNLOCKED 走另一個權限，決定裝置鎖定時虛擬顯示還收不收得到注入的觸控。
        val unlockedGranted = env.context.checkSelfPermission(
            RelcV2Service.ADD_ALWAYS_UNLOCKED_DISPLAY
        ) == PackageManager.PERMISSION_GRANTED
        assertEquals(
            "ADD_ALWAYS_UNLOCKED_DISPLAY granted=$unlockedGranted (ADD_TRUSTED_DISPLAY=$granted) " +
                    "but FLAG_ALWAYS_UNLOCKED disagrees.\n$dump",
            granted && unlockedGranted,
            "FLAG_ALWAYS_UNLOCKED" in dump,
        )
    }

    /** GLES 分發器要真的產出影格，`vision.*` 才有東西可看。 */
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

    /** 啟動 activity 到次要顯示器；缺 `INTERNAL_SYSTEM_WINDOW` 或顯示器擁有權會被擋掉。 */
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

    /** 注入的觸控要真的送到那個顯示器上的視窗——非 trusted 顯示器各 API level 行為不一。 */
    @Test
    fun step5_an_injected_tap_reaches_the_puppet() {
        val displayId = requireDisplay()
        env.service.launchInDisplay(env.puppetPackage, displayId)
        assertTrue("the puppet never came up", PuppetRecorder.awaitReady(displayId))

        val target = PuppetRecorder.markerRect!!
        val probe = tapUntilInside(displayId, target)

        assertNotNull(
            "no ACTION_DOWN reached the puppet on display $displayId at all\n" +
                    "--- windows on this display ---\n" + env.windowsOnDisplay(displayId) +
                    "\n--- logcat ---\n" +
                    env.logcat("RelcV2Service", "InputManager", "InputDispatcher", "InputReader"),
            probe.last,
        )
        assertTrue(
            "taps reach the puppet but never inside the marker: last landed at " +
                    "(${probe.last!!.x}, ${probe.last.y}) on display ${probe.last.displayId}, " +
                    "expected inside ${PuppetRecorder.markerRect} " +
                    "(content ${PuppetRecorder.contentSize})",
            probe.inside,
        )
    }

    /**
     * 全程往返：vision 說標記在哪 → input 打去那裡 → puppet 回報打到標記裡。
     *
     * 自洽的斷言，不依賴 letterbox、density、insets 的任何假設。
     */
    @Test
    fun step6_a_script_finds_the_marker_and_taps_it() = visionTapRoundTrip(orientation = null)

    /**
     * 旋轉後座標換算仍要成立（ADR-0012 的 Surface 空間／邏輯空間）。
     *
     * rotation 0 時 `VisionMatcher::frameToLogical` 是 identity，所以只有轉過的顯示器才驗得到
     * 換算。方向寫反在維度上依然自洽，只有實際點下去、由 puppet 回報落點才分得出來。
     */
    @Test
    fun step7_a_landscape_display_still_maps_vision_to_where_the_tap_lands() =
        visionTapRoundTrip(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE)

    @Test
    fun step8_a_reverse_landscape_display_still_maps_vision_to_where_the_tap_lands() =
        visionTapRoundTrip(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE)

    /**
     * step6/7/8 共用的那一圈：vision 說標記在哪 → 點那裡 → puppet 回報打到哪。
     *
     * 斷言與 rotation 無關——換算對的話，這一圈在任何角度下都成立。
     */
    private fun visionTapRoundTrip(orientation: Int?) {
        val displayId = requireDisplay()

        // 先暖身到 puppet 真的收得到觸控再讓腳本跑：splash 期間注入的觸控會掉，
        // 而腳本只點一次。啟動時序由 step4/step5 負責，這裡測的是座標換算。
        env.service.launchInDisplay(env.puppetPackage, displayId)
        assertTrue("the puppet never came up", PuppetRecorder.awaitReady(displayId))

        if (orientation != null) {
            PuppetActivity.requestOrientation(orientation)
            // 轉了才算數：生效的話 puppet 的 content 會長寬互換。
            val rotated = env.height to env.width
            // 環境能力而非斷言：有些環境不讓 app 宣告的方向傳到虛擬顯示
            // （見 docs/virtual-display-pitfalls.md），轉不動時座標換算無從量起。
            assumeTrue(
                "the puppet asked for orientation $orientation but display $displayId never " +
                        "followed — content is still ${PuppetRecorder.contentSize}, expected " +
                        "$rotated, so there is no rotated frame to check coordinates against.\n" +
                        "accelerometer_rotation=${env.shell("settings get system accelerometer_rotation").trim()}\n" +
                        "user_rotation=${env.shell("settings get system user_rotation").trim()}\n" +
                        "--- dumpsys window (look for mIgnoreOrientationRequest) ---\n" +
                        env.windowsOnDisplay(displayId),
                waitFor { PuppetRecorder.contentSize == rotated },
            )
        }

        // 版面說它好了，影格未必——動畫還在跑的時候比對會命中一個過渡位置。
        assertTrue(
            "display $displayId never stopped changing; vision would match a mid-animation frame",
            env.awaitStableFrame(displayId),
        )

        val warmUpTarget = PuppetRecorder.markerRect!!
        assertTrue(
            "the puppet never became touchable at a settled position, so the script's single " +
                    "tap could never land",
            tapUntilInside(displayId, warmUpTarget).inside,
        )
        // 後面要看的是腳本自己點的那一下，暖身的不算。
        PuppetRecorder.touches.clear()

        val outcome = LuaScriptRunner(
            service = env.service,
            displayId = displayId,
            hasVision = true,
        ).use { runner ->
            runner.run(
                // 腳本不自己 app.launch：puppet 已由測試叫起來，再啟動一次會觸發另一輪轉場動畫。
                main = """
                    data.set("screen_w", screen.width)
                    data.set("screen_h", screen.height)
                    local hit = vision.wait("marker.png", 20000)
                    data.set("found", hit ~= nil)
                    if hit == nil then return end
                    data.set("cx", hit.cx)
                    data.set("cy", hit.cy)
                    data.set("confidence", hit.confidence)

                    -- 不對稱圖樣：模板是直立時截的，旋轉下要引擎轉過模板才會中。
                    local glyph = vision.wait("glyph.png", 5000)
                    data.set("glyph_found", glyph ~= nil)
                    if glyph ~= nil then
                        data.set("glyph_cx", glyph.cx)
                        data.set("glyph_cy", glyph.cy)
                        data.set("glyph_confidence", glyph.confidence)
                    end

                    input.tap(hit.cx, hit.cy)
                """.trimIndent(),
                assets = mapOf(
                    "marker.png" to PuppetMarker.png(),
                    "glyph.png" to PuppetGlyph.png(),
                ),
                timeoutMs = 40_000,
            )
        }

        assertEquals(EngineRunState.Finished, outcome.runState)

        // 腳本看到的尺寸要跟 puppet 實際被排版的尺寸一致：nativeStart 的 rotation 快照與
        // DisplayRotationTracker 後續的更新兩段都到位，native 的 logicalSize 才會是對的。
        assertEquals(
            "the script and the puppet disagree about the display size",
            PuppetRecorder.contentSize,
            (outcome.data["screen_w"] as Double).toInt() to
                    (outcome.data["screen_h"] as Double).toInt(),
        )

        if (outcome.data["found"] != true) {
            // 比不中之前先問畫面上有沒有東西：ATD 映像檔沒有圖形堆疊，影格全黑，
            // 那時比不中是環境不提供被測物，不是 bug。
            val colors = env.distinctColorsOnDisplay(displayId)
            assumeTrue(
                "display $displayId composites nothing — only $colors distinct colour(s) while " +
                        "the puppet is showing. ATD system images have no graphics stack; run " +
                        "the vision tests on hardware or a non-ATD image.",
                colors > 1,
            )
        }

        assertEquals(
            "vision.wait never matched the marker (orientation $orientation, " +
                    "display rotation ${displayRotation(displayId)})\n" +
                    "puppet ready on ${PuppetRecorder.resumedOnDisplay}, " +
                    "marker at ${PuppetRecorder.markerRect}, content ${PuppetRecorder.contentSize}\n" +
                    // miss 時 confidence 一律是 0（低於門檻就 continue），所以這裡讀得到的是
                    // 「哪張圖、找到沒」，不是相關係數。
                    "last match = ${EngineStateRepository.state.value.lastVisionResult}\n" +
                    env.logcat("VisionMatcher", "NativeImageReader", "GlesDistributor", "LuaEngine"),
            true,
            outcome.data["found"],
        )

        val target = PuppetRecorder.markerRect!!
        val cx = (outcome.data["cx"] as Double).toInt()
        val cy = (outcome.data["cy"] as Double).toInt()
        assertTrue(
            "at display rotation ${displayRotation(displayId)} vision put the marker at " +
                    "($cx, $cy) but it was drawn at $target " +
                    "(confidence ${outcome.data["confidence"]})",
            target.contains(cx, cy),
        )

        // 與對稱標記分開斷言：對稱的中了、不對稱的沒中，就是模板方向問題。
        assertEquals(
            "the rotation-symmetric marker matched but the asymmetric glyph did not, at display " +
                    "rotation ${displayRotation(displayId)}. The frame is surface space, so a " +
                    "rotated display rotates the content inside the buffer; an upright template " +
                    "only matches once the engine turns it to the current rotation.\n" +
                    "last match = ${EngineStateRepository.state.value.lastVisionResult}",
            true,
            outcome.data["glyph_found"],
        )
        val glyphTarget = PuppetRecorder.glyphRect!!
        val gx = (outcome.data["glyph_cx"] as Double).toInt()
        val gy = (outcome.data["glyph_cy"] as Double).toInt()
        assertTrue(
            "at display rotation ${displayRotation(displayId)} vision put the glyph at ($gx, $gy) " +
                    "but it was drawn at $glyphTarget " +
                    "(confidence ${outcome.data["glyph_confidence"]})",
            glyphTarget.contains(gx, gy),
        )

        val down = PuppetRecorder.awaitTouch { it.action == MotionEvent.ACTION_DOWN }
        assertNotNull("the script's tap never reached the puppet", down)
        assertTrue(
            "at display rotation ${displayRotation(displayId)} the script tapped " +
                    "(${down!!.x}, ${down.y}), outside the marker at $target",
            target.contains(down.x.toInt(), down.y.toInt()),
        )
    }

    /** 顯示器當下實際的 rotation，只用在訊息裡——測試本身不該依賴它是哪個值。 */
    private fun displayRotation(displayId: Int): Int =
        env.context.getSystemService(DisplayManager::class.java)
            ?.getDisplay(displayId)?.rotation ?: -1

    private inline fun waitFor(timeoutMs: Long = 10_000, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(100)
        }
        return condition()
    }

    /**
     * `vision.wait` 的等待語意：標記在腳本已經在等的時候才畫出來。
     *
     * 鑑別力來自 `found`——標記在延遲前不在畫面上，比中就蘊含有等到；經過時間那條擋的是
     * 「比中了畫面上別的東西」。反向對照見
     * [step10][step10_vision_wait_returns_nil_on_timeout_without_erroring]。
     */
    @Test
    fun step9_vision_wait_blocks_until_the_marker_appears() {
        val displayId = readyDisplayWithHiddenMarkers()
        PuppetActivity.showAfter(APPEAR_DELAY_MS)

        val started = SystemClock.uptimeMillis()
        val outcome = runScript(
            displayId,
            """
            local hit = vision.wait("marker.png", 15000)
            data.set("found", hit ~= nil)
            """.trimIndent(),
        )
        val elapsed = SystemClock.uptimeMillis() - started

        assertEquals(EngineRunState.Finished, outcome.runState)
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
    fun step10_vision_wait_returns_nil_on_timeout_without_erroring() {
        val displayId = readyDisplayWithHiddenMarkers()   // 一直不顯示

        val started = SystemClock.uptimeMillis()
        val outcome = runScript(
            displayId,
            """
            local hit = vision.wait("marker.png", $TIMEOUT_MS)
            data.set("found", hit ~= nil)
            data.set("reached_the_end", true)
            """.trimIndent(),
        )
        val elapsed = SystemClock.uptimeMillis() - started

        assertEquals(
            "timing out must unwind as a normal return, not an error",
            EngineRunState.Finished,
            outcome.runState,
        )
        assertEquals(false, outcome.data["found"])
        assertEquals(true, outcome.data["reached_the_end"])
        assertTrue(
            "returned after only ${elapsed}ms for a ${TIMEOUT_MS}ms timeout — it gave up early",
            elapsed >= TIMEOUT_MS,
        )
    }

    /**
     * `vision.wait_any` 的 index 要指向真的出現的那一個。
     *
     * 只讓其中一個出現：兩個都出現的話 index 只反映呼叫順序，什麼都沒驗到。
     */
    @Test
    fun step11_vision_wait_any_reports_which_one_appeared() {
        val displayId = readyDisplayWithHiddenMarkers()
        PuppetActivity.showAfter(APPEAR_DELAY_MS, marker = false, glyph = true)

        val outcome = runScript(
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
        assertEquals(true, outcome.data["found"])
        assertEquals(
            "only the glyph was ever drawn, so wait_any must report index 2 (1-based)",
            2.0,
            outcome.data["index"],
        )
    }

    /** 顯示器 + puppet 就緒、標記全部藏起來、畫面已經穩定。 */
    private fun readyDisplayWithHiddenMarkers(): Int {
        val displayId = requireDisplay()
        env.service.launchInDisplay(env.puppetPackage, displayId)
        assertTrue("the puppet never came up", PuppetRecorder.awaitReady(displayId))
        PuppetActivity.setVisible(marker = false, glyph = false)
        assertTrue(
            "display $displayId never settled with the markers hidden",
            env.awaitStableFrame(displayId),
        )
        return displayId
    }

    private fun runScript(displayId: Int, main: String): ScriptOutcome =
        LuaScriptRunner(service = env.service, displayId = displayId, hasVision = true).use {
            it.run(
                main = main,
                assets = mapOf(
                    "marker.png" to PuppetMarker.png(),
                    "glyph.png" to PuppetGlyph.png(),
                ),
                timeoutMs = 40_000,
            )
        }

    /** [tapUntilInside] 的結果：有沒有收到、以及最後看到的那一下在哪。 */
    private class TapProbe(val last: PuppetRecorder.Touch?, val inside: Boolean)

    /**
     * 反覆注入同一個 tap，直到 puppet 回報的落點落在 [target] 裡，或逾時。
     *
     * 條件是「落在裡面」而非「收到就好」：啟動動畫期間視窗就收得到觸控，但座標帶著縮放
     * （見 docs/virtual-display-pitfalls.md）。座標若真的算錯就永遠不會落進 [target]，
     * 逾時後由 [TapProbe.last] 分辨「一次都沒收到」與「收到但始終在外面」。
     */
    private fun tapUntilInside(
        displayId: Int,
        target: Rect,
        timeoutMs: Long = 15_000,
    ): TapProbe {
        val deadline = System.currentTimeMillis() + timeoutMs
        var last: PuppetRecorder.Touch? = null
        while (System.currentTimeMillis() < deadline) {
            PuppetRecorder.touches.clear()
            env.service.multiTouchSwipe(
                -1, displayId,
                intArrayOf(target.centerX(), target.centerY(), target.centerX(), target.centerY()),
                50L, false,
            )
            val down = PuppetRecorder.awaitTouch(700) { it.action == MotionEvent.ACTION_DOWN }
            if (down != null) {
                last = down
                if (target.contains(down.x.toInt(), down.y.toInt())) return TapProbe(down, true)
            }
        }
        return TapProbe(last, false)
    }

    private fun requireDisplay(): Int {
        val displayId = env.createDisplay()
        assertTrue("could not create a virtual display (got $displayId) — see step2", displayId > 0)
        return displayId
    }

    private companion object {
        /** 排程改變畫面的延遲，要明顯大於「第一幀就比中」的時間尺度。 */
        const val APPEAR_DELAY_MS = 2_000L
        const val TIMEOUT_MS = 2_000L
    }
}
