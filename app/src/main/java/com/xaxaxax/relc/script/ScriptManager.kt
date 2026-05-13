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
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds

data class ScriptLog(val scriptId: String, val message: String)

class ScriptManager {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val scriptJobs = ConcurrentHashMap<String, Job>()

    private val _scriptStates = MutableStateFlow<Map<String, ScriptState>>(emptyMap())
    val scriptStates: StateFlow<Map<String, ScriptState>> = _scriptStates.asStateFlow()

    private val _logs = MutableSharedFlow<ScriptLog>(extraBufferCapacity = 100)
    val logs: SharedFlow<ScriptLog> = _logs

    private val serviceFlow = UserService.create(
        scope,
        RelcShizukuService::class,
        IRelcShizukuService.Stub::asInterface
    )

    fun startScript(config: ScriptConfig) {
        if (_scriptStates.value[config.id] == ScriptState.RUNNING) {
            Timber.w("Script ${config.id} is already running")
            return
        }

        scriptJobs[config.id]?.cancel()
        _scriptStates.update { it + (config.id to ScriptState.RUNNING) }

        val job = scope.launch {
            try {
                when (config.loopMode) {
                    LoopMode.SINGLE -> executeSingle(config)
                    LoopMode.COUNTED -> {
                        for (i in 0 until config.loopCount) {
                            if (!isActive) break
                            executeSingle(config)
                            if (i < config.loopCount - 1 && isActive) delay(config.intervalMs.milliseconds)
                        }
                    }

                    LoopMode.INFINITE -> {
                        while (isActive) {
                            executeSingle(config)
                            if (isActive) delay(config.intervalMs.milliseconds)
                        }
                    }
                }
                if (isActive) {
                    _scriptStates.update { it + (config.id to ScriptState.FINISHED) }
                }
            } catch (e: CancellationException) {
                Timber.d("Script ${config.id} cancelled")
                _scriptStates.update { it + (config.id to ScriptState.IDLE) }
            } catch (e: Exception) {
                Timber.e(e, "Script ${config.id} execution error")
                _scriptStates.update { it + (config.id to ScriptState.ERROR) }
            } finally {
                scriptJobs.remove(config.id)
            }
        }
        scriptJobs[config.id] = job
    }

    private suspend fun executeSingle(config: ScriptConfig) {
        // Run only when Shizuku is alive
        serviceFlow.runWhenAlive { service ->
            val engine = ScriptEngine(service) { logMsg ->
                scope.launch { _logs.emit(ScriptLog(config.id, logMsg)) }
            }
            // Execute block is interruptible by coroutine cancellation
            engine.execute(config.code)
        }
    }

    fun stopScript(scriptId: String) {
        scriptJobs[scriptId]?.cancel()
        scriptJobs.remove(scriptId)
        _scriptStates.update { it + (scriptId to ScriptState.IDLE) }
    }
}
