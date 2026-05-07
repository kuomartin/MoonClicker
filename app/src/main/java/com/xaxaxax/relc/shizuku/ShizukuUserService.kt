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
            .version(BuildConfig.VERSION_CODE)

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

        if (Shizuku.peekUserService(args, conn) == -1) {
            Shizuku.bindUserService(args, conn)
        }

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