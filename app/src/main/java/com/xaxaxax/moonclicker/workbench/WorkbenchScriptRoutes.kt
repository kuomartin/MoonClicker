package com.xaxaxax.moonclicker.workbench

import com.xaxaxax.moonclicker.script.SafePath
import com.xaxaxax.moonclicker.script.Script
import com.xaxaxax.moonclicker.script.ScriptMeta
import com.xaxaxax.moonclicker.script.ScriptStore
import com.xaxaxax.moonclicker.script.TemplateStore
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.receiveStream
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import java.io.File
import java.io.IOException
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/** 腳本清單、Script Folder 單檔案讀寫同步、觸發執行，委派給既有的 [ScriptStore]／[ScriptRunner]。 */
fun Route.scriptRoutes(
    scriptsRoot: File,
    scriptRunner: ScriptRunner,
    fileChanges: MutableSharedFlow<ScriptFileChange>,
) {
    get("/scripts") {
        val scripts = ScriptStore.scan(scriptsRoot).map { script ->
            val mainMtime = script.mainFile.takeIf { it.isFile }?.lastModified() ?: 0L
            val metaMtime = File(script.dir, TemplateStore.META_FILE).takeIf { it.isFile }?.lastModified() ?: 0L
            WorkbenchScriptSummary(
                id = script.id,
                name = script.name,
                templateCount = TemplateStore.list(script.dir).size,
                modifiedMs = maxOf(mainMtime, metaMtime),
            )
        }
        call.respondText(Json.encodeToString(scripts), ContentType.Application.Json)
    }

    // 新增腳本：{id} 直接當資料夾名兼 script.json 的 uniqueId（見 Script.UNIQUE_ID_PATTERN），
    // 兩者共用同一個值就不用另外想一套 id 產生規則。main.lua 給個最小骨架，讓腳本一建立
    // 就是可執行的狀態（uniqueId 缺失會被 ScriptSession.start 擋下來，見 #103）。
    post("/scripts/{id}") {
        val id = call.parameters["id"]
        if (id.isNullOrBlank() || id.startsWith(".") || !Script.UNIQUE_ID_PATTERN.matches(id)) {
            call.respondText(
                "Invalid script id (expects ${Script.UNIQUE_ID_PATTERN.pattern}, not starting with '.')",
                status = HttpStatusCode.BadRequest,
            )
            return@post
        }
        val dir = File(scriptsRoot, id)
        if (dir.exists()) {
            call.respondText("Script already exists: $id", status = HttpStatusCode.Conflict)
            return@post
        }
        try {
            dir.mkdirs()
            File(dir, Script.MAIN_FILE).writeText("-- $id\n")
            File(dir, Script.META_FILE).writeText(Json.encodeToString(ScriptMeta(uniqueId = id)))
            call.respondText("Created", status = HttpStatusCode.Created)
        } catch (e: IOException) {
            dir.deleteRecursively()
            call.respondText(e.message ?: "Failed to create script", status = HttpStatusCode.InternalServerError)
        }
    }

    // Script Folder 單檔案讀寫（見 vscode-local-mirror-plan）：VS Code 端維護一份本機
    // 鏡像資料夾，靠這幾個路由跟裝置做背景同步——取代整包 zip 的 export/import，
    // 也取代直接把這份 API 接成 vscode.FileSystemProvider 的做法（LuaLS 讀不到
    // virtual scheme，見 vscode-fsprovider-plan E2）。
    get("/scripts/{id}/tree") {
        val script = call.parameters["id"]?.let { id -> ScriptStore.scan(scriptsRoot).find { it.id == id } }
        if (script == null) {
            call.respondText("Script not found", status = HttpStatusCode.NotFound)
            return@get
        }
        val entries = script.dir.walkTopDown()
            .filter { it != script.dir }
            .map { file ->
                ScriptTreeEntry(
                    path = file.relativeTo(script.dir).invariantSeparatorsPath,
                    size = if (file.isFile) file.length() else 0L,
                    mtimeMs = file.lastModified(),
                    isDirectory = file.isDirectory,
                    // VS Code 端拿這個判斷 self-echo（自己剛推上去的改動不用重新下載）
                    // 跟要不要跳過某個檔案的 pull，見 vscode-local-mirror-plan Q2。
                    sha256 = if (file.isFile) file.sha256Hex() else "",
                )
            }
            .toList()
        call.respondText(Json.encodeToString(entries), ContentType.Application.Json)
    }

    get("/scripts/{id}/files/{path...}") {
        val script = call.parameters["id"]?.let { id -> ScriptStore.scan(scriptsRoot).find { it.id == id } }
        if (script == null) {
            call.respondText("Script not found", status = HttpStatusCode.NotFound)
            return@get
        }
        val relativePath = call.filePathParam()
        val target = SafePath.resolve(script.dir, relativePath)
        if (target == null || !target.isFile) {
            call.respondText("File not found", status = HttpStatusCode.NotFound)
            return@get
        }
        call.respondBytes(target.readBytes())
    }

    put("/scripts/{id}/files/{path...}") {
        val script = call.parameters["id"]?.let { id -> ScriptStore.scan(scriptsRoot).find { it.id == id } }
        if (script == null) {
            call.respondText("Script not found", status = HttpStatusCode.NotFound)
            return@put
        }
        val relativePath = call.filePathParam()
        val target = SafePath.resolve(script.dir, relativePath)
        if (target == null || relativePath.isBlank()) {
            call.respondText("Invalid path", status = HttpStatusCode.BadRequest)
            return@put
        }
        if (target.isDirectory) {
            call.respondText("Path is a directory", status = HttpStatusCode.Conflict)
            return@put
        }
        val existed = target.isFile
        target.parentFile?.mkdirs()
        target.writeBytes(call.receiveStream().readBytes())
        fileChanges.tryEmit(
            ScriptFileChange(script.id, relativePath, if (existed) ScriptFileChangeKind.CHANGED else ScriptFileChangeKind.CREATED)
        )
        call.respondText("OK")
    }

    delete("/scripts/{id}/files/{path...}") {
        val script = call.parameters["id"]?.let { id -> ScriptStore.scan(scriptsRoot).find { it.id == id } }
        if (script == null) {
            call.respondText("Script not found", status = HttpStatusCode.NotFound)
            return@delete
        }
        val relativePath = call.filePathParam()
        val target = SafePath.resolve(script.dir, relativePath)
        if (target == null || relativePath.isBlank() || !target.exists()) {
            call.respondText("File not found", status = HttpStatusCode.NotFound)
            return@delete
        }
        if (!target.deleteRecursively()) {
            call.respondText("Failed to delete", status = HttpStatusCode.InternalServerError)
            return@delete
        }
        fileChanges.tryEmit(ScriptFileChange(script.id, relativePath, ScriptFileChangeKind.DELETED))
        call.respondText("OK")
    }

    post("/scripts/{id}/mkdir/{path...}") {
        val script = call.parameters["id"]?.let { id -> ScriptStore.scan(scriptsRoot).find { it.id == id } }
        if (script == null) {
            call.respondText("Script not found", status = HttpStatusCode.NotFound)
            return@post
        }
        val relativePath = call.filePathParam()
        val target = SafePath.resolve(script.dir, relativePath)
        if (target == null || relativePath.isBlank()) {
            call.respondText("Invalid path", status = HttpStatusCode.BadRequest)
            return@post
        }
        if (target.isFile) {
            call.respondText("Path already exists as a file", status = HttpStatusCode.Conflict)
            return@post
        }
        val existed = target.isDirectory
        target.mkdirs()
        if (!existed) {
            fileChanges.tryEmit(ScriptFileChange(script.id, relativePath, ScriptFileChangeKind.CREATED))
        }
        call.respondText("OK")
    }

    // body 是 JSON {"from": "...", "to": "...", "overwrite": false}——跟 mkdir/files 不同，
    // 來源與目的地都是欄位，不適合塞進路徑本身。
    post("/scripts/{id}/rename") {
        val script = call.parameters["id"]?.let { id -> ScriptStore.scan(scriptsRoot).find { it.id == id } }
        if (script == null) {
            call.respondText("Script not found", status = HttpStatusCode.NotFound)
            return@post
        }
        val body = runCatching { Json.decodeFromString<RenameRequest>(call.receiveText()) }.getOrNull()
        if (body == null || body.from.isBlank() || body.to.isBlank()) {
            call.respondText("Invalid request body", status = HttpStatusCode.BadRequest)
            return@post
        }
        val source = SafePath.resolve(script.dir, body.from)
        val destination = SafePath.resolve(script.dir, body.to)
        if (source == null || destination == null || !source.exists()) {
            call.respondText("Invalid rename request", status = HttpStatusCode.BadRequest)
            return@post
        }
        if (destination.exists() && !body.overwrite) {
            call.respondText("Destination already exists", status = HttpStatusCode.Conflict)
            return@post
        }
        destination.parentFile?.mkdirs()
        if (destination.exists()) destination.deleteRecursively()
        if (!source.renameTo(destination)) {
            call.respondText("Failed to rename", status = HttpStatusCode.InternalServerError)
            return@post
        }
        fileChanges.tryEmit(ScriptFileChange(script.id, body.from, ScriptFileChangeKind.DELETED))
        fileChanges.tryEmit(ScriptFileChange(script.id, body.to, ScriptFileChangeKind.CREATED))
        call.respondText("OK")
    }

    // 觸發執行（見 #61）：統一經過既有 ScriptSession，不建立第二條執行路徑——
    // 透過 VS Code 觸發跟透過 App UI 觸發，都會是同一個 ScriptSession.start()。
    post("/scripts/{id}/run") {
        val script = call.parameters["id"]?.let { id -> ScriptStore.scan(scriptsRoot).find { it.id == id } }
        if (script == null) {
            call.respondText("Script not found", status = HttpStatusCode.NotFound)
            return@post
        }
        if (scriptRunner.isRunning()) {
            call.respondText("A script is already running", status = HttpStatusCode.Conflict)
            return@post
        }
        scriptRunner.start(script)
        call.respondText("Started", status = HttpStatusCode.Accepted)
    }

    // 停止目前執行中的腳本（見 vision-test 合約）：一般腳本、vision-test 都吃同一個
    // ScriptRunner slot，這裡不分是哪一種，統一停。沒有腳本在跑時呼叫也不報錯——
    // ScriptSession.stop() 本來就是安全的 no-op。
    post("/run/stop") {
        scriptRunner.stop()
        call.respondText("Stopped")
    }
}
