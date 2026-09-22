package com.xaxaxax.moonclicker.script

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 掃描腳本資料夾根目錄，就是全部。
 *
 * 根目錄選在**外部**私有目錄而不是 `filesDir`：兩者都不需要權限，但前者使用者用檔案管理員
 * 或接電腦看得到、改得動。既然沒有內建編輯器，外部編輯就是主要工作流程，路徑必須看得見。
 */
@Singleton
class ScriptStore @Inject constructor(
    @param:ApplicationContext private val context: Context,
) {
    /** 使用者可以直接把資料夾丟進來的路徑。UI 會把它顯示出來。 */
    val root: File
        get() = File(context.getExternalFilesDir(null), ROOT_DIR_NAME).also { it.mkdirs() }

    private val _scripts = MutableStateFlow<List<Script>>(emptyList())
    val scripts: StateFlow<List<Script>> = _scripts.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        _scripts.value = scan(root)
    }

    fun find(id: String): Script? = _scripts.value.firstOrNull { it.id == id }

    fun findByUniqueId(uniqueId: String): Script? =
        _scripts.value.firstOrNull { it.uniqueId == uniqueId }

    /** 讀 `main.lua`。讀不到就回 null——UI 需要能區分「空腳本」與「檔案不見了」。 */
    fun readSource(script: Script): String? =
        runCatching { script.mainFile.readText() }.getOrNull()

    /** 腳本資料夾裡的圖片，也就是 `vision` 用得到的模板。 */
    fun templates(script: Script): List<File> =
        script.dir.walkTopDown()
            .maxDepth(TEMPLATE_SCAN_DEPTH)
            .filter { it.isFile && it.extension.lowercase() in IMAGE_EXTENSIONS }
            .sortedBy { it.name }
            .toList()

    fun delete(script: Script): Boolean {
        val deleted = script.dir.deleteRecursively()
        refresh()
        return deleted
    }

    companion object {
        const val ROOT_DIR_NAME = "scripts"
        private const val TEMPLATE_SCAN_DEPTH = 3
        private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp")

        /**
         * 純函式，好在 JVM 測試裡直接餵一個暫存目錄進來。
         *
         * 沒有 `main.lua` 的資料夾不算腳本——那多半是使用者放的素材或半成品，
         * 列出來只會讓「按下播放卻什麼都沒發生」。
         */
        fun scan(root: File): List<Script> {
            // "." 開頭的資料夾（例如 vision-test 用的 scratch 腳本）是內部用途，不當成
            // 使用者腳本列出來。
            val dirs = root.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") } ?: return emptyList()
            return dirs.mapNotNull { dir ->
                if (!File(dir, Script.MAIN_FILE).isFile) return@mapNotNull null
                val metaFile = File(dir, Script.META_FILE)
                val metaJson = if (metaFile.isFile) {
                    runCatching { metaFile.readText() }
                        .onFailure { Timber.w(it, "Could not read ${metaFile.path}") }
                        .getOrNull()
                } else {
                    null
                }
                Script.from(dir, metaJson)
            }.sortedBy { it.name.lowercase() }
        }
    }
}
