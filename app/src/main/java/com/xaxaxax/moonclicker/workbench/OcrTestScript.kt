package com.xaxaxax.moonclicker.workbench

import com.xaxaxax.moonclicker.script.Script
import com.xaxaxax.moonclicker.script.ScriptStore
import com.xaxaxax.moonclicker.script.TemplateRoi
import java.io.File

/**
 * `POST /ocr-test` 用的 scratch 腳本：把一次性的 OCR 迴圈材質化成 `main.lua`，走既有的
 * `ScriptSession`/`ScriptEngine` 執行路徑——OCR 的載入與釋放因此跟著腳本生命週期，不另開管道。
 *
 * 資料夾名以 "." 開頭，讓 [ScriptStore.scan] 略過。每輪結果以 `data.set("ocrTest", …)` 回報。
 */
object OcrTestScript {
    private const val FOLDER_NAME = ".__ocr_test__"
    private const val UNIQUE_ID = "ocr-test"

    fun materialize(scriptsRoot: File, request: OcrTestRequest): Script {
        val dir = File(scriptsRoot, FOLDER_NAME).apply { mkdirs() }
        File(dir, Script.MAIN_FILE).writeText(luaSource(request))
        return Script(
            id = dir.name,
            dir = dir,
            name = "OCR Test",
            description = "",
            display = null,
            uniqueId = UNIQUE_ID,
        )
    }

    private fun luaSource(request: OcrTestRequest): String {
        val roi = request.roi?.lua()
        val body = when (request.mode) {
            OcrTestMode.READ -> """
                local line = vision.read($roi)
                data.set("ocrTest", { lines = line and { line } or {} })
            """
            OcrTestMode.READ_LINES -> """
                data.set("ocrTest", { lines = vision.read_lines(${roi ?: ""}) })
            """
            OcrTestMode.FIND -> """
                local hit = vision.find({
                  text = ${request.text.orEmpty().luaString()},
                  exact = ${request.exact},
                  threshold = ${request.threshold},${roi?.let { "\n                  roi = $it," } ?: ""}
                })
                if hit then
                  data.set("ocrTest", { hit = true, confidence = hit.confidence, x = hit.x, y = hit.y, w = hit.w, h = hit.h, line = hit.text })
                else
                  data.set("ocrTest", { hit = false })
                end
            """
        }.trimIndent().prependIndent("  ")
        return "data.set(\"ocrTest\", { started = true })\nwhile true do\n$body\n  sleep(${request.intervalMs})\nend\n"
    }

    private fun TemplateRoi.lua() = "{ x = $x, y = $y, w = $w, h = $h }"

    /** 雙引號字串；引號、反斜線與控制字元都跳脫成 Lua 的 `\ddd`，文字可以是任何內容。 */
    internal fun String.luaString(): String = buildString {
        append('"')
        for (c in this@luaString) {
            when {
                c == '"' || c == '\\' -> append('\\').append(c)
                c < ' ' -> append('\\').append(c.code.toString().padStart(3, '0'))
                else -> append(c)
            }
        }
        append('"')
    }
}
