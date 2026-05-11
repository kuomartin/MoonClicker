package com.xaxaxax.relc.display

import android.graphics.SurfaceTexture
import android.view.Surface
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

class NoOpSink : DisplaySink {
    private val released = AtomicBoolean(false)
    private var surfaceTexture: SurfaceTexture? = null
    private var surface: Surface? = null

    override fun acquireSurface(): Surface? {
        if (surface == null) {
            // 提供一個虛擬的 Surface，讓系統認為顯示器是開啟的，且 App 有地方可以繪製。
            // 這樣即使切換到 NoOpSink，YouTube 等 App 也不會因為失去 Surface 而暫停。
            surfaceTexture = SurfaceTexture(0)
            surface = Surface(surfaceTexture)
        }
        return surface
    }

    override fun start() {
        if (released.get())
            Timber.w("NoOpSink#start called after release")
    }

    override fun stop() {
        if (released.get())
            Timber.w("NoOpSink#stop called after release")
    }

    override fun release() {
        if (!released.compareAndSet(false, true))
            return
        surface?.release()
        surface = null
        surfaceTexture?.release()
        surfaceTexture = null

    }
}
