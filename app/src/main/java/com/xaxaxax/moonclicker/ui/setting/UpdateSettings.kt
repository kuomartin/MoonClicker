package com.xaxaxax.moonclicker.ui.setting

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.text.Html
import android.text.format.DateFormat
import android.text.format.DateUtils
import android.text.format.Formatter
import android.text.method.LinkMovementMethod
import android.util.TypedValue
import android.widget.TextView
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewModelScope
import com.xaxaxax.moonclicker.R
import com.xaxaxax.moonclicker.core.AppSettings
import com.xaxaxax.moonclicker.ui.component.PermissionRationaleDialog
import com.xaxaxax.moonclicker.ui.component.ToggleSettingItem
import com.xaxaxax.moonclicker.update.AvailableUpdate
import com.xaxaxax.moonclicker.update.CheckOutcome
import com.xaxaxax.moonclicker.update.CheckResult
import com.xaxaxax.moonclicker.update.DownloadFailure
import com.xaxaxax.moonclicker.update.DownloadState
import com.xaxaxax.moonclicker.update.UpdateChecker
import com.xaxaxax.moonclicker.update.UpdateDownloader
import com.xaxaxax.moonclicker.update.UpdateInstaller
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class UpdateUiState(
    val result: CheckResult = CheckResult.NeverChecked,
    val checking: Boolean = false,
    val download: DownloadState = DownloadState.None,
    val autoCheck: Boolean = true,
    val includePrereleases: Boolean = false,
) {
    val update: AvailableUpdate? get() = (result as? CheckResult.Available)?.update

    /** 下載的若是別的版本（例如更舊的一次下載），對目前這個新版沒有意義。 */
    val downloadForUpdate: DownloadState
        get() = download.takeUnless { it is DownloadState.Ready && it.version != update?.version } ?: DownloadState.None
}

sealed interface UpdateToast {
    data object UpToDate : UpdateToast
    data class Failed(val reason: String) : UpdateToast
}

@HiltViewModel
class UpdateSettingsViewModel @Inject constructor(
    private val appSettings: AppSettings,
    private val checker: UpdateChecker,
    private val downloader: UpdateDownloader,
    private val installer: UpdateInstaller,
) : ViewModel() {
    val uiState: StateFlow<UpdateUiState> = combine(
        checker.lastResult,
        checker.isChecking,
        downloader.state,
        appSettings.autoCheckUpdates,
        appSettings.includePrereleases,
    ) { result, checking, download, autoCheck, includePrereleases ->
        UpdateUiState(result, checking, download, autoCheck, includePrereleases)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UpdateUiState())

    private val _showUpdateDialog = MutableStateFlow(false)
    val showUpdateDialog: StateFlow<Boolean> = _showUpdateDialog.asStateFlow()

    private val _showSettingsDialog = MutableStateFlow(false)
    val showSettingsDialog: StateFlow<Boolean> = _showSettingsDialog.asStateFlow()

    private val _showInstallRationale = MutableStateFlow(false)

    /** 還沒允許「安裝不明應用程式」，先說明再帶去系統設定。 */
    val showInstallRationale: StateFlow<Boolean> = _showInstallRationale.asStateFlow()

    private val _toast = MutableStateFlow<UpdateToast?>(null)
    val toast: StateFlow<UpdateToast?> = _toast.asStateFlow()

    private var resumed = false
    private var progressJob: Job? = null

    init {
        viewModelScope.launch {
            installer.installRequested.filter { it }.collect {
                installer.consumeInstallRequest()
                install()
            }
        }
    }

    fun onResume() {
        resumed = true
        installer.setSettingsVisible(true)
        viewModelScope.launch {
            if (downloader.refresh() is DownloadState.Running) trackProgress()
        }
    }

    fun onPause() {
        resumed = false
        installer.setSettingsVisible(false)
        progressJob?.cancel()
    }

    /**
     * 點「檢查更新」。已經在下載或下載好、且上次檢查還在一天內，直接開對話框接著處理；
     * 否則重新檢查，有新版開對話框，沒有就 toast。
     */
    fun onRowClick() {
        if (checker.isChecking.value) return
        val state = uiState.value
        if (state.update != null && state.downloadForUpdate !is DownloadState.None && checker.isRecent()) {
            _showUpdateDialog.value = true
            return
        }
        viewModelScope.launch {
            when (val outcome = checker.check()) {
                is CheckOutcome.Available -> _showUpdateDialog.value = true
                CheckOutcome.UpToDate -> _toast.value = UpdateToast.UpToDate
                is CheckOutcome.Failed -> _toast.value = UpdateToast.Failed(outcome.reason)
            }
        }
    }

    fun dismissUpdateDialog() {
        _showUpdateDialog.value = false
    }

    fun download(update: AvailableUpdate) {
        viewModelScope.launch {
            downloader.start(update)
            trackProgress()
        }
    }

    fun cancelDownload() {
        viewModelScope.launch { downloader.cancel() }
    }

    fun skip(update: AvailableUpdate) {
        checker.skip(update.version)
        _showUpdateDialog.value = false
    }

    fun install() {
        viewModelScope.launch {
            val ready = downloader.refresh() as? DownloadState.Ready ?: return@launch
            if (installer.canInstall()) {
                installer.install(ready.downloadId)
            } else {
                _showInstallRationale.value = true
            }
        }
    }

    fun dismissInstallRationale() {
        _showInstallRationale.value = false
    }

    /** 從系統設定回來：允許了就接著安裝，沒允許就停在「安裝」按鈕。 */
    fun onInstallPermissionReturned() {
        if (installer.canInstall()) install()
    }

    fun showSettings(show: Boolean) {
        _showSettingsDialog.value = show
    }

    fun setAutoCheckUpdates(enabled: Boolean) = appSettings.setAutoCheckUpdates(enabled)

    /** 換了範圍，舊的結果不再適用：清掉並重新檢查。 */
    fun setIncludePrereleases(enabled: Boolean) {
        appSettings.setIncludePrereleases(enabled)
        checker.invalidate()
        viewModelScope.launch { checker.check() }
    }

    fun consumeToast() {
        _toast.value = null
    }

    private fun trackProgress() {
        if (!resumed || progressJob?.isActive == true) return
        progressJob = viewModelScope.launch { downloader.trackProgress() }
    }
}

/**
 * 更新的對話框、權限流程與生命週期。放在設定頁最外層：LazyColumn 的項目捲出畫面會被釋放，
 * 掛在項目上的 launcher 收不到結果，生命週期也會誤判成離開設定頁。
 */
@Composable
fun UpdateSettingsHost(viewModel: UpdateSettingsViewModel) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsState()
    val showUpdateDialog by viewModel.showUpdateDialog.collectAsState()
    val showSettingsDialog by viewModel.showSettingsDialog.collectAsState()
    val showRationale by viewModel.showInstallRationale.collectAsState()
    val toast by viewModel.toast.collectAsState()

    LifecycleResumeEffect(viewModel) {
        viewModel.onResume()
        onPauseOrDispose { viewModel.onPause() }
    }

    LaunchedEffect(toast) {
        val text = when (val t = toast ?: return@LaunchedEffect) {
            UpdateToast.UpToDate -> context.getString(R.string.update_up_to_date_toast)
            is UpdateToast.Failed -> context.getString(R.string.update_failed, t.reason)
        }
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
        viewModel.consumeToast()
    }

    val installPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        viewModel.onInstallPermissionReturned()
    }

    // API 27–28 寫進公用的 Download/ 需要儲存權限；29 起不需要。
    var pendingDownload by remember { mutableStateOf<AvailableUpdate?>(null) }
    val storagePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val update = pendingDownload
        pendingDownload = null
        if (granted && update != null) {
            viewModel.download(update)
        } else if (!granted) {
            Toast.makeText(context, R.string.update_storage_permission_denied, Toast.LENGTH_LONG).show()
        }
    }

    fun download(update: AvailableUpdate) {
        val needsStorage = Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        if (needsStorage) {
            pendingDownload = update
            storagePermissionLauncher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        } else {
            viewModel.download(update)
        }
    }

    val update = state.update
    if (showUpdateDialog && update != null) {
        UpdateDialog(
            update = update,
            download = state.downloadForUpdate,
            onDownload = { download(update) },
            onCancelDownload = viewModel::cancelDownload,
            onInstall = viewModel::install,
            onSkip = { viewModel.skip(update) },
            onDismiss = viewModel::dismissUpdateDialog,
        )
    }

    if (showSettingsDialog) {
        UpdateSettingsDialog(
            autoCheck = state.autoCheck,
            includePrereleases = state.includePrereleases,
            onAutoCheckChange = viewModel::setAutoCheckUpdates,
            onIncludePrereleasesChange = viewModel::setIncludePrereleases,
            onDismiss = { viewModel.showSettings(false) },
        )
    }

    if (showRationale) {
        PermissionRationaleDialog(
            title = stringResource(R.string.update_install_permission_title),
            description = stringResource(R.string.update_install_permission_desc),
            icon = painterResource(R.drawable.ic_launcher_foreground),
            onConfirm = {
                viewModel.dismissInstallRationale()
                installPermissionLauncher.launch(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, "package:${context.packageName}".toUri())
                )
            },
            onDismiss = viewModel::dismissInstallRationale,
        )
    }
}

/** 設定頁上的「檢查更新」列：左邊點了檢查，右邊齒輪調整自動檢查與預覽版。 */
@Composable
fun UpdateRow(viewModel: UpdateSettingsViewModel) {
    val state by viewModel.uiState.collectAsState()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = viewModel::onRowClick)
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(stringResource(R.string.update_check), style = MaterialTheme.typography.bodyLarge)
            Text(
                text = updateSubtitle(state),
                style = MaterialTheme.typography.bodyMedium,
                color = if (state.update != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        VerticalDivider(modifier = Modifier.fillMaxHeight().padding(vertical = 12.dp))
        IconButton(
            onClick = { viewModel.showSettings(true) },
            modifier = Modifier.padding(horizontal = 8.dp),
        ) {
            Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.update_settings_title))
        }
    }
}

@Composable
private fun updateSubtitle(state: UpdateUiState): String {
    val context = LocalContext.current
    if (state.checking) return stringResource(R.string.update_checking)

    val update = state.update
    if (update != null) {
        when (val download = state.downloadForUpdate) {
            is DownloadState.Running -> return if (download.total > 0) {
                stringResource(R.string.update_downloading_percent, (download.bytes * 100 / download.total).toInt())
            } else {
                stringResource(R.string.update_downloading)
            }
            DownloadState.Verifying -> return stringResource(R.string.update_verifying)
            is DownloadState.Ready -> return stringResource(R.string.update_ready, download.version.toString())
            is DownloadState.Failed -> return stringResource(
                when (download.reason) {
                    DownloadFailure.DOWNLOAD_FAILED -> R.string.update_download_failed_short
                    DownloadFailure.CORRUPTED -> R.string.update_corrupted_short
                }
            )
            DownloadState.None -> {}
        }
    }

    return when (val result = state.result) {
        CheckResult.NeverChecked -> stringResource(R.string.update_never_checked)
        is CheckResult.UpToDate -> stringResource(
            R.string.update_subtitle,
            stringResource(R.string.update_up_to_date),
            checkedAt(context, result.checkedAtMs, state.autoCheck),
        )
        is CheckResult.Available -> stringResource(
            R.string.update_subtitle,
            stringResource(
                if (result.update.prerelease) R.string.update_available_prerelease else R.string.update_available,
                result.update.version.toString(),
            ),
            checkedAt(context, result.checkedAtMs, state.autoCheck),
        )
    }
}

/** 「今天 17:47 自動檢查」或「上次檢查 10/20 09:12」。 */
private fun checkedAt(context: Context, ms: Long, autoCheck: Boolean): String {
    val time = DateFormat.getTimeFormat(context).format(ms)
    val whenText = if (DateUtils.isToday(ms)) {
        context.getString(R.string.update_today, time)
    } else {
        DateFormat.format(DateFormat.getBestDateTimePattern(context.resources.configuration.locales[0], "MMdd"), ms)
            .toString() + " " + time
    }
    return context.getString(if (autoCheck) R.string.update_checked_auto else R.string.update_checked_manual, whenText)
}

@Composable
private fun UpdateDialog(
    update: AvailableUpdate,
    download: DownloadState,
    onDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onInstall: () -> Unit,
    onSkip: () -> Unit,
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    fun open(url: String) = context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    if (update.prerelease) R.string.update_dialog_title_prerelease else R.string.update_dialog_title,
                    update.version.toString(),
                )
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(
                    modifier = Modifier
                        .heightIn(max = 320.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    val notes = update.notesHtml
                    if (notes.isNullOrBlank()) {
                        TextButton(onClick = { open(update.pageUrl) }) { Text(stringResource(R.string.update_release_notes)) }
                    } else {
                        HtmlText(notes)
                    }
                }
                when (download) {
                    is DownloadState.Running -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        if (download.total > 0) {
                            LinearProgressIndicator(
                                progress = { download.bytes.toFloat() / download.total },
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                stringResource(R.string.update_progress, size(download.bytes), size(download.total)),
                                style = MaterialTheme.typography.bodySmall,
                            )
                        } else {
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        }
                    }
                    DownloadState.Verifying -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(stringResource(R.string.update_verifying), style = MaterialTheme.typography.bodySmall)
                    }
                    is DownloadState.Failed -> Column {
                        Text(
                            stringResource(
                                when (download.reason) {
                                    DownloadFailure.DOWNLOAD_FAILED -> R.string.update_download_failed
                                    DownloadFailure.CORRUPTED -> R.string.update_corrupted
                                }
                            ),
                            color = MaterialTheme.colorScheme.error,
                        )
                        TextButton(onClick = { open(download.pageUrl) }) {
                            Text(stringResource(R.string.update_manual_download))
                        }
                    }
                    DownloadState.None, is DownloadState.Ready -> {}
                }
            }
        },
        confirmButton = {
            when (download) {
                DownloadState.None, is DownloadState.Failed -> {
                    val apk = update.apk
                    if (apk != null) {
                        TextButton(onClick = onDownload) {
                            Text(stringResource(R.string.update_download_and_install, size(apk.size)))
                        }
                    } else {
                        // 沒有符合這台裝置的 APK，只能到 release 頁自己挑。
                        TextButton(onClick = { open(update.pageUrl) }) {
                            Text(stringResource(R.string.update_manual_download))
                        }
                    }
                }
                is DownloadState.Running, DownloadState.Verifying -> TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.update_continue_in_background))
                }
                is DownloadState.Ready -> TextButton(onClick = onInstall) { Text(stringResource(R.string.update_install)) }
            }
        },
        dismissButton = {
            when (download) {
                is DownloadState.Running -> TextButton(onClick = onCancelDownload) {
                    Text(stringResource(R.string.update_cancel_download))
                }
                DownloadState.Verifying -> {}
                DownloadState.None, is DownloadState.Failed, is DownloadState.Ready -> TextButton(onClick = onSkip) {
                    Text(stringResource(R.string.update_skip))
                }
            }
        },
    )
}

/** GitHub 轉好的發布說明 HTML；連結可點。 */
@Composable
private fun HtmlText(html: String) {
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant.toArgb()
    val linkColor = MaterialTheme.colorScheme.primary.toArgb()
    AndroidView(
        factory = { context ->
            TextView(context).apply {
                movementMethod = LinkMovementMethod.getInstance()
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            }
        },
        update = { view ->
            view.text = Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT)
            view.setTextColor(textColor)
            view.setLinkTextColor(linkColor)
        },
    )
}

@Composable
private fun UpdateSettingsDialog(
    autoCheck: Boolean,
    includePrereleases: Boolean,
    onAutoCheckChange: (Boolean) -> Unit,
    onIncludePrereleasesChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.update_settings_title)) },
        text = {
            Column {
                ToggleSettingItem(
                    name = stringResource(R.string.settings_auto_check_updates),
                    description = stringResource(R.string.settings_auto_check_updates_note),
                    checked = autoCheck,
                    onCheckedChange = onAutoCheckChange,
                )
                ToggleSettingItem(
                    name = stringResource(R.string.settings_include_prereleases),
                    description = stringResource(R.string.settings_include_prereleases_note),
                    checked = includePrereleases,
                    onCheckedChange = onIncludePrereleasesChange,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.update_settings_done)) }
        },
    )
}
