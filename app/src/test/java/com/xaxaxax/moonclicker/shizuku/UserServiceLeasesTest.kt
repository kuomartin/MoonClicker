package com.xaxaxax.moonclicker.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UserServiceLeasesTest {

    @Test
    fun `nested holds count up and back down`() {
        val leases = UserServiceLeases()

        leases.hold {
            leases.hold { assertEquals(2, leases.count.value) }
            assertEquals(1, leases.count.value)
        }

        assertEquals(0, leases.count.value)
    }

    @Test
    fun `hold releases even when the block throws`() {
        val leases = UserServiceLeases()

        runCatching { leases.hold { error("boom") } }

        assertEquals(0, leases.count.value)
    }

    @Test(expected = IllegalStateException::class)
    fun `release without acquire is a bug`() {
        UserServiceLeases().release()
    }

    @Test
    fun `runIfIdleSince runs when nobody took a lease since the mark`() {
        val leases = UserServiceLeases()
        val since = leases.mark()
        var ran = false

        assertTrue(leases.runIfIdleSince(since) { ran = true })
        assertTrue(ran)
    }

    /** 查詢到停止之間有人取了租約又放掉——他可能剛建了 VD，查詢結果已經過期。 */
    @Test
    fun `runIfIdleSince refuses when a lease came and went after the mark`() {
        val leases = UserServiceLeases()
        val since = leases.mark()
        leases.hold { }
        var ran = false

        assertFalse(leases.runIfIdleSince(since) { ran = true })
        assertFalse(ran)
    }

    @Test
    fun `runIfIdleSince refuses while a lease is held`() {
        val leases = UserServiceLeases()
        leases.acquire()

        assertFalse(leases.runIfIdleSince(leases.mark()) { })
    }
}
