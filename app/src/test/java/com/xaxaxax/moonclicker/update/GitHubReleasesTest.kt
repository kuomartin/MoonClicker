package com.xaxaxax.moonclicker.update

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GitHubReleasesTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun release(tag: String, prerelease: Boolean = false, draft: Boolean = false, assets: List<String> = emptyList()) =
        """{"tag_name":"$tag","html_url":"https://example.test/$tag","prerelease":$prerelease,"draft":$draft,
           "assets":[${assets.joinToString(",") { """{"name":"$it","browser_download_url":"https://example.test/$it","size":42,"digest":"sha256:ABC"}""" }}],
           "body":"ignored","body_html":"<p>notes for $tag</p>"}"""

    private fun apks(tag: String) = listOf("arm64-v8a", "universal", "x86", "x86_64").map { "MoonClicker-$tag-$it.apk" }

    @Test
    fun `stable channel uses the latest endpoint`() = runTest {
        val urls = mutableListOf<String>()
        val releases = GitHubReleases { url -> urls += url; release("v0.6.0") }

        assertEquals("v0.6.0", releases.newest(includePrereleases = false)?.tag)
        assertTrue(urls.single().endsWith("/releases/latest"))
    }

    @Test
    fun `prerelease channel picks the highest non-draft version from the list`() = runTest {
        val list = listOf(
            release("v0.8.0", draft = true),
            release("v0.6.0"),
            release("v0.7.0", prerelease = true),
            release("vscode-v9.0.0"),
        ).joinToString(",", "[", "]")
        val releases = GitHubReleases { list }

        assertEquals("v0.7.0", releases.newest(includePrereleases = true)?.tag)
    }

    @Test
    fun `version name from git describe compares by its base version`() {
        val installed = Version.fromVersionName("0.5.0-12-g904a939-dirty")
        assertEquals(Version(0, 5, 0), installed)

        val same = json.decodeFromString<Release>(release("v0.5.0"))
        val newer = json.decodeFromString<Release>(release("v0.5.1"))
        assertNull(same.toUpdateFor(installed, listOf("arm64-v8a")))
        assertEquals(Version(0, 5, 1), newer.toUpdateFor(installed, listOf("arm64-v8a"))?.version)
    }

    @Test
    fun `minor and patch compare numerically`() {
        assertTrue(Version(0, 10, 0) > Version(0, 9, 9))
        assertNull(Version.fromTag("v0.6"))
        assertNull(Version.fromTag("0.6.0"))
    }

    @Test
    fun `apk follows abi preference and falls back to universal`() {
        val r = json.decodeFromString<Release>(release("v0.6.0", assets = apks("v0.6.0")))
        val installed = Version(0, 5, 0)

        assertTrue(r.toUpdateFor(installed, listOf("x86_64", "x86"))!!.apk!!.name.endsWith("-x86_64.apk"))
        assertTrue(r.toUpdateFor(installed, listOf("armeabi-v7a", "armeabi"))!!.apk!!.name.endsWith("-universal.apk"))
    }

    @Test
    fun `asset carries size and the sha256 hex from the digest`() {
        val apk = json.decodeFromString<Release>(release("v0.6.0", assets = apks("v0.6.0")))
            .toUpdateFor(Version(0, 5, 0), listOf("arm64-v8a"))!!.apk!!

        assertEquals(42L, apk.size)
        assertEquals("ABC", apk.sha256)
        assertNull(apk.copy(digest = null).sha256)
        assertNull(apk.copy(digest = "md5:abc").sha256)
    }

    @Test
    fun `update carries the release notes html`() {
        val update = json.decodeFromString<Release>(release("v0.6.0")).toUpdateFor(Version(0, 5, 0), emptyList())

        assertEquals("<p>notes for v0.6.0</p>", update?.notesHtml)
    }
}
