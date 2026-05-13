package com.xaxaxax.relc.script

import kotlin.time.Duration

//data class LoopMode(val count: Int = 1, val sleepDuration: Duration = ZERO) {
//    companion object {
//        val None = LoopMode()
//    }
//}


data class LoopMode(val count: Int, val duration: Duration) {
    val string: String = if (count == -1) "Inf:$duration" else "$count:$duration"

    companion object {
        val None = LoopMode(1, Duration.ZERO)
        fun Inf(duration: Duration = Duration.ZERO) = LoopMode(-1, duration)
    }
}
