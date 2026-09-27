package com.xaxaxax.moonclicker.ocr

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class OcrPackInstallerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val contents = mapOf(
        OcrPack.LIBRARY to "runtime",
        OcrPack.DET to "det",
        OcrPack.REC to "rec",
        OcrPack.DICT to "a\nb\n",
    )

    private val pack = OcrPack(
        version = 3,
        abi = "arm64-v8a",
        url = "https://example.invalid/pack.zip",
        files = contents.mapValues { sha256(it.value) },
    )

    private val root by lazy { File(temp.root, "files/ocr") }
    private val cache by lazy { File(temp.root, "cache") }

    private fun installer(pack: OcrPack? = this.pack, open: (String) -> PackDownload = { error("no network") }) =
        OcrPackInstaller(root, cache, pack, unsupportedAbi = "x86", open = open)

    @Test
    fun a_verified_zip_is_installed_into_its_version_directory() = runBlocking {
        val installer = installer()
        installer.installFromZip(zip(contents + ("LICENSES/x" to "license")))

        val state = installer.state.value
        assertEquals(OcrPackState.Installed(3, File(root, "v3")), state)
        contents.forEach { (name, text) -> assertEquals(text, File(root, "v3/$name").readText()) }
        assertFalse("files without a pinned hash are not extracted", File(root, "v3/LICENSES").exists())
        assertFalse(File(root, "v3.tmp").exists())
    }

    @Test
    fun a_tampered_file_fails_verification_and_leaves_nothing_installed() = runBlocking {
        val installer = installer()
        installer.installFromZip(zip(contents + (OcrPack.REC to "tampered")))

        val state = installer.state.value
        assertTrue("expected Failed but was $state", state is OcrPackState.Failed)
        assertTrue((state as OcrPackState.Failed).reason.contains(OcrPack.REC))
        assertFalse(File(root, "v3").exists())
        assertFalse(File(root, "v3.tmp").exists())
    }

    @Test
    fun a_zip_missing_a_file_fails() = runBlocking {
        val installer = installer()
        installer.installFromZip(zip(contents - OcrPack.DICT))

        val state = installer.state.value
        assertTrue("expected Failed but was $state", state is OcrPackState.Failed)
        assertTrue((state as OcrPackState.Failed).reason.contains(OcrPack.DICT))
        assertFalse(File(root, "v3").exists())
    }

    @Test
    fun installing_removes_other_versions() = runBlocking {
        File(root, "v2").mkdirs()
        File(root, "v2/libonnxruntime.so").writeText("old")

        installer().installFromZip(zip(contents))

        assertEquals(listOf("v3"), root.list()!!.toList())
    }

    @Test
    fun an_existing_version_directory_counts_as_installed() {
        File(root, "v3").mkdirs()
        assertEquals(OcrPackState.Installed(3, File(root, "v3")), installer().state.value)
    }

    @Test
    fun download_streams_to_cache_then_installs() = runBlocking {
        val bytes = zip(contents).readBytes()
        var requested: String? = null
        val installer = installer { url ->
            requested = url
            PackDownload(ByteArrayInputStream(bytes), bytes.size.toLong())
        }

        installer.download()

        assertEquals(pack.url, requested)
        assertEquals(OcrPackState.Installed(3, File(root, "v3")), installer.state.value)
        assertTrue("the partial download is removed", cache.listFiles().orEmpty().isEmpty())
    }

    @Test
    fun a_failed_download_is_reported() = runBlocking {
        val installer = installer { throw java.io.IOException("HTTP 404") }

        installer.download()

        assertEquals(OcrPackState.Failed("HTTP 404"), installer.state.value)
    }

    @Test
    fun an_abi_without_a_pack_is_unsupported() {
        assertEquals(OcrPackState.Unsupported("x86"), installer(pack = null).state.value)
        assertEquals(null, OcrPack.forAbi("x86"))
    }

    private fun zip(files: Map<String, String>): File {
        val file = File(temp.root, "pack-${System.nanoTime()}.zip")
        ZipOutputStream(file.outputStream()).use { out ->
            files.forEach { (name, text) ->
                out.putNextEntry(ZipEntry(name))
                out.write(text.toByteArray())
                out.closeEntry()
            }
        }
        return file
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
}
