package com.xaxaxax.moonclicker.engine.streaming

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class ParameterSetPrependerTest {
    private val config = byteArrayOf(0, 0, 0, 1, 0x67, 0, 0, 0, 1, 0x68)
    private val idr = byteArrayOf(0, 0, 0, 1, 0x65, 9)
    private val slice = byteArrayOf(0, 0, 0, 1, 0x41, 9)

    @Test
    fun key_frames_carry_the_latest_codec_config() {
        val prepender = ParameterSetPrepender()
        prepender.onCodecConfig(config)
        assertArrayEquals(config + idr, prepender.frame(idr, isKeyFrame = true))
        assertArrayEquals(config + idr, prepender.frame(idr, isKeyFrame = true))
    }

    @Test
    fun other_frames_are_unchanged() {
        val prepender = ParameterSetPrepender()
        prepender.onCodecConfig(config)
        assertArrayEquals(slice, prepender.frame(slice, isKeyFrame = false))
    }

    @Test
    fun key_frames_before_any_codec_config_are_unchanged() {
        assertArrayEquals(idr, ParameterSetPrepender().frame(idr, isKeyFrame = true))
    }
}
