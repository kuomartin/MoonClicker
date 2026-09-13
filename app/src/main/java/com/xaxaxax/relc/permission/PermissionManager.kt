package com.xaxaxax.relc.permission
import android.content.Context
import android.content.pm.PackageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

import android.provider.Settings

class PermissionManager (private val context: Context) {
    private val _osAllowSecondaryDisplays = MutableStateFlow(getOsAllowSecondaryDisplays())
    val osAllowSecondaryDisplaysFlow = _osAllowSecondaryDisplays.asStateFlow()

    private val _hasOverlayPermission = MutableStateFlow(getHasOverlayPermission())
    val overlayPermissionFlow = _hasOverlayPermission.asStateFlow()

    fun refreshPermission(){
        _hasOverlayPermission.value = getHasOverlayPermission()
        _osAllowSecondaryDisplays.value = getOsAllowSecondaryDisplays()
    }

    private fun getOsAllowSecondaryDisplays() = context.packageManager.hasSystemFeature(
    PackageManager.FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS
    )

    private fun getHasOverlayPermission() = Settings.canDrawOverlays(context)
}