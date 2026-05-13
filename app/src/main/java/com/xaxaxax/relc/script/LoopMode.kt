package com.xaxaxax.relc.script

import kotlin.time.Duration

//data class LoopMode(val count: Int = 1, val sleepDuration: Duration = ZERO) {
//    companion object {
//        val None = LoopMode()
//    }
//}


sealed class LoopMode(val count: Int, val duration: Duration) {
    val string: String = "$count:$duration"

    object None : LoopMode(1, Duration.ZERO)
    class Inf(duration: Duration = Duration.ZERO) : LoopMode(-1, duration)
    class Repeat(count: Int, duration: Duration = Duration.ZERO) : LoopMode(count, duration)
}