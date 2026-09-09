package com.xaxaxax.relc.overlay

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hilt-scoped replacement for the old OverlayServiceProvider static singleton.
 * ClickAssistOverlayService attaches/detaches itself here as it starts/stops
 * (Android instantiates Services itself, so there's no way to constructor-inject
 * "the current running instance" — this is the standard workaround). Everything
 * else that needs to talk to the running overlay service goes through this.
 */
@Singleton
class OverlayController @Inject constructor() {
    private var service: ClickAssistOverlayService? = null

    private val _isServiceRunning = MutableStateFlow(false)
    val isServiceRunning: StateFlow<Boolean> = _isServiceRunning.asStateFlow()

    internal fun attach(service: ClickAssistOverlayService) {
        this.service = service
        _isServiceRunning.value = true
    }

    internal fun detach(service: ClickAssistOverlayService) {
        if (this.service === service) {
            this.service = null
            _isServiceRunning.value = false
        }
    }

    /**
     * Shows the overlay for [scriptId], rendered by whichever [OverlayContentExtension]
     * is registered under [contentType] (by convention, matching the calling side's
     * ScriptConfig.ScriptCodeType.name — :overlay doesn't depend on ScriptConfig).
     * @return true if the request was forwarded, false if the service isn't running.
     */
    fun showOverlay(scriptId: String, contentType: String): Boolean {
        val s = service ?: return false
        s.showOverlayForScript(scriptId, contentType)
        return true
    }

    fun hideOverlay() {
        service?.hideOverlay()
    }
}
