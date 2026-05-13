package com.xaxaxax.relc.script

import com.xaxaxax.relc.IRelcShizukuService
import com.xaxaxax.relc.RelcShizukuService
import com.xaxaxax.relc.shizuku.UserService
import com.xaxaxax.relc.shizuku.runWhenAlive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

class ScriptManager {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var currentJob: Job? = null

    private val _state = MutableStateFlow(ScriptState.IDLE)
    val state: StateFlow<ScriptState> = _state

    private val _logs = MutableSharedFlow<String>(extraBufferCapacity = 100)
    val logs: SharedFlow<String> = _logs

    private val serviceFlow = UserService.create(
        scope,
        RelcShizukuService::class,
        IRelcShizukuService.Stub::asInterface
    )

    fun startScript(config: ScriptConfig) {
        if (_state.value == ScriptState.RUNNING) {
            Timber.w("A script is already running")
            return
        }

        currentJob?.cancel()
        _state.value = ScriptState.RUNNING

        currentJob = scope.launch {
            try {
                when (config.loopMode) {
                    LoopMode.SINGLE -> executeSingle(config)
                    LoopMode.COUNTED -> {
                        for (i in 0 until config.loopCount) {
                            if (!isActive) break
                            executeSingle(config)
                            if (i < config.loopCount - 1 && isActive) delay(config.intervalMs)
                        }
                    }

                    LoopMode.INFINITE -> {
                        while (isActive) {
                            executeSingle(config)
                            if (isActive) delay(config.intervalMs)
                        }
                    }
                }
                if (isActive) {
                    _state.value = ScriptState.FINISHED
                }
            } catch (e: CancellationException) {
                Timber.d("Script cancelled")
                _state.value = ScriptState.IDLE
            } catch (e: Exception) {
                Timber.e(e, "Script execution error")
                _state.value = ScriptState.ERROR
            }
        }
    }

    private suspend fun executeSingle(config: ScriptConfig) {
        // Run only when Shizuku is alive
        serviceFlow.runWhenAlive { service ->
            val engine = ScriptEngine(service) { logMsg ->
                scope.launch { _logs.emit(logMsg) }
            }
            // Execute block is interruptible by coroutine cancellation
            engine.execute(config.code)
        }
    }

    fun stopScript() {
        currentJob?.cancel()
        currentJob = null
        _state.value = ScriptState.IDLE
    }
}
