package com.xaxaxax.relc

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.ActivityOptionsHidden
import android.app.AppOpsManager
import android.app.AppOpsManagerHidden
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManagerHidden
import android.hardware.display.DisplayManager
import android.hardware.display.DisplayManagerHidden
import android.hardware.display.VirtualDisplay
import android.os.Binder
import android.os.Build
import android.os.UserHandle
import android.view.Surface
import androidx.annotation.Keep
import dev.rikka.tools.refine.Refine
import org.lsposed.hiddenapibypass.LSPass
import timber.log.Timber

@Keep
class RelcShizukuService(private val context: Context) : IRelcShizukuService.Stub() {

    init {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            LSPass.addHiddenApiExemptions(
                "Landroid/app/ActivityOptions",
                "Landroid/view/MotionEvent"
            )
        }
    }

    private val vdStore = mutableMapOf<Int, VirtualDisplay>()

    // ─── Permissions ─────────────────────────────────────────────────────────

    override fun grantRuntimePermission(packageName: String, permissionName: String): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return try {
            val uid = context.packageManager.getPackageUid(packageName, 0)
            val handle = UserHandle.getUserHandleForUid(uid)
            Refine.unsafeCast<PackageManagerHidden>(context.packageManager)
                .grantRuntimePermission(packageName, permissionName, handle)
            true
        } catch (t: Throwable) {
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
                code, uid, packageName, AppOpsManager.MODE_ALLOWED
            )
            true
        } catch (t: Throwable) {
            Timber.e(t, "setOverlayAllowed failed")
            false
        }
    }

    // ─── VirtualDisplay ───────────────────────────────────────────────────────

    @SuppressLint("WrongConstant")
    override fun createVirtualDisplay(
        name: String,
        width: Int,
        height: Int,
        densityDpi: Int,
        surface: Surface?,
    ): Int {
        var flags =
            DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                    DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
                    DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                    DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH or
                    DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            flags = flags or
                    DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_TRUSTED or
                    DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP or
                    DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED or
                    DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            flags = flags or
                    DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_OWN_FOCUS or
                    DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_DEVICE_DISPLAY_GROUP
        }

        val dm = context.getSystemService(DisplayManager::class.java)
        val vd = dm.createVirtualDisplay(name, width, height, densityDpi, surface, flags)

        val displayId = vd.display?.displayId ?: return -1
        vdStore[displayId] = vd
        Timber.d("VirtualDisplay created: id=$displayId name=$name ${width}x${height}@$densityDpi")
        return displayId
    }

    override fun setVirtualDisplaySurface(displayId: Int, surface: Surface?): Boolean {
        val vd = vdStore[displayId]
            ?: return false.also { Timber.w("setVirtualDisplaySurface: display $displayId not found") }
        vd.surface = surface
        return true
    }

    override fun destroyVirtualDisplay(displayId: Int): Boolean {
        val vd = vdStore.remove(displayId)
            ?: return false.also { Timber.w("destroyVirtualDisplay: display $displayId not found") }
        vd.release()
        Timber.d("VirtualDisplay destroyed: id=$displayId")
        return true
    }

    // ─── Launch ───────────────────────────────────────────────────────────────

    override fun launchInDisplay(packageName: String, displayId: Int): Boolean {
        return try {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                ?: return false.also { Timber.w("launchInDisplay: no launcher intent for $packageName") }

            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            // setLaunchDisplayId is @hide — access via Refine + LSPass
            val options = ActivityOptions.makeBasic()
            Refine.unsafeCast<ActivityOptionsHidden>(options).setLaunchDisplayId(displayId)

            // clearCallingIdentity so we act as shell uid, not as the calling app
            withOwnCallingIdentity { context.startActivity(intent, options.toBundle()) }

            Timber.d("launchInDisplay: launched $packageName on display $displayId")
            true
        } catch (t: Throwable) {
            Timber.e(t, "launchInDisplay failed: $packageName on display $displayId")
            false
        }
    }

    // ─── Utils ────────────────────────────────────────────────────────────────

    private inline fun <T> withOwnCallingIdentity(block: () -> T): T {
        val token = Binder.clearCallingIdentity()
        return try {
            block()
        } finally {
            Binder.restoreCallingIdentity(token)
        }
    }
}
