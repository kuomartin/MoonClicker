package com.xaxaxax.moonclicker.workbench

import com.xaxaxax.moonclicker.script.ScriptStore
import com.xaxaxax.moonclicker.script.TemplateRoi
import com.xaxaxax.moonclicker.script.TemplateStore
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receiveStream
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import java.io.File
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** Vision-test 觸發與模板 CRUD，委派給既有的 [ScriptRunner]／[TemplateStore]。 */
fun Route.visionRoutes(
    scriptsRoot: File,
    scriptRunner: ScriptRunner,
    fileChanges: MutableSharedFlow<ScriptFileChange>,
) {
    // 拿既有模板去對虛擬顯示做一次性 vision.find 迴圈（見 vision-test 合約）：{id} 只用來
    // 解出模板圖片的絕對路徑，實際執行的目標顯示器是 body 裡的 displayId，不是這份腳本的
    // script.json——所以不能走 scriptRunner.start(script)，得用 startOnDisplay。
    post("/scripts/{id}/vision-test") {
        val script = call.parameters["id"]?.let { id -> ScriptStore.scan(scriptsRoot).find { it.id == id } }
        if (script == null) {
            call.respondText("Script not found", status = HttpStatusCode.NotFound)
            return@post
        }
        val request = runCatching { Json.decodeFromString<VisionTestRequest>(call.receiveText()) }.getOrNull()
        if (request == null || request.image.isBlank()) {
            call.respondText("Invalid request body", status = HttpStatusCode.BadRequest)
            return@post
        }
        if (scriptRunner.isRunning()) {
            call.respondText("A script is already running", status = HttpStatusCode.Conflict)
            return@post
        }
        val imagePath = File(script.dir, request.image).absolutePath
        val syntheticScript = VisionTestScript.materialize(
            scriptsRoot = scriptsRoot,
            imagePath = imagePath,
            roi = request.roi,
            threshold = request.threshold,
            intervalMs = request.intervalMs,
        )
        scriptRunner.startOnDisplay(syntheticScript, request.displayId)
        call.respondText("Started", status = HttpStatusCode.Accepted)
    }

    // 裁切模板存回裝置（見 #75）：body 是裁切完的 PNG bytes，roi（邏輯座標，依 ADR-0013）
    // 走 query string，因為 body 已經是純圖片 bytes、不留給欄位混進去的空間。
    // 已存在同名模板回 409（見 #72 story 8）；script 目錄不存在或 templates.json 現有
    // 內容解析失敗回 4xx，不靜默失敗（見 #72 story 9）。
    put("/scripts/{id}/templates/{name}") {
        val id = call.parameters["id"]
        val name = call.parameters["name"]
        if (id.isNullOrBlank() || name.isNullOrBlank() || name.contains('/') || name.contains("..")) {
            call.respondText("Invalid script id or template name", status = HttpStatusCode.BadRequest)
            return@put
        }
        val script = ScriptStore.scan(scriptsRoot).find { it.id == id }
        if (script == null) {
            call.respondText("Script not found", status = HttpStatusCode.NotFound)
            return@put
        }
        val query = call.request.queryParameters
        val roi = listOf("x", "y", "w", "h").map { query[it]?.toIntOrNull() }
            .let { (x, y, w, h) -> if (x != null && y != null && w != null && h != null) TemplateRoi(x, y, w, h) else null }
        if (roi == null) {
            call.respondText(
                "Missing or invalid roi (expects integer x, y, w, h query params)",
                status = HttpStatusCode.BadRequest,
            )
            return@put
        }
        val pngBytes = call.receiveStream().readBytes()
        when (val result = TemplateStore.write(script.dir, name, roi, pngBytes)) {
            is TemplateStore.WriteResult.Written -> {
                // 這條路徑不是走新的單檔案 API（見上面的 /files/*path），裁切工具直接
                // 呼叫這裡寫檔，寫完得自己補一次廣播，VS Code 的本機鏡像才會知道要重抓
                // 這兩個檔案——不補的話裁切完的模板不會同步回去（見 #103 之後的實測回報）。
                fileChanges.tryEmit(
                    ScriptFileChange(id, result.imageFile.relativeTo(script.dir).invariantSeparatorsPath, ScriptFileChangeKind.CREATED)
                )
                fileChanges.tryEmit(
                    ScriptFileChange(id, result.metaFile.relativeTo(script.dir).invariantSeparatorsPath, ScriptFileChangeKind.CHANGED)
                )
                call.respondText("OK")
            }
            is TemplateStore.WriteResult.Conflict ->
                call.respondText("Template already exists: ${result.name}", status = HttpStatusCode.Conflict)
            is TemplateStore.WriteResult.Failed ->
                call.respondText(result.reason, status = HttpStatusCode.BadRequest)
        }
    }

    // 列出某腳本目前存的所有模板（VS Code 端「建立模板」的清單、「測試模板」的下拉選單都
    // 靠這個回填，不再只是本地工作階段快取）。回傳跟 templates.json 一樣的
    // `{ 檔名: { roi } }` map，壞掉的／不存在的 templates.json 都當空清單，不是錯誤。
    get("/scripts/{id}/templates") {
        val id = call.parameters["id"]
        val script = id?.let { i -> ScriptStore.scan(scriptsRoot).find { it.id == i } }
        if (script == null) {
            call.respondText("Script not found", status = HttpStatusCode.NotFound)
            return@get
        }
        call.respondText(Json.encodeToString(TemplateStore.list(script.dir)), ContentType.Application.Json)
    }

    // 刪除模板：templates.json 裡的那一筆與圖片檔都刪，並跟 PUT 一樣補廣播——不補的話
    // VS Code 本機鏡像不會知道這兩個檔案已經沒了。
    delete("/scripts/{id}/templates/{name}") {
        val id = call.parameters["id"]
        val name = call.parameters["name"]
        if (id.isNullOrBlank() || name.isNullOrBlank() || name.contains('/') || name.contains("..")) {
            call.respondText("Invalid script id or template name", status = HttpStatusCode.BadRequest)
            return@delete
        }
        val script = ScriptStore.scan(scriptsRoot).find { it.id == id }
        if (script == null) {
            call.respondText("Script not found", status = HttpStatusCode.NotFound)
            return@delete
        }
        when (val result = TemplateStore.delete(script.dir, name)) {
            is TemplateStore.DeleteResult.Deleted -> {
                fileChanges.tryEmit(ScriptFileChange(id, name, ScriptFileChangeKind.DELETED))
                fileChanges.tryEmit(
                    ScriptFileChange(id, TemplateStore.META_FILE, ScriptFileChangeKind.CHANGED)
                )
                call.respondText("OK")
            }
            is TemplateStore.DeleteResult.NotFound ->
                call.respondText("Template not found: $name", status = HttpStatusCode.NotFound)
            is TemplateStore.DeleteResult.Failed ->
                call.respondText(result.reason, status = HttpStatusCode.BadRequest)
        }
    }
}
