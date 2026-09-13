package com.xaxaxax.relc.script

import android.content.Context
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
        val fd = uiAutomation.executeShellCommand("logcat -d -t $lines")
        val all = android.os.ParcelFileDescriptor.AutoCloseInputStream(fd)
            .bufferedReader().use { it.readText() }
        return all.lineSequence()
            .filter { line -> tags.any { it in line } }
            .joinToString("\n")
            .ifEmpty { "(nothing in logcat matched ${tags.toList()})" }
    }

    /**
     * `dumpsys window` 裡跟 [displayId] 有關的行。
     *
     * 「注入到達了 InputDispatcher，但 app 沒收到」只有兩種可能：那個顯示器上根本沒有可觸
     * 控的視窗，或者有但系統不肯派送給它。這份輸出把兩者分開。
     */
    fun windowsOnDisplay(displayId: Int): String {
        val fd = uiAutomation.executeShellCommand("dumpsys window displays")
        val all = android.os.ParcelFileDescriptor.AutoCloseInputStream(fd)
            .bufferedReader().use { it.readText() }
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
