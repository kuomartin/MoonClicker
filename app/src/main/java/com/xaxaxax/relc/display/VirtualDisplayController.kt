package com.xaxaxax.relc.display

import android.view.Display
import com.xaxaxax.relc.IRelcShizukuService
import com.xaxaxax.relc.core.DisplayConfig
import timber.log.Timber

class VirtualDisplayController(private val service: IRelcShizukuService) {

    enum class State { IDLE, CREATED, DESTROYED }

    var state: State = State.IDLE
        private set

    var displayId: Int = Display.INVALID_DISPLAY
        private set

    private var sink: DisplaySink = NoOpSink()

    fun create(config: DisplayConfig, sink: DisplaySink = NoOpSink()) {
        check(state == State.IDLE) { "Cannot create: already in state $state" }

        this.sink = sink
        displayId = service.createVirtualDisplay(
            config.name, config.width, config.height, config.densityDpi,
            sink.acquireSurface()
        )
        check(displayId != Display.INVALID_DISPLAY) {
            "createVirtualDisplay returned INVALID_DISPLAY"
        }

        sink.start()
        state = State.CREATED
        Timber.d("VirtualDisplayController: created displayId=$displayId")
    }

    /** 熱替換輸出端，不需要重建 VirtualDisplay */
    fun replaceSink(newSink: DisplaySink) {
        check(state == State.CREATED) { "Cannot replaceSink: state is $state" }

        sink.stop()
        sink = newSink
        service.setVirtualDisplaySurface(displayId, newSink.acquireSurface())
        newSink.start()
        Timber.d("VirtualDisplayController: sink replaced on displayId=$displayId")
    }

    fun destroy() {
        check(state == State.CREATED) { "Cannot destroy: state is $state" }

        sink.stop()
        sink.release()
        service.destroyVirtualDisplay(displayId)
        displayId = Display.INVALID_DISPLAY
        state = State.DESTROYED
        Timber.d("VirtualDisplayController: destroyed")
    }
}
