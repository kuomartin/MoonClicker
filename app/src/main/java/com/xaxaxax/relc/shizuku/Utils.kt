package com.xaxaxax.relc.shizuku

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.IInterface
import com.xaxaxax.relc.BuildConfig
import com.xaxaxax.relc.RelcApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku
import timber.log.Timber
import kotlin.reflect.KClass
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

val isShizukuAvailable: Flow<Boolean> = callbackFlow {
    val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        trySend(true)
    }
    val binderDeadListener = Shizuku.OnBinderDeadListener {
        trySend(false)
    }

    Shizuku.addBinderReceivedListener(binderReceivedListener)
    Shizuku.addBinderDeadListener(binderDeadListener)

    trySend(Shizuku.pingBinder())

    awaitClose {
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
    }
}

val hasShizukuPermission: Flow<Boolean> = callbackFlow {
    val uid = android.os.Process.myUid()
    val listener = Shizuku.OnRequestPermissionResultListener { reqCode, grantResult ->
        if (reqCode == uid) {
            trySend(grantResult == PackageManager.PERMISSION_GRANTED)
        }
    }
    Shizuku.addRequestPermissionResultListener(listener)

    val initialPermission =
        Shizuku.pingBinder() && (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED)

    trySend(initialPermission)

    awaitClose {
        Shizuku.removeRequestPermissionResultListener(listener)
    }
}

val ready: Flow<Boolean> =
    combine(isShizukuAvailable, hasShizukuPermission) { isShizukuAvailable, hasPermission ->
        isShizukuAvailable && hasPermission
    }.distinctUntilChanged()

fun requestShizukuPermission() {
    val uid = android.os.Process.myUid()
    if (Shizuku.pingBinder()) {
        Shizuku.requestPermission(uid)
    } else {
        Timber.e("Shizuku binder not available")
    }
}

fun refreshShizukuPermission() {
    // refresh flow only if permission granted
    if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED)
        requestShizukuPermission()
}

sealed interface UserService<T : IInterface> {
    data class Alive<T : IInterface>(val service: T) : UserService<T>
    class Dead<T : IInterface> : UserService<T>
    class Null<T : IInterface> : UserService<T>
    class Connecting<T : IInterface> : UserService<T>

    companion object {
        /**
         * 建立一個自動管理生命週期的 StateFlow
         */
        inline fun <reified I : IInterface> create(
            scope: CoroutineScope,
            serviceClass: KClass<*>,
            crossinline asInterface: (IBinder) -> I?,
        ): StateFlow<UserService<I>> {
            val context = RelcApplication.instance
            val version = if (BuildConfig.DEBUG) {
                try {
                    context.packageManager.getPackageInfo(
                        context.packageName,
                        0
                    ).lastUpdateTime.toInt()
                } catch (e: Exception) {
                    BuildConfig.VERSION_CODE
                }
            } else {
                BuildConfig.VERSION_CODE
            }

            val componentName = ComponentName(BuildConfig.APPLICATION_ID, serviceClass.java.name)
            val args = Shizuku.UserServiceArgs(componentName)
                .processNameSuffix(I::class.java.simpleName)
                .debuggable(BuildConfig.DEBUG)
                .version(version)

            return callbackFlow {
                val conn = object : ServiceConnection {
                    override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                        val svc = if (binder.isBinderAlive) asInterface(binder) else null
                        trySend(if (svc != null) Alive(svc) else Null())
                    }

                    override fun onServiceDisconnected(name: ComponentName) {
                        Timber.d("$componentName Dead")
                        trySend(Dead())
                    }
                }

                Shizuku.bindUserService(args, conn)
                awaitClose { Shizuku.unbindUserService(args, conn, false) }
            }.stateIn(
                scope = scope,
                started = SharingStarted.WhileSubscribed(5000),
                initialValue = Connecting()
            )
        }
    }
}

/**
 * 擴充函數：讓呼叫端超級簡單
 * 範例：myServiceFlow.runWhenAlive { it.doSomething() }
 */
suspend fun <T : IInterface, V> StateFlow<UserService<T>>.runWhenAlive(
    timeout: Duration = 5.seconds,
    block: suspend (T) -> V
): V? {
    runCatching {
        withTimeout(timeout) {
            filterIsInstance<UserService.Alive<T>>().first()
        }
    }
        .onSuccess {
            return block(it.service)
        }
        .onFailure {
            Timber.e(it, "Shizuku 服務呼叫失敗 (可能是服務已停止)")
        }
    return null
}
