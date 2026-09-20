package com.xaxaxax.moonclicker.ui.displaydetail

import android.graphics.Bitmap
import android.view.TextureView
import com.xaxaxax.moonclicker.workbench.FrameSource
import com.xaxaxax.moonclicker.workbench.WorkbenchServer
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** JPEG 編碼品質，區網串流用；再高只換到肉眼看不出的差別與更大的頻寬。 */
private const val JPEG_QUALITY = 70

/**
 * 兩幀之間的最短間隔，即上限約 10 fps。VD 可以用 60 fps 送新畫面，但每一幀都要全像素轉正
 * 加 JPEG 編碼，照單全收會把一整顆 CPU 吃光換來遠端根本分辨不出的流暢度。
 */
private const val MIN_FRAME_INTERVAL_MS = 100L

/**
 * [WorkbenchServer] 的正式 [FrameSource]：把 [VirtualDisplayMirror] 那個 `TextureView` 的內容
 * 接成 `/mirror/{displayId}` 的連續 JPEG 幀（見 #76）。
 *
 * 需要這一層登記處，是因為擷取端與伺服器端的生命週期本來就對不上——`TextureView` 是
 * [FullscreenDisplayActivity] 的東西，只在鏡像畫面開著時存在，而 [WorkbenchServer] 是跟畫面
 * 無關的 singleton。VD 的內容在這個 app 裡沒有第二個實體，所以鏡像畫面沒開時就沒有幀可送，
 * route 依 [frames] 回傳 null 回 404。
 *
 * 新畫面由 `onSurfaceTextureUpdated` 推進來（[onFrameAvailable]），不是定時輪詢：VD 閒置時
 * 沒有 callback，collector 就停在 [Slot.revision] 上，不做任何事。同一個 `StateFlow` 的
 * conflation 也是丟幀機制——編碼一幀期間累積的更新收斂成醒來後的一次擷取，不排隊補送。
 */
@Singleton
class MirrorFrameSource @Inject constructor() : FrameSource {

    /**
     * 一次擷取的結果：`TextureView` 的 buffer bitmap，已經是邏輯空間（distributor 轉正過，
     * ADR-0017），不需要額外處理，直接編碼。
     */
    data class Captured(val bitmap: Bitmap)

    /** 在 UI thread 上執行的一次擷取；畫面尚未就緒（[TextureView.getBitmap] 回 null）時回 null。 */
    fun interface Capture {
        fun capture(): Captured?
    }

    private class Slot {
        /** 目前活著的擷取來源；鏡像畫面不在前景時是 null。 */
        @Volatile
        var capture: Capture? = null

        /** 每收到一次 `onSurfaceTextureUpdated` 遞增；collector 只在乎它有沒有變。 */
        val revision = MutableStateFlow(0L)
    }

    // slot 不隨 unregister 移除：一條串流中途 activity 重建（新的 TextureView）時，沿用同一個
    // revision flow 才能讓收集中的 client 接上新的來源，而不是永遠停在死掉的那一份。
    private val slots = ConcurrentHashMap<Int, Slot>()

    fun register(displayId: Int, capture: Capture) {
        slots.getOrPut(displayId) { Slot() }.capture = capture
    }

    fun unregister(displayId: Int) {
        slots[displayId]?.capture = null
    }

    fun onFrameAvailable(displayId: Int) {
        slots[displayId]?.revision?.update { it + 1 }
    }

    override fun frames(displayId: Int): Flow<ByteArray>? {
        val slot = slots[displayId] ?: return null
        if (slot.capture == null) return null
        return slot.jpegFrames()
    }

    private fun Slot.jpegFrames(): Flow<ByteArray> = flow {
        var lastSentAtNanos = 0L
        revision.collect {
            // 上一幀送出後還不到 MIN_FRAME_INTERVAL_MS 就被叫醒，先睡滿再擷取；睡的期間 VD
            // 繼續更新，StateFlow 的 conflation 讓那些更新收斂成醒來後的一次擷取。
            val elapsedMs = (System.nanoTime() - lastSentAtNanos) / 1_000_000
            if (elapsedMs < MIN_FRAME_INTERVAL_MS) delay(MIN_FRAME_INTERVAL_MS - elapsedMs)
            val jpeg = encodeFrame() ?: return@collect
            lastSentAtNanos = System.nanoTime()
            emit(jpeg)
        }
    }

    /** @return JPEG bytes；來源已經解除登記或畫面尚未就緒時回 null。 */
    private suspend fun Slot.encodeFrame(): ByteArray? {
        val capture = capture ?: return null
        // getBitmap() 讀的是 view 的 texture，跟既有的縮圖/裁切擷取一樣走 UI thread。
        val captured = withContext(Dispatchers.Main) { capture.capture() } ?: return null
        return withContext(Dispatchers.Default) {
            val jpeg = ByteArrayOutputStream().use { out ->
                captured.bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
                out.toByteArray()
            }
            // 每幀都是新配置的全解析度 bitmap，用完立刻還回去，不留給 GC 追著跑。
            captured.bitmap.recycle()
            jpeg
        }
    }
}
