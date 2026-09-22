package com.xaxaxax.moonclicker.workbench

import com.xaxaxax.moonclicker.script.Script
import com.xaxaxax.moonclicker.script.ScriptStore
import com.xaxaxax.moonclicker.script.TemplateRoi
import java.io.File

/**
 * `POST /scripts/{id}/vision-test` 用的 scratch 腳本：把一次性的 `vision.find` 迴圈材質化成
 * `main.lua`，讓既有的 `ScriptSession`/`ScriptEngine` 執行路徑直接吃，不用另開一條執行路徑
 * （見 WorkbenchServer.kt `/scripts/{id}/run` 旁的說明）。
 *
 * 資料夾名以 "." 開頭，讓 [ScriptStore.scan] 略過，不會出現在使用者的腳本列表裡。
 */
object VisionTestScript {
    private const val FOLDER_NAME = ".__vision_test__"
    private const val UNIQUE_ID = "vision-test"

    /**
     * 在 [scriptsRoot] 底下建立（或覆寫）scratch 腳本資料夾，寫入用 [imagePath]／[roi]／
     * [threshold]／[intervalMs] 產生的 `main.lua`，回傳可以直接拿去 `startOnDisplay` 的
     * [Script]。
     */
    fun materialize(
        scriptsRoot: File,
        imagePath: String,
        roi: TemplateRoi,
        threshold: Double,
        intervalMs: Long,
    ): Script {
        val dir = File(scriptsRoot, FOLDER_NAME).apply { mkdirs() }
        File(dir, Script.MAIN_FILE).writeText(luaSource(imagePath, roi, threshold, intervalMs))
        return Script(
            id = dir.name,
            dir = dir,
            name = "Vision Test",
            description = "",
            display = null,
            uniqueId = UNIQUE_ID,
        )
    }

    private fun luaSource(imagePath: String, roi: TemplateRoi, threshold: Double, intervalMs: Long): String = """
        while true do
          local hit = vision.find({
            image = "${imagePath.escapeLua()}",
            roi = { x = ${roi.x}, y = ${roi.y}, w = ${roi.w}, h = ${roi.h} },
            threshold = $threshold,
          })
          if hit then
            data.set("visionTest", { hit = true, confidence = hit.confidence, cx = hit.cx, cy = hit.cy })
          else
            data.set("visionTest", { hit = false })
          end
          sleep($intervalMs)
        end
    """.trimIndent()

    private fun String.escapeLua(): String = replace("\\", "\\\\").replace("\"", "\\\"")
}
