package com.xaxaxax.relc.ui.displaydetail

import android.content.Context
import android.view.Surface
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xaxaxax.relc.IRelcV2Service
import com.xaxaxax.relc.RelcV2Service
import com.xaxaxax.relc.input.InputController
import com.xaxaxax.relc.shizuku.UserService
import com.xaxaxax.relc.shizuku.runWhenAlive
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
    @ApplicationContext context: Context,
    savedStateHandle: SavedStateHandle
) : ViewModel() {
    enum class ExecutionState {
        IDLE, RUNNING, CROPPING, POINT_SELECTING
    }

    data class UiState(
        val isReadOnly: Boolean = false,
        val menuExpanded: Boolean = false,
        val menuOffsetX: Float = 0f,
        val menuOffsetY: Float = 0f,
        val showAppList: Boolean = false,
        val apps: List<AppEntry> = emptyList(),
        val executionState: ExecutionState = ExecutionState.IDLE,
        val showEditor: Boolean = false
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _capturedBitmap = MutableStateFlow<android.graphics.Bitmap?>(null)
    val capturedBitmap: StateFlow<android.graphics.Bitmap?> = _capturedBitmap.asStateFlow()

    private val serviceFlow = UserService.create(
        viewModelScope,
        RelcV2Service::class,
        IRelcV2Service.Stub::asInterface
    )

    init {
        viewModelScope.launch {
            serviceFlow.runWhenAlive { service ->
                // 初始化 InputController，這會讓 UI 顯示 VirtualDisplaySurfaceView
                _inputController.value = InputController(service)
            }.onFailure {
                Timber.e(it, "Failed to initialize InputController")
            }
        }
    }

    private val _inputController = MutableStateFlow<InputController?>(null)
    val inputController: StateFlow<InputController?> = _inputController.asStateFlow()

    fun toggleReadOnly() {
        _uiState.value = _uiState.value.copy(isReadOnly = !_uiState.value.isReadOnly)
    }

    fun startExecution(displayId: Int, width: Int, height: Int, scriptDir: String) {
        viewModelScope.launch {
            serviceFlow.runWhenAlive { service ->
                com.xaxaxax.relc.engine.LuaEngineControl.stop()
                val mainScript = java.io.File(scriptDir, "main.lua").absolutePath
                val success = com.xaxaxax.relc.engine.LuaEngineControl.startEngineWithService(
                    service,
                    displayId,
                    width,
                    height,
                    mainScript
                )
                if (success != null) {
                    _uiState.value = _uiState.value.copy(executionState = ExecutionState.RUNNING)
                } else {
                    Timber.e("Failed to start Lua engine")
                }
            }
        }
    }

    fun startTemplateTest(
        displayId: Int,
        width: Int,
        height: Int,
        scriptDir: String,
        templateName: String
    ) {
        viewModelScope.launch {
            serviceFlow.runWhenAlive { service ->
                com.xaxaxax.relc.engine.LuaEngineControl.stop()
                val testScript = java.io.File(scriptDir, "_test.lua")
                val templatePath =
                    if (templateName.endsWith(".png")) templateName else "$templateName.png"
                val content = $$"""
                            config = { fps=60, scale=0.5, templates = { { name = 'target', path = '$$templatePath', threshold = 0,grayscale = true} } }

                            function on_start()

                                ui.add(nil, 'test_rect', [[ { 'type': 'box', 'x': '$m_x', 'y': '$m_y', 'width': '$m_w', 'height': '$m_h', 'border': { 'width': 2, 'color': '#FF0000' } } ]])
                                ui.add('test_rect', 'test_text', [[ { 'type': 'text', 'text': '$m_c', 'color': '#FF0000', 'background': { 'color': '#80000000' } } ]])
                            end

                            function on_tick(matches, tick)
                                if tick % 60 == 0 then log("Lua Tick: " .. tick) end
                                local m = matches.target
                                if m and m.found then
                                    app.set_data('m_x', m.x - m.width/2)
                                    app.set_data('m_y', m.y - m.height/2)
                                    app.set_data('m_w', m.width)
                                    app.set_data('m_h', m.height)
                                    app.set_data('m_c', string.format('%.2f', m.confidence))
                                else
                                    app.set_data('m_w', 0)
                                    app.set_data('m_h', 0)
                                end
                            end
                            """.trimIndent()
                testScript.writeText(content)
                val success = com.xaxaxax.relc.engine.LuaEngineControl.startEngineWithService(
                    service,
                    displayId,
                    width,
                    height,
                    testScript.absolutePath
                )
                if (success != null) {
                    _uiState.value = _uiState.value.copy(executionState = ExecutionState.RUNNING)
                } else {
                    Timber.e("Failed to start Lua engine for testing")
                }
            }
        }
    }

    fun stopExecution() {
        com.xaxaxax.relc.engine.LuaEngineControl.stop()
        _uiState.value = _uiState.value.copy(executionState = ExecutionState.IDLE)
    }

    fun startCropping() {
        _uiState.value = _uiState.value.copy(executionState = ExecutionState.CROPPING, showEditor = false)
        // trigger is handled by Activity passing Bitmap to setCapturedBitmap
    }

    fun setCapturedBitmap(bitmap: android.graphics.Bitmap) {
        _capturedBitmap.value = bitmap
    }

    fun cancelCropping() {
        _uiState.value = _uiState.value.copy(executionState = ExecutionState.IDLE, showEditor = true)
        _capturedBitmap.value = null
    }

    fun saveCroppedImage(
        scriptDir: String,
        name: String,
        cropRect: android.graphics.Rect
    ): Boolean {
        val bitmap = _capturedBitmap.value ?: return false
        try {
            // Ensure cropRect is within bounds
            val left = cropRect.left.coerceAtLeast(0)
            val top = cropRect.top.coerceAtLeast(0)
            val right = cropRect.right.coerceAtMost(bitmap.width)
            val bottom = cropRect.bottom.coerceAtMost(bitmap.height)
            val width = right - left
            val height = bottom - top

            if (width <= 0 || height <= 0) return false

            val croppedBitmap =
                android.graphics.Bitmap.createBitmap(bitmap, left, top, width, height)
            val templateDir = java.io.File(scriptDir)
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

    fun setEditorExpanded(expanded: Boolean) {
        _uiState.value = _uiState.value.copy(showEditor = expanded)
    }

    fun startPointSelecting() {
        _uiState.value = _uiState.value.copy(executionState = ExecutionState.POINT_SELECTING, showEditor = false)
    }

    fun cancelPointSelecting() {
        _uiState.value = _uiState.value.copy(executionState = ExecutionState.IDLE, showEditor = true)
    }

    fun updateMenuOffset(dragAmountX: Float, dragAmountY: Float) {
        _uiState.value = _uiState.value.copy(
            menuOffsetX = _uiState.value.menuOffsetX + dragAmountX,
            menuOffsetY = _uiState.value.menuOffsetY + dragAmountY
        )
    }

    fun openAppList() {
        viewModelScope.launch {
            serviceFlow.runWhenAlive { service ->
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
            serviceFlow.runWhenAlive { service ->
                service.launchInDisplay(packageName, displayId)
            }
            closeAppList()
        }
    }

    fun destroyDisplay(displayId: Int) {
        viewModelScope.launch {
            serviceFlow.runWhenAlive { service ->
                service.destroyVirtualDisplay(displayId)
            }.onFailure {
                Timber.e(it)
            }
        }
    }

    private val surfaceHandleMap = mutableMapOf<Surface, Int>()

    fun addSurface(displayId: Int, surface: Surface) {
        viewModelScope.launch {
            serviceFlow.runWhenAlive { service ->
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
            serviceFlow.runWhenAlive { service ->
                surfaceHandleMap.remove(surface)?.let { handle ->
                    service.removeVirtualDisplaySurface(displayId, handle)
                }
            }.onFailure {
                Timber.e(it)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        _inputController.value = null
    }
}
