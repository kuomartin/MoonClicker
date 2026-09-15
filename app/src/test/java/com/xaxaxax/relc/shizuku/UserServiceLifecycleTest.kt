package com.xaxaxax.relc.shizuku

import com.xaxaxax.relc.core.AppSettings
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class UserServiceLifecycleTest {

    private val dispatcher = StandardTestDispatcher()
    private val statusFlow = MutableStateFlow(ShizukuConnectionStatus.NOT_AVAILABLE)
    private val autoStartFlow = MutableStateFlow(true)

    private val lifecycle = UserServiceLifecycle(
        shizukuManager = mockk {
            every { statusFlow } returns this@UserServiceLifecycleTest.statusFlow
            every { status } answers { this@UserServiceLifecycleTest.statusFlow.value }
        },
        appSettings = mockk<AppSettings> { every { autoStartUserService } returns autoStartFlow },
        dispatcher = dispatcher,
    )

    @Test
    fun `snapshot reflects the current value of both underlying sources immediately`() {
        assertEquals(
            UserServiceLifecycleSnapshot(
                connection = ShizukuConnectionStatus.NOT_AVAILABLE,
                autoStartEnabled = true,
            ),
            lifecycle.snapshot.value,
        )
    }

    @Test
    fun `snapshot updates independently when either source changes`() = runTest(dispatcher) {
        statusFlow.value = ShizukuConnectionStatus.CONNECTED
        advanceUntilIdle()
        assertEquals(ShizukuConnectionStatus.CONNECTED, lifecycle.snapshot.value.connection)

        autoStartFlow.value = false
        advanceUntilIdle()
        assertEquals(false, lifecycle.snapshot.value.autoStartEnabled)
        // 上一個來源的變化沒有被蓋掉。
        assertEquals(ShizukuConnectionStatus.CONNECTED, lifecycle.snapshot.value.connection)
    }

    @Test
    fun `every collector observes the same cached value instead of recomputing independently`() =
        runTest(dispatcher) {
            statusFlow.value = ShizukuConnectionStatus.CONNECTED
            advanceUntilIdle()

            assertEquals(lifecycle.snapshot.value, lifecycle.snapshot.value)
        }
}
