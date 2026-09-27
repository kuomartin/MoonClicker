package com.xaxaxax.moonclicker.core

import com.xaxaxax.moonclicker.engine.ScriptEngine
import com.xaxaxax.moonclicker.ocr.OcrCalibration
import com.xaxaxax.moonclicker.ocr.OcrCalibrator
import com.xaxaxax.moonclicker.ocr.OcrPackInstaller
import com.xaxaxax.moonclicker.ocr.OcrPackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/** 校準的狀態：進行中、上次的結果或失敗原因。 */
sealed interface OcrCalibrationState {
    data object Idle : OcrCalibrationState
    data object Running : OcrCalibrationState
    data class Done(val results: List<OcrCalibration>) : OcrCalibrationState
    data class Failed(val reason: String) : OcrCalibrationState
}

/**
 * OCR 套件的下載與執行緒數校準（ADR-0018）。工作跑在自己的 scope：離開設定頁不會中斷下載。
 * 安裝完成後自動校準一次；之後由使用者在設定頁重新測試。
 */
@Singleton
class OcrManager @Inject constructor(
    private val installer: OcrPackInstaller,
    private val appSettings: AppSettings,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var download: Job? = null

    val packState: StateFlow<OcrPackState> = installer.state

    private val _calibration = MutableStateFlow<OcrCalibrationState>(OcrCalibrationState.Idle)
    val calibration: StateFlow<OcrCalibrationState> = _calibration.asStateFlow()

    /** 實際使用的執行緒數：手動設定優先，其次是校準結果；兩者都沒有時取 4（研究中多數機型的最佳值）。 */
    val effectiveThreads: Int
        get() = appSettings.ocrThreads.value.takeIf { it > 0 }
            ?: appSettings.ocrCalibratedThreads.value.takeIf { it > 0 }
            ?: DEFAULT_THREADS

    fun download() {
        if (download?.isActive == true) return
        download = scope.launch {
            installer.download()
            if (installer.state.value is OcrPackState.Installed) calibrate()
        }
    }

    fun cancelDownload() {
        download?.cancel()
    }

    fun uninstall() {
        scope.launch {
            installer.uninstall()
            appSettings.setOcrCalibratedThreads(0)
            _calibration.value = OcrCalibrationState.Idle
        }
    }

    fun recalibrate() {
        scope.launch { calibrate() }
    }

    /** 腳本執行中不校準：同時推論會互相拖慢，量到的數字沒有意義。設定頁也會停用按鈕。 */
    private suspend fun calibrate() {
        val dir = installer.installedDir ?: return
        if (_calibration.value == OcrCalibrationState.Running || ScriptEngine.isRunning) return
        _calibration.value = OcrCalibrationState.Running
        _calibration.value = try {
            val results = OcrCalibrator.measure(dir)
            OcrCalibrator.fastest(results)?.let(appSettings::setOcrCalibratedThreads)
            OcrCalibrationState.Done(results)
        } catch (e: IllegalStateException) {
            Timber.e(e, "OCR calibration failed")
            OcrCalibrationState.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    private companion object {
        const val DEFAULT_THREADS = 4
    }
}
