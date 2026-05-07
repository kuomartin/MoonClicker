package com.xaxaxax.relc

import android.app.AppOpsManager
import android.app.AppOpsManagerHidden
import android.content.Context
import android.content.pm.PackageManagerHidden
import android.os.Build
import android.os.UserHandle
import androidx.annotation.Keep
import dev.rikka.tools.refine.Refine
import timber.log.Timber

@Keep
class RelcShizukuService(private val context: Context) : IRelcShizukuService.Stub() {

    override fun grantRuntimePermission(
        packageName: String,
        permissionName: String
    ): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return try {
            val uid = context.packageManager.getPackageUid(packageName, 0)
            val handle = UserHandle.getUserHandleForUid(uid)
            Refine.unsafeCast<PackageManagerHidden>(context.packageManager).grantRuntimePermission(
                packageName,
                permissionName,
                handle
            )
            true
        } catch (t: Throwable) {
            if (Timber.treeCount == 0) Timber.plant(Timber.DebugTree())
            Timber.e(t, "grantRuntimePermission failed")
            false
        }
    }

    override fun setOverlayAllowed(packageName: String): Boolean {
        return try {
            val uid = context.packageManager.getPackageUid(packageName, 0)
            val op = AppOpsManager.permissionToOp(android.Manifest.permission.SYSTEM_ALERT_WINDOW)
            val appOps = context.getSystemService(AppOpsManager::class.java)
            val code = AppOpsManagerHidden.strOpToOp(op)
            Refine.unsafeCast<AppOpsManagerHidden>(appOps).setMode(
                code,
                uid,
                packageName,
                AppOpsManager.MODE_ALLOWED
            )
            true
        } catch (t: Throwable) {
            if (Timber.treeCount == 0) Timber.plant(Timber.DebugTree())
            Timber.e(t, "setOverlayAllowed failed")
            false
        }
    }
}
