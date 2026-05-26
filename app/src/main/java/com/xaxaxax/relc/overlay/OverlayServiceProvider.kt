package com.xaxaxax.relc.overlay

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object OverlayServiceProvider {
    private var serviceInstance: ClickAssistOverlayService? = null

    // State flow to expose whether the service is currently running
    private val _isServiceRunning = MutableStateFlow(false)
    val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

    fun setService(service: ClickAssistOverlayService?) {
        serviceInstance = service
        _isServiceRunning.value = service != null
    }

    /**
     * Shows the overlay for the given script ID.
     * @return true if the overlay was successfully requested to be shown, false if the service is not running.
     */
    fun showOverlay(scriptId: String): Boolean {
        val service = serviceInstance ?: return false
        service.showOverlayForScript(scriptId)
        return true
    }

    /**
     * Hides the current overlay.
     */
    fun hideOverlay() {
        serviceInstance?.hideOverlay()
    }
}
