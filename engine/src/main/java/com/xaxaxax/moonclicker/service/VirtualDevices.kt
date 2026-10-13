package com.xaxaxax.moonclicker.service

import android.companion.ICompanionDeviceManager
import android.companion.virtual.IVirtualDeviceManager
import android.companion.virtual.VirtualDeviceManagerHidden
import android.companion.virtual.VirtualDeviceParams
import android.content.AttributionSource
import android.content.Context
import android.content.ContextWrapper
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.os.Process
import android.os.ServiceManager
import android.view.Surface
import androidx.annotation.RequiresApi
import timber.log.Timber

/**
 * 經 VirtualDevice 建立虛擬顯示（issue #124），讓 VD 有自己的 display group 與鎖定狀態。
 *
 * 建立 VirtualDevice 要一個 companion association。shell 沒有配對流程，所以用
 * `cmd companiondevice associate` 替 shell 套件建一個假 MAC 的 association（[MAC_PREFIX]），
 * profile 是 APP_STREAMING：API 35 起 VirtualDeviceManagerService 只接受這類 profile 的
 * association，13/14 不檢查、也會忽略這個參數。association 由 App 的實驗性開關決定存廢，
 * 不隨 VD 增減，見 docs/virtual-display-pitfalls.md。
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
internal class VirtualDevices(context: Context, private val callerPackage: String) {

    /** 建好的 VD 連同關掉它背後 VirtualDevice 的方法；關掉 device 會一併拆掉它的顯示器。 */
    class Created(val display: VirtualDisplay, val close: () -> Unit)

    private val shellContext = ShellContext(context, callerPackage)
    private val userId = Process.myUid() / PER_USER_RANGE

    private val manager: VirtualDeviceManagerHidden? by lazy {
        ServiceManager.getService(VIRTUAL_DEVICE_SERVICE)
            ?.let { VirtualDeviceManagerHidden(IVirtualDeviceManager.Stub.asInterface(it), shellContext) }
    }

    private val companionDeviceManager: ICompanionDeviceManager? by lazy {
        ServiceManager.getService(Context.COMPANION_DEVICE_SERVICE)
            ?.let { ICompanionDeviceManager.Stub.asInterface(it) }
    }

    /** API 33 一個 association 同時只能有一個 VirtualDevice，記下哪些正被佔用。 */
    private val busyAssociations = mutableSetOf<Int>()

    /** 至少有一個 association；沒有就建一個。 */
    @Synchronized
    fun ensureAssociation(): Boolean = associations().isNotEmpty() || associate(0) != null

    /** 移除所有 [MAC_PREFIX] 的 association。呼叫端要先銷毀經這裡建立的 VD。 */
    @Synchronized
    fun removeAssociations() {
        associations().forEach { (_, mac) -> runCmd("disassociate", userId.toString(), callerPackage, mac) }
        busyAssociations.clear()
    }

    /**
     * 建一個 VirtualDevice 與它的 VD，失敗回 null。[flags] 被拒（SecurityException）時丟出，
     * 讓呼叫端換一組旗標重試。
     */
    @Synchronized
    fun create(width: Int, height: Int, densityDpi: Int, surface: Surface, flags: Int): Created? {
        val vdm = manager ?: return null.also { Timber.w("virtual device service is not available") }
        val associationId = pickAssociation() ?: return null
        val params = VirtualDeviceParams.Builder()
            .setLockState(VirtualDeviceParams.LOCK_STATE_ALWAYS_UNLOCKED)
            .build()
        val device = vdm.createVirtualDevice(associationId, params)
        val display = try {
            device.createVirtualDisplay(width, height, densityDpi, surface, flags, null, null)
        } catch (t: Throwable) {
            device.close()
            throw t
        }
        if (display == null) {
            device.close()
            return null
        }
        busyAssociations += associationId
        return Created(display) {
            device.close()
            synchronized(this) { busyAssociations -= associationId }
        }
    }

    /** API 34+ 共用第一個 association；API 33 找沒被佔用的，都被佔用就多建一個。 */
    private fun pickAssociation(): Int? {
        val existing = associations()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            return existing.keys.firstOrNull() ?: associate(0)
        }
        existing.keys.firstOrNull { it !in busyAssociations }?.let { return it }
        val usedIndexes = existing.values.mapNotNull { it.removePrefix(MAC_PREFIX).toIntOrNull(16) }.toSet()
        val index = (0..0xff).firstOrNull { it !in usedIndexes } ?: return null
        return associate(index)
    }

    /** association id → MAC，只列 [MAC_PREFIX] 開頭的。 */
    private fun associations(): Map<Int, String> = try {
        companionDeviceManager?.getAssociations(callerPackage, userId).orEmpty()
            .mapNotNull { info ->
                val mac = info.deviceMacAddress?.toString() ?: return@mapNotNull null
                if (mac.startsWith(MAC_PREFIX)) info.id to mac else null
            }
            .toMap()
    } catch (t: Throwable) {
        Timber.w(t, "could not list companion associations")
        emptyMap()
    }

    private fun associate(index: Int): Int? {
        val mac = MAC_PREFIX + "%02x".format(index)
        runCmd("associate", userId.toString(), callerPackage, mac, APP_STREAMING_PROFILE)
        return associations().entries.firstOrNull { it.value == mac }?.key
            ?: null.also { Timber.w("association for $mac was not created") }
    }

    private fun runCmd(vararg args: String) = try {
        val process = ProcessBuilder("cmd", "companiondevice", *args).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
        val exit = process.waitFor()
        Timber.d("cmd companiondevice %s -> exit=%d %s", args.joinToString(" "), exit, output)
    } catch (t: Throwable) {
        Timber.e(t, "cmd companiondevice %s failed", args.joinToString(" "))
    }

    /**
     * 向 VirtualDeviceManager 宣稱 [callerPackage] 的 Context。API 34 起它改用
     * `getApplicationContext()` 取套件名，API 35 起改傳 `getAttributionSource()`，都得對上 calling uid。
     */
    private class ShellContext(base: Context, private val packageName: String) : ContextWrapper(base) {
        override fun getPackageName(): String = packageName
        override fun getOpPackageName(): String = packageName
        override fun getApplicationContext(): Context = this
        override fun getAttributionSource(): AttributionSource =
            AttributionSource.Builder(Process.myUid()).setPackageName(packageName).build()
    }

    private companion object {
        /** `Context.VIRTUAL_DEVICE_SERVICE`，API 34 才公開。 */
        const val VIRTUAL_DEVICE_SERVICE = "virtualdevice"

        /** `AssociationRequest.DEVICE_PROFILE_APP_STREAMING`。 */
        const val APP_STREAMING_PROFILE = "android.app.role.COMPANION_DEVICE_APP_STREAMING"

        /** 本地管理位元設起的假 MAC，末段是編號；"6d:63" 是 "mc"。 */
        const val MAC_PREFIX = "02:6d:63:00:00:"

        /** `UserHandle.PER_USER_RANGE`，@hide。 */
        const val PER_USER_RANGE = 100000
    }
}
