package com.xaxaxax.relc

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.xaxaxax.relc.shizuku.ShizukuManager
import com.xaxaxax.relc.shizuku.UserServiceLifecycle
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 「什麼時候該存在一個 UserService」這條政策的家。
 *
 * 它刻意不住在 [ShizukuManager] 裡：那裡是連線機制，不該決定特權行程何時該被創造出來。
 * 分開之後，沒有 UI 的進入點——通知列的停止鍵、Shizuku 透過 ShizukuProvider 叫醒我們的
 * process——都不可能再默默拉起一個特權行程。
 *
 * 做成 ViewModel 是為了拿到「一次 UI session 一次」這個生命週期：跨轉螢幕存活、回前景不重建，
 * 而 process 被回收後從工作列返回會是全新的一個。三種情境剛好都是我們要的答案。
 */
@HiltViewModel
class UserServiceAutoStarter @Inject constructor(
    private val shizukuManager: ShizukuManager,
    private val userServiceLifecycle: UserServiceLifecycle,
) : ViewModel() {

    private var triggered = false

    /**
     * App 打開了。轉螢幕會讓 Activity 重建、[onAppOpened] 再被呼叫一次，但本物件跨重建存活，
     * 所以這個旗標擋得住；回前景則連 Activity 都沒重建，根本不會走到這裡。
     *
     * `autoStartEnabled` 只在這裡讀一次：這次呼叫之後才切換設定不會回溯生效，要等下次冷啟
     * （新的 [UserServiceAutoStarter] 實例）才會看到新值。
     */
    fun onAppOpened() {
        if (triggered) return
        triggered = true

        if (!userServiceLifecycle.snapshot.value.autoStartEnabled) return

        viewModelScope.launch {
            // 冷啟時 Shizuku 的 binder 是非同步送達的，onCreate 很可能跑在它之前。
            // 唯一的一次觸發不能就這樣用掉，所以等到授權就緒再啟動。
            shizukuManager.statusFlow.first { it.isAuthorized }
            shizukuManager.startUserService()
        }
    }
}
