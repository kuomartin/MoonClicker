package com.xaxaxax.relc.script

import android.os.SystemClock
import com.xaxaxax.relc.IRelcV2Service
import com.xaxaxax.relc.RelcV2Service
import com.xaxaxax.relc.script.runner.NativeLuaScriptRunnerFactory
import com.xaxaxax.relc.script.runner.ScriptRunContext
import com.xaxaxax.relc.script.runner.ScriptRunnerFactory
import com.xaxaxax.relc.script.runner.SimpleScriptRunnerFactory
import com.xaxaxax.relc.shizuku.UserService
import com.xaxaxax.relc.shizuku.runWhenAlive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
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

data class ScriptLog(val scriptId: String, val message: String)

class ScriptManager {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val scriptJobs = ConcurrentHashMap<String, Job>()

    private val _scriptStates = MutableStateFlow<Map<String, ScriptState>>(emptyMap())
    val scriptStates: StateFlow<Map<String, ScriptState>> = _scriptStates.asStateFlow()

    /** Single HUD slice for overlays; cleared when idle / script ends. */
    private val _hudUi = MutableStateFlow<RunningScriptHudUi?>(null)
    val hudUi: StateFlow<RunningScriptHudUi?> = _hudUi.asStateFlow()

    private val _logs = MutableSharedFlow<ScriptLog>(extraBufferCapacity = 100)
    val logs: SharedFlow<ScriptLog> = _logs

    private val serviceFlow = UserService.create(
        scope,
        RelcV2Service::class,
        IRelcV2Service.Stub::asInterface
    )

    private val runnerFactories: Map<ScriptCodeType, ScriptRunnerFactory> = mapOf(
        ScriptCodeType.LUA to NativeLuaScriptRunnerFactory(),
        ScriptCodeType.SIMPLE to SimpleScriptRunnerFactory(),
    )

    fun startScript(config: ScriptConfig) {
        if (_scriptStates.value[config.id] == ScriptState.RUNNING) {
            Timber.w("Script ${config.id} is already running")
            return
        }

        scriptJobs[config.id]?.cancel()
        _scriptStates.update { it + (config.id to ScriptState.RUNNING) }
        val sessionStart = SystemClock.elapsedRealtime()
        _hudUi.value = RunningScriptHudUi(
            scriptId = config.id,
            name = config.name,
            codeType = config.type,
            state = ScriptState.RUNNING,
            progress = null,
            sessionStartElapsedRealtime = sessionStart,
        )

        val job = scope.launch {
            try {
                serviceFlow.runWhenAlive { service ->
                    val emitSimpleProgress =
                        if (config.type == ScriptCodeType.SIMPLE) {
                            { p: SimpleScriptProgress ->
                                _hudUi.value = RunningScriptHudUi(
                                    scriptId = config.id,
                                    name = config.name,
                                    codeType = config.type,
                                    state = ScriptState.RUNNING,
                                    progress = p,
                                    sessionStartElapsedRealtime = sessionStart,
                                )
                            }
                        } else {
                            null
                        }
                    val ctx = ScriptRunContext(
                        service = service,
                        onLog = { logMsg ->
                            scope.launch { _logs.emit(ScriptLog(config.id, logMsg)) }
                        },
                        isActive = { isActive },
                        sessionStartElapsedRealtime = sessionStart,
                        onSimpleProgress = emitSimpleProgress,
                    )
                    runnerFactories.getValue(config.type).create(ctx).run(config)
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
                _hudUi.value = null
            }
        }
        scriptJobs[config.id] = job
    }

    fun stopScript(scriptId: String) {
        scriptJobs[scriptId]?.cancel()
        scriptJobs.remove(scriptId)
        _scriptStates.update { it + (scriptId to ScriptState.IDLE) }
        if (_hudUi.value?.scriptId == scriptId) {
            _hudUi.value = null
        }
    }

}
