package com.xaxaxax.moonclicker.script

import android.content.Context
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import androidx.test.platform.app.InstrumentationRegistry
import com.xaxaxax.moonclicker.MoonClickerService
import com.xaxaxax.moonclicker.script.puppet.PuppetActivity
import com.xaxaxax.moonclicker.script.puppet.PuppetRecorder
import org.lsposed.hiddenapibypass.LSPass

/**
 * Tier 1 的執行環境：真的 [MoonClickerService]，跑在測試進程裡，用
 * `adoptShellPermissionIdentity()` 取得的 shell 身分——與 Shizuku 給 MoonClicker 的身分相同，
 * 所以這不是替身，是同一份 production 程式碼換了宿主進程。
 *
 * 換宿主要補兩件 Shizuku 進程免費拿到的事：隱藏 API 豁免（[exemptHiddenApis]）與
 * 呼叫者套件名（[startService]）。
 */
internal class Tier1Env(
    val width: Int = 720,
    val height: Int = 1280,
    val densityDpi: Int = 320,
) : AutoCloseable {

    val context: Context = InstrumentationRegistry.getInstrumentation().targetContext
    private val uiAutomation = InstrumentationRegistry.getInstrumentation().uiAutomation

    /** 測試 APK 自己的套件名，也就是 puppet 住的地方。 */
    val puppetPackage: String = context.packageName

    lateinit var service: MoonClickerService
        private set

    var displayId: Int = -1
        private set

    fun adoptShellIdentity() {
        uiAutomation.adoptShellPermissionIdentity()
    }

    /**
     * 把裝置叫醒並解掉鎖定畫面。
     *
     * 沒有 `ALWAYS_UNLOCKED` 的虛擬顯示在 Dozing 或 keyguard 下收不到注入的觸控，症狀跟
     * 座標算錯、權限不足、顯示器沒建起來一樣。見 docs/virtual-display-pitfalls.md。
     */
    fun wakeAndUnlock() {
        shell("input keyevent KEYCODE_WAKEUP")
        shell("wm dismiss-keyguard")
        val awake = (1..20).any {
            if (shell("dumpsys power").contains("mWakefulness=Awake")) true
            else { Thread.sleep(100); false }
        }
        check(awake) { "could not wake the device; injected touches will not be dispatched" }
    }

    fun shell(command: String): String {
        val fd = uiAutomation.executeShellCommand(command)
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(fd)
            .bufferedReader().use { it.readText() }
    }

    /**
     * shell 身分給的是權限，不是隱藏 API 豁免——平台上是分開的兩件事。shell uid 整個免受
     * 名單約束，測試進程是普通 app uid，不補這一步那些反射會以 `NoSuchMethodException` 失敗。
     *
     * 一次全開而非逐一列舉：這裡是還原 production 的執行條件，驗哪些隱藏 API 拿得到是
     * `:hidden-api-contract` 的事。
     */
    fun exemptHiddenApis() {
        LSPass.addHiddenApiExemptions("L")
    }

    /**
     * 呼叫者要宣稱成測試 APK 自己的套件名，不是 production 預設的 `com.android.shell`——
     * system_server 會拿它跟 calling uid 對。
     */
    fun startService() {
        service = MoonClickerService(context, callerPackage = context.packageName)
    }

    /** @return 建出來的 displayId，失敗時是負數。 */
    fun createDisplay(): Int {
        displayId = service.createVirtualDisplay("moonclicker-tier1", width, height, densityDpi, 0)
        return displayId
    }

    /** setUp 的常規開頭：shell 身分 → 服務 → 顯示器。 */
    fun bootstrap(): Int {
        PuppetRecorder.reset()
        adoptShellIdentity()
        wakeAndUnlock()
        exemptHiddenApis()
        startService()
        return createDisplay()
    }

    /**
     * 最近的 logcat，只留提到 [tags] 的行。
     *
     * Tier 1 有一半的失敗是 system_server 的決定，只出現在 logcat 裡；接進斷言訊息，
     * 一次執行就知道原因。
     */
    fun logcat(vararg tags: String, lines: Int = 600): String {
        val all = shell("logcat -d -t $lines")
        return all.lineSequence()
            .filter { line -> tags.any { it in line } }
            .joinToString("\n")
            .ifEmpty { "(nothing in logcat matched ${tags.toList()})" }
    }

    /**
     * 掛一個 ImageReader 到這個顯示器上取樣影格，直到 [done] 成立或逾時。
     *
     * 拆除順序不能改：拔 sink → 用鎖等在途回呼做完 → 才 `close()`。`close()` 會讓已取得的
     * Image buffer 失效，回呼還在讀就是 `IllegalStateException: buffer is inaccessible`。
     */
    private fun sampleFrames(
        displayId: Int,
        timeoutMs: Long,
        onFrame: (buffer: java.nio.ByteBuffer, rowStride: Int) -> Unit,
        done: () -> Boolean,
    ): Boolean {
        val thread = HandlerThread("frame-sampler").apply { start() }
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        val lock = Object()
        var closing = false

        reader.setOnImageAvailableListener({ r ->
            synchronized(lock) {
                if (closing) return@setOnImageAvailableListener
                val image = r.acquireLatestImage() ?: return@setOnImageAvailableListener
                image.use { onFrame(it.planes[0].buffer, it.planes[0].rowStride) }
            }
        }, Handler(thread.looper))

        val handle = service.addVirtualDisplaySurface(displayId, reader.surface)
        try {
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (done()) return true
                Thread.sleep(50)
            }
            return done()
        } finally {
            if (handle >= 0) service.removeVirtualDisplaySurface(displayId, handle)
            synchronized(lock) { closing = true }
            reader.close()
            thread.quitSafely()
        }
    }

    /**
     * 等到連續 [stableFrames] 張影格取樣相同，也就是畫面不再變動。
     *
     * 量影格而不是聽版面回報：旋轉動畫期間 `contentSize` 已是新方向，緩衝區裡卻還是轉到
     * 一半的舊內容，`vision` 會以 confidence 1.0 命中過渡位置。這一個條件同時涵蓋啟動動畫、
     * 旋轉動畫與 splash 收起來。見 docs/virtual-display-pitfalls.md。
     */
    fun awaitStableFrame(
        displayId: Int,
        stableFrames: Int = 8,
        timeoutMs: Long = 8_000,
    ): Boolean {
        val runLength = java.util.concurrent.atomic.AtomicInteger(0)
        val lastHash = java.util.concurrent.atomic.AtomicLong(Long.MIN_VALUE)
        return sampleFrames(
            displayId = displayId,
            timeoutMs = timeoutMs,
            onFrame = { buffer, rowStride ->
                var hash = 1125899906842597L
                var offset = 0
                // 每隔幾列取一個 pixel 就夠分辨「畫面有沒有動」，不必掃完整張。
                while (offset + 4 <= buffer.limit()) {
                    hash = hash * 31 + buffer.getInt(offset)
                    offset += rowStride * 4
                }
                if (hash == lastHash.get()) runLength.incrementAndGet()
                else { lastHash.set(hash); runLength.set(0) }
            },
            done = { runLength.get() >= stableFrames },
        )
    }

    /**
     * 這個顯示器抓下來的影格裡有幾種不同的顏色，用來回答「畫面上到底有沒有東西」。
     *
     * ATD 系統映像檔沒有圖形堆疊，顯示器照樣建得起來、影格照樣送，但每一張都是全黑；
     * 那時 `vision.*` 比不中是環境問題。量顏色而不是認裝置名，才不會隨映像檔改名而腐爛。
     */
    fun distinctColorsOnDisplay(displayId: Int, sampleMs: Long = 2_000): Int {
        val seen = java.util.Collections.synchronizedSet(HashSet<Int>())
        sampleFrames(
            displayId = displayId,
            timeoutMs = sampleMs,
            onFrame = { buffer, rowStride ->
                var offset = 0
                while (offset + 4 <= buffer.limit() && seen.size < 8) {
                    seen += buffer.getInt(offset)
                    offset += rowStride * 8
                }
            },
            done = { false },  // 取滿時間，看總共見過幾種顏色
        )
        return seen.size
    }

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

    override fun close() {
        // 顯示器銷毀前先關掉 puppet，否則它會被系統搬回預設顯示器，
        // 下一個測試會把它從那裡撈回前景而不是在新顯示器上重開。
        PuppetActivity.finishAndWait()
        if (displayId >= 0) {
            runCatching { service.destroyVirtualDisplay(displayId) }
            displayId = -1
        }
        runCatching { uiAutomation.dropShellPermissionIdentity() }
    }
}
