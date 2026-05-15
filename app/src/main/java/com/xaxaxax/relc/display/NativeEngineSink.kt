package com.xaxaxax.relc.display

import android.view.Surface
import com.xaxaxax.relc.IRelcV2Service
import com.xaxaxax.relc.display.cv.NativeDetector

class NativeEngineSink(
    private val service: IRelcV2Service,
    private val script: String,
    private val width: Int,
    private val height: Int
) : DisplaySink {


    private val nativeDetector = NativeDetector()
    private var surface: Surface? = null

    override fun acquireSurface(): Surface? {
        if (surface == null) {
            surface = nativeDetector.startEngine(service, width, height, script)
        }
        return surface
    }

    override fun start() {
        // Native engine starts processing once surface is created and frames flow in
    }

    override fun stop() {
        nativeDetector.stopEngine()
    }

    override fun release() {
        surface?.release()
        surface = null
    }
}
