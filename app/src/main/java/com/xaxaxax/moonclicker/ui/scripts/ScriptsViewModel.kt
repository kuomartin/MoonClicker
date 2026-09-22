package com.xaxaxax.moonclicker.ui.scripts

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xaxaxax.moonclicker.R
import com.xaxaxax.moonclicker.script.Script
import com.xaxaxax.moonclicker.script.ScriptArchive
import com.xaxaxax.moonclicker.script.ScriptSession
import com.xaxaxax.moonclicker.script.ScriptSessionState
import com.xaxaxax.moonclicker.script.ScriptStore
import com.xaxaxax.moonclicker.script.ScriptTarget
import com.xaxaxax.moonclicker.shizuku.ShizukuConnectionStatus
import com.xaxaxax.moonclicker.shizuku.ShizukuManager
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
import javax.inject.Inject

data class ScriptsUiState(
    val scripts: List<Script> = emptyList(),
    val session: ScriptSessionState = ScriptSessionState(),
    val shizukuStatus: ShizukuConnectionStatus = ShizukuConnectionStatus.NOT_AVAILABLE,
    val scriptsPath: String = "",
    /** 匯入的 zip 撞了裝置上既有腳本的 uniqueId，等使用者選覆蓋還是取消。 */
    val importConflict: ScriptArchive.ImportResult.Conflict? = null,
)

@HiltViewModel
class ScriptsViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val store: ScriptStore,
    private val session: ScriptSession,
    private val shizukuManager: ShizukuManager,
) : ViewModel() {

    /** 匯入結果之類的一次性訊息。 */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _importConflict = MutableStateFlow<ScriptArchive.ImportResult.Conflict?>(null)

    val uiState: StateFlow<ScriptsUiState> = combine(
        store.scripts,
        session.state,
        shizukuManager.statusFlow,
        _importConflict,
    ) { scripts, sessionState, shizuku, importConflict ->
        ScriptsUiState(
            scripts = scripts,
            session = sessionState,
            shizukuStatus = shizuku,
            scriptsPath = store.root.absolutePath,
            importConflict = importConflict,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = ScriptsUiState(scriptsPath = store.root.absolutePath),
    )

    fun onShizukuAction() = shizukuManager.requestPermissionOrConnect()

    /** 使用者可能剛從檔案管理員丟了資料夾進來，所以掃描是隨時可觸發的動作。 */
    fun refresh() = store.refresh()

    fun run(script: Script, target: ScriptTarget? = null) {
        session.start(script, target ?: session.defaultTargetFor(script))
    }

    fun stop() = session.stop()

    fun import(uri: Uri) {
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        ScriptArchive.import(input, store.root, displayName(uri))
                    } ?: ScriptArchive.ImportResult.Failed(context.getString(R.string.scripts_cannot_read_file))
                }.getOrElse {
                    Timber.e(it, "import failed")
                    ScriptArchive.ImportResult.Failed(it.message ?: context.getString(R.string.scripts_imported_failed, ""))
                }
            }
            applyImportResult(result)
        }
    }

    /** 使用者對 [ScriptsUiState.importConflict] 的決定。 */
    fun resolveImportConflict(overwrite: Boolean) {
        val conflict = uiState.value.importConflict ?: return
        _importConflict.value = null
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                ScriptArchive.resolveConflict(conflict, overwrite)
            }
            applyImportResult(result)
        }
    }

    private fun applyImportResult(result: ScriptArchive.ImportResult) {
        when (result) {
            is ScriptArchive.ImportResult.Imported -> {
                store.refresh()
                _message.value = context.getString(R.string.scripts_imported_success, result.dir.name)
            }
            is ScriptArchive.ImportResult.Failed ->
                _message.value = context.getString(R.string.scripts_imported_failed, result.reason)
            is ScriptArchive.ImportResult.Conflict ->
                _importConflict.value = result
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    private fun displayName(uri: Uri): String =
        uri.lastPathSegment?.substringAfterLast('/') ?: "script"
}
