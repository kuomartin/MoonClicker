package com.xaxaxax.relc

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.xaxaxax.relc.shizuku.ShizukuUserService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertFalse
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import rikka.shizuku.Shizuku
import kotlin.time.Duration.Companion.milliseconds

/**
 * Instrumented tests for [RelcShizukuService] — runs on device / emulator (`connectedAndroidTest`).
 *
 * - Smoke tests (invalid package, API gate) always run.
 * - Shizuku integration test: if binder is up but permission missing, calls [Shizuku.requestPermission]
 *   and waits for [Shizuku.OnRequestPermissionResultListener] after you tap **Allow** (device unlocked).
 */
@RunWith(AndroidJUnit4::class)
class RelcShizukuServiceInstrumentedTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun service(): RelcShizukuService = RelcShizukuService(context)

    /**
     * 先註冊 [Shizuku.addRequestPermissionResultListener]，再 [Shizuku.requestPermission]；
     * 結果只從 callback 來，不用輪詢 [Shizuku.checkSelfPermission]。
     */
    private suspend fun awaitShizukuPermissionGranted(
        requestCode: Int = 0x52454C43, // "RELC"
        waitAtMostMs: Long = 120_000L,
    ) {
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) return

        val granted = withTimeout(waitAtMostMs.milliseconds) {
            val deferred = CompletableDeferred<Boolean>()
            val listener = Shizuku.OnRequestPermissionResultListener { req, grantResult ->
                if (req == requestCode) {
                    deferred.complete(grantResult == PackageManager.PERMISSION_GRANTED)
                }
            }
            Shizuku.addRequestPermissionResultListener(listener)
            try {
                Shizuku.requestPermission(requestCode)
                deferred.await()
            } finally {
                Shizuku.removeRequestPermissionResultListener(listener)
            }
        }

        check(granted) {
            "Shizuku permission denied (listener got PERMISSION_DENIED) — tap Allow or fix in Shizuku app"
        }
    }

    @Test
    fun grantRuntimePermission_unknownPackage_returnsFalse_onApi33Plus() {
        assumeTrue(Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        assertFalse(
            service().grantRuntimePermission(
                "com.nonexistent.package.relc_test",
                Manifest.permission.POST_NOTIFICATIONS,
            ),
        )
    }

    @Test
    @SdkSuppress(maxSdkVersion = Build.VERSION_CODES.S_V2)
    fun grantRuntimePermission_beforeTiramisu_alwaysFalse() {
        assertFalse(
            service().grantRuntimePermission(
                context.packageName,
                Manifest.permission.POST_NOTIFICATIONS,
            ),
        )
    }

    @Test
    fun setOverlayAllowed_unknownPackage_returnsFalse() {
        assertFalse(service().setOverlayAllowed("com.nonexistent.package.relc_test"))
    }

    /**
     * Requires: Shizuku 已啟動（binder 可 ping）。
     * 若尚未授權：會跳出 Shizuku 授權對話框，請在逾時前按允許（螢幕要開著、測試跑在前景）。
     */
    @Test
    fun viaShizuku_connectAndCallService_methodsReturnWithoutThrowing() = runBlocking {
        assumeTrue(
            "Start Shizuku on device first (Shizuku.pingBinder() is false)",
            Shizuku.pingBinder(),
        )
        awaitShizukuPermissionGranted()

        val handle = ShizukuUserService.connect<IRelcShizukuService>(
            serviceClass = RelcShizukuService::class,
            asInterface = IRelcShizukuService.Stub::asInterface,
        )
        try {
            val pkg = context.packageName
            handle.service.setOverlayAllowed(pkg)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                handle.service.grantRuntimePermission(pkg, Manifest.permission.POST_NOTIFICATIONS)
            }
        } finally {
            handle.unbind()
        }
    }
}
