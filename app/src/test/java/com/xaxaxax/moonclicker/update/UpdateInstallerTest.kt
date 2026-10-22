package com.xaxaxax.moonclicker.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateInstallerTest {

    private var scriptRunning = false
    private val notified = mutableListOf<Version>()
    private var cancelled = 0
    private val launched = mutableListOf<Long>()

    private val installer = UpdateInstaller(
        isScriptRunning = { scriptRunning },
        notifier = object : ReadyNotifier {
            override fun notifyReady(version: Version) {
                notified += version
            }

            override fun cancelReady() {
                cancelled++
            }
        },
        canRequestInstalls = { true },
        launchInstaller = { launched += it },
    )

    private val version = Version(0, 6, 0)

    @Test
    fun `on the settings page with no script running it installs right away`() {
        installer.setSettingsVisible(true)
        installer.onVerified(version)

        assertTrue(installer.installRequested.value)
        assertTrue(notified.isEmpty())
    }

    @Test
    fun `a running script is never interrupted`() {
        installer.setSettingsVisible(true)
        scriptRunning = true
        installer.onVerified(version)

        assertFalse(installer.installRequested.value)
        assertEquals(listOf(version), notified)
    }

    @Test
    fun `away from the settings page it notifies`() {
        installer.onVerified(version)

        assertFalse(installer.installRequested.value)
        assertEquals(listOf(version), notified)
    }

    @Test
    fun `installing dismisses the notification`() {
        installer.install(7)

        assertEquals(1, cancelled)
        assertEquals(listOf(7L), launched)
    }
}
