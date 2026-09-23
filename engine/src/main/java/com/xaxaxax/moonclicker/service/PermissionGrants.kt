package com.xaxaxax.moonclicker.service

import android.Manifest
import android.app.AppOpsManager
import android.app.AppOpsManager.permissionToOp
import android.app.AppOpsManagerHidden
import android.app.AppOpsManagerHidden.strOpToOp
import android.content.pm.PackageManager
import android.content.pm.PackageManagerHidden
import android.os.UserHandle
import timber.log.Timber

/** 對外 AIDL 的權限授予：把 runtime permission 或 overlay 權限發給另一個已安裝的 app。 */
internal class PermissionGrants(
    private val packageManager: PackageManager,
    private val packageManagerHidden: PackageManagerHidden,
    private val appOpsManagerHidden: AppOpsManagerHidden,
) {
    fun grantRuntimePermission(packageName: String, permissionName: String): Boolean {
        return try {
            val uid = packageManager.getPackageUid(packageName, 0)
            packageManagerHidden
                .grantRuntimePermission(
                    packageName,
                    permissionName,
                    UserHandle.getUserHandleForUid(uid)
                )
            true
        } catch (t: Throwable) {
            Timber.d(
                t,
                "Unable to grant runtime permission $permissionName permission to package '$packageName'."
            )
            false
        }
    }

    fun setOverlayAllowed(packageName: String): Boolean {
        return try {
            val uid = packageManager.getPackageUid(packageName, 0)
            appOpsManagerHidden.setMode(
                strOpToOp(permissionToOp(Manifest.permission.SYSTEM_ALERT_WINDOW)),
                uid, packageName, AppOpsManager.MODE_ALLOWED
            )
            true
        } catch (t: Throwable) {
            Timber.d(
                t,
                "Unable to grant the android.permission.SYSTEM_ALERT_WINDOW permission to package '$packageName'."
            )
            false
        }
    }
}
