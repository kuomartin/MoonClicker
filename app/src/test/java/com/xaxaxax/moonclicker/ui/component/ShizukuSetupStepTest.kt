package com.xaxaxax.moonclicker.ui.component

import com.xaxaxax.moonclicker.shizuku.ShizukuConnectionStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class ShizukuSetupStepTest {

    @Test
    fun `each not-ready status maps to its own step`() {
        assertEquals(ShizukuSetupStep.INSTALL, ShizukuConnectionStatus.NOT_INSTALLED.toSetupStep())
        assertEquals(ShizukuSetupStep.START, ShizukuConnectionStatus.NOT_RUNNING.toSetupStep())
        assertEquals(ShizukuSetupStep.GRANT, ShizukuConnectionStatus.NEED_PERMISSION.toSetupStep())
    }

    @Test
    fun `authorized is done regardless of UserService connection`() {
        ShizukuConnectionStatus.entries.filter { it.isAuthorized }.forEach {
            assertEquals(ShizukuSetupStep.DONE, it.toSetupStep())
        }
    }
}
