package com.xaxaxax.moonclicker

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
import android.content.pm.PackageManager
import android.content.pm.PackageManagerHidden
import android.hardware.display.DisplayManager
import android.hardware.display.DisplayManagerHidden
import android.view.WindowManagerGlobal
import android.hardware.display.VirtualDisplay
import android.hardware.input.InputManager
import android.hardware.input.InputManagerHidden
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.PowerManagerHidden
import android.os.Process
import android.os.SystemClock
import android.os.UserHandle
import android.view.Display
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.MotionEventHidden
import android.view.Surface
import android.util.DisplayMetrics
import androidx.annotation.Keep
import androidx.core.content.getSystemService
import com.xaxaxax.moonclicker.MoonClickerDisplayInfo
import com.xaxaxax.moonclicker.script.DisplayGeometry
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
class MoonClickerService @JvmOverloads constructor(
    private val context: Context,
    /**
     * 這個進程向系統宣稱自己是誰。不是設定，是宿主進程的事實——system_server 會拿它跟
     * calling uid 對（`packageName must match the calling uid`），所以必須是執行這段程式碼
     * 的 uid 真的擁有的套件名。換宿主進程就得換這個值。
     */
    private val callerPackage: String = "com.android.shell",
) : IMoonClickerService.Stub() {
    companion object {
        init {
            try {
                System.loadLibrary("moonclicker_native")
            } catch (ex: UnsatisfiedLinkError) {
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

        /** 都不在 `Manifest.permission` 裡（signature|privileged，@hide）。 */
        const val ADD_TRUSTED_DISPLAY = "android.permission.ADD_TRUSTED_DISPLAY"
        const val ADD_ALWAYS_UNLOCKED_DISPLAY =
            "android.permission.ADD_ALWAYS_UNLOCKED_DISPLAY"

        /** 呼叫端可以要求的旗標，其餘一律由這裡決定。 */
        const val SUPPORTED_FLAGS =
            DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR or
                    DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL or
                    DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS

        /** 每個 API level 都給的基本盤。 */
        const val ADD_FLAGS = DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
                DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH or
                DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT

        /** API 34 起，兩者都依賴顯示器是 trusted。 */
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
        Timber.d("MoonClickerService V2 (Flattened) started")

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
     * 一個虛擬顯示，連同它建立時的尺寸——那是常數，記下來就不必從邏輯尺寸加 rotation
     * 回推（兩次獨立讀取之間畫面轉了，答案會錯得很有自信）。見 [getDisplaySurfaceSize]。
     */
    private class ManagedDisplay(
        val display: VirtualDisplay,
        val surfaceWidth: Int,
        val surfaceHeight: Int,
    )

    private val vdStore = mutableMapOf<Int, ManagedDisplay>()
    private val distributorStore = mutableMapOf<Int, Long>() // displayId -> nativePtr
    private val mirrorRefCounts = mutableMapOf<Int, Int>() // physicalDisplayId -> refCount
    private val mirrorDisplayMap = mutableMapOf<Int, Int>() // physicalDisplayId -> mirrorVirtualDisplayId
    private val rotationListeners = mutableMapOf<Int, DisplayManager.DisplayListener>()
    private val plainDisplayManager by lazy { context.getSystemService(DisplayManager::class.java) }

    /**
     * v 在這裡（distributor）取消，consumer 不用知道它存在（ADR-0017）。這個 VD 帶
     * `VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT`，所以自己的 rotation 就是 v；讀取方式是
     * 公開的 `Display` API，跑在同一個 process，不需要跨 IPC。也被 [getDisplaySurfaceSize]
     * 用來判斷目前的影格尺寸要不要互換長寬，同一個 `plainDisplayManager` 讀取。
     */
    private fun startRotationTracking(displayId: Int, nativePtr: Long) {
        val manager = plainDisplayManager ?: return
        nativeSetDistributorRotation(nativePtr, manager.getDisplay(displayId)?.rotation ?: 0)
        val listener = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(id: Int) = Unit
            override fun onDisplayRemoved(id: Int) = Unit
            override fun onDisplayChanged(id: Int) {
                if (id != displayId) return
                nativeSetDistributorRotation(nativePtr, manager.getDisplay(displayId)?.rotation ?: 0)
            }
        }
        manager.registerDisplayListener(listener, Handler(Looper.getMainLooper()))
        rotationListeners[displayId] = listener
    }

    private fun stopRotationTracking(displayId: Int) {
        val listener = rotationListeners.remove(displayId) ?: return
        plainDisplayManager?.unregisterDisplayListener(listener)
    }
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

    override fun getVirtualDisplays(): IntArray {
        val internalMirrorVdIds = mirrorDisplayMap.values.toSet()
        return vdStore.keys.filter { it !in internalMirrorVdIds }.sorted().toIntArray()
    }

    @Synchronized
    override fun acquireDisplayMirror(displayId: Int): Boolean {
        val current = mirrorRefCounts[displayId] ?: 0
        if (current > 0 && mirrorDisplayMap.containsKey(displayId)) {
            mirrorRefCounts[displayId] = current + 1
            Timber.d("acquireDisplayMirror: display $displayId refCount incremented to ${current + 1}")
            return true
        }

        val ok = createMirrorInternal(displayId)
        if (ok) {
            mirrorRefCounts[displayId] = 1
            Timber.d("acquireDisplayMirror: display $displayId mirror created, refCount=1")
        }
        return ok
    }

    @Synchronized
    override fun releaseDisplayMirror(displayId: Int): Boolean {
        val current = mirrorRefCounts[displayId] ?: 0
        if (current <= 0) {
            Timber.w("releaseDisplayMirror: display $displayId has refCount <= 0")
            return false
        }
        val newRef = current - 1
        Timber.d("releaseDisplayMirror: display $displayId refCount decremented to $newRef")
        if (newRef == 0) {
            mirrorRefCounts.remove(displayId)
            val mirrorVdId = mirrorDisplayMap.remove(displayId)
            if (mirrorVdId != null) {
                destroyVirtualDisplay(mirrorVdId)
                Timber.d("releaseDisplayMirror: destroyed mirror VD $mirrorVdId for display $displayId")
            }
        } else {
            mirrorRefCounts[displayId] = newRef
        }
        return true
    }

    override fun isDisplayMirrorActive(displayId: Int): Boolean {
        return (mirrorRefCounts[displayId] ?: 0) > 0 && mirrorDisplayMap.containsKey(displayId)
    }

    private fun createMirrorInternal(displayId: Int): Boolean {
        val dm = buildDisplayManagerForVirtualDisplay()
        val sourceDisplay = dm.getDisplay(displayId) ?: run {
            Timber.e("createMirrorInternal: source display $displayId not found")
            return false
        }
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        sourceDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val densityDpi = metrics.densityDpi

        val nativePtr = nativeCreateDistributor(width, height)
        if (nativePtr == 0L) return false
        val sourceSurface = nativeGetDistributorSurface(nativePtr) ?: run {
            nativeDestroyDistributor(nativePtr)
            return false
        }

        val vd = try {
            val method = DisplayManager::class.java.getMethod(
                "createVirtualDisplay",
                String::class.java,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                Surface::class.java
            )
            method.invoke(null, "moonclicker-mirror-$displayId", width, height, displayId, sourceSurface) as? VirtualDisplay
        } catch (e: Throwable) {
            Timber.e(e, "createMirrorInternal: reflection on createVirtualDisplay failed")
            null
        }

        if (vd == null) {
            nativeDestroyDistributor(nativePtr)
            return false
        }

        val mirrorVdId = vd.display?.displayId ?: run {
            vd.release()
            nativeDestroyDistributor(nativePtr)
            return false
        }

        vdStore[mirrorVdId] = ManagedDisplay(vd, surfaceWidth = width, surfaceHeight = height)
        distributorStore[mirrorVdId] = nativePtr
        mirrorDisplayMap[displayId] = mirrorVdId
        Timber.d("createMirrorInternal: created mirror VD $mirrorVdId for source display $displayId (${width}x${height}@$densityDpi)")
        return true
    }

    override fun createVirtualDisplay(
        name: String,
        width: Int,
        height: Int,
        densityDpi: Int,
        flags: Int,
    ): Int {
        val baseFlags = (flags and SUPPORTED_FLAGS) or ADD_FLAGS
        val privilegedFlags = baseFlags or privilegedFlags()

        val nativePtr = nativeCreateDistributor(width, height)
        if (nativePtr == 0L) return -1
        val sourceSurface = nativeGetDistributorSurface(nativePtr) ?: run {
            nativeDestroyDistributor(nativePtr)
            return -1
        }

        val dm = buildDisplayManagerForVirtualDisplay()
        Timber.d(
            "createVD: callingUid=${getCallingUid()} serviceUid=${Process.myUid()} fakePkg=${fakeDisplayContext.packageName} surfaceValid=${sourceSurface.isValid}"
        )
        val vd = createDisplay(dm, name, width, height, densityDpi, sourceSurface, privilegedFlags)
            ?: createDisplay(dm, name, width, height, densityDpi, sourceSurface, baseFlags)
            ?: run {
                nativeDestroyDistributor(nativePtr)
                return -1
            }

        val displayId = vd.display?.displayId ?: run {
            vd.release()
            nativeDestroyDistributor(nativePtr)
            return -1
        }
        vdStore[displayId] = ManagedDisplay(vd, surfaceWidth = width, surfaceHeight = height)
        distributorStore[displayId] = nativePtr
        startRotationTracking(displayId, nativePtr)
        Timber.d("VirtualDisplay created: id=$displayId name=$name ${width}x${height}@$densityDpi (Distributor Active)")
        return displayId
    }

    /**
     * API 33+ 的特權旗標，逐項按它自己的前提決定——`DisplayManagerService`
     * 裡是三條獨立的檢查，不要綁成一包給或一包丟。
     *
     * 綁成一包的代價：有 `ADD_TRUSTED_DISPLAY` 卻沒有 `ADD_ALWAYS_UNLOCKED_DISPLAY` 的機器
     * 會為一個旗標讓整個顯示器退回非 trusted，而少了 `ALWAYS_UNLOCKED`，虛擬顯示在裝置
     * 鎖定或休眠時收不到注入的觸控。API 33 這條界線與 scrcpy 的 `NewDisplayCapture` 一致；
     * 見 docs/virtual-display-pitfalls.md。
     */
    private fun privilegedFlags(): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return 0

        // 沒有權限檢查，一律給。
        var f = DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED

        if (holds(ADD_TRUSTED_DISPLAY)) {
            f = f or DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_TRUSTED or
                    DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP

            // 依賴 OWN_DISPLAY_GROUP（見 javadoc），所以巢狀在這裡，但要的是另一個權限。
            if (holds(ADD_ALWAYS_UNLOCKED_DISPLAY)) {
                f = f or DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED
            }

            // 依賴顯示器是 trusted。
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                f = f or ADD_FLAGS_34
            }
        }
        return f
    }

    /**
     * 這個進程有沒有某個權限——直接問，不要用丟例外去試。`checkSelfPermission` 查的是
     * `Process.myUid()`，正是 `DisplayManagerService` 會拿去對的那個身分。
     */
    private fun holds(permission: String): Boolean =
        grantedPermissions.getOrPut(permission) {
            val granted = context.checkSelfPermission(permission) ==
                    PackageManager.PERMISSION_GRANTED
            Timber.d("$permission granted=$granted for uid=${Process.myUid()}")
            granted
        }

    /**
     * `FLAG_OWN_DISPLAY_GROUP` 的顯示器有自己獨立的 wakefulness 計時器，閒置逾時後
     * `state` 變 OFF——而 OFF 之後 `InputDispatcher` 直接丟棄送進來的輸入事件，
     * 正常的「輸入喚醒 userActivity」路徑因此叫不醒它，是個死結（issue #6）。
     *
     * 在每個會讓使用者看到/操作這個顯示器的入口都主動喚醒一次，讓它沒有機會卡進那個死結。
     * 只對本服務自己建立、確實拿到這個旗標的顯示器做，不動主螢幕或其他一般顯示器。
     */
    private fun wakeDisplayGroupIfOwned(displayId: Int) {
        if (displayId == Display.DEFAULT_DISPLAY) return
        if (!vdStore.containsKey(displayId)) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        try {
            val pm = context.getSystemService(PowerManager::class.java)
            Refine.unsafeCast<PowerManagerHidden>(pm).wakeUp(
                SystemClock.uptimeMillis(),
                PowerManagerHidden.WAKE_REASON_APPLICATION,
                "MoonClicker own-display-group keep-awake",
                displayId,
            )
        } catch (t: Throwable) {
            Timber.d(t, "wakeUp(displayId=$displayId) failed")
        }
    }

    private val grantedPermissions = ConcurrentHashMap<String, Boolean>()

    /**
     * 建一個虛擬顯示，失敗回 `null`。
     *
     * 呼叫端用 [privilegedFlags] 試一次、被擋下來再用基本旗標試一次——那些旗標各自還有
     * 權限以外的前提。非 trusted 的顯示器仍然建得起來、收得到影格與觸控，只是行為降級。
     */
    private fun createDisplay(
        dm: DisplayManager,
        name: String,
        width: Int,
        height: Int,
        densityDpi: Int,
        surface: Surface,
        flags: Int,
    ): VirtualDisplay? = try {
        dm.createVirtualDisplay(name, width, height, densityDpi, surface, flags)
    } catch (e: SecurityException) {
        Timber.w(e, "createVirtualDisplay rejected with flags=0x${flags.toString(16)}")
        null
    }

    override fun addVirtualDisplaySurface(displayId: Int, surface: Surface): Int {
        Timber.d("addVirtualDisplaySurface: id=$displayId surfaceValid=${surface.isValid}")
        wakeDisplayGroupIfOwned(displayId)
        val actualId = mirrorDisplayMap[displayId] ?: displayId
        val ptr = distributorStore[actualId] ?: run {
            Timber.e("addVirtualDisplaySurface: distributor not found for id=$displayId (actualId=$actualId)")
            return -1
        }
        return nativeAddSurface(ptr, surface)
    }

    override fun removeVirtualDisplaySurface(displayId: Int, handle: Int): Boolean {
        Timber.d("removeVirtualDisplaySurface: id=$displayId handle=$handle")
        val actualId = mirrorDisplayMap[displayId] ?: displayId
        val ptr = distributorStore[actualId] ?: return false
        nativeRemoveSurface(ptr, handle)
        return true
    }

    override fun destroyVirtualDisplay(displayId: Int): Boolean {
        stopRotationTracking(displayId)
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
    private external fun nativeSetDistributorRotation(ptr: Long, rotation: Int)
    private external fun nativeDestroyDistributor(ptr: Long)

    override fun launchInDisplay(packageName: String, displayId: Int): Boolean {
        wakeDisplayGroupIfOwned(displayId)
        // IActivityManager 的 startActivity/createStackOnDisplay/moveTaskToStack 只到 API 28
        // 為止（見 HIDDEN_API_CONTRACTS），API 29 起一律走 ActivityTaskManager。
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            launchViaActivityTaskManager(packageName, displayId)
        } else {
            launchOrMoveViaActivityManager(packageName, displayId)
        }
    }

    /**
     * `startActivity` 的回傳值只抓得到同步的框架錯誤（找不到 activity、權限不足）。
     *
     * 「activity 不支援次要顯示器、被悄悄轉去別台」不在其中：`ActivityStarter` 會把
     * `START_ABORTED` 換成 `START_SUCCESS` 回給呼叫端，該場景只透過 `ITaskStackListener`
     * 非同步通知。要抓它得在啟動後查 task 實際落在哪個顯示器——見 issue #25。
     */
    private fun launchViaActivityTaskManager(packageName: String, displayId: Int): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val options = ActivityOptions.makeBasic()
        Refine.unsafeCast<ActivityOptionsHidden>(options).setLaunchDisplayId(displayId)
        return try {
            val result = Workaround.startActivity(intent, options, callerPackage)
            Timber.d("ActivityTaskManager.startActivity result = $result")
            checkStartActivityResult(result, intent)
            true
        } catch (t: Throwable) {
            Timber.d(t, "Failed to launch $packageName in display#$displayId.")
            false
        }
    }

    // ── Legacy (API 27–28) ──────────────────────────────────────────────────────────────
    // 只在 Q 以下走到。除了啟動，這條路也涵蓋「app 已經跑在別的顯示器上」——
    // Workaround.startActivity 的 AM 退路不涵蓋，它一律重新啟動。

    private fun launchOrMoveViaActivityManager(packageName: String, displayId: Int): Boolean = runCatching {
        val iam = ActivityManagerHidden.getService()
        val tasks = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) iam.getTasks(50) else iam.getTasks(50, 0)
        val task = tasks.find { it.baseActivity?.packageName == packageName }
        if (task != null) {
            val hiddenInfo = Refine.unsafeCast<RunningTaskInfoHidden_API_27>(task)
            Timber.d("moveToDisplay (API <= 28): taskId=${hiddenInfo.id} pkg=$packageName to displayId=$displayId")
            // 只搬這一個 task：在目標顯示器上開一個新 stack，再把 task 移過去。
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
            wakeDisplayGroupIfOwned(displayId)
            inputManager.injectInputEvent(event, 0)
        } catch (t: Throwable) {
            Timber.d(t, "Failed to inject $event on display#$displayId.")
            false
        }
    }

    override fun injectKeyEvent(event: KeyEvent, displayId: Int): Boolean {
        return try {
            wakeDisplayGroupIfOwned(displayId)
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
                    wm.freezeDisplayRotation(displayId, quarterTurns, "MoonClicker")
                } else {
                    @Suppress("DEPRECATION")
                    wm.freezeDisplayRotation(displayId, quarterTurns)
                }
                return true
            } catch (t: Throwable) {
                // 真正的失敗，不退回 shell——那會把可回報的錯誤變成靜默成功。
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
     * consumer 目前該用的影格尺寸（見 CONTEXT.md「Surface 空間 / 邏輯空間」，ADR-0017）。
     *
     * - 自己建立的虛擬顯示：distributor 已經把 v 轉正，這裡回的是轉正後的自然尺寸——
     *   建立尺寸依該 VD**目前**的 rotation 決定要不要互換長寬，rotation 跟建立尺寸同一次
     *   呼叫、同進程讀取，沒有時間差。呼叫端若在腳本執行期間再問一次，拿到的會是新值；
     *   但既有 consumer（AImageReader、TextureView）都只在啟動當下讀一次，不會跟著重開，
     *   這是已知限制，見 ADR-0017 的 Consequences。
     * - 實體螢幕：只能由邏輯尺寸與 rotation 推得，兩者同進程讀取，沒有時間差。
     * - 其他 id：回 [0, 0]，讓呼叫端當場失敗，好過帶著可能錯的尺寸跑完整個腳本。
     */
    override fun getDisplaySurfaceSize(displayId: Int): IntArray {
        val actualId = mirrorDisplayMap[displayId] ?: displayId
        vdStore[actualId]?.let { managed ->
            val rotation = plainDisplayManager?.getDisplay(actualId)?.rotation ?: 0
            return if (rotation and 1 != 0) {
                intArrayOf(managed.surfaceHeight, managed.surfaceWidth)
            } else {
                intArrayOf(managed.surfaceWidth, managed.surfaceHeight)
            }
        }

        val dm = context.getSystemService(DisplayManager::class.java)
        val display = dm?.getDisplay(displayId) ?: run {
            Timber.w("getDisplaySurfaceSize($displayId): display not found")
            return intArrayOf(0, 0)
        }
        val outSize = android.graphics.Point()
        @Suppress("DEPRECATION") // TODO: check if this method will still work in future Android versions.
        display.getRealSize(outSize)
        val (width, height) = DisplayGeometry.surfaceSize(outSize.x, outSize.y, display.rotation)
        return intArrayOf(width, height)
    }

    override fun getDisplayInfo(displayId: Int): MoonClickerDisplayInfo? {
        val dm = context.getSystemService(DisplayManager::class.java)
        val display = dm?.getDisplay(displayId) ?: return null
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        display.getRealMetrics(metrics)
        val isPhysical = !vdStore.containsKey(displayId)
        val isMirrorActive = isDisplayMirrorActive(displayId)
        return MoonClickerDisplayInfo().apply {
            this.displayId = displayId
            this.name = display.name ?: "Display $displayId"
            this.width = metrics.widthPixels
            this.height = metrics.heightPixels
            this.densityDpi = metrics.densityDpi
            this.isPhysical = isPhysical
            this.isMirrorActive = isMirrorActive
        }
    }

    override fun getDisplayInfos(): Array<MoonClickerDisplayInfo> {
        val dm = context.getSystemService(DisplayManager::class.java)
        val allDisplays = dm?.displays ?: emptyArray()
        val internalMirrorVdIds = mirrorDisplayMap.values.toSet()
        return allDisplays
            .filter { it.displayId !in internalMirrorVdIds }
            .mapNotNull { getDisplayInfo(it.displayId) }
            .toTypedArray()
    }

    override fun debug(input: String?): String = "MoonClickerService Active"

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
