package com.xaxaxax.moonclicker

import android.os.Build
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import androidx.annotation.Keep
import com.xaxaxax.moonclicker.service.ActivityLauncher
import com.xaxaxax.moonclicker.service.DisplayMirroring
import com.xaxaxax.moonclicker.service.DisplayQuery
import com.xaxaxax.moonclicker.service.GlesDistributor
import com.xaxaxax.moonclicker.service.InputInjector
import com.xaxaxax.moonclicker.service.LauncherAppsCache
import com.xaxaxax.moonclicker.service.PermissionGrants
import com.xaxaxax.moonclicker.service.PlatformHandles
import com.xaxaxax.moonclicker.service.VirtualDisplayLifecycle
import org.lsposed.hiddenapibypass.LSPass
import timber.log.Timber
import kotlin.system.exitProcess

/**
 * Shizuku 用 `ComponentName(..., MoonClickerService::class.java.name)` 反射綁定這個類別，
 * 六個 native distributor 方法也是 JNI name-based 綁定（`Java_com_xaxaxax_moonclicker_
 * MoonClickerService_nativeXxx`）——類別名稱、package、建構子形狀、native fun 的宣告位置
 * 都不能動。除此之外這裡只是一個薄 adapter：把 `IMoonClickerService.Stub` 的每個方法路由給
 * `service/` 底下對應的深模組，實際邏輯都在那些模組裡。
 */
@Keep
class MoonClickerService @JvmOverloads constructor(
    private val context: android.content.Context,
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

        /** 都不在 `Manifest.permission` 裡（signature|privileged，@hide）。 */
        const val ADD_TRUSTED_DISPLAY = "android.permission.ADD_TRUSTED_DISPLAY"
        const val ADD_ALWAYS_UNLOCKED_DISPLAY =
            "android.permission.ADD_ALWAYS_UNLOCKED_DISPLAY"
    }

    // 建構順序是這個類別唯一還留著的「順序敏感」：platformHandles 必須最先建好，
    // 其餘模組才能安全地把它當依賴傳下去（見 PlatformHandles 的說明）。
    private val platformHandles = PlatformHandles(context, callerPackage)
    private val glesDistributor = GlesDistributor(
        displayManager = platformHandles.displayManager,
        create = ::nativeCreateDistributor,
        getSurface = ::nativeGetDistributorSurface,
        addSurface = ::nativeAddSurface,
        removeSurface = ::nativeRemoveSurface,
        setRotation = ::nativeSetDistributorRotation,
        destroyNative = ::nativeDestroyDistributor,
    )
    private val virtualDisplayLifecycle = VirtualDisplayLifecycle(context, platformHandles, glesDistributor)
    private val displayMirroring = DisplayMirroring(virtualDisplayLifecycle, glesDistributor, platformHandles.displayManager)
    private val inputInjector = InputInjector(platformHandles.inputManager, virtualDisplayLifecycle)
    private val activityLauncher = ActivityLauncher(platformHandles.packageManager, callerPackage, virtualDisplayLifecycle)
    private val permissionGrants = PermissionGrants(
        platformHandles.packageManager,
        platformHandles.packageManagerHidden,
        platformHandles.appOpsManagerHidden,
    )
    private val displayQuery = DisplayQuery(platformHandles.displayManager, virtualDisplayLifecycle, displayMirroring)

    // launcherAppsCache 的建構本身有副作用（重掃套件、API 35+ 註冊套件變動回呼），得排在
    // LSPass exemption 生效之後，所以放進 init 區塊，不跟上面幾個模組一起在宣告時建構。
    private val launcherAppsCache: LauncherAppsCache

    init {
        Timber.plant(Timber.DebugTree())
        Timber.d("MoonClickerService V2 (Flattened) started")

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            LSPass.addHiddenApiExemptions(
                "Landroid/app/ActivityManager",        // ActivityLauncher
                "Landroid/app/ActivityOptions",         // ActivityLauncher
                "Landroid/app/ActivityTaskManager",     // ActivityLauncher
                "Landroid/app/AppOpsManager",            // PermissionGrants
                "Landroid/content/pm/PackageManager",    // LauncherAppsCache, PermissionGrants
                "Landroid/hardware/input/InputManager",  // InputInjector
                "Landroid/os/IPowerManager",             // DisplayGroupWakeLocks
                "Landroid/os/ServiceManager",            // PlatformHandles
                "Landroid/view/MotionEvent",             // InputInjector
                "Landroid/view/WindowManagerGlobal",     // DisplayQuery
            )
        }

        launcherAppsCache = LauncherAppsCache(context, platformHandles.packageManager)
    }

    // ─── IMoonClickerService.Stub — 路由到對應模組 ───

    override fun setOverlayAllowed(packageName: String): Boolean =
        permissionGrants.setOverlayAllowed(packageName)

    override fun grantRuntimePermission(packageName: String, permissionName: String): Boolean =
        permissionGrants.grantRuntimePermission(packageName, permissionName)

    override fun createVirtualDisplay(name: String, width: Int, height: Int, densityDpi: Int, flags: Int): Int =
        virtualDisplayLifecycle.createVirtualDisplay(name, width, height, densityDpi, flags)

    override fun addVirtualDisplaySurface(displayId: Int, surface: Surface): Int {
        Timber.d("addVirtualDisplaySurface: id=$displayId surfaceValid=${surface.isValid}")
        // 有 surface 掛著就是有人在看（全螢幕預覽、串流、vision 腳本取影格），這段期間不讓它逾時。
        virtualDisplayLifecycle.holdDisplayGroupAwake(displayId)
        val actualId = displayMirroring.resolveDistributorId(displayId)
        val handle = glesDistributor.attachSurface(actualId, surface)
        if (handle < 0) {
            Timber.e("addVirtualDisplaySurface: distributor not found for id=$displayId (actualId=$actualId)")
            virtualDisplayLifecycle.releaseDisplayGroupAwake(displayId)
        }
        return handle
    }

    override fun removeVirtualDisplaySurface(displayId: Int, handle: Int): Boolean {
        Timber.d("removeVirtualDisplaySurface: id=$displayId handle=$handle")
        val actualId = displayMirroring.resolveDistributorId(displayId)
        val detached = glesDistributor.detachSurface(actualId, handle)
        if (detached) virtualDisplayLifecycle.releaseDisplayGroupAwake(displayId)
        return detached
    }

    override fun resizeVirtualDisplay(displayId: Int, width: Int, height: Int, densityDpi: Int): Boolean =
        virtualDisplayLifecycle.resizeVirtualDisplay(displayId, width, height, densityDpi)

    override fun destroyVirtualDisplay(displayId: Int): Boolean =
        virtualDisplayLifecycle.destroyVirtualDisplay(displayId)

    override fun sleepVirtualDisplay(displayId: Int): Boolean =
        virtualDisplayLifecycle.sleepVirtualDisplay(displayId)

    override fun getVirtualDisplays(): IntArray {
        val internalMirrorVdIds = displayMirroring.internalMirrorDisplayIds()
        return virtualDisplayLifecycle.allDisplayIds().filter { it !in internalMirrorVdIds }.sorted().toIntArray()
    }

    override fun acquireDisplayMirror(displayId: Int): Boolean =
        displayMirroring.acquireDisplayMirror(displayId)

    override fun releaseDisplayMirror(displayId: Int): Boolean =
        displayMirroring.releaseDisplayMirror(displayId)

    override fun isDisplayMirrorActive(displayId: Int): Boolean =
        displayMirroring.isDisplayMirrorActive(displayId)

    override fun launchInDisplay(packageName: String, displayId: Int): Boolean =
        activityLauncher.launchInDisplay(packageName, displayId)

    override fun getLauncherApps(): List<String> = launcherAppsCache.getLauncherApps()

    override fun refreshLauncherApps(): List<String> = launcherAppsCache.refreshLauncherApps()

    override fun multiTouchSwipe(pointerId: Int, displayId: Int, points: IntArray, duration: Long, keep: Boolean) =
        inputInjector.multiTouchSwipe(pointerId, displayId, points, duration, keep)

    override fun getPointers(displayId: Int): IntArray = inputInjector.getPointers(displayId)

    override fun injectMotionEvent(event: MotionEvent, displayId: Int): Boolean =
        inputInjector.injectMotionEvent(event, displayId)

    override fun injectKeyEvent(event: KeyEvent, displayId: Int): Boolean =
        inputInjector.injectKeyEvent(event, displayId)

    override fun setDisplayRotation(displayId: Int, rotation: Int): Boolean =
        displayQuery.setDisplayRotation(displayId, rotation)

    override fun getDisplaySize(displayId: Int): IntArray = displayQuery.getDisplaySize(displayId)

    override fun getDisplaySurfaceSize(displayId: Int): IntArray = displayQuery.getDisplaySurfaceSize(displayId)

    override fun getDisplayInfo(displayId: Int): MoonClickerDisplayInfo? = displayQuery.getDisplayInfo(displayId)

    override fun getDisplayInfos(): Array<MoonClickerDisplayInfo> = displayQuery.getDisplayInfos()

    override fun debug(input: String?): String = "MoonClickerService Active"

    override fun destroy() {
        virtualDisplayLifecycle.releaseAll()
        exitProcess(0)
    }

    // --- Native GLES Distributor ---
    // JNI 是 name-based 綁定，這幾個方法只能留在這個類別上（見檔案開頭說明）；
    // GlesDistributor 透過上面建構子傳入的函式參考來呼叫它們。
    private external fun nativeCreateDistributor(width: Int, height: Int): Long
    private external fun nativeGetDistributorSurface(ptr: Long): Surface?
    private external fun nativeAddSurface(ptr: Long, surface: Surface): Int
    private external fun nativeRemoveSurface(ptr: Long, handle: Int)
    private external fun nativeSetDistributorRotation(ptr: Long, rotation: Int)
    private external fun nativeDestroyDistributor(ptr: Long)
}
