package com.xaxaxax.moonclicker.update

import com.xaxaxax.moonclicker.core.AppSettings
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import kotlin.time.Duration.Companion.hours

class MemoryUpdateStore : UpdateStore {
    override var lastCheckedAtMs = 0L
    override var skippedVersion: String? = null
    override var lastAvailable: AvailableUpdate? = null
    override var download: DownloadRecord? = null
}

class UpdateCheckerTest {

    private val store = MemoryUpdateStore()
    private var nowMs = 100.hours.inWholeMilliseconds
    private var fetches = 0
    private var latestTag = "v0.6.0"
    private var failing = false
    private val autoCheck = MutableStateFlow(true)

    private fun checker(installed: String = "0.5.0") = UpdateChecker(
        releases = GitHubReleases {
            fetches++
            if (failing) throw IOException("offline")
            """{"tag_name":"$latestTag","html_url":"https://example.test"}"""
        },
        appSettings = mockk<AppSettings> {
            every { autoCheckUpdates } returns autoCheck
            every { includePrereleases } returns MutableStateFlow(false)
        },
        store = store,
        installedVersionName = installed,
        abis = listOf("arm64-v8a"),
        now = { nowMs },
    )

    @Test
    fun `auto check finds a newer version and raises the badge`() = runTest {
        val checker = checker()
        checker.checkIfDue()

        assertEquals(Version(0, 6, 0), (checker.lastResult.value as CheckResult.Available).update.version)
        assertTrue(checker.hasPendingUpdate.value)
        assertEquals(nowMs, store.lastCheckedAtMs)
    }

    @Test
    fun `auto check runs at most once a day`() = runTest {
        val checker = checker()
        checker.checkIfDue()
        nowMs += 23.hours.inWholeMilliseconds
        checker.checkIfDue()
        assertEquals(1, fetches)

        nowMs += 1.hours.inWholeMilliseconds
        checker.checkIfDue()
        assertEquals(2, fetches)
    }

    @Test
    fun `found update survives a restart within the interval`() = runTest {
        checker().checkIfDue()

        val restarted = checker()
        restarted.checkIfDue()

        assertEquals(1, fetches)
        assertTrue(restarted.lastResult.value is CheckResult.Available)
        assertTrue(restarted.hasPendingUpdate.value)
    }

    @Test
    fun `badge clears once the update is installed`() = runTest {
        checker().checkIfDue()

        assertFalse(checker(installed = "0.6.0").hasPendingUpdate.value)
    }

    @Test
    fun `failed check keeps the badge and does not count toward the interval`() = runTest {
        val checker = checker()
        checker.check()
        failing = true
        nowMs += 24.hours.inWholeMilliseconds
        assertTrue(checker.check() is CheckOutcome.Failed)
        assertTrue(checker.lastResult.value is CheckResult.Available)
        assertTrue(checker.hasPendingUpdate.value)

        failing = false
        checker.checkIfDue()
        assertEquals(3, fetches)
    }

    @Test
    fun `skipping clears the badge until a newer version appears`() = runTest {
        val checker = checker()
        checker.checkIfDue()
        checker.skip(Version(0, 6, 0))
        assertFalse(checker.hasPendingUpdate.value)
        assertTrue(checker.lastResult.value is CheckResult.Available)

        latestTag = "v0.6.1"
        nowMs += 24.hours.inWholeMilliseconds
        checker.checkIfDue()
        assertTrue(checker.hasPendingUpdate.value)
    }

    @Test
    fun `no auto check when the user turned it off`() = runTest {
        autoCheck.value = false
        checker().checkIfDue()

        assertEquals(0, fetches)
    }

    @Test
    fun `same version is up to date`() = runTest {
        val checker = checker(installed = "0.6.0")
        assertEquals(CheckOutcome.UpToDate, checker.check())
        assertEquals(CheckResult.UpToDate(nowMs), checker.lastResult.value)
    }

    @Test
    fun `never checked until the first successful check`() = runTest {
        failing = true
        val checker = checker()
        checker.check()

        assertEquals(CheckResult.NeverChecked, checker.lastResult.value)
    }
}
