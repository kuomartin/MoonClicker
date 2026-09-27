package com.xaxaxax.moonclicker.shizuku

import com.xaxaxax.moonclicker.IMoonClickerService
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class UserServiceAutoStopperTest {

    private val dispatcher = StandardTestDispatcher()
    private val leases = UserServiceLeases()
    private val service = MutableStateFlow<IMoonClickerService?>(mockk())
    private var activeDisplays = false
    private var onQuery: () -> Unit = {}
    private var stops = 0

    private fun TestScope.startStopper() {
        UserServiceAutoStopper(
            leases = leases,
            serviceFlow = service,
            hasActiveDisplays = { onQuery(); activeDisplays },
            stop = { stops++; service.value = null },
            grace = GRACE,
            dispatcher = dispatcher,
        )
        runCurrent()
    }

    @Test
    fun `stops once the grace period passes with no lease and no display`() = runTest(dispatcher) {
        startStopper()

        advanceTimeBy(GRACE - 1.seconds)
        assertEquals(0, stops)

        advanceTimeBy(2.seconds)
        assertEquals(1, stops)
    }

    @Test
    fun `keeps the service while it still has a managed display or mirror`() = runTest(dispatcher) {
        activeDisplays = true
        startStopper()

        advanceUntilIdle()

        assertEquals(0, stops)
    }

    @Test
    fun `a lease taken during the grace period cancels the countdown`() = runTest(dispatcher) {
        startStopper()

        advanceTimeBy(GRACE - 1.seconds)
        leases.acquire()
        advanceTimeBy(GRACE * 2)

        assertEquals(0, stops)
    }

    @Test
    fun `releasing the last lease restarts the countdown from zero`() = runTest(dispatcher) {
        leases.acquire()
        startStopper()
        advanceTimeBy(GRACE * 2)

        leases.release()
        advanceTimeBy(GRACE - 1.seconds)
        assertEquals(0, stops)

        advanceTimeBy(2.seconds)
        assertEquals(1, stops)
    }

    /** 查詢說沒有 VD，但查詢期間有人取租約建了一個——那份查詢結果不能拿來停服務。 */
    @Test
    fun `a lease taken while querying displays prevents the stop`() = runTest(dispatcher) {
        onQuery = { leases.hold { } }
        startStopper()

        advanceUntilIdle()

        assertEquals(0, stops)
    }

    @Test
    fun `a failed display query keeps the service`() = runTest(dispatcher) {
        onQuery = { error("DeadObjectException") }
        startStopper()

        advanceUntilIdle()

        assertEquals(0, stops)
    }

    @Test
    fun `nothing to stop while disconnected`() = runTest(dispatcher) {
        service.value = null
        startStopper()

        advanceUntilIdle()

        assertEquals(0, stops)
    }

    private companion object {
        val GRACE = 30.seconds
    }
}
