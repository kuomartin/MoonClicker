package com.xaxaxax.relc

import com.xaxaxax.relc.shizuku.ShizukuConnectionStatus
import com.xaxaxax.relc.shizuku.ShizukuManager
import com.xaxaxax.relc.shizuku.UserServiceLifecycle
import com.xaxaxax.relc.shizuku.UserServiceLifecycleSnapshot
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UserServiceAutoStarterTest {

    private val statusFlow = MutableStateFlow(ShizukuConnectionStatus.NOT_AVAILABLE)
    private val shizukuManager = mockk<ShizukuManager>(relaxed = true) {
        every { statusFlow } returns this@UserServiceAutoStarterTest.statusFlow
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    /** 用真的 MutableStateFlow 而不是 flowOf：跟真正的 snapshot 一樣是永不結束的熱資料源。 */
    private fun lifecycle(snapshot: UserServiceLifecycleSnapshot) = mockk<UserServiceLifecycle>(relaxed = true) {
        every { this@mockk.snapshot } returns MutableStateFlow(snapshot)
    }

    @Test
    fun `disabled auto-start never calls startUserService`() {
        val lifecycle = lifecycle(
            UserServiceLifecycleSnapshot(
                connection = ShizukuConnectionStatus.NOT_AVAILABLE,
                autoStartEnabled = false,
            ),
        )

        UserServiceAutoStarter(shizukuManager, lifecycle).onAppOpened()

        verify(exactly = 0) { shizukuManager.startUserService() }
    }

    @Test
    fun `enabled auto-start waits for authorization before starting`() {
        val lifecycle = lifecycle(
            UserServiceLifecycleSnapshot(
                connection = ShizukuConnectionStatus.NOT_AVAILABLE,
                autoStartEnabled = true,
            ),
        )

        UserServiceAutoStarter(shizukuManager, lifecycle).onAppOpened()
        // 冷啟時 binder 還沒送達，statusFlow 還沒 isAuthorized，不該啟動。
        verify(exactly = 0) { shizukuManager.startUserService() }

        statusFlow.value = ShizukuConnectionStatus.DISCONNECTED // 已授權

        verify(exactly = 1) { shizukuManager.startUserService() }
    }

    @Test
    fun `onAppOpened only triggers once per instance even if called again`() {
        statusFlow.value = ShizukuConnectionStatus.DISCONNECTED // 已授權
        val lifecycle = lifecycle(
            UserServiceLifecycleSnapshot(
                connection = ShizukuConnectionStatus.DISCONNECTED,
                autoStartEnabled = true,
            ),
        )
        val starter = UserServiceAutoStarter(shizukuManager, lifecycle)

        starter.onAppOpened()
        starter.onAppOpened() // 轉螢幕造成的第二次呼叫

        verify(exactly = 1) { shizukuManager.startUserService() }
    }
}
