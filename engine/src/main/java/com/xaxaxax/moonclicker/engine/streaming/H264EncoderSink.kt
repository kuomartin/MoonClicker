package com.xaxaxax.moonclicker.engine.streaming

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import com.xaxaxax.moonclicker.IMoonClickerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import timber.log.Timber

/**
 * H264EncoderSink acts as a deep module that manages the lifecycle of a Java MediaCodec and
 * bridges it with the MoonClickerService's GlesDistributor via zero-copy Surface passing.
 * It provides a Kotlin Flow of H.264 NALUs (ByteArrays) suitable for streaming over network.
 */
class H264EncoderSink(
    private val service: IMoonClickerService,
    private val displayId: Int,
    private val width: Int,
    private val height: Int,
    private val bitrate: Int = 2_000_000,
    private val frameRate: Int = 30
) {
    val h264Flow: Flow<ByteArray> = callbackFlow {
        var codec: MediaCodec? = null
        var surfaceHandle: Int = -1

        try {
            codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            // H.264 encoder 普遍不收奇數寬高（例如 Qualcomm OMX 回 ERROR_UNSUPPORTED），向下取偶數；
            // GlesDistributor 會把畫面縮放到 sink surface 的大小，差一像素不影響內容。
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width and 1.inv(), height and 1.inv()).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // 1 second keyframe interval
                
                // Ultra-low latency tuning (inspired by scrcpy)
                setInteger("max-bframes", 0) // Disable B-frames for real-time
                setInteger("priority", 0)    // Real-time priority
                setInteger("operating-rate", frameRate.toShort().toInt())
                // 不支援的 encoder（例如部分 Qualcomm OMX）遇到這個 key 會讓 configure() 直接 BAD_VALUE。
                if (android.os.Build.VERSION.SDK_INT >= 30 &&
                    codec.codecInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                        .isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_LowLatency)
                ) {
                    setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                }
            }
            
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = codec.createInputSurface()
            codec.start()

            // Register to MoonClickerService (Zero-copy GLES fan-out)
            surfaceHandle = service.addVirtualDisplaySurface(displayId, inputSurface)
            if (surfaceHandle < 0) {
                Timber.e("H264EncoderSink: failed to add surface to display $displayId")
                close(RuntimeException("Failed to add surface"))
                return@callbackFlow
            }
            
            // Release the surface locally since the native side has a global ref
            inputSurface.release()

            val bufferInfo = MediaCodec.BufferInfo()
            // spike/latency：輸入 surface 的 presentationTimeUs 是 CLOCK_MONOTONIC，與 System.nanoTime 同一個時鐘。
            var latFrames = 0
            var latSum = 0.0
            var latMax = 0.0
            var latBytes = 0L
            var latWindow = System.nanoTime()
            // dequeueOutputBuffer is blocking, runs on IO dispatcher
            while (isActive) {
                val outputBufferId = codec.dequeueOutputBuffer(bufferInfo, 10000L)
                if (outputBufferId >= 0) {
                    val outputBuffer = codec.getOutputBuffer(outputBufferId)
                    if (outputBuffer != null && bufferInfo.size > 0) {
                        outputBuffer.position(bufferInfo.offset)
                        outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                        val bytes = ByteArray(bufferInfo.size)
                        outputBuffer.get(bytes)
                        trySend(bytes)
                        if (bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                            val nowNs = System.nanoTime()
                            val age = (nowNs / 1000 - bufferInfo.presentationTimeUs) / 1000.0
                            latFrames++
                            latSum += age
                            if (age > latMax) latMax = age
                            latBytes += bufferInfo.size
                            if (nowNs - latWindow >= 2_000_000_000L) {
                                val seconds = (nowNs - latWindow) / 1e9
                                latLog(
                                    "h264 display=$displayId frames=$latFrames fps=%.1f encAvg=%.2f encMax=%.2f kbps=%.0f".format(
                                        latFrames / seconds, latSum / latFrames, latMax, latBytes * 8 / 1000 / seconds,
                                    ),
                                )
                                latFrames = 0; latSum = 0.0; latMax = 0.0; latBytes = 0; latWindow = nowNs
                            }
                        }
                    }
                    codec.releaseOutputBuffer(outputBufferId, false)
                } else if (outputBufferId == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val newFormat = codec.outputFormat
                    Timber.d("H264EncoderSink: output format changed to $newFormat")
                    
                    // JMuxer (and most H.264 decoders) needs SPS/PPS before the first IDR frame.
                    // Some encoders don't emit them as separate buffers, so we extract them from the format.
                    val csd0 = newFormat.getByteBuffer("csd-0")
                    if (csd0 != null) {
                        val bytes0 = ByteArray(csd0.remaining())
                        csd0.get(bytes0)
                        trySend(bytes0)
                    }
                    val csd1 = newFormat.getByteBuffer("csd-1")
                    if (csd1 != null) {
                        val bytes1 = ByteArray(csd1.remaining())
                        csd1.get(bytes1)
                        trySend(bytes1)
                    }
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "H264EncoderSink error")
            // 讓 collector 收到失敗而結束；否則下面的 awaitClose 會永遠等下去，串流端只看到連線卻沒有畫面。
            close(e)
        } finally {
            if (surfaceHandle >= 0) {
                try {
                    service.removeVirtualDisplaySurface(displayId, surfaceHandle)
                } catch (e: Exception) {
                    Timber.e(e, "Failed to remove surface handle $surfaceHandle")
                }
            }
            try {
                codec?.stop()
                codec?.release()
            } catch (e: Exception) {
                Timber.e(e, "Failed to release codec")
            }
        }
        awaitClose { }
    }.flowOn(Dispatchers.IO)
}

/** spike/latency：同 native 的 LatLog.h，寫 logcat 與 files/lat.log。 */
internal fun latLog(message: String) {
    android.util.Log.i("LAT", message)
    val pkg = java.io.File("/proc/self/cmdline").readText().trimEnd('\u0000').substringBefore(':')
    runCatching {
        java.io.File("/data/user/0/$pkg/files/lat.log")
            .appendText("%.3f %s\n".format(System.currentTimeMillis() / 1000.0, message))
    }
}
