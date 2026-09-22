package com.xaxaxax.moonclicker.script

import com.xaxaxax.moonclicker.core.DisplayConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * `script.json` 的形狀。除了 [uniqueId]，全部欄位都可省略——缺了就用資料夾名與預設值。
 *
 * [uniqueId] 是唯一必要欄位：缺了、格式不符 [Script.UNIQUE_ID_PATTERN]，或整個檔案不存在，
 * 都會讓 [Script.from] 產出 `uniqueId = null`，UI 據此擋下執行（見 issue #103）。
 */
@Serializable
data class ScriptMeta(
    val name: String? = null,
    val description: String? = null,
    val uniqueId: String? = null,
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
 * （`Android/data/com.xaxaxax.moonclicker/files/scripts`），使用者用檔案管理員或接電腦直接改。
 */
data class Script(
    /** 資料夾名。UI 顯示用路徑，不再是識別——見 [uniqueId]。 */
    val id: String,
    val dir: File,
    val name: String,
    val description: String,
    val display: DisplayConfig?,
    /** 持久、跟資料夾名無關的識別。null 代表缺失或格式不符，此時腳本可見但不可執行。 */
    val uniqueId: String?,
) {
    val mainFile: File get() = File(dir, MAIN_FILE)

    companion object {
        const val MAIN_FILE = "main.lua"
        const val META_FILE = "script.json"

        /** [ScriptMeta.uniqueId] 的合法字元集。 */
        val UNIQUE_ID_PATTERN = Regex("^[a-z0-9._-]+$")

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * 從資料夾與（可能不存在或壞掉的）meta JSON 組出一份腳本。
         *
         * meta 解析失敗不會讓整個列表消失——退回資料夾名，壞掉的那份仍然看得到、跑得動。
         * 但 `uniqueId` 缺失或格式不符時，回傳的 [Script.uniqueId] 是 null，讓腳本不可執行。
         */
        fun from(dir: File, metaJson: String?): Script {
            val meta = metaJson?.takeIf { it.isNotBlank() }?.let {
                runCatching { json.decodeFromString<ScriptMeta>(it) }.getOrNull()
            }
            val uniqueId = meta?.uniqueId?.takeIf { UNIQUE_ID_PATTERN.matches(it) }
            return Script(
                id = dir.name,
                dir = dir,
                name = meta?.name?.takeIf { it.isNotBlank() } ?: dir.name,
                description = meta?.description.orEmpty(),
                display = meta?.display?.let { displayMeta ->
                    uniqueId?.let {
                        DisplayConfig(
                            name = it,
                            width = displayMeta.width,
                            height = displayMeta.height,
                            densityDpi = displayMeta.densityDpi,
                        )
                    }
                },
                uniqueId = uniqueId,
            )
        }
    }
}
