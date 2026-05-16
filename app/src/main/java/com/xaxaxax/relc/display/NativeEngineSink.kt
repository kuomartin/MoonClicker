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
    private var isStarted = false

    override fun acquireSurface(): Surface? {
        // Unused. VirtualDisplay is created by C++ via Lua script.
        return null
    }

    override fun start() {
        if (!isStarted) {
            nativeDetector.startEngine(service, width, height, script)
            isStarted = true
        }
    }

    override fun stop() {
        if (isStarted) {
            nativeDetector.stopEngine()
            isStarted = false
        }
    }

    override fun release() {
        // Surface is managed by C++
    }

    fun isEngineRunning(): Boolean {
        return isStarted && nativeDetector.isEngineRunning()
    }
}
