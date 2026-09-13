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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
import timber.log.Timber
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** 放棄等待 bind 的時限，逾時後狀態退回 [ShizukuConnectionStatus.DISCONNECTED] 讓使用者手動重試。 */
private val BIND_TIMEOUT = 10.seconds

/**
 * Shizuku 的可用程度：一條階梯，後面的必然蘊含前面的。
 *
 * 合併 available 與 hasPermission 兩個布林，是因為它們從來就不獨立——binder 不在時不可能有授權。
 * 拆成兩個布林，「binder 不在卻已授權」這種不存在的狀態就會變成型別允許的。
 */
private enum class ShizukuAccess { NOT_AVAILABLE, NEED_PERMISSION, GRANTED }

class ShizukuManager(private val context: Context) {
    private val myUid: Int get() = Process.myUid()

    /** 與 App 生命週期等長，不需要取消。 */
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _access = MutableStateFlow(ShizukuAccess.NOT_AVAILABLE)

    private val _serviceFlow = MutableStateFlow<IRelcV2Service?>(null)
    val serviceFlow = _serviceFlow.asStateFlow()

    val service: IRelcV2Service?
        get() = _serviceFlow.value

    /** bind 已送出但服務還沒接上，也就是 UI 的「連線中」。 */
    private val _isBinding = MutableStateFlow(false)

    /** 唯一對外的 Shizuku 狀態，Displays/Scripts/Settings 共用同一份判斷。 */
    val statusFlow: StateFlow<ShizukuConnectionStatus> = combine(
        _access, serviceFlow, _isBinding
    ) { access, service, binding ->
        when {
            access == ShizukuAccess.NOT_AVAILABLE -> ShizukuConnectionStatus.NOT_AVAILABLE
            access == ShizukuAccess.NEED_PERMISSION -> ShizukuConnectionStatus.NEED_PERMISSION
            service != null -> ShizukuConnectionStatus.CONNECTED
            binding -> ShizukuConnectionStatus.CONNECTING
            else -> ShizukuConnectionStatus.DISCONNECTED
        }
    }.stateIn(
        scope = scope,
        started = SharingStarted.Eagerly, // App 啟動即監聽
        initialValue = ShizukuConnectionStatus.NOT_AVAILABLE
    )

    val status: ShizukuConnectionStatus
        get() = statusFlow.value

    // -------------------------------------------------------------
    // 單例 UserService: IRelcV2Service
    // -------------------------------------------------------------
    private val serviceArgs = Shizuku.UserServiceArgs(
        ComponentName(BuildConfig.APPLICATION_ID, RelcV2Service::class.java.name)
    )
        .processNameSuffix("RelcV2Service")
        .debuggable(BuildConfig.DEBUG)
        .version(BuildConfig.VERSION_CODE)

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            _serviceFlow.value = if (binder.isBinderAlive) {
                IRelcV2Service.Stub.asInterface(binder)
            } else {
                null
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            Timber.d("RelcV2Service disconnected")
            _serviceFlow.value = null
        }
    }

    private var bindJob: Job? = null

    init {
        // Sticky：ShizukuProvider 在 Application attach 階段就送出 binder，很可能早於本物件被建立，
        // 非 sticky 版不會補發。
        Shizuku.addBinderReceivedListenerSticky { refreshAccess() }
        Shizuku.addBinderDeadListener {
            _access.value = ShizukuAccess.NOT_AVAILABLE
            _serviceFlow.value = null
        }
        Shizuku.addRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == myUid) {
                _access.value = if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    ShizukuAccess.GRANTED
                } else {
                    ShizukuAccess.NEED_PERMISSION
                }
            }
        }

        // 條件一齊就自己連上，使用者不必在每個畫面各按一次。
        //
        // 只在「授權或服務狀態真的變了」時試一次：bind 逾時不改變這兩者，所以連不上不會變成無止境的
        // 背景重試——連不上通常是環境問題，交給狀態列的按鈕讓使用者決定何時重試。
        scope.launch {
            combine(_access, serviceFlow) { access, service -> access to service }
                .distinctUntilChanged()
                .collect { (access, service) ->
                    if (access == ShizukuAccess.GRANTED && service == null) bindUserService()
                }
        }
    }

    /**
     * 不彈窗地把 Shizuku 目前的可用程度同步進 [statusFlow]。
     *
     * [Shizuku.checkSelfPermission] 在 binder 未送達時會丟 IllegalStateException，所以先 ping 再問。
     */
    fun refreshAccess() {
        _access.value = when {
            !Shizuku.pingBinder() -> ShizukuAccess.NOT_AVAILABLE

            runCatching { Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED }
                .getOrDefault(false) -> ShizukuAccess.GRANTED

            else -> ShizukuAccess.NEED_PERMISSION
        }
    }

    /** 狀態列按鈕的統一入口：未授權先要授權，已授權就（重）連線。 */
    fun requestPermissionOrConnect() {
        when (_access.value) {
            ShizukuAccess.NOT_AVAILABLE -> Timber.w("Shizuku binder is not available")
            ShizukuAccess.NEED_PERMISSION -> requestPermission()
            ShizukuAccess.GRANTED -> bindUserService()
        }
    }

    /**
     * 連上 UserService。等到服務真的接上才離開「連線中」，逾時就放棄，
     * 讓狀態退回 DISCONNECTED 而不是永遠卡在 CONNECTING。
     */
    fun bindUserService() {
        if (bindJob?.isActive == true || _serviceFlow.value != null) return
        if (_access.value != ShizukuAccess.GRANTED) {
            Timber.w("Shizuku permission not available, skip binding")
            return
        }

        bindJob = scope.launch {
            _isBinding.value = true
            try {
                Shizuku.bindUserService(serviceArgs, serviceConnection)
                withTimeoutOrNull(BIND_TIMEOUT) { serviceFlow.filterNotNull().first() }
                    ?: Timber.w("Timed out waiting for RelcV2Service to connect")
            } catch (e: Exception) {
                Timber.e(e, "Failed to bind UserService")
                _serviceFlow.value = null
            } finally {
                _isBinding.value = false
            }
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

    fun requestPermission(): Unit = when (_access.value) {
        ShizukuAccess.NOT_AVAILABLE -> Timber.w("Shizuku binder is not available")
        ShizukuAccess.NEED_PERMISSION -> Shizuku.requestPermission(myUid)
        ShizukuAccess.GRANTED -> Timber.d("Already has permission")
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

/** 宣告順序即階梯順序，[isAuthorized] 靠它比較。 */
enum class ShizukuConnectionStatus {
    NOT_AVAILABLE,
    NEED_PERMISSION,
    DISCONNECTED,
    CONNECTING,
    CONNECTED;

    /** 已授權，不論 UserService 連上沒。 */
    val isAuthorized: Boolean get() = this >= DISCONNECTED

    val isConnected: Boolean get() = this == CONNECTED
}
