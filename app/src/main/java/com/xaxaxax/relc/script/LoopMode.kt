package com.xaxaxax.relc.script

import kotlinx.serialization.Serializable
import kotlin.time.Duration

@Serializable
data class LoopMode(val count: Int, val duration: Duration) {
    val string: String = if (count == -1) "Inf:$duration" else "$count:$duration"

    companion object {
        val None = LoopMode(1, Duration.ZERO)
        fun Inf(duration: Duration = Duration.ZERO) = LoopMode(-1, duration)
    }
}
