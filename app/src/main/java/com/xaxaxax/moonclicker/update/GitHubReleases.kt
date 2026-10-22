package com.xaxaxax.moonclicker.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** `MAJOR.MINOR.PATCH`。release workflow 只接受這個格式，prerelease 由 GitHub 的旗標區分，不寫在版本號裡。 */
@Serializable
data class Version(val major: Int, val minor: Int, val patch: Int) : Comparable<Version> {
    override fun compareTo(other: Version): Int =
        compareValuesBy(this, other, Version::major, Version::minor, Version::patch)

    override fun toString() = "$major.$minor.$patch"

    companion object {
        private val TAG = Regex("""^v(\d+)\.(\d+)\.(\d+)$""")
        private val VERSION_NAME_PREFIX = Regex("""^(\d+)\.(\d+)\.(\d+)""")

        /** release 的 tag，例如 `v0.6.0`；其他格式回傳 `null`。 */
        fun fromTag(tag: String): Version? = TAG.matchEntire(tag)?.toVersion()

        /**
         * App 的 versionName。本機 build 是 `git describe` 的結果（例如 `0.5.0-12-g904a939`），只取前三段：
         * 它比 0.5.0 新、比 0.5.1 舊，同版號的 release 不算更新。
         */
        fun fromVersionName(name: String): Version? = VERSION_NAME_PREFIX.find(name)?.toVersion()

        private fun MatchResult.toVersion(): Version {
            val (major, minor, patch) = destructured
            return Version(major.toInt(), minor.toInt(), patch.toInt())
        }
    }
}

@Serializable
data class Release(
    @SerialName("tag_name") val tag: String,
    @SerialName("html_url") val pageUrl: String,
    val prerelease: Boolean = false,
    val draft: Boolean = false,
    val assets: List<ReleaseAsset> = emptyList(),
    /** 發布說明，GitHub 已從 markdown 轉成 HTML。 */
    @SerialName("body_html") val bodyHtml: String? = null,
) {
    val version: Version? get() = Version.fromTag(tag)
}

@Serializable
data class ReleaseAsset(
    val name: String,
    @SerialName("browser_download_url") val downloadUrl: String,
    val size: Long = 0,
    /** GitHub 算好的雜湊，格式 `sha256:<hex>`。 */
    val digest: String? = null,
) {
    /** 小寫 hex；GitHub 沒給或不是 sha256 時為 `null`。 */
    val sha256: String? get() = digest?.takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:")
}

@Serializable
data class AvailableUpdate(
    val version: Version,
    val prerelease: Boolean,
    val pageUrl: String,
    /** 符合這台裝置 ABI 的 APK；`null` 表示只能開 release 頁。 */
    val apk: ReleaseAsset?,
    val notesHtml: String? = null,
)

/**
 * [installed] 比這個 release 舊時回傳更新資訊。[abis] 依偏好排序（`Build.SUPPORTED_ABIS`），
 * 依序找 `-<abi>.apk`，都沒有才用 `-universal.apk`。
 */
fun Release.toUpdateFor(installed: Version?, abis: List<String>): AvailableUpdate? {
    val version = version ?: return null
    if (installed != null && version <= installed) return null
    val apk = abis.firstNotNullOfOrNull { abi -> assets.find { it.name.endsWith("-$abi.apk") } }
        ?: assets.find { it.name.endsWith("-universal.apk") }
    return AvailableUpdate(version, prerelease, pageUrl, apk, bodyHtml)
}

/**
 * 查 GitHub Releases 上最新的 App 版本。正式版走 `/releases/latest`，它本身就排除 draft 與 prerelease；
 * 含 prerelease 時沒有對應的端點，只能取列表自己挑版本號最大的。
 *
 * @param fetch 以 GET 取回內容；預設走 HTTPS，測試時換掉。
 */
class GitHubReleases(
    private val fetch: (url: String) -> String = ::httpGet,
) {
    suspend fun newest(includePrereleases: Boolean): Release? = withContext(Dispatchers.IO) {
        if (includePrereleases) {
            json.decodeFromString<List<Release>>(fetch("$API/releases?per_page=20"))
                .filter { !it.draft && it.version != null }
                .maxByOrNull { it.version!! }
        } else {
            json.decodeFromString<Release>(fetch("$API/releases/latest")).takeIf { it.version != null }
        }
    }

    private companion object {
        const val API = "https://api.github.com/repos/kuomartin/MoonClicker"
        val json = Json { ignoreUnknownKeys = true }
    }
}

private fun httpGet(url: String): String {
    val connection = URL(url).openConnection() as HttpURLConnection
    try {
        connection.connectTimeout = 15_000
        connection.readTimeout = 15_000
        // html+json 讓 GitHub 附上 body_html，App 不必自己解析 markdown。
        connection.setRequestProperty("Accept", "application/vnd.github.html+json")
        val code = connection.responseCode
        if (code != HttpURLConnection.HTTP_OK) throw IOException("HTTP $code from $url")
        return connection.inputStream.bufferedReader().use { it.readText() }
    } finally {
        connection.disconnect()
    }
}
