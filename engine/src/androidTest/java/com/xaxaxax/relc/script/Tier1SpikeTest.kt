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
 * 每個 step 回答一個原本**不知道答案**的問題，而且刻意各自獨立、依名稱排序執行，所以一次
 * 跑完就知道是哪一環斷掉，而不是只知道「Tier 1 不行」。
 *
 * 只有一處用 `Assume`：畫面全黑的環境（ATD 系統映像檔沒有圖形堆疊）跳過比對那一段。
 * 其餘一律用斷言——這裡的目的是把不成立的假設吵出來，不是把它藏起來。
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class Tier1SpikeTest {

    private lateinit var env: Tier1Env

    @Before
    fun setUp() {
        // Tier 1 的下限是 API 29，因為它整個建立在 adoptShellPermissionIdentity 上。
        // 實測 27 與 28 的映像檔都沒有那個方法（NoSuchMethodError）。
        //
        // 這是**測試框架**的限制，不是產品的：production 在舊版上跑在 Shizuku 真正的 shell
        // 進程裡，本來就不需要 adopt 任何身分。minSdk 仍然是 27，只是這一層沒辦法替 Shizuku
        // 站在那裡，27/28 的行為得用別的方式確認。
        //
        // 順帶一個巧合值得記著：`MotionEvent.setDisplayId` 也是 API 29 才有的，所以「注入
        // 不到虛擬顯示」這個產品在 27/28 上的能力邊界，**Tier 1 永遠觀察不到**——兩個下限
        // 剛好重合。曾經為它加過一個能力閘門，在可達範圍內恆為真，已拆除。
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
        // setUp 的 assumption 不成立時 env 根本沒建起來，而 @After 照樣會跑——不擋的話
        // 「跳過」會變成 UninitializedPropertyAccessException，也就是一個假的失敗。
        if (::env.isInitialized) env.close()
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

    /**
     * Q: 那組需要 `ADD_TRUSTED_DISPLAY` 的旗標，有沒有依權限正確地給或不給？
     *
     * 這條存在的原因是 30/30 全綠**分辨不出**這件事：非 trusted 的顯示器一樣建得起來、
     * 一樣收得到觸控（SM-A217F 上實測），所以旗標決策錯了其餘測試照樣通過。ReLC 曾經在
     * API 31 無條件要求 TRUSTED，讓那台機器完全不能建顯示器——沒有這條斷言，同樣的錯誤
     * 再犯一次也不會有人發現。
     *
     * 斷言的是**兩者一致**，不是某個特定值：拿得到權限就該是 trusted，拿不到就不該是。
     * 這樣同一條測試在兩種裝置上都有意義。
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

        // ALWAYS_UNLOCKED 走的是**另一個權限**，而且它決定虛擬顯示在裝置鎖定時還收不收得到
        // 注入的觸控——把它跟 TRUSTED 綁成一包丟掉，代價就是那個。
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
     * 全部串起來：腳本啟動 puppet、用 `vision.wait` 找到畫在上面的標記、點它，
     * 而 puppet 確認被點到的位置就在標記裡。
     *
     * 這個斷言是**自洽**的——vision 說它在哪，input 就打去哪，puppet 回報打到哪。中間任何
     * 一段的座標換算錯了都會露出來，而且不依賴 letterbox、density、insets 的任何假設。
     */
    @Test
    fun step6_a_script_finds_the_marker_and_taps_it() = visionTapRoundTrip(orientation = null)

    /**
     * Q: 顯示器轉了之後，`vision` 回的座標還是指得到那個標記嗎？
     *
     * rotation 0 時 `VisionMatcher::frameToLogical` 是 identity——所以 step6 其實一段換算
     * 都沒驗到。真正會動的是 case 1/2/3，也就是 ADR-0012 與 CONTEXT.md「Surface 空間 /
     * 邏輯空間」在講的那件事。
     *
     * 特別要抓的是**方向寫反**：case 1 與 case 3 在維度上都自洽（rotation 1 時 lx 落在
     * [0, frameHeight)、ly 落在 [0, frameWidth)，case 3 反過來也對），所以把兩者對調不會
     * 讓任何維度檢查失敗，用看的也很難發現——只有真的點下去、由 puppet 回報打到哪，才分
     * 得出來。
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
     * 斷言本身跟 rotation 無關，這正是重點——換算對的話，這一圈在任何角度下都成立。
     */
    private fun visionTapRoundTrip(orientation: Int?) {
        val displayId = requireDisplay()

        // 先把 puppet 叫起來、並確認它**真的收得到觸控**，再讓腳本跑。
        //
        // 不這樣做的話這個測試是時序賭博：Android 12 的 splash screen 會在 activity 都
        // resume、也畫完之後還壓在上面一陣子（step5 量到的），而腳本只點一次——比對命中得
        // 夠快時那一下就落進 splash 還在的窗口，掉了。那時失敗訊息會說「點擊沒送達」，看起
        // 來像座標換算錯了，其實測到的是啟動時序。
        //
        // 啟動時序本身已經有 step4/step5 在管。這裡要測的是座標換算，所以先把環境弄成
        // 穩定的，再讓腳本做它那一次點擊。
        env.service.launchInDisplay(env.puppetPackage, displayId)
        assertTrue("the puppet never came up", PuppetRecorder.awaitReady(displayId))

        if (orientation != null) {
            PuppetActivity.requestOrientation(orientation)
            // 轉了才算數：生效的話 puppet 的 content 會長寬互換。先確認這件事，否則
            // 「其實根本沒轉」會被誤讀成「換算錯了」——兩者要查的地方完全不同。
            val rotated = env.height to env.width
            // 這是**環境能力**，不是斷言：有些環境不讓 app 宣告的方向傳到虛擬顯示。實測
            // API 31 的 AOSP 模擬器映像檔不跟隨，但同樣是 API 31 的 SM-A217F 實機會跟隨，
            // API 30/33/34/35 的模擬器也會——所以不是版本問題。
            //
            // **原因未定。** 最大嫌疑是 `ignoreOrientationRequest`（API 31 引進的 per-display
            // 設定，開啟後 WindowManager 會無視 app 宣告的方向），所以訊息裡帶上
            // `dumpsys window displays`，下一個看到的人不必從頭查。auto-rotate 已排除
            // （accelerometer_rotation=1 時照樣不跟隨）。
            //
            // 用 assume 而不是硬性失敗，是因為轉不動時「座標換算對不對」根本無從量起。
            // 但它是逐次實測的環境條件、不是寫死的裝置清單：任何轉得動的環境仍然照常斷言。
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
        // 暖身的那幾下不算數——後面斷言要看的是腳本自己點的那一下。
        PuppetRecorder.touches.clear()

        val outcome = LuaScriptRunner(
            service = env.service,
            displayId = displayId,
            hasVision = true,
        ).use { runner ->
            runner.run(
                // 腳本不再自己 app.launch：puppet 已經由測試叫起來、也暖身確認過可觸控了，
                // 再啟動一次會把同一個 task 重新帶到前景、觸發另一輪轉場動畫，於是腳本那唯一
                // 的一下又落在動畫中途（step8 三次中飄一次就是這樣來的）。啟動本身有 step4
                // 在管；這一條只測往返。
                main = """
                    data.set("screen_w", screen.width)
                    data.set("screen_h", screen.height)
                    local hit = vision.wait("marker.png", 20000)
                    data.set("found", hit ~= nil)
                    if hit == nil then return end
                    data.set("cx", hit.cx)
                    data.set("cy", hit.cy)
                    data.set("confidence", hit.confidence)

                    -- 同一畫面上的不對稱圖樣。模板是直立時截的，所以只有在引擎把模板
                    -- 轉到當前方向之後，旋轉下才會中。
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

        // 腳本看到的尺寸要跟 puppet 實際被排版的尺寸一致。這條把 ADR-0012 的交接釘住：
        // 啟動時隨 nativeStart 傳進去的那個 rotation 快照，加上 DisplayRotationTracker
        // 後續推進來的更新，兩段都得到位，native 端的 logicalSize 才會是對的。
        // 少了這條，下面的往返即使通過也只是「推論」native 知道自己轉了。
        assertEquals(
            "the script and the puppet disagree about the display size",
            PuppetRecorder.contentSize,
            (outcome.data["screen_w"] as Double).toInt() to
                    (outcome.data["screen_h"] as Double).toInt(),
        )

        if (outcome.data["found"] != true) {
            // 比不中之前，先問畫面上到底有沒有東西。ATD 系統映像檔沒有圖形堆疊，虛擬顯示
            // 送出來的每一張影格都是全黑——那時比不中是環境不提供被測物，不是 bug。
            // puppet 這時還在（tearDown 才收），所以量到的就是腳本剛才看到的那個畫面。
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
                    // 最後一次比對的結果。注意 miss 時 confidence 一律是 0——match() 在低於
                    // 門檻時 continue，留下預設值——所以這裡讀得到的是「哪張圖、找到沒」，
                    // 不是相關係數。要分辨「畫面全黑」與「有內容但比不中」，靠下面的
                    // distinctColorsOnDisplay，不要靠分數。
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

        // 對稱標記中了、不對稱的沒中 —— 那就不是環境問題也不是座標問題，是模板方向。
        // 這兩條分開斷言就是為了讓失敗訊息自己講出是哪一類。
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
     * Q: `vision.wait` 真的會**等**嗎？
     *
     * 這一組存在的理由是：其餘所有 vision 測試的畫面都是靜態的，比對第一幀就中——也就是說
     * 它們驗的其實是 `vision.find`，而 `wait` 的等待語意從來沒被執行到。畫面必須在腳本
     * 已經在等的時候才改變，這件事才問得出來。
     *
     * 真正的鑑別力來自 `found`：標記在延遲之前根本不在畫面上，所以「有比中」本身就蘊含
     * 「有等到」。經過時間那條是額外的防線，擋的是「比中了畫面上別的東西」。
     *
     * [step10][step10_vision_wait_returns_nil_on_timeout_without_erroring] 是它的反向對照：
     * 同樣藏起來、但永遠不顯示，斷言 `found == false`。兩支一起看才完整——只有前者的話，
     * 一個永遠回傳 true 的實作也會過。
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

    /**
     * Q: 逾時是回 `nil` 還是拋錯？
     *
     * `docs/lua-api.md` 承諾「逾時回傳 nil」，而那條路徑在此之前完全沒有測試守著——腳本
     * 作者的錯誤處理全建立在它上面。
     */
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
     * Q: `vision.wait_any` 回的 index 指的是**出現的那一個**嗎？
     *
     * 兩個候選同時出現的話 index 只反映呼叫順序，什麼都沒驗到。所以只讓其中一個出現，
     * 另一個永遠不畫。
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
     * 反覆注入同一個 tap，直到 puppet 回報的落點**落在 [target] 裡**，或逾時。
     *
     * 為什麼條件是「落在裡面」而不是「收到就好」：視窗在啟動動畫期間就已經收得到觸控，但那
     * 時它還帶著縮放，回報的座標是動畫中途的值。實測兩次都注入 y=506，收到 334.01 與
     * 369.01——x 精確不變、y 各自差一個純縮放（1.515 與 1.371），正是垂直方向還在動的樣子。
     *
     * 每輪先清掉記錄，回傳的才是這一次注入的結果而不是更早的殘留。
     *
     * 這不是「重試到過為止」：座標若真的算錯，它永遠不會落進 [target]，逾時後由
     * [TapProbe.last] 指出它一直落在哪裡。兩種失敗因此分得開——一次都沒收到是派送不通，
     * 收到但始終在外面是座標錯。
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
        /** 排程改變畫面的延遲。要明顯大於「第一幀就比中」的時間尺度才有鑑別力。 */
        const val APPEAR_DELAY_MS = 2_000L
        const val TIMEOUT_MS = 2_000L
    }
}
