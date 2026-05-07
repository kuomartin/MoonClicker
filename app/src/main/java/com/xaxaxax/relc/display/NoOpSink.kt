package com.xaxaxax.relc.display

import android.view.Surface
import timber.log.Timber
import java.util.concurrent.atomic.AtomicBoolean

class NoOpSink : DisplaySink {
    private val released = AtomicBoolean(false)

    override fun acquireSurface(): Surface? = null

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
            Timber.w("NoOpSink#release called more than once")
    }
}
