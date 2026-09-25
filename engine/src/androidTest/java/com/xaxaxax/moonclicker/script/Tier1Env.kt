package com.xaxaxax.moonclicker.script

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.PixelFormat
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.media.ImageReader
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.os.SystemClock
import android.view.MotionEvent
import androidx.test.platform.app.InstrumentationRegistry
import com.xaxaxax.moonclicker.MoonClickerService
import com.xaxaxax.moonclicker.script.puppet.PuppetActivity
import com.xaxaxax.moonclicker.script.puppet.PuppetControl
import com.xaxaxax.moonclicker.script.puppet.PuppetGlyph
import com.xaxaxax.moonclicker.script.puppet.PuppetMarker
import com.xaxaxax.moonclicker.script.puppet.PuppetRecorder
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.rules.ExternalResource
import org.lsposed.hiddenapibypass.LSPass
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** 虛擬顯示要轉到哪個方向；[requested] 為 null 表示維持直向、不要求任何方向。 */
enum class Orientation(val requested: Int?) {
    PORTRAIT(null),
    LANDSCAPE(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE),
    REVERSE_LANDSCAPE(ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE),
}

/**
 * Tier 1 的執行環境（見 `docs/lua-api-testing.md`），以 `@get:Rule` 掛上。
 *
 * 真的 [MoonClickerService]，跑在測試進程裡，用 `adoptShellPermissionIdentity()` 取得的 shell
 * 身分——與 Shizuku 給 MoonClicker 的身分相同，所以這不是替身，是同一份 production 程式碼換了
 * 宿主進程。換宿主要補兩件 Shizuku 進程免費拿到的事：隱藏 API 豁免與呼叫者套件名。
 */
class Tier1Env : ExternalResource() {

    val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation

    /** 測試 APK 自己的套件名，也就是 puppet 住的地方。 */
    val puppetPackage: String = context.packageName

    lateinit var service: MoonClickerService
        private set

    private var displayId: Int = -1

    override fun before() {
        // 下限 API 29 是 adoptShellPermissionIdentity 的限制，不是產品的——production 跑在
        // Shizuku 的 shell 進程裡，minSdk 仍是 27。
        assumeTrue(
            "UiAutomation.adoptShellPermissionIdentity does not exist below API 29, so this " +
                    "harness cannot stand in for Shizuku here. Says nothing about whether MoonClicker " +
                    "works there — Tier 0 still covers the Lua layer.",
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q,
        )
        PuppetRecorder.reset()
        PuppetControl.reset()
        uiAutomation.adoptShellPermissionIdentity()
        try {
            requireShellPermissions()
            wakeAndUnlock()
            // shell 身分給的是權限，不是隱藏 API 豁免。一次全開：這裡是還原 production 的執行
            // 條件，驗哪些隱藏 API 拿得到是 `:hidden-api-contract` 的事。
            LSPass.addHiddenApiExemptions("L")
            // 呼叫者要宣稱成測試 APK 自己的套件名——system_server 會拿它跟 calling uid 對。
            service = MoonClickerService(context, callerPackage = context.packageName)
        } catch (t: Throwable) {
            // before() 失敗時 after() 不會跑。
            uiAutomation.dropShellPermissionIdentity()
            throw t
        }
    }

    override fun after() {
        // 顯示器銷毀前先關掉 puppet，否則它會被系統搬回預設顯示器，
        // 下一個測試會把它從那裡撈回前景而不是在新顯示器上重開。
        PuppetActivity.finishAndWait()
        if (displayId >= 0) {
            runCatching { service.destroyVirtualDisplay(displayId) }
            displayId = -1
        }
        runCatching { uiAutomation.dropShellPermissionIdentity() }
    }

    /**
     * `ADD_TRUSTED_DISPLAY` 不在必要清單裡——不是每台裝置的 shell 都有，缺了就退回
     * 非 trusted 顯示器（`MoonClickerService.createDisplay`）。
     */
    private fun requireShellPermissions() {
        val missing = listOf(
            "android.permission.INJECT_EVENTS",
            "android.permission.INTERNAL_SYSTEM_WINDOW",
        ).filterNot(::isGranted)
        check(missing.isEmpty()) {
            "shell identity did not carry $missing; Tier 1 cannot work here " +
                    "(trusted displays available = ${isGranted(MoonClickerService.ADD_TRUSTED_DISPLAY)})"
        }
    }

    fun isGranted(permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /**
     * 把裝置叫醒並解掉鎖定畫面。
     *
     * 沒有 `ALWAYS_UNLOCKED` 的虛擬顯示在 Dozing 或 keyguard 下收不到注入的觸控，症狀跟
     * 座標算錯、權限不足、顯示器沒建起來一樣。見 docs/virtual-display-pitfalls.md。
     */
    private fun wakeAndUnlock() {
        val power = context.getSystemService(PowerManager::class.java)
        val screenOn = CountDownLatch(1)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = screenOn.countDown()
        }
        // 不用 ContextCompat：它在 API 33 以下靠 manifest merge 宣告的權限模擬 NOT_EXPORTED，
        // library 的測試 APK 沒有那個權限。ACTION_SCREEN_ON 是受保護的系統廣播，不需要模擬。
        val filter = IntentFilter(Intent.ACTION_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        try {
            shell("input keyevent KEYCODE_WAKEUP")
            if (!power.isInteractive) screenOn.await(2, TimeUnit.SECONDS)
        } finally {
            context.unregisterReceiver(receiver)
        }
        check(power.isInteractive) { "could not wake the device; injected touches will not be dispatched" }
        shell("wm dismiss-keyguard")
    }

    fun shell(command: String): String {
        val fd = uiAutomation.executeShellCommand(command)
        return ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().use { it.readText() }
    }

    /** 建一個虛擬顯示，[after] 時銷毀。 */
    fun createDisplay(): Int {
        displayId = service.createVirtualDisplay("moonclicker-tier1", WIDTH, HEIGHT, DENSITY_DPI, 0)
        assertTrue("could not create a virtual display (got $displayId)", displayId > 0)
        return displayId
    }

    fun launchPuppet(displayId: Int) {
        val launched = service.launchInDisplay(puppetPackage, displayId)
        assertTrue("launchInDisplay($puppetPackage, $displayId) returned false", launched)
        assertTrue(
            "the puppet never resumed on display $displayId (${PuppetRecorder.current})",
            PuppetRecorder.awaitReady(displayId),
        )
    }

    /**
     * 讓 puppet 要求 [orientation]，等到它的 content 長寬互換。
     *
     * 有些環境不讓 app 宣告的方向傳到虛擬顯示（見 docs/virtual-display-pitfalls.md）；轉不動時
     * 沒有旋轉過的影格可驗，所以是 assume 而非斷言。
     */
    fun rotate(displayId: Int, orientation: Orientation) {
        val requested = orientation.requested ?: return
        PuppetActivity.requestOrientation(requested)
        val rotated = HEIGHT to WIDTH
        if (PuppetRecorder.await(10_000) { it.contentSize == rotated } != null) return
        assumeTrue(
            "the puppet asked for $orientation but display $displayId never followed — content " +
                    "is still ${PuppetRecorder.current.contentSize}, expected $rotated.\n" +
                    "accelerometer_rotation=${shell("settings get system accelerometer_rotation").trim()}\n" +
                    "user_rotation=${shell("settings get system user_rotation").trim()}\n" +
                    "--- dumpsys window (look for mIgnoreOrientationRequest) ---\n" +
                    windowsOnDisplay(displayId),
            false,
        )
    }

    /**
     * 等到連續 [stableFrames] 張影格取樣相同，也就是畫面不再變動。
     *
     * 量影格而不是聽版面回報：旋轉動畫期間 `contentSize` 已是新方向，緩衝區裡卻還是轉到
     * 一半的舊內容，`vision` 會以 confidence 1.0 命中過渡位置。這一個條件同時涵蓋啟動動畫、
     * 旋轉動畫與 splash 收起來。見 docs/virtual-display-pitfalls.md。
     */
    fun awaitSettled(displayId: Int, stableFrames: Int = 8, timeoutMs: Long = 8_000) {
        var runLength = 0
        var lastHash = Long.MIN_VALUE
        val settled = sampleFrames(displayId, timeoutMs) { buffer, rowStride ->
            var hash = 1125899906842597L
            // 格點取樣就夠分辨「畫面有沒有動」，不必掃完整張。每一列都要橫跨整個寬度：
            // 啟動轉場會把整個畫面水平滑入，只取某一行的話那一行始終是底色，看不出在動。
            for (row in 0 until HEIGHT step 4) {
                for (col in 0 until WIDTH step 16) {
                    val offset = row * rowStride + col * 4
                    if (offset + 4 > buffer.limit()) break
                    hash = hash * 31 + buffer.getInt(offset)
                }
            }
            if (hash == lastHash) runLength++ else { lastHash = hash; runLength = 0 }
            runLength >= stableFrames
        }
        assertTrue("display $displayId never stopped changing; vision would match a mid-animation frame", settled)
    }

    /**
     * 建顯示器 → 啟動 puppet → 轉向 → 等轉場結束。
     *
     * 只看影格不夠：轉場開始前畫面會先靜止一段，[awaitSettled] 在那時就成立，之後整個畫面
     * 才水平滑入，vision 會以 confidence 1.0 比中平移過的位置。轉場期間注入的觸控座標也帶著
     * 位移，所以「點下去落在標記裡」才是轉場結束的訊號。
     */
    fun stage(orientation: Orientation = Orientation.PORTRAIT): Int {
        val displayId = createDisplay()
        launchPuppet(displayId)
        rotate(displayId, orientation)
        awaitSettled(displayId)
        assertTapLandsInside(displayId, PuppetRecorder.current.markerRect!!)
        PuppetRecorder.clearTouches()
        return displayId
    }

    /** 顯示器送出至少一張影格。 */
    fun awaitFrame(displayId: Int, timeoutMs: Long = 10_000): Boolean =
        sampleFrames(displayId, timeoutMs) { _, _ -> true }

    /**
     * 比不中之前先問畫面上有沒有東西。
     *
     * ATD 系統映像檔沒有圖形堆疊，顯示器照樣建得起來、影格照樣送，但每一張都是全黑；那時
     * `vision.*` 比不中是環境不提供被測物，不是 bug。量顏色而不是認裝置名，才不會隨映像檔
     * 改名而腐爛。
     */
    fun assumeFramesHaveContent(displayId: Int) {
        val seen = HashSet<Int>()
        val hasContent = sampleFrames(displayId, timeoutMs = 2_000) { buffer, rowStride ->
            // 格點橫跨整張影格：只取某一行的話，那一行可能整條都是底色。
            for (row in 0 until HEIGHT step 16) {
                for (col in 0 until WIDTH step 16) {
                    val offset = row * rowStride + col * 4
                    if (offset + 4 > buffer.limit()) break
                    seen += buffer.getInt(offset)
                }
            }
            seen.size > 1
        }
        assumeTrue(
            "display $displayId composites nothing — every frame is a single colour while the " +
                    "puppet is showing. ATD system images have no graphics stack; run the vision " +
                    "tests on hardware or a non-ATD image.",
            hasContent,
        )
    }

    /**
     * 掛一個 ImageReader 到這個顯示器上，每張影格交給 [onFrame]，直到它回傳 true 或逾時。
     * [onFrame] 只在取樣執行緒上被呼叫。
     *
     * 拆除順序不能改：拔 sink → 用鎖等在途回呼做完 → 才 `close()`。`close()` 會讓已取得的
     * Image buffer 失效，回呼還在讀就是 `IllegalStateException: buffer is inaccessible`。
     */
    private fun sampleFrames(
        displayId: Int,
        timeoutMs: Long,
        onFrame: (buffer: ByteBuffer, rowStride: Int) -> Boolean,
    ): Boolean {
        val thread = HandlerThread("frame-sampler").apply { start() }
        val reader = ImageReader.newInstance(WIDTH, HEIGHT, PixelFormat.RGBA_8888, 2)
        val lock = Object()
        var closing = false
        val done = CountDownLatch(1)

        reader.setOnImageAvailableListener({ r ->
            synchronized(lock) {
                if (closing) return@setOnImageAvailableListener
                val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                val finished = image.use { onFrame(it.planes[0].buffer, it.planes[0].rowStride) }
                if (finished) done.countDown()
            }
        }, Handler(thread.looper))

        val handle = service.addVirtualDisplaySurface(displayId, reader.surface)
        try {
            assertTrue("addVirtualDisplaySurface returned $handle", handle >= 0)
            return done.await(timeoutMs, TimeUnit.MILLISECONDS)
        } finally {
            if (handle >= 0) service.removeVirtualDisplaySurface(displayId, handle)
            synchronized(lock) { closing = true }
            reader.close()
            thread.quitSafely()
        }
    }

    /**
     * 反覆注入同一個 tap，直到 puppet 回報的落點落在 [target] 裡，或逾時就讓測試失敗。
     *
     * 條件是「落在裡面」而非「收到就好」：啟動動畫期間視窗就收得到觸控，但座標帶著縮放，
     * splash 期間的注入也會掉（見 docs/virtual-display-pitfalls.md）。座標若真的算錯就永遠不會
     * 落進 [target]，失敗訊息分辨「一次都沒收到」與「收到但始終在外面」。
     */
    fun assertTapLandsInside(displayId: Int, target: Rect, timeoutMs: Long = 15_000) {
        // 單調時鐘：模擬器從 snapshot 還原後會把牆上時鐘往前校正一大段，deadline 會瞬間過期。
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var last: PuppetRecorder.Touch? = null
        while (SystemClock.uptimeMillis() < deadline) {
            PuppetRecorder.clearTouches()
            service.multiTouchSwipe(
                -1, displayId,
                intArrayOf(target.centerX(), target.centerY(), target.centerX(), target.centerY()),
                50L, false,
            )
            val down = PuppetRecorder.awaitTouch(700) { it.action == MotionEvent.ACTION_DOWN }
            if (down != null) {
                if (target.contains(down.x.toInt(), down.y.toInt())) return
                last = down
            }
        }
        if (last == null) {
            throw AssertionError(
                "no ACTION_DOWN reached the puppet on display $displayId at all\n" +
                        "--- windows on this display ---\n" + windowsOnDisplay(displayId) +
                        "\n--- logcat ---\n" +
                        logcat("MoonClickerService", "InputManager", "InputDispatcher", "InputReader")
            )
        }
        throw AssertionError(
            "taps reach the puppet but never inside $target: last landed at (${last.x}, ${last.y}) " +
                    "on display ${last.displayId} at display rotation ${displayRotation(displayId)} " +
                    "(content ${PuppetRecorder.current.contentSize})"
        )
    }

    /** 跑一份以 [PuppetMarker]（`marker.png`）與 [PuppetGlyph]（`glyph.png`）為模板的腳本。 */
    internal fun runScript(displayId: Int, main: String, timeoutMs: Long = 40_000): ScriptOutcome =
        LuaScriptRunner(service = service, displayId = displayId, hasVision = true).use {
            it.run(
                main = main,
                assets = mapOf("marker.png" to PuppetMarker.png(), "glyph.png" to PuppetGlyph.png()),
                timeoutMs = timeoutMs,
            )
        }

    /** 顯示器當下實際的 rotation，只用在訊息裡——測試本身不該依賴它是哪個值。 */
    fun displayRotation(displayId: Int): Int =
        context.getSystemService(DisplayManager::class.java)?.getDisplay(displayId)?.rotation ?: -1

    /**
     * 最近的 logcat，只留提到 [tags] 的行。
     *
     * Tier 1 有一半的失敗是 system_server 的決定，只出現在 logcat 裡；接進斷言訊息，
     * 一次執行就知道原因。
     */
    fun logcat(vararg tags: String, lines: Int = 600): String =
        shell("logcat -d -t $lines").lineSequence()
            .filter { line -> tags.any { it in line } }
            .joinToString("\n")
            .ifEmpty { "(nothing in logcat matched ${tags.toList()})" }

    /** `dumpsys display` 裡描述 [displayId] 的那一段。 */
    fun displayDump(displayId: Int): String {
        val all = shell("dumpsys display")
        val start = all.indexOf("mDisplayId=$displayId")
        if (start < 0) return "(display $displayId not in `dumpsys display`)"
        return all.substring(start, minOf(start + 1200, all.length))
    }

    /**
     * `dumpsys window` 裡跟 [displayId] 有關的行——用來分辨「顯示器上沒有可觸控的視窗」
     * 與「有但系統不肯派送」。
     */
    fun windowsOnDisplay(displayId: Int): String {
        val all = shell("dumpsys window displays")
        val start = all.indexOf("Display: mDisplayId=$displayId")
        if (start < 0) return "(display $displayId not in `dumpsys window displays`)"
        val next = all.indexOf("Display: mDisplayId=", start + 1)
        return all.substring(start, if (next < 0) all.length else next).take(4000)
    }

    companion object {
        const val WIDTH = 720
        const val HEIGHT = 1280
        const val DENSITY_DPI = 320
    }
}
