package com.xaxaxax.relc.script

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
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
    }

    /** zip bomb 的粗略上限——腳本是幾個 .lua 加幾張截圖，正常不會接近這個量級。 */
    private const val MAX_ENTRIES = 2_000
    private const val MAX_TOTAL_BYTES = 200L * 1024 * 1024

    /**
     * 把一個 zip 解成 [root] 底下的新腳本資料夾。
     *
     * 拒絕的情況：entry 路徑逃出目標目錄（zip slip）、entry 數或總大小超標、
     * 解完找不到 `main.lua`。三種都會回 [ImportResult.Failed] 而不是留下半個資料夾。
     */
    fun import(input: InputStream, root: File, suggestedName: String): ImportResult =
        unpackInto(input, root, uniqueId(root, sanitizeId(suggestedName)))

    /**
     * 跟 [import] 一樣把 zip 解開驗證，但目的地是**指定的既有 id**，整份覆蓋掉，不像
     * [import] 那樣在 id 衝突時退讓成 `-2`。這是 VS Code push 回裝置（見 #58）要的語意：
     * 「這就是這份腳本現在該有的內容」，不是「多一份新腳本」。
     */
    fun replace(input: InputStream, root: File, id: String): ImportResult =
        unpackInto(input, root, sanitizeId(id))

    private fun unpackInto(input: InputStream, root: File, id: String): ImportResult {
        val staging = File(root, ".import-$id")
        staging.deleteRecursively()
        if (!staging.mkdirs()) return ImportResult.Failed("無法建立暫存目錄")

        try {
            val stagingPath = staging.canonicalPath + File.separator
            var entries = 0
            var totalBytes = 0L

            ZipInputStream(input).use { zip ->
                var entry: ZipEntry? = zip.nextEntry
                while (entry != null) {
                    if (++entries > MAX_ENTRIES) {
                        return fail(staging, "壓縮檔內容過多（超過 $MAX_ENTRIES 個項目）")
                    }

                    val target = File(staging, entry.name)
                    // canonicalPath 會把 `..` 化解掉，逃出目標目錄的 entry 在這裡被擋下。
                    if (!(target.canonicalPath + if (entry.isDirectory) File.separator else "")
                            .startsWith(stagingPath)
                    ) {
                        return fail(staging, "壓縮檔含有不安全的路徑：${entry.name}")
                    }

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
                                    return fail(staging, "壓縮檔解開後過大")
                                }
                                out.write(buffer, 0, read)
                            }
                        }
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }

            val contentRoot = findContentRoot(staging)
                ?: return fail(staging, "壓縮檔裡找不到 ${Script.MAIN_FILE}")

            val destination = File(root, id)
            destination.deleteRecursively()
            if (!contentRoot.renameTo(destination)) {
                // 跨裝置或 staging 本身就是 contentRoot 以外的情況，退回複製。
                contentRoot.copyRecursively(destination, overwrite = true)
            }
            staging.deleteRecursively()
            return ImportResult.Imported(destination)
        } catch (e: IOException) {
            return fail(staging, e.message ?: "讀取壓縮檔失敗")
        }
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

    private fun fail(staging: File, reason: String): ImportResult {
        staging.deleteRecursively()
        return ImportResult.Failed(reason)
    }

    /** 資料夾名同時是識別，所以限制在安全的字元集合裡。 */
    internal fun sanitizeId(raw: String): String {
        val trimmed = raw.substringAfterLast('/').removeSuffix(".zip").trim()
        val cleaned = trimmed.map { if (it.isLetterOrDigit() || it in "-_" ) it else '-' }
            .joinToString("")
            .trim('-')
        return cleaned.ifEmpty { "script" }.take(64)
    }

    private fun uniqueId(root: File, base: String): String {
        if (!File(root, base).exists()) return base
        var index = 2
        while (File(root, "$base-$index").exists()) index++
        return "$base-$index"
    }
}
