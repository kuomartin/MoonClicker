package com.xaxaxax.relc.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Process
import androidx.core.net.toUri
import com.xaxaxax.relc.BuildConfig
import com.xaxaxax.relc.IRelcV2Service
import com.xaxaxax.relc.RelcV2Service
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withTimeout
import rikka.shizuku.Shizuku
import timber.log.Timber
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class ShizukuManager(private val context: Context) {
    private val myUid: Int get() = Process.myUid()

    /** 監聽 Shizuku Binder 連線狀態 */
    val isAvailableFlow: StateFlow<Boolean> = callbackFlow {
        val receivedListener = Shizuku.OnBinderReceivedListener { trySend(true) }
        val deadListener = Shizuku.OnBinderDeadListener { trySend(false) }

        Shizuku.addBinderReceivedListener(receivedListener)
        Shizuku.addBinderDeadListener(deadListener)

        awaitClose {
            Shizuku.removeBinderReceivedListener(receivedListener)
            Shizuku.removeBinderDeadListener(deadListener)
        }
    }.stateIn(
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
        started = SharingStarted.Eagerly, // App 啟動即監聽
        initialValue = Shizuku.pingBinder()
    )
    val isAvailable: Boolean
        get() = isAvailableFlow.value

    /** 監聽 Shizuku 授權狀態 */
    val hasPermissionFlow: StateFlow<Boolean> = callbackFlow {
        val listener = Shizuku.OnRequestPermissionResultListener { reqCode, grantResult ->
            if (reqCode == myUid) {
                trySend(grantResult == PackageManager.PERMISSION_GRANTED)
            }
        }
        Shizuku.addRequestPermissionResultListener(listener)
        awaitClose { Shizuku.removeRequestPermissionResultListener(listener) }
    }.stateIn(
        scope = CoroutineScope(Dispatchers.Default + SupervisorJob()),
        started = SharingStarted.Eagerly, // App 啟動即監聽
        initialValue = false
    )
    val hasPermission: Boolean
        get() = hasPermissionFlow.value

    val isReadyFlow = combine(isAvailableFlow,hasPermissionFlow){ a, p -> a && p }


    // -------------------------------------------------------------
    // 單例 UserService: IRelcV2Service
    // -------------------------------------------------------------
    private val serviceArgs = Shizuku.UserServiceArgs(
        ComponentName(BuildConfig.APPLICATION_ID, RelcV2Service::class.java.name)
    )
        .processNameSuffix("RelcV2Service")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)

    // 內部使用 MutableStateFlow 保存實例，對外只暴露唯讀 StateFlow
    private val _serviceFlow = MutableStateFlow<IRelcV2Service?>(null)
    val serviceFlow = _serviceFlow.asStateFlow()

    val service: IRelcV2Service?
        get() = _serviceFlow.value

    // 單例 ServiceConnection
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            val svc = if (binder.isBinderAlive) IRelcV2Service.Stub.asInterface(binder) else null
            _serviceFlow.value = svc
        }

        override fun onServiceDisconnected(name: ComponentName) {
            Timber.d("RelcV2Service disconnected")
            _serviceFlow.value = null
        }
    }

    private var isBound = false

    // 手動呼叫連線
    fun bindUserService() {
        when {
            isBound -> Unit
            !isAvailable->
                Timber.w("Shizuku binder not available, skip binding")
            !hasPermission->
                Timber.w("Shizuku permission not available, skip binding")
            else->
                try {
                    Shizuku.bindUserService(serviceArgs, serviceConnection)
                    isBound = true
                } catch (e: Exception) {
                    Timber.e(e, "Failed to bind UserService")
                    _serviceFlow.value = null
                }
        }
    }

    // 手動中斷連線
    fun unbindUserService() {
        if (!isBound) return
        try {
            Shizuku.unbindUserService(serviceArgs, serviceConnection, true)
        } catch (e: Exception) {
            Timber.e(e, "Failed to unbind UserService")
        } finally {
            isBound = false
            _serviceFlow.value = null
        }
    }
    /**
     * 確保等待到 Service 處於連線狀態後執行，若逾時則回傳 Failure
     */
    suspend inline fun <R> withService(
        timeout: Duration = 5.seconds,
        block: suspend (IRelcV2Service) -> R
    ): Result<R> = runCatching {
        val svc = withTimeout(timeout) {
            serviceFlow.filterNotNull().first()
        }
        block(svc)
    }.onFailure {
        Timber.e(it, "呼叫 RelcV2Service 逾時或服務不可用")
    }

    // -------------------------------------------------------------
    // Actions
    // -------------------------------------------------------------

    fun checkPermission(): Boolean =
        isAvailable && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED

    fun requestPermission(): Unit = when {
        !isAvailable -> Timber.w("Shizuku binder is not available")
        !hasPermission -> Shizuku.requestPermission(myUid)
        else -> Timber.d("Already has permission")
    }

    fun getOpenShizukuIntent(): Intent {
        val intent =
            context.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
                ?: Intent(
                    Intent.ACTION_VIEW,
                    "market://details?id=moe.shizuku.privileged.api".toUri()
                ).takeIf { it.resolveActivity(context.packageManager) != null }
                ?: Intent(
                    Intent.ACTION_VIEW,
                    "https://shizuku.rikka.app/download/".toUri()
                )
        return intent.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
    }
}