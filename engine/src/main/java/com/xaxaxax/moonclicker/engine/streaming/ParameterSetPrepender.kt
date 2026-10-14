package com.xaxaxax.moonclicker.engine.streaming

/**
 * 在每個關鍵影格前補上 SPS/PPS。encoder 只在開頭輸出一次參數集，解碼端中途重設（例如 JMuxer
 * 遇到解碼錯誤）後就再也等不到，畫面會黑到重新連線為止。
 */
internal class ParameterSetPrepender {
    private var parameterSets: ByteArray? = null

    /** encoder 的 codec config：`BUFFER_FLAG_CODEC_CONFIG` 的輸出，或 output format 的 csd-0 加 csd-1。 */
    fun onCodecConfig(bytes: ByteArray) {
        parameterSets = bytes
    }

    fun frame(bytes: ByteArray, isKeyFrame: Boolean): ByteArray {
        val sets = parameterSets
        return if (isKeyFrame && sets != null) sets + bytes else bytes
    }
}
