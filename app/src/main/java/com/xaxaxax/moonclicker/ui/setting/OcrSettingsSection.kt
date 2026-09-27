package com.xaxaxax.moonclicker.ui.setting

import android.text.format.Formatter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xaxaxax.moonclicker.R
import com.xaxaxax.moonclicker.core.AppSettings
import com.xaxaxax.moonclicker.core.OcrCalibrationState
import com.xaxaxax.moonclicker.core.OcrManager
import com.xaxaxax.moonclicker.ocr.OcrCalibrator
import com.xaxaxax.moonclicker.ocr.OcrPackState
import com.xaxaxax.moonclicker.script.ScriptSession
import com.xaxaxax.moonclicker.ui.component.Section
import com.xaxaxax.moonclicker.ui.component.SingleChoiceDialog
import com.xaxaxax.moonclicker.ui.theme.SuccessColor
import dagger.hilt.android.lifecycle.HiltViewModel
import jakarta.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class OcrSettingsUiState(
    val pack: OcrPackState = OcrPackState.NotInstalled,
    val calibration: OcrCalibrationState = OcrCalibrationState.Idle,
    /** 0 表示自動。 */
    val threads: Int = 0,
    val calibratedThreads: Int = 0,
    val isScriptRunning: Boolean = false,
)

@HiltViewModel
class OcrSettingsViewModel @Inject constructor(
    private val ocrManager: OcrManager,
    private val appSettings: AppSettings,
    scriptSession: ScriptSession,
) : ViewModel() {

    val uiState: StateFlow<OcrSettingsUiState> = combine(
        ocrManager.packState,
        ocrManager.calibration,
        appSettings.ocrThreads,
        appSettings.ocrCalibratedThreads,
        scriptSession.state,
    ) { pack, calibration, threads, calibrated, session ->
        OcrSettingsUiState(pack, calibration, threads, calibrated, session.isRunning)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), OcrSettingsUiState())

    fun download() = ocrManager.download()
    fun cancelDownload() = ocrManager.cancelDownload()
    fun uninstall() = ocrManager.uninstall()
    fun recalibrate() = ocrManager.recalibrate()
    fun setThreads(threads: Int) = appSettings.setOcrThreads(threads)
}

/** 設定頁的「文字辨識」區塊：OCR 套件的下載／刪除、執行緒數與重新測試。 */
@Composable
fun OcrSettingsSection(viewModel: OcrSettingsViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsState()
    var confirmUninstall by remember { mutableStateOf(false) }

    Section(name = stringResource(R.string.settings_ocr)) {
        Text(
            text = stringResource(R.string.settings_ocr_desc),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OcrPackStatus(
            state = state,
            onDownload = viewModel::download,
            onCancel = viewModel::cancelDownload,
            onUninstall = { confirmUninstall = true },
        )
        if (state.pack is OcrPackState.Installed) {
            OcrThreadsSettingItem(state = state, onChange = viewModel::setThreads)
            OcrCalibration(state = state, onRecalibrate = viewModel::recalibrate)
        }
    }

    if (confirmUninstall) {
        AlertDialog(
            onDismissRequest = { confirmUninstall = false },
            title = { Text(stringResource(R.string.settings_ocr_uninstall_title)) },
            text = { Text(stringResource(R.string.settings_ocr_uninstall_desc)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmUninstall = false
                    viewModel.uninstall()
                }) { Text(stringResource(R.string.settings_ocr_uninstall)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmUninstall = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}

@Composable
private fun OcrPackStatus(
    state: OcrSettingsUiState,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onUninstall: () -> Unit,
) {
    val context = LocalContext.current
    Column(modifier = Modifier.padding(vertical = 12.dp)) {
        when (val pack = state.pack) {
            is OcrPackState.Unsupported -> StatusText(
                stringResource(R.string.settings_ocr_unsupported, pack.abi), MaterialTheme.colorScheme.error,
            )

            OcrPackState.NotInstalled -> {
                StatusText(stringResource(R.string.settings_ocr_not_installed), MaterialTheme.colorScheme.onSurfaceVariant)
                Button(onClick = onDownload, modifier = Modifier.padding(top = 8.dp)) {
                    Text(stringResource(R.string.settings_ocr_download))
                }
            }

            is OcrPackState.Downloading -> {
                val downloaded = Formatter.formatShortFileSize(context, pack.bytes)
                StatusText(
                    if (pack.total > 0) {
                        stringResource(
                            R.string.settings_ocr_downloading_of, downloaded,
                            Formatter.formatShortFileSize(context, pack.total),
                        )
                    } else {
                        stringResource(R.string.settings_ocr_downloading, downloaded)
                    },
                    MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (pack.total > 0) {
                    LinearProgressIndicator(
                        progress = { pack.bytes.toFloat() / pack.total },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                    )
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
                }
                OutlinedButton(onClick = onCancel) { Text(stringResource(R.string.common_cancel)) }
            }

            OcrPackState.Verifying -> {
                StatusText(stringResource(R.string.settings_ocr_verifying), MaterialTheme.colorScheme.onSurfaceVariant)
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
            }

            is OcrPackState.Installed -> Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusText(stringResource(R.string.settings_ocr_installed, pack.version), SuccessColor)
                OutlinedButton(onClick = onUninstall, enabled = !state.isScriptRunning) {
                    Text(stringResource(R.string.settings_ocr_uninstall))
                }
            }

            is OcrPackState.Failed -> {
                StatusText(stringResource(R.string.settings_ocr_failed, pack.reason), MaterialTheme.colorScheme.error)
                Button(onClick = onDownload, modifier = Modifier.padding(top = 8.dp)) {
                    Text(stringResource(R.string.settings_ocr_retry))
                }
            }
        }
    }
}

@Composable
private fun StatusText(text: String, color: androidx.compose.ui.graphics.Color) {
    Text(text = text, style = MaterialTheme.typography.bodyLarge, color = color)
}

@Composable
private fun OcrThreadsSettingItem(state: OcrSettingsUiState, onChange: (Int) -> Unit) {
    val auto = if (state.calibratedThreads > 0) {
        stringResource(R.string.settings_ocr_threads_auto_value, state.calibratedThreads)
    } else {
        stringResource(R.string.settings_ocr_threads_auto)
    }
    val options = listOf(0) + OcrCalibrator.CANDIDATES
    val label = { threads: Int -> if (threads == 0) auto else threads.toString() }
    var showDialog by remember { mutableStateOf(false) }
    val title = stringResource(R.string.settings_ocr_threads)

    if (showDialog) {
        SingleChoiceDialog(
            title = title,
            values = options,
            selected = state.threads,
            transform = label,
            onSelect = onChange,
            onDismiss = { showDialog = false },
        )
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showDialog = true }
            .padding(vertical = 12.dp),
    ) {
        Text(text = title, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = label(state.threads),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun OcrCalibration(state: OcrSettingsUiState, onRecalibrate: () -> Unit) {
    val context = LocalContext.current
    val running = state.calibration == OcrCalibrationState.Running
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        OutlinedButton(onClick = onRecalibrate, enabled = !running && !state.isScriptRunning) {
            Text(stringResource(R.string.settings_ocr_recalibrate))
        }
        val note = when (val calibration = state.calibration) {
            OcrCalibrationState.Running -> stringResource(R.string.settings_ocr_calibrating)
            is OcrCalibrationState.Done -> calibration.results.joinToString("\n") {
                context.getString(R.string.settings_ocr_calibration_result, it.threads, it.millis.toInt())
            }
            is OcrCalibrationState.Failed -> stringResource(R.string.settings_ocr_calibration_failed, calibration.reason)
            OcrCalibrationState.Idle -> if (state.isScriptRunning) stringResource(R.string.settings_ocr_calibration_script_running) else null
        }
        if (running) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
        if (note != null) {
            Text(
                text = note,
                style = MaterialTheme.typography.bodySmall,
                color = if (state.calibration is OcrCalibrationState.Failed) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}
