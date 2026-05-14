package com.xaxaxax.relc.script.runner

import com.xaxaxax.relc.script.LoopMode
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

internal suspend fun macroIterate(
    loopMode: LoopMode,
    body: suspend () -> Unit,
) {
    macroIterateIndexed(loopMode) { _, _ ->
        body()
    }
}

/**
 * [body] receives 1-based [iteration] index; [totalIterations] is null when loop is infinite.
 */
internal suspend fun macroIterateIndexed(
    loopMode: LoopMode,
    body: suspend (iteration: Int, totalIterations: Int?) -> Unit,
) {
    if (loopMode.count == -1) {
        var i = 0
        while (currentCoroutineContext().isActive) {
            i++
            body(i, null)
            if (!currentCoroutineContext().isActive) break
            delay(loopMode.duration)
        }
    } else {
        repeat(loopMode.count) { idx ->
            body(idx + 1, loopMode.count)
            if (idx < loopMode.count - 1) delay(loopMode.duration)
        }
    }
}
