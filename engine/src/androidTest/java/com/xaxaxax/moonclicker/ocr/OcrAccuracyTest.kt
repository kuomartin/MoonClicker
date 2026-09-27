package com.xaxaxax.moonclicker.ocr

import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.text.Normalizer

/**
 * OCR 在裝置上的準確率與延遲，對照 `docs/research/ocr-engine-selection.md` 的選型量測。
 *
 * 樣本是選型研究用的 16 張 2400×1080 遊戲截圖，不進 repo；標準答案在 androidTest assets。
 * 套件與樣本要先手動推到 `/data/local/tmp/ocr/`，沒有就跳過：
 *
 * ```
 * adb shell mkdir -p /data/local/tmp/ocr
 * adb push ocr-pack-<abi>.zip /data/local/tmp/ocr/
 * adb push <樣本目錄> /data/local/tmp/ocr/samples
 * ```
 *
 * app 讀不到 `/data/local/tmp`，測試以 shell 身分（`UiAutomation.executeShellCommand`）讀出來
 * 複製到自己的 cache。推到 app 的外部檔案目錄行不通：shell 建立的子目錄 app 沒有權限進入。
 *
 * 準確率有斷言（不得低於研究的 72/79 行、26/28 讀數）；延遲只印在 logcat（tag `OcrAccuracyTest`），
 * 因為它取決於機型，要人和研究的表對照。
 */
@RunWith(AndroidJUnit4::class)
class OcrAccuracyTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun accuracy_and_latency_match_the_engine_selection_research() = runBlocking {
        val pack = OcrPack.forThisProcess()
        assumeTrue("no OCR pack for ${OcrPack.processAbi()}", pack != null)
        val zip = copyFromShell("$SHELL_DIR/ocr-pack-${pack!!.abi}.zip")
        assumeTrue("push ocr-pack-${pack.abi}.zip into $SHELL_DIR to run this test", zip != null)
        val sampleNames = shell("ls $SHELL_DIR/samples").lines().map { it.trim() }.filter { it.endsWith(".png") }.toSet()
        assumeTrue("push the samples into $SHELL_DIR/samples to run this test", sampleNames.isNotEmpty())

        val installer = OcrPackInstaller(File(context.filesDir, "ocr"), context.cacheDir, pack, OcrPack.processAbi())
        installer.installFromZip(zip!!)
        val state = installer.state.value
        assertTrue("installing $zip failed: $state", state is OcrPackState.Installed)
        val packDir = (state as OcrPackState.Installed).dir

        val calibration = OcrCalibrator.measure(packDir)
        val threads = OcrCalibrator.fastest(calibration)!!
        log("calibration ${calibration.joinToString { "${it.threads}=${"%.0f".format(it.millis)}ms" }} -> $threads threads")

        val truth = JSONObject(context.assets.open("ocr/ground_truth.json").bufferedReader().readText())
        val handle = OcrNative.nativeCreate(packDir.absolutePath, threads)
        try {
            var lines = 0
            var linesExact = 0
            var reads = 0
            var readsExact = 0
            val fullMs = mutableListOf<Double>()
            val readMs = mutableListOf<Double>()
            val wrong = mutableListOf<String>()

            for (name in truth.keys()) {
                if (name.startsWith("_")) continue
                assumeTrue("missing sample $name", name in sampleNames)
                val image = copyFromShell("$SHELL_DIR/samples/$name")!!
                val expected = truth.getJSONObject(name)

                val full = read(handle, image, roi = null, detect = true)
                fullMs += full.getDouble("det_ms") + full.getDouble("rec_ms")
                val texts = full.getJSONArray("lines").let { a -> List(a.length()) { a.getJSONObject(it).getString("text") } }
                val expectedLines = expected.getJSONArray("lines")
                for (i in 0 until expectedLines.length()) {
                    val line = expectedLines.getString(i)
                    val best = texts.minByOrNull { cer(line, it) } ?: ""
                    lines++
                    if (norm(line) == norm(best)) linesExact++ else wrong += "line $name: $line -> $best"
                }

                val expectedReads = expected.getJSONArray("reads")
                for (i in 0 until expectedReads.length()) {
                    val spec = expectedReads.getJSONObject(i)
                    val roi = spec.getJSONArray("roi").let { r -> IntArray(4) { r.getInt(it) } }
                    // 每個 ROI 跑 5 次，第一次視為暖機，與研究的量法相同。
                    val results = List(5) { read(handle, image, roi, detect = false) }
                    results.drop(1).forEach { readMs += it.getDouble("rec_ms") }
                    val text = results.first().getJSONArray("lines").getJSONObject(0).getString("text")
                    reads++
                    if (norm(text) == norm(spec.getString("text"))) readsExact++
                    else wrong += "read $name/${spec.getString("name")}: ${spec.getString("text")} -> $text"
                }
            }

            log("lines $linesExact/$lines, reads $readsExact/$reads")
            log("full frame p50 ${p(fullMs, 50)} ms p95 ${p(fullMs, 95)} ms; ROI read p50 ${p(readMs, 50)} ms p95 ${p(readMs, 95)} ms")
            wrong.forEach(::log)

            assertEquals("every expected line and read was scored", 79 to 28, lines to reads)
            assertTrue("only $linesExact/$lines lines exact; research measured 72/79", linesExact >= 72)
            assertTrue("only $readsExact/$reads ROI reads exact; research measured 26/28", readsExact >= 26)
        } finally {
            OcrNative.nativeDestroy(handle)
        }
    }

    /** 以 shell 身分執行指令並回傳 stdout。 */
    private fun shell(command: String): String =
        ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        ).use { it.readBytes().toString(Charsets.UTF_8) }

    /** 以 shell 身分讀出 [path] 複製到 cache；檔案不存在回傳 `null`。 */
    private fun copyFromShell(path: String): File? {
        if (shell("ls $path").trim() != path) return null
        val target = File(context.cacheDir, "ocr-test/${path.substringAfterLast('/')}")
        target.parentFile!!.mkdirs()
        ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("cat $path")
        ).use { input -> target.outputStream().use { input.copyTo(it) } }
        return target
    }

    private fun read(handle: Long, image: File, roi: IntArray?, detect: Boolean) =
        JSONObject(String(OcrNative.nativeReadImage(handle, image.absolutePath, roi, detect), Charsets.UTF_8))

    private companion object {
        const val SHELL_DIR = "/data/local/tmp/ocr"
    }

    private fun log(message: String) {
        Log.i("OcrAccuracyTest", message)
    }

    private fun p(values: List<Double>, percentile: Int): Long {
        val sorted = values.sorted()
        return Math.round(sorted[minOf(sorted.size - 1, Math.round(percentile / 100.0 * (sorted.size - 1)).toInt())])
    }

    /** 與研究的評分腳本相同：NFKC 正規化並去除空白。 */
    private fun norm(s: String) = Normalizer.normalize(s, Normalizer.Form.NFKC).filterNot { it.isWhitespace() }

    private fun cer(expected: String, actual: String): Double {
        val a = norm(expected)
        val b = norm(actual)
        val d = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var previous = d[0]
            d[0] = i
            for (j in 1..b.length) {
                val current = minOf(d[j] + 1, d[j - 1] + 1, previous + if (a[i - 1] == b[j - 1]) 0 else 1)
                previous = d[j]
                d[j] = current
            }
        }
        return d[b.length].toDouble() / maxOf(a.length, 1)
    }
}
