package com.xaxaxax.relc.display

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.view.Surface
import com.xaxaxax.relc.core.DisplayConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.nio.ByteBuffer

/**
 * H264 編碼輸出端。
 * 它會建立一個 MediaCodec 編碼器，並提供其 Input Surface 給 VirtualDisplay 使用。
 */
class H264EncoderSink(
    private val config: DisplayConfig,
    private val onEncodedFrame: (ByteBuffer, MediaCodec.BufferInfo) -> Unit
) : DisplaySink {

    private var codec: MediaCodec? = null
    private var inputSurface: Surface? = null
    private var workerJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    override fun acquireSurface(): Surface? {
        if (codec == null) {
            setupCodec()
        }
        return inputSurface
    }

    private fun setupCodec() {
        // 1. 配置 MediaFormat
        val format = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC,
            config.width,
            config.height
        ).apply {
            // 必須設定為 Surface 輸入
            setInteger(
                MediaFormat.KEY_COLOR_FORMAT,
                MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface
            )
            setInteger(MediaFormat.KEY_BIT_RATE, 4_000_000) // 4Mbps
            setInteger(MediaFormat.KEY_FRAME_RATE, 60)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // 1 秒一個 I-frame

            // 低延遲相關設定 (API 26+)
            setInteger(MediaFormat.KEY_LATENCY, 0)
            // 優先權設定 (API 23+)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
        }

        // 2. 建立編碼器
        codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            // 3. 建立輸入用的 Surface
            inputSurface = createInputSurface()
        }
    }

    override fun start() {
        val c = codec ?: return
        c.start()

        // 4. 啟動協程不斷讀取編碼後的資料 (Output Buffer)
        workerJob = scope.launch {
            val info = MediaCodec.BufferInfo()
            while (isActive) {
                try {
                    // 等待輸出資料，超時設為 10ms 避免阻塞太久
                    val index = c.dequeueOutputBuffer(info, 10_000)
                    if (index >= 0) {
                        val buffer = c.getOutputBuffer(index)
                        if (buffer != null) {
                            // 這裡回傳的是原始的 H264 NAL Units (包含 SPS/PPS 和 IDR/P-Frame)
                            onEncodedFrame(buffer, info)
                        }
                        c.releaseOutputBuffer(index, false)
                    } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        Timber.d("Encoder output format changed: ${c.outputFormat}")
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Error during encoding")
                    break
                }
            }
        }
    }

    override fun stop() {
        workerJob?.cancel()
        workerJob = null
        try {
            codec?.stop()
        } catch (e: Exception) {
            // ignore
        }
    }

    override fun release() {
        stop()
        codec?.release()
        codec = null
        inputSurface?.release()
        inputSurface = null
        scope.cancel()
    }
}
