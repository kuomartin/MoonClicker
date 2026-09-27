package com.xaxaxax.moonclicker.ocr

import android.os.ParcelFileDescriptor
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import java.io.File

/**
 * androidTest 用的 OCR 套件與樣本：手動推到 [SHELL_DIR]，測試以 shell 身分讀出來。
 *
 * ```
 * adb shell mkdir -p /data/local/tmp/ocr
 * adb push ocr-pack-<abi>.zip /data/local/tmp/ocr/
 * adb push <樣本目錄> /data/local/tmp/ocr/samples
 * ```
 *
 * app 讀不到 `/data/local/tmp`，所以經 `UiAutomation.executeShellCommand` 複製到自己的 cache。
 * 推到 app 的外部檔案目錄行不通：shell 建立的子目錄 app 沒有權限進入。
 */
internal object OcrTestPack {

    const val SHELL_DIR = "/data/local/tmp/ocr"

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /** 安裝推上來的套件並回傳目錄；這個 ABI 沒有套件或沒推上來時回傳 `null`。 */
    fun install(): File? {
        val pack = OcrPack.forThisProcess() ?: return null
        val zip = copyFromShell("$SHELL_DIR/ocr-pack-${pack.abi}.zip") ?: return null
        val installer = OcrPackInstaller(File(context.filesDir, "ocr"), context.cacheDir, pack, OcrPack.processAbi())
        runBlocking { installer.installFromZip(zip) }
        val state = installer.state.value
        check(state is OcrPackState.Installed) { "installing $zip failed: $state" }
        return state.dir
    }

    /** [SHELL_DIR]`/samples` 裡的檔名。 */
    fun sampleNames(): Set<String> =
        shell("ls $SHELL_DIR/samples").lines().map { it.trim() }.filter { it.endsWith(".png") }.toSet()

    /** 以 shell 身分讀出 [path] 複製到 cache；檔案不存在回傳 `null`。 */
    fun copyFromShell(path: String): File? {
        if (shell("ls $path").trim() != path) return null
        val target = File(context.cacheDir, "ocr-test/${path.substringAfterLast('/')}")
        target.parentFile!!.mkdirs()
        ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cat $path")
        ).use { input -> target.outputStream().use { input.copyTo(it) } }
        return target
    }

    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        ).use { it.readBytes().toString(Charsets.UTF_8) }
}
