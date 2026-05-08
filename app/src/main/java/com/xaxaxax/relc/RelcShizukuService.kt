package com.xaxaxax.relc

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.ActivityOptionsHidden
import android.app.AppOpsManager
import android.app.AppOpsManagerHidden
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManagerHidden
import android.hardware.display.DisplayManager
import android.hardware.display.DisplayManagerHidden
import android.hardware.display.VirtualDisplay
import android.os.Binder
import android.os.Build
import android.os.Bundle
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
            LSPass.addHiddenApiExemptions("")
//            LSPass.addHiddenApiExemptions(
//                "Landroid/app/ActivityOptions",
//                "Landroid/view/MotionEvent",
//                "Landroid/app/ActivityOptions",
//            )
        }

        Timber.plant(Timber.DebugTree())
        Timber.d("Service started with UID: ${android.os.Process.myUid()}")
        Timber.d("Shizuku is here~~")
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
            withOwnCallingIdentity {
                val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                    ?: return false.also { Timber.w("launchInDisplay: no launcher intent for $packageName") }

                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

                // setLaunchDisplayId is @hide — access via Refine + LSPass
                val options = ActivityOptions.makeBasic()
                Refine.unsafeCast<ActivityOptionsHidden>(options).setLaunchDisplayId(displayId)

                // Context.startActivity() → ContextImpl.startActivity()
                //   → execStartActivity(getOuterContext(), ...)
                // getOuterContext() is ContextImpl.mOuterContext — a reference set at
                // context-creation time that bypasses every ContextWrapper layer we add.
                // So callingPackage always leaks as "com.xaxaxax.relc" regardless of
                // any shellContext wrapper, causing START_PERMISSION_DENIED (UID mismatch).
                //
                // Fix: call IActivityTaskManager.startActivity() directly so we control
                // callingPackage = "com.android.shell" explicitly.
                startActivityViaAtm(intent, options.toBundle())
            }

            Timber.d("launchInDisplay: launched $packageName on display $displayId")
            true
        } catch (t: Throwable) {
            Timber.e(t, "launchInDisplay failed: $packageName on display $displayId")
            false
        }
    }

    /**
     * Calls IActivityTaskManager.startActivity() directly with
     * callingPackage="com.android.shell", bypassing Context.startActivity()
     * which would leak the real package name via ContextImpl.getOuterContext().
     *
     * Signature (API 30+):
     *   startActivity(IApplicationThread, String, String, Intent, String,
     *                 IBinder, String, int, int, ProfilerInfo, Bundle)
     * Signature (API 28-29):
     *   startActivity(IApplicationThread, String, Intent, String,
     *                 IBinder, String, int, int, ProfilerInfo, Bundle)
     */
    @SuppressLint("PrivateApi", "BlockedPrivateApi")
    private fun startActivityViaAtm(intent: Intent, options: Bundle?) {
        // IActivityTaskManager service (API 29+) / IActivityManager (API 28)
        val (serviceObj, paramCount) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val atm = Class.forName("android.app.ActivityTaskManager")
                .getDeclaredMethod("getService")
                .apply { isAccessible = true }
                .invoke(null) ?: error("IActivityTaskManager is null")
            // callingFeatureId added in API 30 → 11 params; API 29 → 10
            atm to if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) 11 else 10
        } else {
            // API 28: use IActivityManager
            val am = Class.forName("android.app.ActivityManager")
                .getDeclaredMethod("getService")
                .apply { isAccessible = true }
                .invoke(null) ?: error("IActivityManager is null")
            am to 10
        }

        val method = serviceObj.javaClass.methods
            .filter { it.name == "startActivity" && it.parameterCount == paramCount }
            .also {
                Timber.d(
                    "IActivityTaskManager.startActivity candidates " +
                            "(paramCount=$paramCount): ${it.size} found"
                )
            }
            .firstOrNull() ?: error("startActivity($paramCount params) not found")

        Timber.d(
            "Invoking ${method.declaringClass.simpleName}.${method.name} " +
                    "with callingPackage=com.android.shell"
        )

        // Build the arg array:
        //   [0] IApplicationThread caller  → null (no real thread)
        //   [1] String callingPackage      → "com.android.shell"
        //   [2] String callingFeatureId    → null  (API 30+ only)
        //   [?] Intent                     → intent
        //   remaining                      → null / 0
        val args = arrayOfNulls<Any>(paramCount)
        args[0] = null                    // IApplicationThread
        args[1] = "com.android.shell"    // callingPackage
        val intentIdx = if (paramCount == 11) 3 else 2
        args[intentIdx] = intent
        args[paramCount - 1] = options   // Bundle (last param)
        // int params default to 0 via null — but primitives need explicit values
        method.parameterTypes.forEachIndexed { i, type ->
            if (type == Int::class.javaPrimitiveType && args[i] == null) args[i] = 0
        }

        val result = method.invoke(serviceObj, *args) as? Int
            ?: error("startActivity returned unexpected type")

        Timber.d("IActivityTaskManager.startActivity result = $result")
        checkStartActivityResult(result, intent)
    }

    /**
     * Mirror of the @hide Instrumentation.checkStartActivityResult().
     * Negative result codes are fatal; positive ones (1 = success, 3 = task-to-front, …) are OK.
     */
    private fun checkStartActivityResult(result: Int, intent: Intent) {
        if (result >= 1) return  // ActivityManager.START_SUCCESS and other non-error codes
        when (result) {
            -1, -2 -> throw ActivityNotFoundException(
                "No Activity found to handle $intent")
            -4     -> throw SecurityException(
                "Not allowed to start activity $intent")
            -5     -> throw IllegalArgumentException(
                "PendingIntent is not an activity")
            -6     -> throw RuntimeException(
                "Activity could not be started for $intent")
            -7     -> throw SecurityException(
                "Starting under voice control not allowed for: $intent")
            else   -> if (result < 0) throw RuntimeException(
                "Unknown error code $result when starting $intent")
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
