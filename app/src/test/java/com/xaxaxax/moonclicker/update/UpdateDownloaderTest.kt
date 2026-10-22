package com.xaxaxax.moonclicker.update

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.security.MessageDigest

class UpdateDownloaderTest {

    private class FakeBackend : DownloadBackend {
        var nextId = 1L
        val statuses = mutableMapOf<Long, DownloadStatus>()
        val files = mutableMapOf<Long, ByteArray>()
        val enqueued = mutableListOf<String>()
        val removed = mutableListOf<Long>()

        override fun enqueue(url: String, fileName: String, title: String): Long {
            enqueued += fileName
            return nextId++.also { statuses[it] = DownloadStatus.InProgress(0, -1) }
        }

        override fun query(id: Long) = statuses[id]

        override fun remove(id: Long) {
            removed += id
            statuses.remove(id)
            files.remove(id)
        }

        override fun open(id: Long): InputStream? = files[id]?.inputStream()

        fun complete(id: Long, content: ByteArray) {
            statuses[id] = DownloadStatus.Successful
            files[id] = content
        }
    }

    private val content = "apk".toByteArray()
    private val contentSha = MessageDigest.getInstance("SHA-256").digest(content).joinToString("") { "%02x".format(it) }

    private val backend = FakeBackend()
    private val store = MemoryUpdateStore()
    private val verified = mutableListOf<Version>()

    private fun downloader(installed: String = "0.5.0") =
        UpdateDownloader(backend, store, installed, title = { "MoonClicker $it" }, onVerified = { verified += it })

    private fun update(version: Version = Version(0, 6, 0), sha256: String? = contentSha) = AvailableUpdate(
        version = version,
        prerelease = false,
        pageUrl = "https://example.test/v$version",
        apk = ReleaseAsset("MoonClicker-v$version-arm64-v8a.apk", "https://example.test/apk", 3, sha256?.let { "sha256:$it" }),
    )

    @Test
    fun `matching sha256 becomes ready and hands over to the installer`() = runTest {
        val downloader = downloader()
        downloader.start(update())
        backend.complete(1, content)
        downloader.onDownloadComplete(1)

        assertEquals(DownloadState.Ready(Version(0, 6, 0), 1), downloader.state.value)
        assertEquals(listOf(Version(0, 6, 0)), verified)
    }

    @Test
    fun `sha256 mismatch removes the file and points to the release page`() = runTest {
        val downloader = downloader()
        downloader.start(update(sha256 = "0".repeat(64)))
        backend.complete(1, content)
        downloader.onDownloadComplete(1)

        assertEquals(DownloadState.Failed(DownloadFailure.CORRUPTED, "https://example.test/v0.6.0"), downloader.state.value)
        assertEquals(listOf(1L), backend.removed)
        assertNull(store.download)
        assertTrue(verified.isEmpty())
    }

    @Test
    fun `progress polling and the completion broadcast verify only once`() = runTest {
        val downloader = downloader()
        downloader.start(update())
        backend.complete(1, content)
        downloader.refresh()
        downloader.onDownloadComplete(1)

        assertEquals(1, verified.size)
    }

    @Test
    fun `same version is not downloaded twice`() = runTest {
        val downloader = downloader()
        downloader.start(update())
        downloader.start(update())
        backend.complete(1, content)
        downloader.refresh()
        downloader.start(update())

        assertEquals(1, backend.enqueued.size)
    }

    @Test
    fun `state is restored from the record after a restart`() = runTest {
        downloader().start(update())
        backend.complete(1, content)

        val restarted = downloader()
        assertEquals(DownloadState.Ready(Version(0, 6, 0), 1), restarted.refresh())
    }

    @Test
    fun `file deleted by the user goes back to nothing downloaded`() = runTest {
        val downloader = downloader()
        downloader.start(update())
        backend.complete(1, content)
        downloader.refresh()
        backend.files.clear()

        assertEquals(DownloadState.None, downloader.refresh())
    }

    @Test
    fun `cancel from the system notification goes back to nothing downloaded`() = runTest {
        val downloader = downloader()
        downloader.start(update())
        backend.statuses.clear()
        downloader.onDownloadComplete(1)

        assertEquals(DownloadState.None, downloader.state.value)
    }

    @Test
    fun `record is dropped once that version is installed but the file is kept`() = runTest {
        downloader().start(update())
        backend.complete(1, content)

        downloader(installed = "0.6.0")

        assertNull(store.download)
        assertTrue(backend.removed.isEmpty())
    }

    @Test
    fun `missing digest falls back to trusting the signature check`() = runTest {
        val downloader = downloader()
        downloader.start(update(sha256 = null))
        backend.complete(1, content)

        assertTrue(downloader.refresh() is DownloadState.Ready)
    }
}
