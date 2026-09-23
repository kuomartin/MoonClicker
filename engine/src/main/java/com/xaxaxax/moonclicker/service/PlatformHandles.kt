package com.xaxaxax.moonclicker.service

import android.app.AppOpsManager
import android.app.AppOpsManagerHidden
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManagerHidden
import android.hardware.display.DisplayManager
import android.hardware.display.DisplayManagerHidden
import android.hardware.input.InputManager
import android.hardware.input.InputManagerHidden
import android.os.PowerManager
import android.os.PowerManagerHidden
import androidx.core.content.getSystemService
import dev.rikka.tools.refine.Refine

/**
 * 這個進程要用到的系統 handle，全部集中在這裡、由 [com.xaxaxax.moonclicker.MoonClickerService]
 * 的建構子第一個建好再往下傳給其餘模組。原本這批 `by lazy` property 散在 `MoonClickerService`
 * 上時，有踩過一次 Kotlin property initializer 依原始碼順序跑、init 區塊提早存取到 null
 * delegate 的坑——集中到一個獨立、最早建構的物件，讓這個順序問題不會在拆分後的模組裡重演。
 */
internal class PlatformHandles(
    context: Context,
    callerPackage: String,
) {
    /**
     * 向系統宣稱是 [callerPackage] 的假 Context，`DisplayManagerHidden`／`SurfaceControl`
     * 這類需要 opPackageName 的 hidden API 都得靠它才能跟真正的 calling uid 對上。
     */
    val fakeDisplayContext: Context = object : ContextWrapper(context) {
        override fun getPackageName(): String = callerPackage
        override fun getOpPackageName(): String = callerPackage
        override fun getApplicationContext(): Context = this
    }

    val packageManager by lazy { context.packageManager }
    val packageManagerHidden: PackageManagerHidden by lazy { Refine.unsafeCast(packageManager) }

    val inputManager: InputManagerHidden by lazy {
        context.getSystemService<InputManager>()
            ?.let { Refine.unsafeCast(it) }
            ?: throw IllegalStateException("Can not get InputManager")
    }

    val displayManager: DisplayManager by lazy {
        context.getSystemService()
            ?: throw IllegalStateException("Cannot get DisplayManager")
    }

    val displayManagerHidden: DisplayManagerHidden by lazy {
        DisplayManagerHidden(fakeDisplayContext)
    }

    val appOpsManagerHidden: AppOpsManagerHidden by lazy {
        context.getSystemService<AppOpsManager>()
            ?.let { Refine.unsafeCast(it) }
            ?: throw IllegalStateException("Cannot get AppOpsManager")
    }

    val powerManagerHidden: PowerManagerHidden by lazy {
        context.getSystemService<PowerManager>()
            ?.let { Refine.unsafeCast(it) }
            ?: throw IllegalStateException("Cannot get PowerManager")
    }
}
