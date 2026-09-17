package com.xaxaxax.relc.ui.scriptdetail

import android.content.Context
import android.content.res.Resources
import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xaxaxax.relc.R
import com.xaxaxax.relc.ScriptDetailRoute
import com.xaxaxax.relc.core.DisplayConfig
import com.xaxaxax.relc.core.DisplayInfo
import com.xaxaxax.relc.core.readDisplayInfo
import com.xaxaxax.relc.engine.ScriptEngine
import com.xaxaxax.relc.script.Script
import com.xaxaxax.relc.script.ScriptArchive
import com.xaxaxax.relc.script.ScriptSession
import com.xaxaxax.relc.script.ScriptSessionState
import com.xaxaxax.relc.script.ScriptStore
import com.xaxaxax.relc.script.ScriptTarget
import com.xaxaxax.relc.shizuku.ShizukuManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import androidx.navigation.toRoute
import java.io.File
import javax.inject.Inject

data class ScriptDetailUiState(
    val script: Script? = null,
    val source: String? = null,
    val templates: List<File> = emptyList(),
    val sharedData: Map<String, Any> = emptyMap(),
    val session: ScriptSessionState = ScriptSessionState(),
    val virtualDisplays: List<DisplayInfo> = emptyList(),
)

@HiltViewModel
class ScriptDetailViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    savedStateHandle: SavedStateHandle,
    private val store: ScriptStore,
    private val session: ScriptSession,
    private val shizukuManager: ShizukuManager,
) : ViewModel() {

    private val scriptId: String = savedStateHandle.toRoute<ScriptDetailRoute>().id

    private val virtualDisplays = MutableStateFlow<List<DisplayInfo>>(emptyList())
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val uiState: StateFlow<ScriptDetailUiState> = combine(
        store.scripts,
        session.state,
        ScriptEngine.sharedData,
        virtualDisplays,
    ) { scripts, sessionState, sharedData, displays ->
        val script = scripts.firstOrNull { it.id == scriptId }
        ScriptDetailUiState(
            script = script,
            source = script?.let(store::readSource),
            templates = script?.let(store::templates).orEmpty(),
            sharedData = sharedData,
            session = sessionState,
            virtualDisplays = displays,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ScriptDetailUiState(),
    )

    /** 建新顯示器時的預設尺寸：腳本自己說了就用它的，沒說就跟著本機螢幕。 */
    val newDisplayConfig: DisplayConfig
        get() = uiState.value.script?.display ?: DisplayConfig(
            name = "ReLC",
            width = Resources.getSystem().displayMetrics.widthPixels,
            height = Resources.getSystem().displayMetrics.heightPixels,
            densityDpi = Resources.getSystem().displayMetrics.densityDpi,
        )

    init {
        refreshDisplays()
    }

    fun refreshDisplays() {
        viewModelScope.launch {
            shizukuManager.withService { service ->
                val ids = service.virtualDisplays.toList()
                virtualDisplays.value = withContext(Dispatchers.Default) {
                    ids.mapNotNull { context.readDisplayInfo(it) }
                }
            }.onFailure { Timber.w(it, "Could not list virtual displays") }
        }
    }

    fun run(target: ScriptTarget? = null) {
        val script = uiState.value.script ?: return
        session.start(script, target ?: session.defaultTargetFor(script))
    }

    fun stop() = session.stop()

    fun export(uri: Uri) {
        val script = uiState.value.script ?: return
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openOutputStream(uri)?.use {
                        ScriptArchive.export(script, it)
                    } ?: error(context.getString(R.string.scripts_cannot_write_target))
                }
            }
            _message.value = result.fold(
                { context.getString(R.string.scripts_exported_success, script.name) },
                { context.getString(R.string.scripts_exported_failed, it.message ?: "") }
            )
        }
    }

    /** @return true 表示刪掉了，呼叫端該離開這一頁。 */
    fun delete(): Boolean {
        val script = uiState.value.script ?: return false
        if (session.state.value.isRunning && session.state.value.script?.id == script.id) {
            _message.value = context.getString(R.string.scripts_running_cannot_operate)
            return false
        }
        return store.delete(script)
    }

    fun consumeMessage() {
        _message.value = null
    }
}
