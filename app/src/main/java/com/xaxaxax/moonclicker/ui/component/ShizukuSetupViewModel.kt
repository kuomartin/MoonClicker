package com.xaxaxax.moonclicker.ui.component

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xaxaxax.moonclicker.shizuku.ShizukuConnectionStatus
import com.xaxaxax.moonclicker.shizuku.ShizukuManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

/** [ShizukuSetupDialog] 的狀態與動作；對話框出現在多個畫面，引導邏輯只在這裡寫一份。 */
@HiltViewModel
class ShizukuSetupViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val shizukuManager: ShizukuManager,
) : ViewModel() {

    val step: StateFlow<ShizukuSetupStep> = shizukuManager.statusFlow
        .map { it.toSetupStep() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, shizukuManager.status.toSetupStep())

    fun install() = context.startActivity(shizukuManager.getInstallShizukuIntent())

    /** 找不到啟動入口代表 manager 已經不在了，重查讓引導退回安裝那一步。 */
    fun openShizuku() {
        val intent = shizukuManager.getLaunchShizukuIntent()
        if (intent == null) shizukuManager.refreshAccess() else context.startActivity(intent)
    }

    fun requestPermission() = shizukuManager.requestPermission()
}

internal fun ShizukuConnectionStatus.toSetupStep(): ShizukuSetupStep = when (this) {
    ShizukuConnectionStatus.NOT_INSTALLED -> ShizukuSetupStep.INSTALL
    ShizukuConnectionStatus.NOT_RUNNING -> ShizukuSetupStep.START
    ShizukuConnectionStatus.NEED_PERMISSION -> ShizukuSetupStep.GRANT
    ShizukuConnectionStatus.DISCONNECTED,
    ShizukuConnectionStatus.CONNECTING,
    ShizukuConnectionStatus.CONNECTED -> ShizukuSetupStep.DONE
}
