package com.xaxaxax.relc.ui

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xaxaxax.relc.IRelcShizukuService
import com.xaxaxax.relc.RelcShizukuService
import com.xaxaxax.relc.core.DisplayConfig
import com.xaxaxax.relc.display.ImageReaderSink
import com.xaxaxax.relc.display.VirtualDisplayController
import com.xaxaxax.relc.display.cv.CVManager
import com.xaxaxax.relc.display.cv.DetectionResult
import com.xaxaxax.relc.shizuku.UserService
import com.xaxaxax.relc.shizuku.runWhenAlive
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

data class CVTestUiState(
    val isSearching: Boolean = false,
    val displayId: Int = -1,
    val latestResult: DetectionResult? = null,
    val log: List<String> = emptyList()
)

@HiltViewModel
class CVTestViewModel @Inject constructor(
    private val cvManager: CVManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(CVTestUiState())
    val uiState = _uiState.asStateFlow()

    private val serviceFlow by lazy {
        UserService.create(
            viewModelScope,
            RelcShizukuService::class,
            IRelcShizukuService.Stub::asInterface
        )
    }

    private var virtualDisplayController: VirtualDisplayController? = null
    private var sink: ImageReaderSink? = null
    private var testTarget: Bitmap? = null

    val displayConfig by lazy {
        val width = 1080 // Resources.getSystem().displayMetrics.widthPixels
        val height = 2400 // Resources.getSystem().displayMetrics.heightPixels
        val density = Resources.getSystem().displayMetrics.densityDpi
        DisplayConfig(
            name = "CV-Test-Display",
            width = width,
            height = height,
            densityDpi = density,
        )
    }

    fun startTest() {
        if (_uiState.value.isSearching) return

        _uiState.update { it.copy(isSearching = true) }
        log("Starting Shizuku service binding...")

        viewModelScope.launch {
            serviceFlow.runWhenAlive { service ->
                log("Shizuku connected. Creating VirtualDisplay...")
                val ctrl = VirtualDisplayController(service)
                val newSink = ImageReaderSink(displayConfig.width, displayConfig.height)
                ctrl.create(displayConfig, newSink)

                virtualDisplayController = ctrl
                sink = newSink
                cvManager.init(newSink)

                _uiState.update { it.copy(displayId = ctrl.displayId) }
                log("VirtualDisplay ID: ${ctrl.displayId}")

                // 等待幾秒讓 App 啟動並渲染畫面
                delay(3000)

                val currentFrame = newSink.getLatestBitmap()
                if (currentFrame == null) {
                    log("Error: Still no frame from ImageReader after 3s.")
                } else {
                    log("Frame received! Capturing center 100x100 as template...")
                    // 抓取中心點 100x100 作為測試模板
                    testTarget = Bitmap.createBitmap(
                        currentFrame,
                        currentFrame.width / 2 - 50,
                        currentFrame.height / 2 - 50,
                        100, 100
                    )
                    log("Template captured. Starting search loop...")
                    startCVLoop()
                }
            }
        }
    }
    private fun startCVLoop() {
        viewModelScope.launch {
            while (_uiState.value.isSearching) {
                testTarget?.let { target ->
                    val result = cvManager.findImage(target, threshold = 0.5)
                    _uiState.update { it.copy(latestResult = result) }
                }
                delay(500) // 每半秒找一次
            }
        }
    }

    fun openApp(packageName: String) {
        val displayId = _uiState.value.displayId
        if (displayId == -1) {
            log("Error: VirtualDisplay not created yet.")
            return
        }
        viewModelScope.launch {
            serviceFlow.runWhenAlive { service ->
                log("Launching $packageName into display $displayId...")
                val result = service.launchInDisplay(packageName, displayId)
                log("Launch result: $result")
            }
        }
    }

    fun stopTest() {
        _uiState.update { it.copy(isSearching = false, displayId = -1, latestResult = null) }
        cvManager.release()

        virtualDisplayController?.destroy()
        virtualDisplayController = null

        sink = null
        log("Test stopped and VirtualDisplay destroyed.")
    }

    private fun log(msg: String) {
        Timber.d("[CVTest] $msg")
        _uiState.update { it.copy(log = it.log + msg) }
    }

    override fun onCleared() {
        stopTest()
        super.onCleared()
    }
}
