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
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
                setInteger(MediaFormat.KEY_FRAME_RATE, frameRate)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // 1 second keyframe interval
                
                // Ultra-low latency tuning (inspired by scrcpy)
                setInteger("max-bframes", 0) // Disable B-frames for real-time
                setInteger("priority", 0)    // Real-time priority
                setInteger("operating-rate", frameRate.toShort().toInt())
                if (android.os.Build.VERSION.SDK_INT >= 30) {
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
