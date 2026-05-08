package com.xaxaxax.relc

import android.annotation.SuppressLint
import android.app.ActivityManagerHidden
import android.app.ActivityOptions
import android.app.ActivityOptionsHidden
import android.app.ActivityTaskManager
import android.app.AppOpsManager
import android.app.AppOpsManagerHidden
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManagerHidden
import android.hardware.display.DisplayManager
import android.hardware.display.DisplayManagerHidden
import android.hardware.display.VirtualDisplay
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
    private val fakeDisplayContext = object : ContextWrapper(context) {
        override fun getPackageName(): String = "com.android.shell"
        override fun getOpPackageName(): String = "com.android.shell"
        override fun getApplicationContext(): Context = this
    }

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

        val dm = buildDisplayManagerForVirtualDisplay()
        val vd = run {
            Timber.d(
                "createVD: callingUid=${getCallingUid()} serviceUid=${android.os.Process.myUid()} fakePkg=${fakeDisplayContext.packageName}"
            )
            dm.createVirtualDisplay(name, width, height, densityDpi, surface, flags)
        }

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
            run {
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
    private fun startActivityViaAtm(intent: Intent, options: Bundle?) {
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val am = ActivityTaskManager.getService()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                am.startActivity(
                    null, // IApplicationThread
                    "com.android.shell",
                    null, // callingFeatureId
                    intent,
                    null, // resolvedType
                    null, // resultTo
                    null, // resultWho
                    0,    // requestCode
                    0,    // flags
                    null, // ProfilerInfo
                    options
                )
            } else {
                am.startActivity(
                    null, // IApplicationThread
                    "com.android.shell",
                    intent,
                    null, // resolvedType
                    null, // resultTo
                    null, // resultWho
                    0,    // requestCode
                    0,    // flags
                    null, // ProfilerInfo
                    options
                )
            }
        } else {
            val am = ActivityManagerHidden.getService()
            am.startActivity(
                null, // IApplicationThread
                "com.android.shell",
                intent,
                null, // resolvedType
                null, // resultTo
                null, // resultWho
                0,    // requestCode
                0,    // flags
                null, // ProfilerInfo
                options
            )

        }
        Timber.d("IActivityTaskManager.startActivityWithFeature result = $result")
        checkStartActivityResult(result, intent)
        return
    }

    /**
     * Mirror of the @hide Instrumentation.checkStartActivityResult().
     * Negative result codes are fatal; positive ones (1 = success, 3 = task-to-front, …) are OK.
     */
    private fun checkStartActivityResult(result: Int, intent: Intent) {
        if (result >= 1) return  // ActivityManager.START_SUCCESS and other non-error codes
        when (result) {
            -1, -2 -> throw ActivityNotFoundException(
                "No Activity found to handle $intent"
            )

            -4 -> throw SecurityException(
                "Not allowed to start activity $intent"
            )

            -5 -> throw IllegalArgumentException(
                "PendingIntent is not an activity"
            )

            -6 -> throw RuntimeException(
                "Activity could not be started for $intent"
            )

            -7 -> throw SecurityException(
                "Starting under voice control not allowed for: $intent"
            )

            else -> if (result < 0) throw RuntimeException(
                "Unknown error code $result when starting $intent"
            )
        }
    }

    // ─── Utils ────────────────────────────────────────────────────────────────
    private fun buildDisplayManagerForVirtualDisplay(): DisplayManager {
        val ctor = DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
        ctor.isAccessible = true
        return ctor.newInstance(fakeDisplayContext)
    }
}
