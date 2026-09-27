package com.xaxaxax.moonclicker.ocr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import kotlin.coroutines.cancellation.CancellationException

sealed interface OcrPackState {
    /** 這個行程的 ABI 沒有套件。 */
    data class Unsupported(val abi: String) : OcrPackState
    data object NotInstalled : OcrPackState
    /** [total] 為 -1 表示伺服器沒給長度。 */
    data class Downloading(val bytes: Long, val total: Long) : OcrPackState
    data object Verifying : OcrPackState
    data class Installed(val version: Int, val dir: File) : OcrPackState
    data class Failed(val reason: String) : OcrPackState
}

/** 一次下載：內容串流與長度（-1 表示未知）。 */
class PackDownload(val stream: InputStream, val length: Long)

/**
 * 下載、驗證並安裝 OCR 套件（ADR-0018）。
 *
 * 套件解壓到 [root]`/v<版本>/`。[root] 必須在 app 的 `filesDir` 底下：`libonnxruntime.so` 放在
 * 外部儲存會被 linker namespace 拒絕。解壓先寫進 `v<版本>.tmp/`、逐檔驗完雜湊才 rename，
 * 中斷不會留下半套；目錄存在就代表安裝完整。
 *
 * @param pack 這個行程 ABI 的套件；`null` 表示不支援。
 * @param open 開啟下載串流；預設走 HTTPS，測試時換掉。
 */
class OcrPackInstaller(
    private val root: File,
    private val downloadDir: File,
    private val pack: OcrPack?,
    private val unsupportedAbi: String,
    private val open: (url: String) -> PackDownload = ::httpDownload,
) {
    private val mutex = Mutex()

    private val _state = MutableStateFlow(installedState())
    val state: StateFlow<OcrPackState> = _state.asStateFlow()

    /** 已安裝的套件目錄；未安裝回傳 `null`。 */
    val installedDir: File?
        get() = (_state.value as? OcrPackState.Installed)?.dir

    /** 下載並安裝。取消時回到未安裝，其他失敗留在 [OcrPackState.Failed]。 */
    suspend fun download() = mutex.withLock {
        val pack = pack ?: return@withLock
        withContext(Dispatchers.IO) {
            downloadDir.mkdirs()
            val part = File(downloadDir, "ocr-pack-${pack.abi}.zip.part")
            try {
                open(pack.url).let { download ->
                    download.stream.use { input ->
                        part.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var bytes = 0L
                            _state.value = OcrPackState.Downloading(0, download.length)
                            while (true) {
                                currentCoroutineContext().ensureActive()
                                val read = input.read(buffer)
                                if (read < 0) break
                                output.write(buffer, 0, read)
                                bytes += read
                                _state.value = OcrPackState.Downloading(bytes, download.length)
                            }
                        }
                    }
                }
                install(pack, part)
            } catch (e: CancellationException) {
                _state.value = installedState()
                throw e
            } catch (e: IOException) {
                _state.value = OcrPackState.Failed(e.message ?: e.javaClass.simpleName)
            } finally {
                part.delete()
            }
        }
    }

    /** 從本機的 zip 安裝，驗證方式與下載相同。給測試與手動側載用。 */
    suspend fun installFromZip(zip: File) = mutex.withLock {
        val pack = pack ?: return@withLock
        withContext(Dispatchers.IO) {
            try {
                install(pack, zip)
            } catch (e: IOException) {
                _state.value = OcrPackState.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    suspend fun uninstall() = mutex.withLock {
        withContext(Dispatchers.IO) { root.deleteRecursively() }
        _state.value = installedState()
    }

    private fun install(pack: OcrPack, zip: File) {
        _state.value = OcrPackState.Verifying
        root.mkdirs()
        val staging = File(root, "v${pack.version}.tmp")
        staging.deleteRecursively()
        staging.mkdirs()

        val seen = mutableSetOf<String>()
        ZipInputStream(zip.inputStream().buffered()).use { entries ->
            while (true) {
                val entry = entries.nextEntry ?: break
                // 只取釘了雜湊的檔案，其他（授權、pack.json）不落地；名稱是白名單，也就不會有路徑穿越。
                val expected = pack.files[entry.name] ?: continue
                val digest = MessageDigest.getInstance("SHA-256")
                File(staging, entry.name).outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = entries.read(buffer)
                        if (read < 0) break
                        digest.update(buffer, 0, read)
                        output.write(buffer, 0, read)
                    }
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (actual != expected) {
                    staging.deleteRecursively()
                    throw IOException("${entry.name} failed verification (SHA-256 $actual)")
                }
                seen += entry.name
            }
        }
        val missing = pack.files.keys - seen
        if (missing.isNotEmpty()) {
            staging.deleteRecursively()
            throw IOException("OCR pack is missing ${missing.sorted().joinToString()}")
        }

        val target = File(root, "v${pack.version}")
        target.deleteRecursively()
        if (!staging.renameTo(target)) {
            staging.deleteRecursively()
            throw IOException("cannot move OCR pack into $target")
        }
        root.listFiles()?.filter { it != target }?.forEach { it.deleteRecursively() }
        _state.value = OcrPackState.Installed(pack.version, target)
    }

    private fun installedState(): OcrPackState {
        val pack = pack ?: return OcrPackState.Unsupported(unsupportedAbi)
        val dir = File(root, "v${pack.version}")
        return if (dir.isDirectory) OcrPackState.Installed(pack.version, dir) else OcrPackState.NotInstalled
    }
}

private fun httpDownload(url: String): PackDownload {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.connectTimeout = 15_000
    connection.readTimeout = 30_000
    val code = connection.responseCode
    if (code != HttpURLConnection.HTTP_OK) {
        connection.disconnect()
        throw IOException("HTTP $code from $url")
    }
    return PackDownload(connection.inputStream, connection.contentLengthLong)
}
