package com.xaxaxax.moonclicker.script

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.io.InputStream
import java.util.zip.ZipInputStream
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 打包在 APK 裡的範例腳本（`assets/examples/<名稱>.zip`，由 `:app:bundleExamples` 從 repo 的
 * `examples/` 產生）。安裝走 [ScriptArchive.import]，與使用者匯入 zip 是同一條路。
 */
@Singleton
class BundledExamples @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    data class Example(
        /** asset 檔名去掉 `.zip`，也是安裝時建議的資料夾名。 */
        val assetName: String,
        val name: String,
        val description: String?,
        val uniqueId: String?,
    )

    fun list(): List<Example> {
        val names = runCatching { context.assets.list(ASSET_DIR) }.getOrNull().orEmpty()
        return names.filter { it.endsWith(".zip") }.sorted().mapNotNull { file ->
            runCatching {
                context.assets.open("$ASSET_DIR/$file").use { read(file.removeSuffix(".zip"), it) }
            }.onFailure { Timber.e(it, "failed to read bundled example %s", file) }.getOrNull()
        }
    }

    fun install(example: Example, root: File): ScriptArchive.ImportResult =
        context.assets.open("$ASSET_DIR/${example.assetName}.zip").use { input ->
            ScriptArchive.import(input, root, example.assetName)
        }

    companion object {
        private const val ASSET_DIR = "examples"
        private val json = Json { ignoreUnknownKeys = true }

        /** 從 zip 根目錄的 `script.json` 讀出名稱與說明；沒有 `script.json` 就用 [assetName]。 */
        fun read(assetName: String, zip: InputStream): Example {
            val meta = ZipInputStream(zip).use { stream ->
                generateSequence { stream.nextEntry }
                    .firstOrNull { it.name == Script.META_FILE }
                    ?.let { runCatching { json.decodeFromString<ScriptMeta>(stream.readBytes().decodeToString()) }.getOrNull() }
            }
            return Example(
                assetName = assetName,
                name = meta?.name ?: assetName,
                description = meta?.description,
                uniqueId = meta?.uniqueId,
            )
        }
    }
}
