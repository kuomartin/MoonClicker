package com.xaxaxax.relc.display

import android.view.Surface

class DirectSink(val surface: Surface) : DisplaySink {
    override fun acquireSurface(): Surface {
        return surface
    }

    override fun start() {
    }

    override fun stop() {
    }

    override fun release() {
//        surface.release()
    }
}