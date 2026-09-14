package com.xaxaxax.relc.ui.displaydetail

import android.content.Context
import android.view.Surface
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xaxaxax.relc.IRelcV2Service
import com.xaxaxax.relc.shizuku.ShizukuManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class FullscreenDisplayViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    savedStateHandle: SavedStateHandle,
    private val shizukuManager: ShizukuManager
) : ViewModel() {
    /**
     * 這個畫面只管顯示器本身與模板裁切。跑腳本是 ScriptSession 的事（issue #5），
     * 所以這裡沒有 RUNNING。
     */
    enum class ExecutionState {
        IDLE, CROPPING
    }

    data class UiState(
        val isReadOnly: Boolean = false,
        val menuExpanded: Boolean = false,
        val menuOffsetX: Float = 0f,
        val menuOffsetY: Float = 0f,
        val showAppList: Boolean = false,
        val apps: List<AppEntry> = emptyList(),
        val executionState: ExecutionState = ExecutionState.IDLE
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _capturedBitmap = MutableStateFlow<android.graphics.Bitmap?>(null)
    val capturedBitmap: StateFlow<android.graphics.Bitmap?> = _capturedBitmap.asStateFlow()

    /**
     * 綁好的服務，也是 UI 判斷「可以顯示鏡像了沒」的依據——服務在，鏡像才有東西可映。
     *
     * 直接轉發 [ShizukuManager] 的 flow，不另外存一份：多存一份就多一個會跟真實綁定狀態
     * 走樣的地方，而它表達的是同一件事。
     */
    val service: StateFlow<IRelcV2Service?> = shizukuManager.serviceFlow

    fun toggleReadOnly() {
        _uiState.value = _uiState.value.copy(isReadOnly = !_uiState.value.isReadOnly)
    }

    fun startCropping() {
        _uiState.value = _uiState.value.copy(executionState = ExecutionState.CROPPING)
    }

    fun setCapturedBitmap(bitmap: android.graphics.Bitmap) {
        _capturedBitmap.value = bitmap
    }

    fun cancelCropping() {
        _uiState.value = _uiState.value.copy(executionState = ExecutionState.IDLE)
        _capturedBitmap.value = null
    }

    fun saveCroppedImage(
        scriptDir: String,
        name: String,
        cropRect: android.graphics.Rect
    ): Boolean {
        val bitmap = _capturedBitmap.value ?: return false
        try {
            val left = cropRect.left.coerceAtLeast(0)
            val top = cropRect.top.coerceAtLeast(0)
            val right = cropRect.right.coerceAtMost(bitmap.width)
            val bottom = cropRect.bottom.coerceAtMost(bitmap.height)
            val width = right - left
            val height = bottom - top

            if (width <= 0 || height <= 0) return false

            val croppedBitmap =
                android.graphics.Bitmap.createBitmap(bitmap, left, top, width, height)
            val templateDir = if (scriptDir.isBlank()) {
                java.io.File(context.getExternalFilesDir(null), "images")
            } else {
                java.io.File(scriptDir)
            }
            if (!templateDir.exists()) templateDir.mkdirs()

            val file = java.io.File(templateDir, "$name.png")
            java.io.FileOutputStream(file).use { out ->
                croppedBitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
            }
            cancelCropping()
            return true
        } catch (e: Exception) {
            Timber.e(e, "Failed to save cropped image")
            return false
        }
    }

    fun setMenuExpanded(expanded: Boolean) {
        _uiState.value = _uiState.value.copy(menuExpanded = expanded)
    }

    fun updateMenuOffset(dragAmountX: Float, dragAmountY: Float) {
        _uiState.value = _uiState.value.copy(
            menuOffsetX = _uiState.value.menuOffsetX + dragAmountX,
            menuOffsetY = _uiState.value.menuOffsetY + dragAmountY
        )
    }

    fun openAppList() {
        viewModelScope.launch {
            shizukuManager.withService { service ->
                val rawApps = service.launcherApps
                val appEntries = rawApps.map {
                    val parts = it.split("|")
                    AppEntry(parts[0], parts.getOrElse(1) { parts[0] })
                }.sortedBy { it.label }
                _uiState.value = _uiState.value.copy(apps = appEntries, showAppList = true)
            }.onFailure {
                Timber.e(it)
            }
        }
    }

    fun closeAppList() {
        _uiState.value = _uiState.value.copy(showAppList = false)
    }

    fun launchApp(packageName: String, displayId: Int) {
        viewModelScope.launch {
            shizukuManager.withService { service ->
                service.launchInDisplay(packageName, displayId)
            }
            closeAppList()
        }
    }

    fun destroyDisplay(displayId: Int) {
        viewModelScope.launch {
            shizukuManager.withService { service ->
                service.destroyVirtualDisplay(displayId)
            }.onFailure {
                Timber.e(it)
            }
        }
    }

    private val surfaceHandleMap = mutableMapOf<Surface, Int>()

    fun addSurface(displayId: Int, surface: Surface) {
        viewModelScope.launch {
            shizukuManager.withService{ service ->
                val handle = service.addVirtualDisplaySurface(displayId, surface)
                if (handle != -1) {
                    surfaceHandleMap[surface] = handle
                }
            }.onFailure {
                Timber.e(it)
            }
        }
    }

    fun removeSurface(displayId: Int, surface: Surface) {
        viewModelScope.launch {
            shizukuManager.withService { service ->
                surfaceHandleMap.remove(surface)?.let { handle ->
                    service.removeVirtualDisplaySurface(displayId, handle)
                }
            }.onFailure {
                Timber.e(it)
            }
        }
    }

}
