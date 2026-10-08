package com.xaxaxax.moonclicker.script

import android.graphics.Bitmap
import android.os.Process
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.view.InputDevice
import android.view.MotionEvent
import com.xaxaxax.moonclicker.engine.state.EngineRunState
import com.xaxaxax.moonclicker.engine.streaming.H264EncoderSink
import com.xaxaxax.moonclicker.engine.streaming.latLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import com.xaxaxax.moonclicker.ocr.OcrRuntime
import com.xaxaxax.moonclicker.ocr.OcrTestPack
import com.xaxaxax.moonclicker.script.puppet.PuppetActivity
import com.xaxaxax.moonclicker.script.puppet.PuppetMarker
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * spike/latency：#133 的量測，不是驗證。耗時由引擎內的 `LAT` log 記錄，這裡只負責擺好畫面與
 * 跑迴圈；結果以 `adb logcat -s LAT` 收集，見 docs/research/latency.md。
 *
 * ```
 * adb shell am instrument -w -e class com.xaxaxax.moonclicker.script.LatencyBenchTest \
 *   -e ocrThreads 4 com.xaxaxax.moonclicker.engine.test/androidx.test.runner.AndroidJUnitRunner
 * ```
 */
@RunWith(AndroidJUnit4::class)
class LatencyBenchTest {

    @get:Rule
    val env = Tier1Env()

    private val args get() = InstrumentationRegistry.getArguments()
    private val rounds get() = args.getString("rounds")?.toInt() ?: 30

    @Test
    fun template_matching_720x1280() = templateMatching(720, 1280, 320)

    @Test
    fun template_matching_1080x2400() = templateMatching(1080, 2400, 420)

    @Test
    fun ocr_720x1280() {
        val threads = args.getString("ocrThreads")?.toInt() ?: 2
        val dir: File? = OcrTestPack.install()
        assumeTrue("push ocr-pack-<abi>.zip into ${OcrTestPack.SHELL_DIR}", dir != null)
        val displayId = env.createDisplay()
        env.launchPuppet(displayId)
        PuppetActivity.setVisible(marker = false, glyph = false)
        PuppetActivity.setText("LEVEL 12 SCORE 3450 START")
        SystemClock.sleep(SETTLE_MS)
        latLog("begin ocr threads=$threads")
        val outcome = env.runScript(
            displayId,
            """
            for i = 1, $rounds do vision.read({ 0, 560, 720, 160 }) end
            for i = 1, $rounds do vision.read_lines({ 0, 480, 720, 320 }) end
            for i = 1, 10 do vision.read_lines() end
            for i = 1, 10 do vision.find({ text = "START" }) end
            """.trimIndent(),
            timeoutMs = 600_000,
            ocr = OcrRuntime(dir!!, threads = threads),
        )
        assertEquals("script did not finish: ${outcome.runState}", EngineRunState.Finished, outcome.runState)
    }

    /** 等不到的 `vision.wait`：各個 step_ms 跑 20 秒，記錄本行程的 CPU 時間。 */
    @Test
    fun wait_polling_cpu() {
        val displayId = env.createDisplay()
        env.launchPuppet(displayId)
        SystemClock.sleep(SETTLE_MS)
        for (step in listOf(0, 100, 500, 1000)) {
            val before = cpuMs()
            val started = SystemClock.uptimeMillis()
            val stepArg = if (step == 0) "" else ", $step"
            env.runScript(
                displayId,
                """vision.wait({ image = "t64.png", roi = { 0, 0, 200, 200 } }, 20000$stepArg)""",
                timeoutMs = 60_000,
                extraAssets = templates(),
            )
            val wall = SystemClock.uptimeMillis() - started
            latLog("wait step=$step wallMs=$wall cpuMs=${cpuMs() - before} cpuPct=%.1f".format((cpuMs() - before) * 100.0 / wall))
        }
    }

    /** 畫面持續變動時的 H.264 編碼延遲與 distributor 負擔；log 由 H264EncoderSink 與 GlesDistributor 寫。 */
    @Test
    fun h264_encode() {
        val displayId = env.createDisplay()
        env.launchPuppet(displayId)
        SystemClock.sleep(SETTLE_MS)
        val sink = H264EncoderSink(env.service, displayId, Tier1Env.WIDTH, Tier1Env.HEIGHT)
        latLog("begin h264 static")
        runBlocking {
            val job = launch(Dispatchers.IO) { sink.h264Flow.collect { } }
            delay(12_000)
            latLog("begin h264 animated")
            val animator = launch(Dispatchers.Default) {
                var on = true
                while (isActive) {
                    PuppetActivity.setVisible(marker = on, glyph = !on)
                    on = !on
                    delay(33)
                }
            }
            delay(12_000)
            animator.cancel()
            job.cancel()
        }
    }

    /** 注入一次觸控（down＋up）要多久，對照 VS Code 送點擊的網路來回。 */
    @Test
    fun input_injection() {
        val displayId = env.createDisplay()
        env.launchPuppet(displayId)
        SystemClock.sleep(SETTLE_MS)
        val samples = (1..50).map {
            val t0 = System.nanoTime()
            val now = SystemClock.uptimeMillis()
            listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach { action ->
                val event = MotionEvent.obtain(now, now, action, 360f, 640f, 0)
                event.source = InputDevice.SOURCE_TOUCHSCREEN
                env.service.injectMotionEvent(event, displayId)
                event.recycle()
            }
            (System.nanoTime() - t0) / 1e6
        }.sorted()
        latLog("inject n=50 p50=%.2f p90=%.2f max=%.2f".format(samples[25], samples[45], samples.last()))
    }

    /**
     * 鏡像實體螢幕的負擔：各狀態 60 秒，記錄本行程、system_server、surfaceflinger 的 CPU。
     * 狀態：只有 VD；加上實體螢幕鏡像（沒有消費者）；鏡像加上 H.264 編碼。
     */
    @Test
    fun mirror_cost() {
        val displayId = env.createDisplay()
        env.launchPuppet(displayId)
        SystemClock.sleep(SETTLE_MS)
        measureCpu("vd-only")
        check(env.service.acquireDisplayMirror(0)) { "acquireDisplayMirror(0) failed" }
        try {
            SystemClock.sleep(SETTLE_MS)
            measureCpu("mirror-idle")
            val physical = env.context.resources.displayMetrics
            val sink = H264EncoderSink(env.service, 0, physical.widthPixels, physical.heightPixels)
            runBlocking {
                val job = launch(Dispatchers.IO) { sink.h264Flow.collect { } }
                delay(SETTLE_MS)
                withContext(Dispatchers.IO) { measureCpu("mirror-h264") }
                job.cancel()
            }
        } finally {
            env.service.releaseDisplayMirror(0)
        }
    }

    private fun measureCpu(label: String) {
        val pids = mapOf(
            "self" to Process.myPid().toString(),
            "system_server" to env.shell("pidof system_server").trim(),
            "surfaceflinger" to env.shell("pidof surfaceflinger").trim(),
        )
        fun ticks(pid: String): Long {
            val fields = env.shell("cat /proc/$pid/stat").substringAfterLast(')').trim().split(" ")
            return fields[11].toLong() + fields[12].toLong()
        }
        val before = pids.mapValues { ticks(it.value) }
        val temp0 = env.shell("dumpsys battery").lineSequence().first { "temperature" in it }.trim()
        val t0 = SystemClock.uptimeMillis()
        SystemClock.sleep(60_000)
        val wall = SystemClock.uptimeMillis() - t0
        val usage = pids.map { (name, pid) -> "$name=%.1f%%".format((ticks(pid) - before.getValue(name)) * 10.0 * 100 / wall) }
        latLog("cpu state=$label ${usage.joinToString(" ")} $temp0")
    }

    private fun templateMatching(width: Int, height: Int, dpi: Int) {
        val displayId = env.createDisplay(width, height, dpi)
        env.launchPuppet(displayId)
        SystemClock.sleep(SETTLE_MS)
        latLog("begin tm display=${width}x$height")
        val outcome = env.runScript(
            displayId,
            """
            local W, H = screen.width, screen.height
            for _, name in ipairs({ "t32.png", "t64.png", "t128.png", "t256.png" }) do
                for i = 1, $rounds do vision.find(name) end
                for i = 1, $rounds do vision.find({ image = name, gray = true }) end
                for i = 1, $rounds do vision.find({ image = name, roi = { 0, 0, W // 2, H // 4 } }) end
                for i = 1, $rounds do vision.find({ image = name, scale = 0.5 }) end
            end
            """.trimIndent(),
            timeoutMs = 900_000,
            extraAssets = templates(),
        )
        assertEquals("script did not finish: ${outcome.runState}", EngineRunState.Finished, outcome.runState)
    }

    private fun templates(): Map<String, ByteArray> {
        val marker = PuppetMarker.bitmap()
        return listOf(32, 64, 128, 256).associate { size ->
            val scaled = Bitmap.createScaledBitmap(marker, size, size, true)
            "t$size.png" to ByteArrayOutputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
                out.toByteArray()
            }
        }
    }

    /** 本行程的 user+sys CPU 時間（`/proc/self/stat` 第 14、15 欄，單位 clock tick）。 */
    private fun cpuMs(): Long {
        val fields = File("/proc/${Process.myPid()}/stat").readText().substringAfterLast(')').trim().split(" ")
        val ticks = fields[11].toLong() + fields[12].toLong()
        return ticks * 1000 / 100
    }

    private companion object {
        const val SETTLE_MS = 4_000L
    }
}
