package com.xaxaxax.moonclicker.script

import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * 腳本資料夾與 zip 之間的往返。沒有內建編輯器，所以「拿出去改、改完拿回來」得是通順的。
 *
 * 全部是純函式，吃 [File] 與 stream，方便在 JVM 測試裡直接跑。
 */
object ScriptArchive {

    sealed interface ImportResult {
        data class Imported(val dir: File) : ImportResult
        data class Failed(val reason: String) : ImportResult

        /**
         * zip 帶的 `uniqueId` 撞到裝置上另一份腳本（[existingDir]）。呼叫端要問使用者
         * 覆蓋還是取消，再呼叫 [ScriptArchive.resolveConflict]——這裡不擅自決定。
         */
        data class Conflict(
            val existingDir: File,
            val uniqueId: String,
            internal val stagingDir: File,
            internal val contentRoot: File,
        ) : ImportResult
    }

    /** zip bomb 的粗略上限——腳本是幾個 .lua 加幾張截圖，正常不會接近這個量級。 */
    private const val MAX_ENTRIES = 2_000
    private const val MAX_TOTAL_BYTES = 200L * 1024 * 1024

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * 把一個 zip 解成 [root] 底下的新腳本資料夾。
     *
     * 拒絕的情況：entry 路徑逃出目標目錄（zip slip）、entry 數或總大小超標、
     * 解完找不到 `main.lua`。三種都會回 [ImportResult.Failed] 而不是留下半個資料夾。
     *
     * `script.json` 沒帶 `uniqueId`（或格式不符）就自動產生一個並寫回去；帶了但跟裝置上
     * 其他腳本撞號，回 [ImportResult.Conflict] 讓呼叫端問使用者，不擅自覆蓋或另外改號
     * （見 issue #103：使用者可能是刻意要對齊外部工具，系統偷改值只會讓兩邊對不上）。
     */
    fun import(input: InputStream, root: File, suggestedName: String): ImportResult {
        val stagingDir = File(root, ".import-${UUID.randomUUID()}")
        stagingDir.deleteRecursively()
        if (!stagingDir.mkdirs()) return ImportResult.Failed("Failed to create staging directory")

        val contentRoot = when (val extracted = extractZip(input, stagingDir)) {
            is ExtractResult.Failed -> {
                stagingDir.deleteRecursively()
                return ImportResult.Failed(extracted.reason)
            }
            is ExtractResult.Success -> extracted.contentRoot
        }

        val uniqueId = ensureUniqueId(contentRoot)
        findScriptByUniqueId(root, uniqueId)?.let { existingDir ->
            return ImportResult.Conflict(existingDir, uniqueId, stagingDir, contentRoot)
        }

        val destination = File(root, uniqueFolderName(root, sanitizeId(suggestedName)))
        return moveToDestination(contentRoot, destination, stagingDir)
    }

    /**
     * 使用者對 [ImportResult.Conflict] 的決定：[overwrite] 為 true 就用匯入內容蓋掉
     * [ImportResult.Conflict.existingDir]（資料夾名不變，只換內容，`uniqueId` 跟著保持一致）；
     * 否則整個匯入作廢，裝置上兩份都不動。
     */
    fun resolveConflict(conflict: ImportResult.Conflict, overwrite: Boolean): ImportResult {
        if (!overwrite) {
            conflict.stagingDir.deleteRecursively()
            return ImportResult.Failed("Import cancelled")
        }
        return moveToDestination(conflict.contentRoot, conflict.existingDir, conflict.stagingDir)
    }

    /**
     * 跟 [import] 一樣把 zip 解開驗證，但目的地是**指定的既有 id**，整份覆蓋掉，不像
     * [import] 那樣在 id 衝突時退讓成 `-2`。這是 VS Code push 回裝置（見 #58）要的語意：
     * 「這就是這份腳本現在該有的內容」，不是「多一份新腳本」。不經過 `uniqueId` 撞號檢查——
     * 那條檢查只守 [import] 這個入口，見 docs/plans 對這個已知缺口的記錄。
     */
    fun replace(input: InputStream, root: File, id: String): ImportResult {
        val safeId = sanitizeId(id)
        val stagingDir = File(root, ".import-$safeId")
        stagingDir.deleteRecursively()
        if (!stagingDir.mkdirs()) return ImportResult.Failed("Failed to create staging directory")

        val contentRoot = when (val extracted = extractZip(input, stagingDir)) {
            is ExtractResult.Failed -> {
                stagingDir.deleteRecursively()
                return ImportResult.Failed(extracted.reason)
            }
            is ExtractResult.Success -> extracted.contentRoot
        }

        return moveToDestination(contentRoot, File(root, safeId), stagingDir)
    }

    private sealed interface ExtractResult {
        data class Success(val contentRoot: File) : ExtractResult
        data class Failed(val reason: String) : ExtractResult
    }

    private fun extractZip(input: InputStream, stagingDir: File): ExtractResult {
        try {
            var entries = 0
            var totalBytes = 0L

            ZipInputStream(input).use { zip ->
                var entry: ZipEntry? = zip.nextEntry
                while (entry != null) {
                    if (++entries > MAX_ENTRIES) {
                        return ExtractResult.Failed("Archive contains too many entries (exceeds $MAX_ENTRIES)")
                    }

                    // SafePath 把 `..` 解掉，逃出目標目錄的 entry 在這裡被擋下（zip slip）。
                    val target = SafePath.resolve(stagingDir, entry.name)
                        ?: return ExtractResult.Failed("Archive contains insecure path: ${entry.name}")

                    if (entry.isDirectory) {
                        target.mkdirs()
                    } else {
                        target.parentFile?.mkdirs()
                        target.outputStream().use { out ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            while (true) {
                                val read = zip.read(buffer)
                                if (read <= 0) break
                                totalBytes += read
                                if (totalBytes > MAX_TOTAL_BYTES) {
                                    return ExtractResult.Failed("Extracted archive size is too large")
                                }
                                out.write(buffer, 0, read)
                            }
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }

            val contentRoot = findContentRoot(stagingDir)
                ?: return ExtractResult.Failed("Archive missing ${Script.MAIN_FILE}")
            return ExtractResult.Success(contentRoot)
        } catch (e: IOException) {
            return ExtractResult.Failed(e.message ?: "Failed to read archive")
        }
    }

    private fun moveToDestination(contentRoot: File, destination: File, stagingDir: File): ImportResult {
        destination.deleteRecursively()
        if (!contentRoot.renameTo(destination)) {
            // 跨裝置或 staging 本身就是 contentRoot 以外的情況，退回複製。
            contentRoot.copyRecursively(destination, overwrite = true)
        }
        stagingDir.deleteRecursively()
        return ImportResult.Imported(destination)
    }

    /** 把腳本資料夾寫成 zip。 */
    fun export(script: Script, output: OutputStream) {
        ZipOutputStream(output).use { zip ->
            script.dir.walkTopDown()
                .filter { it.isFile }
                .forEach { file ->
                    val relative = file.relativeTo(script.dir).invariantSeparatorsPath
                    zip.putNextEntry(ZipEntry(relative))
                    file.inputStream().use { it.copyTo(zip) }
                    zip.closeEntry()
                }
        }
    }

    /**
     * `main.lua` 可能在 zip 的根，也可能被包在一層資料夾裡（大部分壓縮工具的預設行為）。
     * 兩者都接受，其餘一律視為不是腳本。
     */
    private fun findContentRoot(staging: File): File? {
        if (File(staging, Script.MAIN_FILE).isFile) return staging
        val subdirs = staging.listFiles()?.filter { it.isDirectory }.orEmpty()
        return subdirs.singleOrNull { File(it, Script.MAIN_FILE).isFile }
    }

    /** 資料夾名同時是顯示用路徑，所以限制在安全的字元集合裡。 */
    internal fun sanitizeId(raw: String): String {
        val trimmed = raw.substringAfterLast('/').removeSuffix(".zip").trim()
        val cleaned = trimmed.map { if (it.isLetterOrDigit() || it in "-_" ) it else '-' }
            .joinToString("")
            .trim('-')
        return cleaned.ifEmpty { "script" }.take(64)
    }

    private fun uniqueFolderName(root: File, base: String): String {
        if (!File(root, base).exists()) return base
        var index = 2
        while (File(root, "$base-$index").exists()) index++
        return "$base-$index"
    }

    /** 讀 [contentRoot] 的 `script.json`，缺 `uniqueId`（或格式不符）就產生一個並寫回去。 */
    private fun ensureUniqueId(contentRoot: File): String {
        val metaFile = File(contentRoot, Script.META_FILE)
        val existingMeta = readMeta(metaFile)
        existingMeta?.uniqueId?.takeIf { Script.UNIQUE_ID_PATTERN.matches(it) }?.let { return it }

        val generated = UUID.randomUUID().toString().take(8)
        metaFile.writeText(json.encodeToString((existingMeta ?: ScriptMeta()).copy(uniqueId = generated)))
        return generated
    }

    /** [root] 底下（排除 staging）是否已經有腳本用著這個 `uniqueId`。 */
    private fun findScriptByUniqueId(root: File, uniqueId: String): File? =
        root.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".import-") }
            ?.firstOrNull { dir -> readMeta(File(dir, Script.META_FILE))?.uniqueId == uniqueId }

    private fun readMeta(metaFile: File): ScriptMeta? {
        if (!metaFile.isFile) return null
        val text = runCatching { metaFile.readText() }.getOrNull()?.takeIf { it.isNotBlank() } ?: return null
        return runCatching { json.decodeFromString<ScriptMeta>(text) }.getOrNull()
    }
}
