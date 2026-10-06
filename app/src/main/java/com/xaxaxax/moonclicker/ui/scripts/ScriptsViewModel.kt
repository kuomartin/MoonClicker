package com.xaxaxax.moonclicker.ui.scripts

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xaxaxax.moonclicker.R
import com.xaxaxax.moonclicker.script.BundledExamples
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
    /** [importConflict] 來自加入內建範例而不是匯入 zip：對話框要說明覆蓋會蓋掉使用者的修改。 */
    val importConflictIsExample: Boolean = false,
    /** null 代表範例清單沒開。 */
    val examples: List<ExampleItem>? = null,
)

data class ExampleItem(val example: BundledExamples.Example, val installed: Boolean)

@HiltViewModel
class ScriptsViewModel @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val store: ScriptStore,
    private val session: ScriptSession,
    private val shizukuManager: ShizukuManager,
    private val bundledExamples: BundledExamples,
) : ViewModel() {

    /** 匯入結果之類的一次性訊息。 */
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private data class PendingConflict(val conflict: ScriptArchive.ImportResult.Conflict, val isExample: Boolean)

    private val _importConflict = MutableStateFlow<PendingConflict?>(null)

    private val _examples = MutableStateFlow<List<BundledExamples.Example>?>(null)

    /** 剛加入或匯入的腳本資料夾名，畫面捲到它之後呼叫 [consumeAddedScript]。 */
    private val _addedScriptId = MutableStateFlow<String?>(null)
    val addedScriptId: StateFlow<String?> = _addedScriptId.asStateFlow()

    val uiState: StateFlow<ScriptsUiState> = combine(
        store.scripts,
        session.state,
        shizukuManager.statusFlow,
        _importConflict,
        _examples,
    ) { scripts, sessionState, shizuku, pending, examples ->
        val installedIds = scripts.mapNotNullTo(HashSet()) { it.uniqueId }
        ScriptsUiState(
            scripts = scripts,
            session = sessionState,
            shizukuStatus = shizuku,
            scriptsPath = store.root.absolutePath,
            importConflict = pending?.conflict,
            importConflictIsExample = pending?.isExample == true,
            examples = examples?.map { ExampleItem(it, it.uniqueId != null && it.uniqueId in installedIds) },
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
            applyImportResult(result, isExample = false)
        }
    }

    fun showExamples() {
        viewModelScope.launch {
            _examples.value = withContext(Dispatchers.IO) { bundledExamples.list() }
        }
    }

    fun dismissExamples() {
        _examples.value = null
    }

    fun addExample(example: BundledExamples.Example) {
        _examples.value = null
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { bundledExamples.install(example, store.root) }.getOrElse {
                    Timber.e(it, "installing example %s failed", example.assetName)
                    ScriptArchive.ImportResult.Failed(it.message ?: example.assetName)
                }
            }
            applyImportResult(result, isExample = true)
        }
    }

    /** 使用者對 [ScriptsUiState.importConflict] 的決定。 */
    fun resolveImportConflict(overwrite: Boolean) {
        val pending = _importConflict.value ?: return
        _importConflict.value = null
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                ScriptArchive.resolveConflict(pending.conflict, overwrite)
            }
            applyImportResult(result, pending.isExample)
        }
    }

    private fun applyImportResult(result: ScriptArchive.ImportResult, isExample: Boolean) {
        when (result) {
            is ScriptArchive.ImportResult.Imported -> {
                store.refresh()
                _addedScriptId.value = result.dir.name
                _message.value = context.getString(R.string.scripts_imported_success, result.dir.name)
            }
            is ScriptArchive.ImportResult.Failed ->
                _message.value = context.getString(R.string.scripts_imported_failed, result.reason)
            is ScriptArchive.ImportResult.Conflict ->
                _importConflict.value = PendingConflict(result, isExample)
        }
    }

    fun consumeAddedScript() {
        _addedScriptId.value = null
    }

    fun consumeMessage() {
        _message.value = null
    }

    private fun displayName(uri: Uri): String =
        uri.lastPathSegment?.substringAfterLast('/') ?: "script"
}
