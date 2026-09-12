package com.xaxaxax.relc.script

import com.xaxaxax.relc.core.DisplayConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** `script.json` 的形狀。全部欄位都可省略——缺了就用資料夾名與預設值。 */
@Serializable
data class ScriptMeta(
    val name: String? = null,
    val description: String? = null,
    /**
     * 要跑在什麼樣的虛擬顯示上。**刻意不存 displayId**：虛擬顯示每次重建 id 都會變，
     * 存下來的必然過期。存的是「要一個長這樣的顯示器」，執行時再解析成實際的 id。
     * 省略代表預設跑實體螢幕（那裡沒有畫面辨識）。
     */
    val display: DisplayMeta? = null,
) {
    @Serializable
    data class DisplayMeta(
        val width: Int,
        val height: Int,
        val densityDpi: Int = 320,
    )
}

/**
 * 一份腳本 = 一個資料夾。裡面必須有 `main.lua`，可以有 `script.json` 與模板圖片。
 *
 * 沒有內建編輯器是刻意的：資料夾放在 app 的外部私有目錄
 * （`Android/data/com.xaxaxax.relc/files/scripts`），使用者用檔案管理員或接電腦直接改。
 */
data class Script(
    /** 資料夾名，同時是穩定識別。 */
    val id: String,
    val dir: File,
    val name: String,
    val description: String,
    val display: DisplayConfig?,
) {
    val mainFile: File get() = File(dir, MAIN_FILE)

    companion object {
        const val MAIN_FILE = "main.lua"
        const val META_FILE = "script.json"

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * 從資料夾與（可能不存在或壞掉的）meta JSON 組出一份腳本。
         *
         * meta 解析失敗不會讓整個列表消失——退回資料夾名，壞掉的那份仍然看得到、跑得動。
         */
        fun from(dir: File, metaJson: String?): Script {
            val meta = metaJson?.takeIf { it.isNotBlank() }?.let {
                runCatching { json.decodeFromString<ScriptMeta>(it) }.getOrNull()
            }
            return Script(
                id = dir.name,
                dir = dir,
                name = meta?.name?.takeIf { it.isNotBlank() } ?: dir.name,
                description = meta?.description.orEmpty(),
                display = meta?.display?.let {
                    DisplayConfig(
                        name = meta.name ?: dir.name,
                        width = it.width,
                        height = it.height,
                        densityDpi = it.densityDpi,
                    )
                },
            )
        }
    }
}
