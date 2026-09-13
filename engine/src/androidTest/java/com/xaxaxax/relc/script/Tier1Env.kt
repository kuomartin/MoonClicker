package com.xaxaxax.relc.script

import android.content.Context
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import androidx.test.platform.app.InstrumentationRegistry
import com.xaxaxax.relc.RelcV2Service
import com.xaxaxax.relc.script.puppet.PuppetActivity
import com.xaxaxax.relc.script.puppet.PuppetRecorder
import org.lsposed.hiddenapibypass.LSPass

/**
 * Tier 1 的執行環境：**真的** [RelcV2Service]，跑在測試進程裡，用 shell 身分。
 *
 * 這件事之所以成立，是因為 `UiAutomation.adoptShellPermissionIdentity()` 讓權限檢查以
 * shell 身分進行——而 Shizuku 給 ReLC 的正是 shell 身分。所以這裡不是替身，是同一份
 * production 程式碼，只是換了一個宿主進程；順帶讓 [RelcV2Service] 第一次有了覆蓋。
 *
 * 換宿主進程要補的兩件事，都是 Shizuku 進程「免費」拿到、而這裡拿不到的：
 *
 *  - **隱藏 API 豁免**（[exemptHiddenApis]）——shell uid 整個免受名單約束，app uid 不是。
 *  - **呼叫者套件名**（[startService]）——production 寫死 `com.android.shell`，在這裡
 *    對不上 calling uid，system_server 會擋。
 *
 * 兩者都不是 production 的 bug，是「這段程式碼一直假設自己住在 shell 進程裡」這件事第一次
 * 被寫下來。
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

    lateinit var service: RelcV2Service
        private set

    var displayId: Int = -1
        private set

    fun adoptShellIdentity() {
        uiAutomation.adoptShellPermissionIdentity()
    }

    /**
     * 把裝置叫醒並解掉鎖定畫面。
     *
     * 沒有 `ALWAYS_UNLOCKED`（在 API 33 那組旗標裡）的虛擬顯示，在裝置 Dozing 或停在
     * keyguard 時**收不到注入的觸控**，而症狀跟座標換算錯、權限不足、顯示器沒建起來一樣。
     *
     * 這是測試可以自己建立的前提，不該寫進 README 要人記得。
     * 見 docs/virtual-display-pitfalls.md。
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
     * shell 身分給的是**權限**，不是隱藏 API 的豁免——那兩件事在平台上是分開的。
     *
     * Shizuku 進程不需要這一步，因為它跑在 shell uid 上，而 shell/system uid 整個免受
     * 隱藏 API 名單約束。測試進程是普通 app uid，所以 `RelcV2Service` 那些反射會以
     * `NoSuchMethodException` 失敗——它的 init 裡那份 LSPass 名單也沒有涵蓋
     * `DisplayManager.<init>`，因為 production 從來不需要。
     *
     * 一次全開而不是逐一列舉：這裡不是在驗「哪些隱藏 API 拿得到」（那是
     * `:hidden-api-contract` 的事），是在還原 production 的執行條件。
     */
    fun exemptHiddenApis() {
        LSPass.addHiddenApiExemptions("L")
    }

    /**
     * 向系統宣稱的呼叫者要是**我們自己**的套件名，不是 production 預設的
     * `com.android.shell`——system_server 會拿它跟 calling uid 對，而這裡的 calling uid
     * 是測試 APK 的，不是 shell 的。
     */
    fun startService() {
        service = RelcV2Service(context, callerPackage = context.packageName)
    }

    /** @return 建出來的 displayId，失敗時是負數。 */
    fun createDisplay(): Int {
        displayId = service.createVirtualDisplay("relc-tier1", width, height, densityDpi, 0)
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
     * Tier 1 有一半的失敗是 system_server 那邊做的決定，而它只會出現在 logcat 裡——
     * 把它接進斷言訊息，一次執行就知道原因，不用再手動重跑一次去撈。
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
     * **拆除順序不能改**：拔 sink → 用鎖等在途回呼做完 → 才 `close()`。`close()` 會讓已取得
     * 的 Image buffer 失效，回呼還在讀就是 `IllegalStateException: buffer is inaccessible`。
     * 與 `NativeImageReader::release` 同一個約束。
     *
     * 收在這裡，取樣邏輯就不必各自重寫一遍。
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
     * 等到這個顯示器的畫面**不再變動**。
     *
     * 「版面回報它好了」是代理訊號，合成出來的影格比它慢：旋轉動畫期間 `contentSize` 已經
     * 是新方向，緩衝區裡還是舊內容轉到一半，`vision` 會以 confidence 1.0 命中一個過渡位置。
     *
     * 所以量真正在意的性質：連續 [stableFrames] 張影格取樣相同。這一個條件同時涵蓋啟動
     * 動畫、旋轉動畫與 splash 收起來，不必各補一條。見 docs/virtual-display-pitfalls.md。
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
     * 這個顯示器抓下來的影格裡有幾種不同的顏色。
     *
     * 用來回答「畫面上到底有沒有東西」。ATD（automated test device）系統映像檔把圖形堆疊
     * 拿掉了，虛擬顯示照樣建得起來、`GlesDistributor` 照樣以 60fps 送影格——但每一張都是
     * 全黑。那時 `vision.*` 比不中不是 bug，是這個環境不提供被測的東西。
     *
     * 量性質而不是認裝置名：`Build.PRODUCT` 裡有沒有 "atd" 是 proxy，會隨映像檔改名而腐爛，
     * 而「影格是不是全同色」就是我們真正在意的那件事。
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
     * `dumpsys window` 裡跟 [displayId] 有關的行。
     *
     * 「注入到達了 InputDispatcher，但 app 沒收到」只有兩種可能：那個顯示器上根本沒有可觸
     * 控的視窗，或者有但系統不肯派送給它。這份輸出把兩者分開。
     */
    fun windowsOnDisplay(displayId: Int): String {
        val all = shell("dumpsys window displays")
        val start = all.indexOf("Display: mDisplayId=$displayId")
        if (start < 0) return "(display $displayId not in `dumpsys window displays`)"
        val next = all.indexOf("Display: mDisplayId=", start + 1)
        return all.substring(start, if (next < 0) all.length else next).take(4000)
    }

    override fun close() {
        // 顯示器銷毀前先關掉 puppet——不然它會被系統搬回預設顯示器，下一個測試就會把它
        // 從那裡撈回前景，而不是在新的顯示器上重開。
        PuppetActivity.finishAndWait()
        if (displayId >= 0) {
            runCatching { service.destroyVirtualDisplay(displayId) }
            displayId = -1
        }
        runCatching { uiAutomation.dropShellPermissionIdentity() }
    }
}
