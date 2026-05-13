package com.xaxaxax.relc.display

object NoOpSink : DisplaySink {
    override fun acquireSurface() = null
    override fun start() = Unit
    override fun stop() = Unit
    override fun release() = Unit
}
