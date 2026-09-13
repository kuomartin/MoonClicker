package com.xaxaxax.relc

import android.Manifest
import android.annotation.SuppressLint
import android.app.ActivityManagerHidden
import android.app.ActivityOptions
import android.app.ActivityOptionsHidden
import android.app.ActivityTaskManager
import android.app.AppOpsManager
import android.app.AppOpsManagerHidden
import android.app.RunningTaskInfoHidden_API_27
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManagerHidden
import android.hardware.display.DisplayManager
import android.hardware.display.DisplayManagerHidden
import android.view.WindowManagerGlobal
import android.hardware.display.VirtualDisplay
import android.hardware.input.InputManager
import android.hardware.input.InputManagerHidden
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.os.UserHandle
import android.view.Display
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.MotionEventHidden
import android.view.Surface
import androidx.annotation.Keep
import androidx.core.content.getSystemService
import com.xaxaxax.relc.script.DisplayGeometry
import dev.rikka.tools.refine.Refine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.lsposed.hiddenapibypass.LSPass
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.hypot
import kotlin.system.exitProcess
import kotlin.time.Duration.Companion.milliseconds

@Keep
class RelcV2Service @JvmOverloads constructor(
    private val context: Context,
    /**
     * 這個進程向系統宣稱自己是誰。
     *
     * 建立虛擬顯示與啟動 activity 都會把它送進 system_server，而那邊會拿它跟 calling uid
     * 對（`packageName must match the calling uid`）——所以它必須是**執行這段程式碼的 uid
     * 真的擁有的**套件名，不是任意字串。它不是設定，是宿主進程的事實。
     *
     * 預設 `com.android.shell`，因為服務跑在 Shizuku 起的 shell 進程裡，那裡這是實話。
     * 換一個宿主進程就得換這個值。`@JvmOverloads` 是為了讓 Shizuku 反射找得到原本的
     * `(Context)` 建構子。
     */
    private val callerPackage: String = "com.android.shell",
) : IRelcV2Service.Stub() {
    companion object {
        init {
            try {
                System.loadLibrary("relc_native")
            } catch (ex: UnsatisfiedLinkError) {
                // In Shizuku environment, sometimes we need to wait or handle library loading differently
                // but standard loadLibrary is the first step.
                Timber.e(ex)
            }
        }

        private fun MotionEvent.setDisplayId(displayId: Int): Boolean {
                return if (Build.VERSION.SDK_INT>= Build.VERSION_CODES.Q) {
                    Refine.unsafeCast<MotionEventHidden>(this).setDisplayId(displayId)
                    true
                }
                else {
                    Timber.d("Cannot associate a display id to the input event")
                    false
                }
        }

        const val DELAY_MS = 16 // 60fps

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
    private val swipeJobs = ConcurrentHashMap<Int, kotlinx.coroutines.Job>()

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
                "Landroid/view/WindowManagerGlobal",
            )
        }
    }

    private val inputManager: InputManagerHidden by lazy {
        val im = context.getSystemService<InputManager>()
            ?: throw IllegalStateException("Can not get InputManager")
        Refine.unsafeCast(im)
    }

    /**
     * 一個虛擬顯示，連同它**建立時**的尺寸。
     *
     * 尺寸留在這裡而不是事後回推：`Display.getRealSize` 回的是套用旋轉後的邏輯尺寸，
     * 要換回 surface 尺寸就得再讀一次 rotation——兩次獨立的讀取之間畫面轉了，算出來的
     * 答案會錯得很有自信（見 [getDisplaySurfaceSize]）。建立尺寸是常數，記下來就不必猜。
     *
     * 名字不放這裡：`Display.getName()` 原樣回傳建立時給的名字（實機驗證過，被加前綴的是
     * `uniqueId` 不是 name），平台已經是它的擁有者了。
     */
    private class ManagedDisplay(
        val display: VirtualDisplay,
        val surfaceWidth: Int,
        val surfaceHeight: Int,
    )

    private val vdStore = mutableMapOf<Int, ManagedDisplay>()
    private val distributorStore = mutableMapOf<Int, Long>() // displayId -> nativePtr
    private val fakeDisplayContext = object : ContextWrapper(context) {
        override fun getPackageName(): String = callerPackage
        override fun getOpPackageName(): String = callerPackage
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

        val logId = if (pointerId == -1) nextInternalPointerId.getAndDecrement() else pointerId

        // Cancel previous interpolation job for this pointer if any
        swipeJobs[logId]?.cancel()

        val job = serviceScope.launch {
            try {
                val state = inputMutex.withLock {
                    val pointers = activePointersByDisplay.getOrPut(displayId) { mutableListOf() }
                    var s = pointers.find { it.logicalId == logId }
                    val now = SystemClock.uptimeMillis()

                    if (s == null) {
                        val usedPhysIds = pointers.map { it.physicalId }.toSet()
                        val physId =
                            (0..9).firstOrNull { it !in usedPhysIds } ?: return@withLock null

                        s = PointerState(
                            logId,
                            physId,
                            points[0].toFloat(),
                            points[1].toFloat(),
                            now
                        )
                        s.downTime = now
                        pointers.add(s)

                        val actionIndex = pointers.indexOf(s)
                        val action = if (pointers.size == 1) MotionEvent.ACTION_DOWN
                        else MotionEvent.ACTION_POINTER_DOWN or (actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)

                        sendMultiTouchMotionEvent(action, now, displayId, pointers)
                    }
                    s
                } ?: return@launch

                if (points.size >= 4 && duration > 0) {
                    performSwipeInterpolation(state, points, duration, displayId)
                } else {
                    inputMutex.withLock {
                        val pointers = activePointersByDisplay[displayId] ?: return@withLock
                        state.x = points[points.size - 2].toFloat()
                        state.y = points[points.size - 1].toFloat()
                        sendMultiTouchMotionEvent(
                            MotionEvent.ACTION_MOVE,
                            SystemClock.uptimeMillis(),
                            displayId,
                            pointers
                        )
                    }
                }

                if (!keep) {
                    inputMutex.withLock {
                        val pointers = activePointersByDisplay[displayId] ?: return@withLock
                        if (!pointers.contains(state)) return@withLock

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
            } finally {
                // Only remove if it's still our job
                swipeJobs.remove(logId, coroutineContext[kotlinx.coroutines.Job])
            }
        }
        swipeJobs[logId] = job
    }

    private suspend fun performSwipeInterpolation(
        state: PointerState,
        points: IntArray,
        duration: Long,
        displayId: Int
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

        val steps = (duration / DELAY_MS).toInt().coerceAtLeast(1)
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

                val newX = (xA + (xB - xA) * u).toFloat()
                val newY = (yA + (yB - yA) * u).toFloat()

                inputMutex.withLock {
                    val pointers = activePointersByDisplay[displayId] ?: return@withLock
                    state.x = newX
                    state.y = newY
                    sendMultiTouchMotionEvent(
                        MotionEvent.ACTION_MOVE,
                        SystemClock.uptimeMillis(),
                        displayId,
                        pointers
                    )
                }
            }
            delay(DELAY_MS.milliseconds)
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

    override fun getPointers(displayId: Int): IntArray = runBlocking {
        inputMutex.withLock {
            val pointers = activePointersByDisplay[displayId] ?: return@withLock intArrayOf()
            val result = IntArray(pointers.size * 3)
            for (i in pointers.indices) {
                result[i * 3] = pointers[i].logicalId
                result[i * 3 + 1] = pointers[i].x.toInt()
                result[i * 3 + 2] = pointers[i].y.toInt()
            }
            result
        }
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
            Timber.d(
                t,
                "Unable to grant runtime permission $permissionName permission to package '$packageName'."
            )
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
            Timber.d(
                t,
                "Unable to grant the android.permission.SYSTEM_ALERT_WINDOW permission to package '$packageName'."
            )
            false
        }
    }

    override fun getVirtualDisplays(): IntArray = vdStore.keys.sorted().toIntArray()

    override fun createVirtualDisplay(
        name: String,
        width: Int,
        height: Int,
        densityDpi: Int,
        flags: Int,
    ): Int {
        var flags = flags and SUPPORTED_FLAGS
        flags = flags or ADD_FLAGS

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags = flags or DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_TRUSTED or
                    DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            flags = flags or ADD_FLAGS_33
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            flags = flags or ADD_FLAGS_34
        }

        // 1. 建立 Native GLES 分發器並獲取 Source Surface
        val nativePtr = nativeCreateDistributor(width, height)
        if (nativePtr == 0L) return -1
        val sourceSurface = nativeGetDistributorSurface(nativePtr) ?: run {
            nativeDestroyDistributor(nativePtr)
            return -1
        }

        val dm = buildDisplayManagerForVirtualDisplay()
        val vd = run {
            Timber.d(
                "createVD: callingUid=${getCallingUid()} serviceUid=${Process.myUid()} fakePkg=${fakeDisplayContext.packageName} surfaceValid=${sourceSurface.isValid}"
            )
            @SuppressLint("WrongConstant")
            dm.createVirtualDisplay(name, width, height, densityDpi, sourceSurface, flags)
        }

        val displayId = vd.display?.displayId ?: run {
            vd.release()
            nativeDestroyDistributor(nativePtr)
            return -1
        }
        vdStore[displayId] = ManagedDisplay(vd, surfaceWidth = width, surfaceHeight = height)
        distributorStore[displayId] = nativePtr
        Timber.d("VirtualDisplay created: id=$displayId name=$name ${width}x${height}@$densityDpi (Distributor Active)")
        return displayId
    }

    override fun addVirtualDisplaySurface(displayId: Int, surface: Surface): Int {
        Timber.d("addVirtualDisplaySurface: id=$displayId surfaceValid=${surface.isValid}")
        val ptr = distributorStore[displayId] ?: run {
            Timber.e("addVirtualDisplaySurface: distributor not found for id=$displayId")
            return -1
        }
        return nativeAddSurface(ptr, surface)
    }

    override fun removeVirtualDisplaySurface(displayId: Int, handle: Int): Boolean {
        Timber.d("removeVirtualDisplaySurface: id=$displayId handle=$handle")
        val ptr = distributorStore[displayId] ?: return false
        nativeRemoveSurface(ptr, handle)
        return true
    }

    override fun destroyVirtualDisplay(displayId: Int): Boolean {
        vdStore.remove(displayId)?.display?.release()
        distributorStore.remove(displayId)?.let { ptr ->
            nativeDestroyDistributor(ptr)
        }
        return true
    }

    // --- Native GLES Distributor ---
    private external fun nativeCreateDistributor(width: Int, height: Int): Long
    private external fun nativeGetDistributorSurface(ptr: Long): Surface?
    private external fun nativeAddSurface(ptr: Long, surface: Surface): Int
    private external fun nativeRemoveSurface(ptr: Long, handle: Int)
    private external fun nativeDestroyDistributor(ptr: Long)

    override fun launchInDisplay(packageName: String, displayId: Int): Boolean {
        // IActivityManager's startActivity/createStackOnDisplay/moveTaskToStack only exist
        // through API 28 (see hidden-api-contract's HIDDEN_API_CONTRACTS) — API 29 must go
        // through ActivityTaskManager, which Workaround.startActivity already branches
        // correctly for (10-param overload on Q, 11-param from R).
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            launchViaActivityTaskManager(packageName, displayId)
        } else {
            launchOrMoveViaActivityManager(packageName, displayId)
        }
    }

    private fun launchViaActivityTaskManager(packageName: String, displayId: Int): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val options = ActivityOptions.makeBasic()
        Refine.unsafeCast<ActivityOptionsHidden>(options).setLaunchDisplayId(displayId)
        return try {
            //TODO parse the result
            Workaround.startActivity(intent, options, callerPackage)
            true
        } catch (t: Throwable) {
            Timber.d(t, "Failed to launch $packageName in display#$displayId.")
            false
        }
    }

    // ── Legacy (API 27–28) ──────────────────────────────────────────────────────────────
    // Ported from RelcShizukuService, which this class replaces — that implementation was
    // hard-won, so it's kept rather than redone. IActivityManager's startActivity/
    // createStackOnDisplay/moveTaskToStack don't exist past API 28, so this path is only ever
    // reached below Q; it also covers the "app is already running elsewhere" case that
    // Workaround.startActivity's AM fallback does not (that fallback always relaunches fresh).

    private fun launchOrMoveViaActivityManager(packageName: String, displayId: Int): Boolean = runCatching {
        val iam = ActivityManagerHidden.getService()
        val tasks = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) iam.getTasks(50) else iam.getTasks(50, 0)
        val task = tasks.find { it.baseActivity?.packageName == packageName }
        if (task != null) {
            // App already running — move its task instead of relaunching.
            val hiddenInfo = Refine.unsafeCast<RunningTaskInfoHidden_API_27>(task)
            Timber.d("moveToDisplay (API <= 28): taskId=${hiddenInfo.id} pkg=$packageName to displayId=$displayId")
            // To move only one task, we create a new stack on the target display and move the task to it.
            val newStackId = iam.createStackOnDisplay(displayId)
            Timber.d("Created stack $newStackId on display $displayId")
            iam.moveTaskToStack(hiddenInfo.id, newStackId, true)
        } else {
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
                ?: throw IllegalArgumentException("launchInDisplay: no launcher intent for $packageName")
                    .also { Timber.w(it) }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            // setLaunchDisplayId is @hide — access via Refine + LSPass
            val options = ActivityOptions.makeBasic()
            Refine.unsafeCast<ActivityOptionsHidden>(options).setLaunchDisplayId(displayId)

            val result = iam.startActivity(
                null, // IApplicationThread
                callerPackage,
                intent,
                null, // resolvedType
                null, // resultTo
                null, // resultWho
                0,    // requestCode
                0,    // flags
                null, // ProfilerInfo
                options.toBundle()
            )
            Timber.d("IActivityManager.startActivity result = $result")
            checkStartActivityResult(result, intent)
        }
    }.onFailure {
        Timber.e(it, "Failed to launch/move $packageName in display#$displayId.")
    }.isSuccess

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

    override fun getLauncherApps(): List<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return context.packageManager.queryIntentActivities(intent, 0).map {
            "${it.activityInfo.packageName}|${it.loadLabel(context.packageManager)}"
        }
    }

    override fun injectMotionEvent(event: MotionEvent, displayId: Int): Boolean {
        return try {
            if (displayId!=0 && !event.setDisplayId(displayId))
                return false
            inputManager.injectInputEvent(event, 0)
        } catch (t: Throwable) {
            Timber.d(t, "Failed to inject $event on display#$displayId.")
            false
        }
    }

    override fun injectKeyEvent(event: KeyEvent, displayId: Int): Boolean {
        return try {
            inputManager.injectInputEvent(event, 0)
        } catch (t: Throwable) {
            Timber.d(t, "Failed to inject $event on display#$displayId.")
            false
        }
    }

    override fun setDisplayRotation(displayId: Int, rotation: Int): Boolean {
        val quarterTurns = rotation and 3
        // API 29 起才有 freezeDisplayRotation；27–28 沒有可用的 Java 路徑，退回 command line。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val wm = WindowManagerGlobal.getWindowManagerService()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
                    wm.freezeDisplayRotation(displayId, quarterTurns, "ReLC")
                } else {
                    @Suppress("DEPRECATION")
                    wm.freezeDisplayRotation(displayId, quarterTurns)
                }
                return true
            } catch (t: Throwable) {
                // 版本簽章不符、權限不足、displayId 不存在等——真正的失敗。**不退回 shell**：
                // 那會把可回報的錯誤變成靜默成功，摧毀 #16 Q3 兩級失敗處理的前提。
                Timber.e(t, "freezeDisplayRotation(%d, %d) failed", displayId, quarterTurns)
                return false
            }
        }
        return setDisplayRotationViaShell(displayId, quarterTurns)
    }

    /**
     * API 27–28 專用的退路——該版本區間沒有 `freezeDisplayRotation`。
     * 服務以 shell UID 執行，可直接呼叫 `cmd`。
     */
    private fun setDisplayRotationViaShell(displayId: Int, quarterTurns: Int): Boolean = try {
        val process = ProcessBuilder(
            "cmd", "window", "user-rotation", "-d", displayId.toString(), "lock", quarterTurns.toString()
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        val exit = process.waitFor()
        Timber.d("cmd window user-rotation -d %d lock %d -> exit=%d %s", displayId, quarterTurns, exit, output)
        exit == 0
    } catch (t: Throwable) {
        Timber.e(t, "cmd window user-rotation failed for display %d", displayId)
        false
    }

    override fun getDisplaySize(displayId: Int): IntArray {
        val dm = context.getSystemService(DisplayManager::class.java)
        val display = dm.getDisplay(displayId) ?: return intArrayOf(0, 0)
        val outSize = android.graphics.Point()
        @Suppress("DEPRECATION") // TODO: check if this method will still work in future Android versions.
        display.getRealSize(outSize)
        return intArrayOf(outSize.x, outSize.y)
    }

    /**
     * Surface 空間的尺寸（見 CONTEXT.md「Surface 空間 / 邏輯空間」）。
     *
     * 三條路，差別在我們對這個顯示器知道多少：
     * - 自己建立的虛擬顯示：建立尺寸是常數，直接回存下來的那一份，完全不經過推導。
     * - 實體螢幕：沒有「建立尺寸」可言，只能推。但邏輯尺寸與 rotation 都來自這個進程裡
     *   的同一個 DisplayManager，沒有跨進程的時間差。
     * - 其他 id：回 [0, 0]。不是我們建的顯示器，我們沒有立場猜它的幾何——讓呼叫端當場
     *   失敗，比帶著可能錯的尺寸跑完整個腳本好。
     */
    override fun getDisplaySurfaceSize(displayId: Int): IntArray {
        vdStore[displayId]?.let { return intArrayOf(it.surfaceWidth, it.surfaceHeight) }
        if (displayId != Display.DEFAULT_DISPLAY) {
            Timber.w("getDisplaySurfaceSize($displayId): not a display this service created")
            return intArrayOf(0, 0)
        }

        val dm = context.getSystemService(DisplayManager::class.java)
        val display = dm.getDisplay(displayId) ?: return intArrayOf(0, 0)
        val outSize = android.graphics.Point()
        @Suppress("DEPRECATION") // TODO: check if this method will still work in future Android versions.
        display.getRealSize(outSize)
        val (width, height) = DisplayGeometry.surfaceSize(outSize.x, outSize.y, display.rotation)
        return intArrayOf(width, height)
    }

    override fun debug(input: String?): String = "RelcV2Service Active"

    override fun destroy() {
        vdStore.forEach { (_, managed) -> managed.display.release() }
        exitProcess(0)
    }

    private fun buildDisplayManagerForVirtualDisplay(): DisplayManager {
        val ctor = DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
        ctor.isAccessible = true
        return ctor.newInstance(fakeDisplayContext)
    }
}
