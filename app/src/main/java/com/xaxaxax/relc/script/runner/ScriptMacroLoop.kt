package com.xaxaxax.relc.script.runner

import com.xaxaxax.relc.script.LoopMode
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

internal suspend fun macroIterate(
    loopMode: LoopMode,
    body: suspend () -> Unit,
) {
    when (loopMode) {
        LoopMode.None -> {
            body()
        }

        is LoopMode.Repeat -> {
            repeat(loopMode.count) { i ->
                body()
                if (i < loopMode.count - 1)
                    delay(loopMode.duration)
            }
        }

        is LoopMode.Inf -> {
            while (currentCoroutineContext().isActive) {
                body()
                if (!currentCoroutineContext().isActive) break
                delay(loopMode.duration)
            }
        }
    }
}
