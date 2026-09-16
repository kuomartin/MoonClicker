package com.xaxaxax.relc.engine.streaming

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import com.xaxaxax.relc.IRelcV2Service
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import timber.log.Timber

/**
 * H264EncoderSink acts as a deep module that manages the lifecycle of a Java MediaCodec and
 * bridges it with the RelcV2Service's GlesDistributor via zero-copy Surface passing.
 * It provides a Kotlin Flow of H.264 NALUs (ByteArrays) suitable for streaming over network.
 */
class H264EncoderSink(
    private val service: IRelcV2Service,
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
            }
            
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            val inputSurface = codec.createInputSurface()
            codec.start()

            // Register to RelcV2Service (Zero-copy GLES fan-out)
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
                    Timber.d("H264EncoderSink: output format changed to ${codec.outputFormat}")
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
