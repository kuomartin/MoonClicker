package com.xaxaxax.relc.shizuku

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import android.os.IInterface
import com.xaxaxax.relc.BuildConfig
import kotlin.reflect.KClass
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku
import kotlin.time.Duration.Companion.milliseconds

object ShizukuUserService {

    data class Handle<I : IInterface>(
        val service: I,
        val unbind: () -> Unit
    )

    suspend inline fun <reified I : IInterface> connect(
        serviceClass: KClass<*>,
        processNameSuffix: String = I::class.java.simpleName,
        tag: String = processNameSuffix,
        timeoutMs: Long = 5_000,
        /**
         * 用來讓 Shizuku 判斷是否要 kill 舊 UserService 並重啟。
         * 預設使用 [BuildConfig.VERSION_CODE]，但建議傳入
         * `context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime.toInt()`，
         * 這樣每次重新安裝（即使 versionCode 沒有改變）都會讓 Shizuku 重啟 service 進程，
         * 確保新程式碼生效。
         */
        version: Int = BuildConfig.VERSION_CODE,
        crossinline asInterface: (IBinder) -> I?
    ): Handle<I> {
        require(Shizuku.pingBinder()) { "Shizuku not running" }
        require(Shizuku.checkSelfPermission() == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            "Shizuku permission denied"
        }

        val className = serviceClass.java.name
        val args = Shizuku.UserServiceArgs(
            ComponentName(BuildConfig.APPLICATION_ID, className)
        )
            .processNameSuffix(processNameSuffix)
            .tag(tag)
            .debuggable(BuildConfig.DEBUG)
            .version(version)

        val deferred = CompletableDeferred<I>()

        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                if (!binder.isBinderAlive) {
                    deferred.completeExceptionally(IllegalStateException("Dead binder: $className"))
                    return
                }
                val svc = asInterface(binder)
                    ?: run {
                        deferred.completeExceptionally(IllegalStateException("asInterface null: $className"))
                        return
                    }
                deferred.complete(svc)
            }

            override fun onServiceDisconnected(name: ComponentName) {
                if (!deferred.isCompleted) {
                    deferred.completeExceptionally(IllegalStateException("Disconnected before ready: $className"))
                }
            }
        }

        // 一律呼叫 bindUserService：
        // - 若 service 尚未運行 → 啟動並連線
        // - 若 service 已運行且版本相同 → 直接連線（不重啟）
        // - 若 service 已運行但版本不同 → Shizuku 會 kill 舊進程並重啟
        // 不使用 peekUserService 做條件判斷，避免重新安裝後仍連到舊版 service 進程。
        Shizuku.bindUserService(args, conn)

        val service = try {
            withTimeout(timeoutMs.milliseconds) { deferred.await() }
        } catch (e: Throwable) {
            runCatching { Shizuku.unbindUserService(args, conn, true) }
            throw e
        }

        return Handle(
            service = service,
            unbind = { runCatching { Shizuku.unbindUserService(args, conn, true) } }
        )
    }
}
