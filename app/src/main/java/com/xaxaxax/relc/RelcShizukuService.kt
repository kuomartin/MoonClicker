package com.xaxaxax.relc

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManagerHidden
import android.app.ActivityOptions
import android.app.ActivityOptionsHidden
import android.app.ActivityTaskManager
import android.app.AppOpsManager
import android.app.AppOpsManagerHidden
import android.app.RunningTaskInfoHidden
import android.app.RunningTaskInfoHidden_API_27
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManagerHidden
import android.hardware.display.DisplayManager
import android.hardware.display.DisplayManagerHidden
import android.hardware.display.VirtualDisplay
import android.hardware.input.InputManager
import android.hardware.input.InputManagerHidden
import android.os.Build
import android.os.Process
import android.os.UserHandle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.MotionEventHidden
import android.view.Surface
import androidx.annotation.Keep
import androidx.annotation.RequiresApi
import androidx.core.content.getSystemService
import dev.rikka.tools.refine.Refine
import org.lsposed.hiddenapibypass.LSPass
import timber.log.Timber
import kotlin.system.exitProcess

@Keep
class RelcShizukuService(private val context: Context) : IRelcShizukuService.Stub() {

    init {
        Timber.plant(Timber.DebugTree())
        Timber.d("Service started with UID: ${Process.myUid()}")
        Timber.d("Shizuku is here~~")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            LSPass.addHiddenApiExemptions(
                "Landroid/app/ActivityManager",
                "Landroid/app/ActivityOptions",
                "Landroid/app/ActivityTaskManager",
                "Landroid/app/AppOpsManager",
                "Landroid/content/pm/PackageManager",
                "Landroid/hardware/input/InputManager",
                "Landroid/view/MotionEvent",
            )
        }
    }

    private val inputManager: InputManagerHidden by lazy {
        val im = context.getSystemService<InputManager>()
            ?: throw IllegalStateException("Can not get InputManager")
        Refine.unsafeCast(im)
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
            val op = AppOpsManager.permissionToOp(Manifest.permission.SYSTEM_ALERT_WINDOW)
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
    override fun getVirtualDisplays(): IntArray {
        return vdStore.keys.sorted().toIntArray()
    }

    override fun createVirtualDisplay(
        name: String,
        width: Int,
        height: Int,
        densityDpi: Int,
        surface: Surface?,
        destroyContent: Boolean,
    ): Int {
        var flags =
            DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                    DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
                    DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                    DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH or
                    DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT

        if (destroyContent)
            flags = flags or DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            flags = flags or
                    DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_TRUSTED or
//                    DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP or
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
                "createVD: callingUid=${getCallingUid()} serviceUid=${Process.myUid()} fakePkg=${fakeDisplayContext.packageName} surfaceValid=${surface?.isValid}"
            )
            @SuppressLint("WrongConstant")
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
        Timber.d("setVirtualDisplaySurface: display $displayId surfaceValid=${surface?.isValid}")
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
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            launchAppViaATM(packageName, displayId)
        } else {
            launchAppViaIAM(packageName, displayId)
        }
    }

    override fun launchHome(displayId: Int): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            launchHomeViaATM(displayId)
        } else {
            launchHomeViaIAM(displayId)
        }
    }

    @RequiresApi(Build.VERSION_CODES.R)
    private fun launchHomeViaATM(displayId: Int) = runCatching {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val options = ActivityOptions.makeBasic()
        Refine.unsafeCast<ActivityOptionsHidden>(options).setLaunchDisplayId(displayId)

        val atm = ActivityTaskManager.getService()
        val result = atm.startActivity(
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
            options.toBundle()
        )
        Timber.d("launchHomeViaATM result = $result")
        checkStartActivityResult(result, intent)
    }.isSuccess

    private fun launchHomeViaIAM(displayId: Int) = runCatching {
        val intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val options = ActivityOptions.makeBasic()
        Refine.unsafeCast<ActivityOptionsHidden>(options).setLaunchDisplayId(displayId)

        val iam = ActivityManagerHidden.getService()
        val result = iam.startActivity(
            null, // IApplicationThread
            "com.android.shell",
            intent,
            null, // resolvedType
            null, // resultTo
            null, // resultWho
            0,    // requestCode
            0,    // flags
            null, // ProfilerInfo
            options.toBundle()
        )
        Timber.d("launchHomeViaIAM result = $result")
        checkStartActivityResult(result, intent)
    }.isSuccess

    @RequiresApi(Build.VERSION_CODES.R)
    private fun launchAppViaATM(packageName: String, displayId: Int) = runCatching {
        // Whether the application are running or not, ATM will handle everything.

        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            ?: throw IllegalArgumentException("launchInDisplay: no launcher intent for $packageName")
                .also { Timber.w(it) }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        // setLaunchDisplayId is @hide — access via Refine + LSPass
        val options = ActivityOptions.makeBasic()
        Refine.unsafeCast<ActivityOptionsHidden>(options).setLaunchDisplayId(displayId)

        val atm = ActivityTaskManager.getService()
        val result = atm.startActivity(
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
            options.toBundle()
        )
        Timber.d("IActivityTaskManager.startActivity result = $result")
        checkStartActivityResult(result, intent)
    }.isSuccess


    private fun launchAppViaIAM(packageName: String, displayId: Int) = runCatching {
        // Used for API 29 and below

        val iam = ActivityManagerHidden.getService()
        val tasks = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            iam.getTasks(50)
        else
            iam.getTasks(50, 0)
        val task = tasks.find { it.baseActivity?.packageName == packageName }
        if (task != null) {
            // if app is running, move it
            val hiddenInfo = Refine.unsafeCast<RunningTaskInfoHidden_API_27>(task)
            Timber.d("moveToDisplay (API <= 29): taskId=${hiddenInfo.id} pkg=$packageName to displayId=$displayId")
            // To move only one task, we create a new stack on the target display and move the task to it.
            val newStackId = iam.createStackOnDisplay(displayId)
            Timber.d("Created stack $newStackId on display $displayId")
            iam.moveTaskToStack(hiddenInfo.id, newStackId, true)
        } else {
            // start app by IAM
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                ?: throw IllegalArgumentException("launchInDisplay: no launcher intent for $packageName")
                    .also { Timber.w(it) }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            // setLaunchDisplayId is @hide — access via Refine + LSPass
            val options = ActivityOptions.makeBasic()
            Refine.unsafeCast<ActivityOptionsHidden>(options).setLaunchDisplayId(displayId)

            val result = iam.startActivity(
                null, // IApplicationThread
                "com.android.shell",
                intent,
                null, // resolvedType
                null, // resultTo
                null, // resultWho
                0,    // requestCode
                0,    // flags
                null, // ProfilerInfo
                options.toBundle()
            )
            Timber.d("IActivityTaskManager.startActivity result = $result")
            checkStartActivityResult(result, intent)
        }
    }.onFailure {
        Timber.e(it)
    }.isSuccess

    override fun debug(input: String?): String {
        // API Level 27
        // 1. 找 Task
        return runCatching {
            val am = ActivityManagerHidden.getService()
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                am.getTasks(50).map {
                    val hidden = Refine.unsafeCast<RunningTaskInfoHidden>(it)
                    hidden.id to hidden.baseActivity.packageName
                }
            } else {
                am.getTasks(50, 0).map {
                    val hidden = Refine.unsafeCast<RunningTaskInfoHidden_API_27>(it)
                    hidden.id to hidden.baseActivity.packageName
                }
            }.joinToString("\n") { (id, pkg) ->
                "Task [$id] pkg=$pkg"
            }
        }
            .onFailure { Timber.e(it) }
            .getOrElse { it.message ?: "" }
    }

    override fun injectMotionEvent(event: MotionEvent, displayId: Int): Boolean {
        return try {
            Refine.unsafeCast<MotionEventHidden>(event).setDisplayId(displayId)
            inputManager.injectInputEvent(event, 0) // INJECT_INPUT_EVENT_MODE_ASYNC
        } catch (t: Throwable) {
            Timber.e(t, "injectMotionEvent failed")
            false
        }
    }

    override fun injectKeyEvent(event: KeyEvent, displayId: Int): Boolean {
        return try {
            inputManager.injectInputEvent(event, 0) // INJECT_INPUT_EVENT_MODE_ASYNC
        } catch (t: Throwable) {
            Timber.e(t, "injectKeyEvent failed")
            false
        }
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

    override fun destroy() {
        Timber.i("Closing service")
        Timber.i("Close existing ${vdStore.size} virtualDisplays.")
        vdStore.forEach { (_, display) ->
            display.release()
        }
        exitProcess(0)
    }


    // ─── Utils ────────────────────────────────────────────────────────────────

    private fun buildDisplayManagerForVirtualDisplay(): DisplayManager {
        val ctor = DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
        ctor.isAccessible = true
        return ctor.newInstance(fakeDisplayContext)
    }
}
