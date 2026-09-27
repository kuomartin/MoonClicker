package com.xaxaxax.moonclicker.shizuku

import androidx.lifecycle.LifecycleOwner
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test

class UserServiceForegroundLeaseTest {

    @Test
    fun `holds a lease exactly while the app is started`() {
        val leases = UserServiceLeases()
        val lease = UserServiceForegroundLease(leases)
        val owner = mockk<LifecycleOwner>()

        lease.onStart(owner)
        assertEquals(1, leases.count.value)

        lease.onStop(owner)
        assertEquals(0, leases.count.value)
    }
}
