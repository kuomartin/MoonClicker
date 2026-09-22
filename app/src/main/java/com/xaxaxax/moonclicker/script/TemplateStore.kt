package com.xaxaxax.moonclicker.script

import java.io.File
import java.io.IOException
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** `roi` 的形狀，依 ADR-0013（docs/adr/0013-templates-are-logical-space.md）一律是邏輯座標。 */
@Serializable
data class TemplateRoi(val x: Int, val y: Int, val w: Int, val h: Int)

@Serializable
data class TemplateEntry(val roi: TemplateRoi)

/**
 * `templates.json` 的讀寫。見 #51 定案格式：`{ 模板檔名: { roi } }` 的 map，一個 script 一份，
 * 放在 script 資料夾根目錄、獨立於 `script.json`。純函式，吃 [File]，方便在 JVM 測試裡
 * 直接餵一個暫存目錄進來，比照 [ScriptArchive] 的模式。
 */
object TemplateStore {
    const val META_FILE = "templates.json"

    private val json = Json { prettyPrint = true }

    sealed interface WriteResult {
        data class Written(val imageFile: File, val metaFile: File) : WriteResult
        data class Conflict(val name: String) : WriteResult
        data class Failed(val reason: String) : WriteResult
    }

    /**
     * 把裁切完的 PNG bytes 與 [roi] 存進 [scriptDir]：新增模板圖片本身，加上 `templates.json`
     * 裡新增的一筆。已存在同名模板回 [WriteResult.Conflict]——見 #72 story 8，不靜默覆蓋。
     * `templates.json` 現有內容解析失敗回 [WriteResult.Failed]，不拿半份資料去蓋掉一份
     * 可能還記著其他模板 roi 的檔案（見 #72 story 9：失敗要失敗得大聲）。
     */
    fun write(scriptDir: File, name: String, roi: TemplateRoi, pngBytes: ByteArray): WriteResult {
        val metaFile = File(scriptDir, META_FILE)
        val existing = readExisting(metaFile)
            ?: return WriteResult.Failed("Could not parse existing $META_FILE")

        val imageFile = File(scriptDir, name)
        if (existing.containsKey(name) || imageFile.exists()) {
            return WriteResult.Conflict(name)
        }

        return try {
            imageFile.writeBytes(pngBytes)
            val updated = existing + (name to TemplateEntry(roi))
            metaFile.writeText(json.encodeToString(updated))
            WriteResult.Written(imageFile, metaFile)
        } catch (e: IOException) {
            WriteResult.Failed(e.message ?: "Failed to write template")
        }
    }

    /**
     * 列出 [scriptDir] 目前存的所有模板。`templates.json` 不存在視為空清單；解析失敗
     * 一樣回空清單（列出來的用途是給 UI 顯示，不像 [write]/[delete] 動到檔案，壞掉的
     * meta 檔在這裡不用大聲失敗——呼叫端看到空清單，改用 [write] 存新模板時才會踩到
     * 壞掉的 `templates.json` 並被擋下來）。
     */
    fun list(scriptDir: File): Map<String, TemplateEntry> = readExisting(File(scriptDir, META_FILE)) ?: emptyMap()

    sealed interface DeleteResult {
        data object Deleted : DeleteResult
        data object NotFound : DeleteResult
        data class Failed(val reason: String) : DeleteResult
    }

    /**
     * 從 [scriptDir] 刪除模板 [name]：`templates.json` 裡的那一筆與圖片檔都刪。名稱不在
     * `templates.json` 裡回 [DeleteResult.NotFound]（圖片檔可能還在但沒有 meta 紀錄，
     * 視為「這個模板不存在」，不去猜孤兒檔案要不要一併清掉）。
     */
    fun delete(scriptDir: File, name: String): DeleteResult {
        val metaFile = File(scriptDir, META_FILE)
        val existing = readExisting(metaFile)
            ?: return DeleteResult.Failed("Could not parse existing $META_FILE")
        if (!existing.containsKey(name)) {
            return DeleteResult.NotFound
        }

        return try {
            val imageFile = File(scriptDir, name)
            if (imageFile.isFile) imageFile.delete()
            val updated = existing - name
            metaFile.writeText(json.encodeToString(updated))
            DeleteResult.Deleted
        } catch (e: IOException) {
            DeleteResult.Failed(e.message ?: "Failed to delete template")
        }
    }

    /** 沒有 `templates.json` 視為空 map（第一個模板），讀不動／解不動視為壞掉（回 null）。 */
    private fun readExisting(metaFile: File): Map<String, TemplateEntry>? {
        if (!metaFile.isFile) return emptyMap()
        return try {
            json.decodeFromString<Map<String, TemplateEntry>>(metaFile.readText())
        } catch (e: SerializationException) {
            null
        } catch (e: IOException) {
            null
        }
    }
}
