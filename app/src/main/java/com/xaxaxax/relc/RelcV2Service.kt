package com.xaxaxax.relc

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManagerHidden
import android.app.ActivityOptions
import android.app.ActivityOptionsHidden
import android.app.ActivityTaskManager
import android.app.AppOpsManager
import android.app.AppOpsManagerHidden
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManagerHidden
import android.graphics.SurfaceTexture
import android.hardware.display.DisplayManager
import android.hardware.display.DisplayManagerHidden
import android.hardware.display.VirtualDisplay
import android.hardware.input.InputManager
import android.hardware.input.InputManagerHidden
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.os.UserHandle
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.MotionEventHidden
import android.view.Surface
import androidx.annotation.Keep
import androidx.core.content.getSystemService
import dev.rikka.tools.refine.Refine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.lsposed.hiddenapibypass.LSPass
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.hypot
import kotlin.system.exitProcess

@Keep
class RelcV2Service(private val context: Context) : IRelcV2Service.Stub() {
    private companion object {
        const val SUPPORTED_FLAGS =
            DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR or
                    DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL or
                    DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS
        const val ADD_FLAGS = DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
                DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH or
                DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT
        const val ADD_FLAGS_33 = DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_TRUSTED or
                DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP or
                DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED or
                DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED
        const val ADD_FLAGS_34 = DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_OWN_FOCUS or
                DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_DEVICE_DISPLAY_GROUP
    }


    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val inputMutex = Mutex()
    private val nextInternalPointerId = AtomicInteger(-100)

    private data class PointerState(
        val logicalId: Int,
        val physicalId: Int, // 0-9
        var x: Float,
        var y: Float,
        var downTime: Long = 0
    )

    private val activePointersByDisplay = ConcurrentHashMap<Int, MutableList<PointerState>>()

    init {
        Timber.plant(Timber.DebugTree())
        Timber.d("RelcV2Service V2 (Flattened) started")

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
    private val nsStore = mutableMapOf<Int, NullSurface>()
    private val fakeDisplayContext = object : ContextWrapper(context) {
        override fun getPackageName(): String = "com.android.shell"
        override fun getOpPackageName(): String = "com.android.shell"
        override fun getApplicationContext(): Context = this
    }

    // ─── Input V2 ───

    override fun multiTouchSwipe(
        pointerId: Int,
        displayId: Int,
        points: IntArray,
        duration: Long,
        keep: Boolean
    ) {
        if (points.size < 2) return

        serviceScope.launch {
            val logId = if (pointerId == -1) nextInternalPointerId.getAndDecrement() else pointerId

            inputMutex.withLock {
                val pointers = activePointersByDisplay.getOrPut(displayId) { mutableListOf() }
                var state = pointers.find { it.logicalId == logId }
                val now = SystemClock.uptimeMillis()

                if (state == null) {
                    val usedPhysIds = pointers.map { it.physicalId }.toSet()
                    val physId = (0..9).firstOrNull { it !in usedPhysIds } ?: return@launch

                    state =
                        PointerState(logId, physId, points[0].toFloat(), points[1].toFloat(), now)
                    pointers.add(state)

                    val actionIndex = pointers.indexOf(state)
                    val action = if (pointers.size == 1) MotionEvent.ACTION_DOWN
                    else MotionEvent.ACTION_POINTER_DOWN or (actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)

                    sendMultiTouchMotionEvent(action, now, displayId, pointers)
                }

                if (points.size >= 4 && duration > 0) {
                    performSwipeInterpolation(state, points, duration, displayId, pointers)
                } else {
                    state.x = points[points.size - 2].toFloat()
                    state.y = points[points.size - 1].toFloat()
                    sendMultiTouchMotionEvent(
                        MotionEvent.ACTION_MOVE,
                        SystemClock.uptimeMillis(),
                        displayId,
                        pointers
                    )
                }

                if (!keep) {
                    val actionIndex = pointers.indexOf(state)
                    val action = if (pointers.size == 1) MotionEvent.ACTION_UP
                    else MotionEvent.ACTION_POINTER_UP or (actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)

                    sendMultiTouchMotionEvent(
                        action,
                        SystemClock.uptimeMillis(),
                        displayId,
                        pointers
                    )
                    pointers.remove(state)
                }
            }
        }
    }

    private suspend fun performSwipeInterpolation(
        state: PointerState,
        points: IntArray,
        duration: Long,
        displayId: Int,
        allPointers: List<PointerState>
    ) {
        val numPoints = points.size / 2
        val segLen = mutableListOf<Double>()
        var total = 0.0
        for (i in 0 until numPoints - 1) {
            val x1 = points[i * 2]
            val y1 = points[i * 2 + 1]
            val x2 = points[(i + 1) * 2]
            val y2 = points[(i + 1) * 2 + 1]
            val len = hypot((x2 - x1).toDouble(), (y2 - y1).toDouble())
            segLen.add(len)
            total += len
        }

        val steps = (duration / 16).toInt().coerceAtLeast(1)
        for (i in 1..steps) {
            val t = i.toDouble() / steps
            var dist = t * total
            var segIdx = 0
            while (segIdx < segLen.size && dist > segLen[segIdx]) {
                dist -= segLen[segIdx]
                segIdx++
            }

            if (segIdx < numPoints - 1) {
                val xA = points[segIdx * 2]
                val yA = points[segIdx * 2 + 1]
                val xB = points[(segIdx + 1) * 2]
                val yB = points[(segIdx + 1) * 2 + 1]
                val u = if (segLen[segIdx] > 0) dist / segLen[segIdx] else 1.0

                state.x = (xA + (xB - xA) * u).toFloat()
                state.y = (yA + (yB - yA) * u).toFloat()

                sendMultiTouchMotionEvent(
                    MotionEvent.ACTION_MOVE,
                    SystemClock.uptimeMillis(),
                    displayId,
                    allPointers
                )
            }
            delay(16)
        }
    }

    private fun sendMultiTouchMotionEvent(
        action: Int,
        eventTime: Long,
        displayId: Int,
        pointers: List<PointerState>
    ) {
        val pointerCount = pointers.size
        val properties = Array(pointerCount) { i ->
            MotionEvent.PointerProperties().apply {
                id = pointers[i].physicalId
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coords = Array(pointerCount) { i ->
            MotionEvent.PointerCoords().apply {
                x = pointers[i].x
                y = pointers[i].y
                pressure = 1.0f
                size = 1.0f
            }
        }

        val event = MotionEvent.obtain(
            pointers[0].downTime, eventTime, action, pointerCount,
            properties, coords, 0, 0, 1.0f, 1.0f, 0, 0,
            InputDevice.SOURCE_TOUCHSCREEN, 0
        )
        injectMotionEvent(event, displayId)
        event.recycle()
    }

    override fun getPointers(displayId: Int): IntArray {
        val pointers = activePointersByDisplay[displayId] ?: return intArrayOf()
        val result = IntArray(pointers.size * 3)
        for (i in pointers.indices) {
            result[i * 3] = pointers[i].logicalId
            result[i * 3 + 1] = pointers[i].x.toInt()
            result[i * 3 + 2] = pointers[i].y.toInt()
        }
        return result
    }

    // ─── 其餘實作 ───

    override fun grantRuntimePermission(packageName: String, permissionName: String): Boolean {
        return try {
            val uid = context.packageManager.getPackageUid(packageName, 0)
            Refine.unsafeCast<PackageManagerHidden>(context.packageManager)
                .grantRuntimePermission(
                    packageName,
                    permissionName,
                    UserHandle.getUserHandleForUid(uid)
                )
            true
        } catch (t: Throwable) {
            false
        }
    }

    override fun setOverlayAllowed(packageName: String): Boolean {
        return try {
            val uid = context.packageManager.getPackageUid(packageName, 0)
            val appOps = context.getSystemService(AppOpsManager::class.java)
            Refine.unsafeCast<AppOpsManagerHidden>(appOps).setMode(
                AppOpsManagerHidden.strOpToOp(AppOpsManager.permissionToOp(Manifest.permission.SYSTEM_ALERT_WINDOW)),
                uid, packageName, AppOpsManager.MODE_ALLOWED
            )
            true
        } catch (t: Throwable) {
            false
        }
    }

    override fun getVirtualDisplays(): IntArray = vdStore.keys.sorted().toIntArray()

    override fun createVirtualDisplay(
        name: String,
        width: Int,
        height: Int,
        densityDpi: Int,
        surface: Surface?,
        flags: Int,
    ): Int {
        var flags = flags and SUPPORTED_FLAGS
        flags = flags or ADD_FLAGS

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            flags = flags or ADD_FLAGS_33
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            flags = flags or ADD_FLAGS_34
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
        val vd = vdStore[displayId] ?: return false
        if (surface != null) {
            vd.surface = surface
            nsStore[displayId]?.close()
        } else {
            val ns = nsStore.getOrPut(displayId) { NullSurface() }
            vd.surface = ns.surface
        }
        return true
    }

    override fun destroyVirtualDisplay(displayId: Int): Boolean {
        vdStore.remove(displayId)?.release()
        return true
    }

    override fun launchInDisplay(packageName: String, displayId: Int): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val options = ActivityOptions.makeBasic()
        Refine.unsafeCast<ActivityOptionsHidden>(options).setLaunchDisplayId(displayId)
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                ActivityTaskManager.getService().startActivity(
                    null,
                    "com.android.shell",
                    null,
                    intent,
                    null,
                    null,
                    null,
                    0,
                    0,
                    null,
                    options.toBundle()
                )
            } else {
                ActivityManagerHidden.getService().startActivity(
                    null,
                    "com.android.shell",
                    intent,
                    null,
                    null,
                    null,
                    0,
                    0,
                    null,
                    options.toBundle()
                )
            }
            true
        } catch (t: Throwable) {
            false
        }
    }

    override fun getLauncherApps(): List<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return context.packageManager.queryIntentActivities(intent, 0).map {
            "${it.activityInfo.packageName}|${it.loadLabel(context.packageManager)}"
        }
    }

    override fun injectMotionEvent(event: MotionEvent, displayId: Int): Boolean {
        return try {
            Refine.unsafeCast<MotionEventHidden>(event).setDisplayId(displayId)
            inputManager.injectInputEvent(event, 0)
        } catch (t: Throwable) {
            false
        }
    }

    override fun injectKeyEvent(event: KeyEvent, displayId: Int): Boolean {
        return try {
            inputManager.injectInputEvent(event, 0)
        } catch (t: Throwable) {
            false
        }
    }

    override fun getDisplaySize(displayId: Int): IntArray {
        val dm = context.getSystemService(DisplayManager::class.java)
        val display = dm.getDisplay(displayId) ?: return intArrayOf(0, 0)
        val outSize = android.graphics.Point()
        display.getRealSize(outSize)
        return intArrayOf(outSize.x, outSize.y)
    }

    override fun debug(input: String?): String = "RelcV2Service Active"

    override fun destroy() {
        vdStore.forEach { (_, display) -> display.release() }
        exitProcess(0)
    }

    private fun buildDisplayManagerForVirtualDisplay(): DisplayManager {
        val ctor = DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
        ctor.isAccessible = true
        return ctor.newInstance(fakeDisplayContext)
    }

    private class NullSurface : AutoCloseable {
        val surfaceTexture = SurfaceTexture(0).apply { setDefaultBufferSize(0, 0) }
        val surface = Surface(surfaceTexture)
        override fun close() {
            surface.release()
            surfaceTexture.release()
        }
    }
}
