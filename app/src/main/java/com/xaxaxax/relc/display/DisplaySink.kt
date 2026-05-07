package com.xaxaxax.relc.display

import android.view.Surface

interface DisplaySink {
    /** 提供給 VirtualDisplay 渲染用的 Surface，null = 不需要捕捉畫面 */
    fun acquireSurface(): Surface?
    fun start()
    fun stop()
    fun release()
}
