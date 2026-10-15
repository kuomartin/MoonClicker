package com.xaxaxax.moonclicker.workbench

import com.xaxaxax.moonclicker.script.Script
import com.xaxaxax.moonclicker.script.ScriptLogLine
import com.xaxaxax.moonclicker.script.ScriptStartOutcome
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import moonclicker.workbench.StreamEvent

/** [ScriptSession] 是 Hilt @Singleton、沒有現成的假實作——測試只需要 [ScriptRunner] 這個縮小介面。 */
private class FakeScriptRunner : ScriptRunner {
    val running = mutableSetOf<String>()
    var startedScript: Script? = null
    var stoppedScriptId: String? = "not stopped"
    var startedOnDisplayScript: Script? = null
    var startedOnDisplayId: Int? = null
    var outcome: ScriptStartOutcome = ScriptStartOutcome.Started

    override fun isRunning(scriptId: String) = scriptId in running

    override suspend fun start(script: Script): ScriptStartOutcome {
        startedScript = script
        return outcome
    }

    override suspend fun startOnDisplay(script: Script, displayId: Int): ScriptStartOutcome {
        startedOnDisplayScript = script
        startedOnDisplayId = displayId
        return outcome
    }

    override suspend fun stop(scriptId: String?) {
        stoppedScriptId = scriptId
    }
}

/** 只有真的原生引擎在跑才會發事件——測試改用可控制的假 flow。 */
private class FakeScriptStream : ScriptStream {
    private val _sharedData = MutableStateFlow<Map<String, Map<String, Any>>>(emptyMap())
    override val sharedData: Flow<Map<String, Map<String, Any>>> = _sharedData
    override val logLines: Flow<ScriptLogLine> get() = _logLines
    private val _logLines = MutableSharedFlow<ScriptLogLine>(extraBufferCapacity = 64)
    val running = MutableStateFlow<List<String>>(emptyList())
    override val runningScriptIds: Flow<List<String>> get() = running

    fun setData(scriptId: String, data: Map<String, Any>) {
        _sharedData.value = _sharedData.value + (scriptId to data)
    }

    fun emitLog(scriptId: String, line: String) {
        _logLines.tryEmit(ScriptLogLine(scriptId, line))
    }
}

private class FakeDisplaySource : DisplaySource {
    var displaysToReturn: List<WorkbenchDisplaySummary>? = null
    var lastToggle: Pair<Int, Boolean>? = null
    var toggleResult: Boolean = true

    override suspend fun getDisplays(): List<WorkbenchDisplaySummary>? = displaysToReturn

    override suspend fun toggleMirror(displayId: Int, enable: Boolean): Boolean {
        lastToggle = displayId to enable
        return toggleResult
    }
}

/** 收一筆二進位 frame，解碼成 [StreamEvent]——跟 WorkbenchServer.kt 產生它用的是同一份 schema。 */
private suspend fun DefaultClientWebSocketSession.receiveStreamEvent(): StreamEvent {
    val frame = incoming.receive() as Frame.Binary
    return StreamEvent.ADAPTER.decode(frame.data)
}

/** 略過其他種類的事件（連線時各種快照的先後不固定），收到符合 [predicate] 的那一筆。 */
private suspend fun DefaultClientWebSocketSession.receiveStreamEvent(predicate: (StreamEvent) -> Boolean): StreamEvent {
    while (true) {
        val event = receiveStreamEvent()
        if (predicate(event)) return event
    }
}

private const val STREAM = "/?protocol=3"

class WorkbenchServerTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val fakeRunner = FakeScriptRunner()
    private val fakeStream = FakeScriptStream()
    private val fakeDisplays = FakeDisplaySource().apply {
        displaysToReturn = listOf(WorkbenchDisplaySummary(0, "Physical Display", 1080, 1920, false))
    }

    private fun scriptFolder(id: String, mainLua: String): File {
        val dir = temp.newFolder(id)
        File(dir, "main.lua").writeText(mainLua)
        return dir
    }

    @Test
    fun `health route returns 200`() = runTest {
        testApplication {
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeDisplays) }

            val response = client.get("/health")

            assertEquals(HttpStatusCode.OK, response.status)
        }
    }

    @Test
    fun `displays route returns list of displays`() = runTest {
        testApplication {
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeDisplays) }

            val response = client.get("/displays")

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("\"Physical Display\""))
            assertTrue(response.bodyAsText().contains("\"width\":1080"))
        }
    }

    @Test
    fun `displays route returns 503 if service is disconnected`() = runTest {
        fakeDisplays.displaysToReturn = null
        testApplication {
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeDisplays) }

            val response = client.get("/displays")

            assertEquals(HttpStatusCode.ServiceUnavailable, response.status)
        }
    }

    @Test
    fun `mirror control route toggles mirror via displaySource`() = runTest {
        testApplication {
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeDisplays) }

            val responseEnable = client.post("/displays/0/mirror?enable=true")
            assertEquals(HttpStatusCode.OK, responseEnable.status)
            assertEquals("Mirror acquired", responseEnable.bodyAsText())
            assertEquals(0 to true, fakeDisplays.lastToggle)

            val responseDisable = client.post("/displays/0/mirror?enable=false")
            assertEquals(HttpStatusCode.OK, responseDisable.status)
            assertEquals("Mirror released", responseDisable.bodyAsText())
            assertEquals(0 to false, fakeDisplays.lastToggle)
        }
    }

    @Test
    fun `mirror control route returns 500 when displaySource fails`() = runTest {
        fakeDisplays.toggleResult = false
        testApplication {
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeDisplays) }

            val response = client.post("/displays/0/mirror?enable=true")
            assertEquals(HttpStatusCode.InternalServerError, response.status)
            assertEquals("Failed to update mirror", response.bodyAsText())
        }
    }

    @Test
    fun `websocket route echoes back what it receives`() = runTest {
        testApplication {
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeDisplays) }
            val client = createClient { install(ClientWebSockets) }

            client.webSocket(STREAM) {
                send(Frame.Text("hello"))
                var reply = incoming.receive()
                // 連上就會先收到 run state（見 #62）——跳過它才是這裡要驗的 echo。
                while (reply !is Frame.Text) reply = incoming.receive()
                assertEquals("hello", reply.readText())
            }
        }
    }

    @Test
    fun `websocket closes a client speaking another protocol version`() = runTest {
        testApplication {
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeDisplays) }
            val client = createClient { install(ClientWebSockets) }

            client.webSocket("/?protocol=1") {
                val reason = closeReason.await()

                assertEquals(CloseReason.Codes.VIOLATED_POLICY.code, reason?.code)
                assertTrue(reason?.message.orEmpty().contains("app 2"))
            }
        }
    }

    @Test
    fun `websocket connect immediately sends each script's data snapshot`() = runTest {
        fakeStream.setData("demo", mapOf("status" to "claimed", "count" to 3.0))
        testApplication {
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeDisplays) }
            val client = createClient { install(ClientWebSockets) }

            client.webSocket(STREAM) {
                val data = receiveStreamEvent { it.data_ != null }.data_!!

                assertEquals("demo", data.script_id)
                assertTrue(data.reset)
                assertEquals("claimed", data.values?.get("status"))
            }
        }
    }

    @Test
    fun `websocket sends the running scripts on connect and when they change`() = runTest {
        fakeStream.running.value = listOf("demo")
        testApplication {
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeDisplays) }
            val client = createClient { install(ClientWebSockets) }

            client.webSocket(STREAM) {
                assertEquals(listOf("demo"), receiveStreamEvent { it.run_state != null }.run_state!!.running_script_ids)

                fakeStream.running.value = listOf("demo", "other")

                assertEquals(
                    listOf("demo", "other"),
                    receiveStreamEvent { it.run_state != null }.run_state!!.running_script_ids,
                )
            }
        }
    }

    @Test
    fun `websocket streams log lines tagged with their script`() = runTest {
        testApplication {
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeDisplays) }
            val client = createClient { install(ClientWebSockets) }

            client.webSocket(STREAM) {
                receiveStreamEvent { it.run_state != null } // 連上時的 run state，表示串流已經接好。

                fakeStream.emitLog("demo", "hello from script")

                val log = receiveStreamEvent { it.log != null }.log!!
                assertEquals("demo", log.script_id)
                assertEquals("hello from script", log.line)
            }
        }
    }

    @Test
    fun `websocket sends only what changed in data after the snapshot`() = runTest {
        fakeStream.setData("demo", mapOf("progress" to 0.25, "status" to "busy", "stale" to true))
        testApplication {
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeDisplays) }
            val client = createClient { install(ClientWebSockets) }

            client.webSocket(STREAM) {
                receiveStreamEvent { it.data_ != null } // reset 快照。

                fakeStream.setData("demo", mapOf("progress" to 0.5, "status" to "busy"))

                val diff = receiveStreamEvent { it.data_ != null }.data_!!
                assertFalse(diff.reset)
                assertEquals(mapOf("progress" to 0.5), diff.values)
                assertEquals(listOf("stale"), diff.removed)
            }
        }
    }

    @Test
    fun `data of different scripts is sent separately`() = runTest {
        fakeStream.setData("a", mapOf("n" to 1.0))
        testApplication {
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeDisplays) }
            val client = createClient { install(ClientWebSockets) }

            client.webSocket(STREAM) {
                receiveStreamEvent { it.data_?.script_id == "a" }

                fakeStream.setData("b", mapOf("n" to 2.0))

                val b = receiveStreamEvent { it.data_ != null }.data_!!
                assertEquals("b", b.script_id)
                assertTrue(b.reset)
                assertEquals(2.0, b.values?.get("n"))
            }
        }
    }

    @Test
    fun `data values outside Double, String or Boolean are stringified instead of crashing the session`() = runTest {
        // sharedData 的型別是 Map<String, Any>，Double/String/Boolean 只是註解上的約定，
        // 型別系統不保證；Wire 的 Struct 編碼遇到其他型別會丟例外，這裡驗證不會讓整個
        // WebSocket session 崩潰。
        fakeStream.setData("demo", mapOf("count" to 3))
        testApplication {
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeDisplays) }
            val client = createClient { install(ClientWebSockets) }

            client.webSocket(STREAM) {
                val event = receiveStreamEvent { it.data_ != null }

                assertEquals("3", event.data_?.values?.get("count"))
            }
        }
    }

    @Test
    fun `reconnecting resends the full current data snapshot, not history`() = runTest {
        testApplication {
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeDisplays) }
            val client = createClient { install(ClientWebSockets) }

            client.webSocket(STREAM) {
                receiveStreamEvent { it.run_state != null }
                fakeStream.setData("demo", mapOf("status" to "claimed"))
                receiveStreamEvent { it.data_ != null } // 模擬「這筆資料在斷線前已經送過」。
            }

            // 重新連線：不需要重播任何歷史，新連線一開始就該看到目前的完整快照。
            client.webSocket(STREAM) {
                val event = receiveStreamEvent { it.data_ != null }
                assertTrue(event.data_!!.reset)
                assertEquals("claimed", event.data_?.values?.get("status"))
            }
        }
    }

    @Test
    fun `scripts route lists script folders on device`() = runTest {
        scriptFolder("hello", "log('hi')")
        testApplication {
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeDisplays) }

            val response = client.get("/scripts")

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("\"id\":\"hello\""))
        }
    }

    @Test
    fun `scripts route includes template count and last-modified time`() = runTest {
        val dir = scriptFolder("hello", "log('hi')")
        File(dir, "templates.json").writeText(
            """{"button.png":{"roi":{"x":1,"y":2,"w":3,"h":4}},"other.png":{"roi":{"x":1,"y":2,"w":3,"h":4}}}"""
        )
        testApplication {
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeDisplays) }

            val response = client.get("/scripts")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"templateCount\":2"))
            assertTrue(File(dir, "main.lua").lastModified() > 0)
        }
    }

    @Test
    fun `POST scripts creates a runnable script folder`() = runTest {
        testApplication {
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeDisplays) }

            val response = client.post("/scripts/my_new_script")

            assertEquals(HttpStatusCode.Created, response.status)
            val dir = File(temp.root, "my_new_script")
            assertTrue(File(dir, "main.lua").isFile)
            val metaJson = File(dir, "script.json").readText()
            assertTrue(metaJson.contains("\"uniqueId\":\"my_new_script\""))
        }
    }

    @Test
    fun `POST scripts rejects an id that already exists with 409`() = runTest {
        scriptFolder("hello", "log('hi')")
        testApplication {
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeDisplays) }

            val response = client.post("/scripts/hello")

            assertEquals(HttpStatusCode.Conflict, response.status)
        }
    }

    @Test
    fun `POST scripts rejects an id with characters outside the uniqueId pattern`() = runTest {
        testApplication {
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeDisplays) }

            val response = client.post("/scripts/Not-Lowercase")

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(!File(temp.root, "Not-Lowercase").exists())
        }
    }

    @Test
    fun `POST scripts rejects an id starting with a dot`() = runTest {
        testApplication {
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeDisplays) }

            val response = client.post("/scripts/.hidden")

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `a newly created script does not show up until it's scanned again, then lists with zero templates`() = runTest {
        testApplication {
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeDisplays) }

            client.post("/scripts/my_new_script")
            val response = client.get("/scripts")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"id\":\"my_new_script\""))
            assertTrue(body.contains("\"templateCount\":0"))
        }
    }

    @Test
    fun `tree route lists every file recursively with directories flagged`() = runTest {
        val dir = scriptFolder("hello", "log('hi')")
        File(dir, "assets").mkdirs()
        File(dir, "assets/template.png").writeText("fake image bytes")

        testApplication {
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeDisplays) }

            val response = client.get("/scripts/hello/tree")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"path\":\"main.lua\""))
            assertTrue(body.contains("\"path\":\"assets\""))
            assertTrue(body.contains("\"isDirectory\":true"))
            assertTrue(body.contains("\"path\":\"assets/template.png\""))
        }
    }

    @Test
    fun `tree route reports sha256 for files and an empty hash for directories`() = runTest {
        val dir = scriptFolder("hello", "log('hi')")
        File(dir, "assets").mkdirs()

        testApplication {
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeDisplays) }

            val response = client.get("/scripts/hello/tree")

            val body = response.bodyAsText()
            val expectedHash = java.security.MessageDigest.getInstance("SHA-256")
                .digest("log('hi')".toByteArray())
                .joinToString("") { "%02x".format(it) }
            assertTrue(body.contains("\"sha256\":\"$expectedHash\""))
            assertTrue(Regex("""\{"path":"assets","size":0,"mtimeMs":\d+,"isDirectory":true,"sha256":""}""").containsMatchIn(body))
        }
    }

    @Test
    fun `tree route 404s for an unknown script id`() = runTest {
        testApplication {
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeDisplays) }

            val response = client.get("/scripts/does-not-exist/tree")

            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    @Test
    fun `files route reads a single file's raw bytes`() = runTest {
        scriptFolder("hello", "log('hi')")

        testApplication {
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeDisplays) }

            val response = client.get("/scripts/hello/files/main.lua")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("log('hi')", response.bodyAsText())
        }
    }

    @Test
    fun `files route 404s for a missing file`() = runTest {
        scriptFolder("hello", "log('hi')")
        testApplication {
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeDisplays) }

            val response = client.get("/scripts/hello/files/nope.lua")

            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    @Test
    fun `files route rejects a path that escapes the script folder`() = runTest {
        scriptFolder("hello", "log('hi')")
        testApplication {
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeDisplays) }

            val response = client.get("/scripts/hello/files/../secret.txt")

            assertTrue(response.status != HttpStatusCode.OK)
        }
    }

    @Test
    fun `PUT files route creates a new file including intermediate directories`() = runTest {
        val dir = scriptFolder("hello", "log('hi')")

        testApplication {
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeDisplays) }

            val response = client.put("/scripts/hello/files/lib/util.lua") {
                setBody("return 1")
            }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("return 1", File(dir, "lib/util.lua").readText())
        }
    }

    @Test
    fun `PUT files route overwrites an existing file in place`() = runTest {
        val dir = scriptFolder("hello", "log('old')")

        testApplication {
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeDisplays) }

            val response = client.put("/scripts/hello/files/main.lua") {
                setBody("log('new')")
            }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("log('new')", File(dir, "main.lua").readText())
        }
    }

    @Test
    fun `DELETE files route removes a file`() = runTest {
        val dir = scriptFolder("hello", "log('hi')")
        File(dir, "old.png").writeText("stale")

        testApplication {
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeDisplays) }

            val response = client.delete("/scripts/hello/files/old.png")

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(!File(dir, "old.png").exists())
        }
    }

    @Test
    fun `DELETE files route 404s for a missing file`() = runTest {
        scriptFolder("hello", "log('hi')")
        testApplication {
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeDisplays) }

            val response = client.delete("/scripts/hello/files/nope.lua")

            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    @Test
    fun `DELETE scripts removes the whole script folder`() = runTest {
        scriptFolder("hello", "log('hi')")
        testApplication {
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeDisplays) }

            val response = client.delete("/scripts/hello")

            assertEquals(HttpStatusCode.OK, response.status)
            assertFalse(File(temp.root, "hello").exists())
        }
    }

    @Test
    fun `DELETE scripts refuses while that script is running`() = runTest {
        scriptFolder("hello", "log('hi')")
        fakeRunner.running += "hello"
        testApplication {
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeDisplays) }

            val response = client.delete("/scripts/hello")

            assertEquals(HttpStatusCode.Conflict, response.status)
            assertTrue(File(temp.root, "hello/main.lua").isFile)
        }
    }

    @Test
    fun `DELETE scripts 404s for an unknown script`() = runTest {
        testApplication {
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeDisplays) }

            assertEquals(HttpStatusCode.NotFound, client.delete("/scripts/nope").status)
        }
    }

    @Test
    fun `device route returns the instance id and name`() = runTest {
        testApplication {
            application {
                workbenchModule(
                    temp.root, fakeRunner, fakeStream, fakeDisplays,
                    deviceInfo = { WorkbenchDeviceInfo("abc", "Pixel", WORKBENCH_PROTOCOL_VERSION) },
                )
            }

            val body = client.get("/device").bodyAsText()

            assertTrue(body.contains("\"id\":\"abc\""))
            assertTrue(body.contains("\"name\":\"Pixel\""))
            assertTrue(body.contains("\"protocolVersion\":3"))
        }
    }

    @Test
    fun `mkdir route creates an empty directory`() = runTest {
        val dir = scriptFolder("hello", "log('hi')")
        testApplication {
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeDisplays) }

            val response = client.post("/scripts/hello/mkdir/assets")

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(File(dir, "assets").isDirectory)
        }
    }

    @Test
    fun `mkdir route conflicts when the path already exists as a file`() = runTest {
        scriptFolder("hello", "log('hi')")
        testApplication {
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeDisplays) }

            val response = client.post("/scripts/hello/mkdir/main.lua")

            assertEquals(HttpStatusCode.Conflict, response.status)
        }
    }

    @Test
    fun `rename route moves a file within the script folder`() = runTest {
        val dir = scriptFolder("hello", "log('hi')")
        File(dir, "old.lua").writeText("return 1")

        testApplication {
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeDisplays) }

            val response = client.post("/scripts/hello/rename") {
                setBody("""{"from":"old.lua","to":"new.lua"}""")
            }

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(!File(dir, "old.lua").exists())
            assertEquals("return 1", File(dir, "new.lua").readText())
        }
    }

    @Test
    fun `rename route conflicts when destination exists and overwrite is not set`() = runTest {
        val dir = scriptFolder("hello", "log('hi')")
        File(dir, "old.lua").writeText("return 1")
        File(dir, "new.lua").writeText("return 2")

        testApplication {
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeDisplays) }

            val response = client.post("/scripts/hello/rename") {
                setBody("""{"from":"old.lua","to":"new.lua"}""")
            }

            assertEquals(HttpStatusCode.Conflict, response.status)
        }
    }

    @Test
    fun `writing a file over the websocket connection broadcasts a file_change event`() = runTest {
        scriptFolder("hello", "log('hi')")
        testApplication {
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeDisplays) }
            val wsClient = createClient { install(ClientWebSockets) }

            wsClient.webSocket(STREAM) {
                receiveStreamEvent { it.run_state != null } // 連上時的 run state。

                client.put("/scripts/hello/files/main.lua") { setBody("log('new')") }

                val event = receiveStreamEvent()
                assertEquals("hello", event.file_change?.script_id)
                assertEquals("main.lua", event.file_change?.path)
                assertEquals(moonclicker.workbench.FileChangeEvent.Kind.CHANGED, event.file_change?.kind)
            }
        }
    }

    @Test
    fun `saving a template over the websocket connection broadcasts file_change for both the image and templates json`() = runTest {
        scriptFolder("hello", "log('hi')")
        testApplication {
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeDisplays) }
            val wsClient = createClient { install(ClientWebSockets) }

            wsClient.webSocket(STREAM) {
                receiveStreamEvent { it.run_state != null } // 連上時的 run state。

                client.put("/scripts/hello/templates/button.png?x=1&y=2&w=3&h=4") {
                    setBody("fake png bytes".toByteArray())
                }

                val first = receiveStreamEvent()
                assertEquals("hello", first.file_change?.script_id)
                assertEquals("button.png", first.file_change?.path)
                assertEquals(moonclicker.workbench.FileChangeEvent.Kind.CREATED, first.file_change?.kind)

                val second = receiveStreamEvent()
                assertEquals("templates.json", second.file_change?.path)
                assertEquals(moonclicker.workbench.FileChangeEvent.Kind.CHANGED, second.file_change?.kind)
            }
        }
    }

    @Test
    fun `run route starts the script through the existing runner`() = runTest {
        scriptFolder("hello", "log('hi')")
        testApplication {
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeDisplays) }

            val response = client.post("/scripts/hello/run")

            assertEquals(HttpStatusCode.Accepted, response.status)
            assertEquals("hello", response.bodyAsText())
            assertEquals("hello", fakeRunner.startedScript?.id)
        }
    }

    @Test
    fun `run route 404s for an unknown script id`() = runTest {
        testApplication {
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeDisplays) }

            val response = client.post("/scripts/does-not-exist/run")

            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    @Test
    fun `run route 409s with the occupying script when the target display is busy`() = runTest {
        scriptFolder("hello", "log('hi')")
        fakeRunner.outcome = ScriptStartOutcome.DisplayBusy(displayId = 3, scriptId = "other")
        testApplication {
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeDisplays) }

            val response = client.post("/scripts/hello/run")

            assertEquals(HttpStatusCode.Conflict, response.status)
            assertTrue(response.bodyAsText().contains("other"))
        }
    }

    @Test
    fun `run route 409s when that script is already running`() = runTest {
        scriptFolder("hello", "log('hi')")
        fakeRunner.outcome = ScriptStartOutcome.AlreadyRunning
        testApplication {
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeDisplays) }

            val response = client.post("/scripts/hello/run")

            assertEquals(HttpStatusCode.Conflict, response.status)
        }
    }

    @Test
    fun `templates route writes the template image and roi into templates json`() = runTest {
        val dir = scriptFolder("hello", "log('hi')")
        testApplication {
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeDisplays) }

            val response = client.put("/scripts/hello/templates/button.png?x=1&y=2&w=30&h=40") {
                setBody("fake png bytes".toByteArray())
            }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("fake png bytes", File(dir, "button.png").readText())
            val templatesJson = File(dir, "templates.json").readText()
            assertTrue(templatesJson.contains("\"button.png\""))
            assertTrue(templatesJson.contains("\"x\": 1"))
            assertTrue(templatesJson.contains("\"h\": 40"))
        }
    }

    @Test
    fun `templates route rejects a name that already exists with 409`() = runTest {
        val dir = scriptFolder("hello", "log('hi')")
        File(dir, "button.png").writeText("existing")
        testApplication {
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeDisplays) }

            val response = client.put("/scripts/hello/templates/button.png?x=1&y=2&w=3&h=4") {
                setBody("new png bytes".toByteArray())
            }

            assertEquals(HttpStatusCode.Conflict, response.status)
            assertEquals("existing", File(dir, "button.png").readText())
        }
    }

    @Test
    fun `templates route 404s for an unknown script id`() = runTest {
        testApplication {
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeDisplays) }

            val response = client.put("/scripts/does-not-exist/templates/button.png?x=1&y=2&w=3&h=4") {
                setBody("png bytes".toByteArray())
            }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    @Test
    fun `templates route rejects when roi query params are missing or invalid`() = runTest {
        scriptFolder("hello", "log('hi')")
        testApplication {
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeDisplays) }

            val response = client.put("/scripts/hello/templates/button.png?x=1&y=2&w=3") {
                setBody("png bytes".toByteArray())
            }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `templates route rejects when existing templates json cannot be parsed`() = runTest {
        val dir = scriptFolder("hello", "log('hi')")
        File(dir, "templates.json").writeText("not valid json")
        testApplication {
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeDisplays) }

            val response = client.put("/scripts/hello/templates/button.png?x=1&y=2&w=3&h=4") {
                setBody("png bytes".toByteArray())
            }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(!File(dir, "button.png").exists())
        }
    }

    @Test
    fun `GET templates lists what's in templates json`() = runTest {
        val dir = scriptFolder("hello", "log('hi')")
        File(dir, "templates.json").writeText(
            """{"button.png":{"roi":{"x":1,"y":2,"w":30,"h":40}}}"""
        )
        testApplication {
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeDisplays) }

            val response = client.get("/scripts/hello/templates")

            assertEquals(HttpStatusCode.OK, response.status)
            val body = response.bodyAsText()
            assertTrue(body.contains("\"button.png\""))
            assertTrue(body.contains("\"w\":30"))
        }
    }

    @Test
    fun `GET templates is an empty object when templates json doesn't exist`() = runTest {
        scriptFolder("hello", "log('hi')")
        testApplication {
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeDisplays) }

            val response = client.get("/scripts/hello/templates")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("{}", response.bodyAsText())
        }
    }

    @Test
    fun `GET templates 404s for an unknown script id`() = runTest {
        testApplication {
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeDisplays) }

            val response = client.get("/scripts/does-not-exist/templates")

            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    @Test
    fun `DELETE templates removes the image and the templates json entry`() = runTest {
        val dir = scriptFolder("hello", "log('hi')")
        File(dir, "button.png").writeText("fake png bytes")
        File(dir, "templates.json").writeText(
            """{"button.png":{"roi":{"x":1,"y":2,"w":30,"h":40}}}"""
        )
        testApplication {
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeDisplays) }

            val response = client.delete("/scripts/hello/templates/button.png")

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(!File(dir, "button.png").exists())
            assertEquals("{}", File(dir, "templates.json").readText())
        }
    }

    @Test
    fun `DELETE templates broadcasts file_change for the image and templates json`() = runTest {
        val dir = scriptFolder("hello", "log('hi')")
        File(dir, "button.png").writeText("fake png bytes")
        File(dir, "templates.json").writeText(
            """{"button.png":{"roi":{"x":1,"y":2,"w":30,"h":40}}}"""
        )
        testApplication {
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeDisplays) }
            val wsClient = createClient { install(ClientWebSockets) }

            wsClient.webSocket(STREAM) {
                receiveStreamEvent { it.run_state != null } // 連上時的 run state。

                client.delete("/scripts/hello/templates/button.png")

                val first = receiveStreamEvent()
                assertEquals("hello", first.file_change?.script_id)
                assertEquals("button.png", first.file_change?.path)
                assertEquals(moonclicker.workbench.FileChangeEvent.Kind.DELETED, first.file_change?.kind)

                val second = receiveStreamEvent()
                assertEquals("templates.json", second.file_change?.path)
                assertEquals(moonclicker.workbench.FileChangeEvent.Kind.CHANGED, second.file_change?.kind)
            }
        }
    }

    @Test
    fun `DELETE templates 404s for a name that's not in templates json`() = runTest {
        val dir = scriptFolder("hello", "log('hi')")
        File(dir, "templates.json").writeText("{}")
        testApplication {
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeDisplays) }

            val response = client.delete("/scripts/hello/templates/button.png")

            assertEquals(HttpStatusCode.NotFound, response.status)
        }
        assertTrue(File(dir, "templates.json").isFile)
    }

    @Test
    fun `DELETE templates 404s for an unknown script id`() = runTest {
        testApplication {
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeDisplays) }

            val response = client.delete("/scripts/does-not-exist/templates/button.png")

            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    @Test
    fun `vision-test route materializes a scratch script with a start signal and starts it on the given display`() = runTest {
        val dir = scriptFolder("hello", "log('hi')")
        testApplication {
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeDisplays) }

            val response = client.post("/scripts/hello/vision-test") {
                setBody("""{"displayId":7,"image":"button.png","roi":{"x":1,"y":2,"w":3,"h":4},"threshold":0.85}""")
            }

            assertEquals(HttpStatusCode.Accepted, response.status)
            assertEquals(7, fakeRunner.startedOnDisplayId)
            assertEquals(".__vision_test__", fakeRunner.startedOnDisplayScript?.id)

            val lua = File(temp.root, ".__vision_test__/main.lua").readText()
            assertTrue(lua.contains("""data.set("visionTest", { started = true })"""))
            assertTrue(lua.contains(File(dir, "button.png").absolutePath))
            assertTrue(lua.contains("threshold = 0.85"))
        }
    }

    @Test
    fun `ocr-test find materializes a text search loop and starts it on the given display`() = runTest {
        testApplication {
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeDisplays, ocrAvailable = { true }) }

            val response = client.post("/ocr-test") {
                setBody("""{"displayId":7,"mode":"find","text":"開始\"\n","exact":true,"threshold":0.9,"roi":{"x":1,"y":2,"w":3,"h":4}}""")
            }

            assertEquals(HttpStatusCode.Accepted, response.status)
            assertEquals(7, fakeRunner.startedOnDisplayId)
            assertEquals(".__ocr_test__", fakeRunner.startedOnDisplayScript?.id)
            val lua = File(temp.root, ".__ocr_test__/main.lua").readText()
            assertTrue(lua.contains("""data.set("ocrTest", { started = true })"""))
            // 引號跳脫、換行寫成 \010：一個字元都不能讓 Lua 字串提早結束或斷行。
            assertTrue(lua, lua.contains("""text = "開始\"\010","""))
            assertTrue(lua.contains("exact = true"))
            assertTrue(lua.contains("threshold = 0.9"))
            assertTrue(lua.contains("roi = { x = 1, y = 2, w = 3, h = 4 }"))
            assertTrue(lua.contains("sleep(500)"))
        }
    }

    @Test
    fun `ocr-test read and read_lines call the matching read api`() = runTest {
        testApplication {
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeDisplays, ocrAvailable = { true }) }

            client.post("/ocr-test") { setBody("""{"displayId":7,"mode":"read","roi":{"x":1,"y":2,"w":3,"h":4}}""") }
            val read = File(temp.root, ".__ocr_test__/main.lua").readText()
            assertTrue(read, read.contains("vision.read({ x = 1, y = 2, w = 3, h = 4 })"))

            client.post("/ocr-test") { setBody("""{"displayId":7,"mode":"read_lines","intervalMs":800}""") }
            val lines = File(temp.root, ".__ocr_test__/main.lua").readText()
            assertTrue(lines, lines.contains("vision.read_lines()"))
            assertTrue(lines.contains("sleep(800)"))
        }
    }

    @Test
    fun `ocr-test rejects requests missing what their mode needs`() = runTest {
        testApplication {
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeDisplays, ocrAvailable = { true }) }

            assertEquals(HttpStatusCode.BadRequest, client.post("/ocr-test") { setBody("""{"displayId":7,"mode":"read"}""") }.status)
            assertEquals(HttpStatusCode.BadRequest, client.post("/ocr-test") { setBody("""{"displayId":7,"mode":"find"}""") }.status)
            assertEquals(HttpStatusCode.BadRequest, client.post("/ocr-test") { setBody("""{"displayId":7,"mode":"guess"}""") }.status)
            assertEquals(null, fakeRunner.startedOnDisplayScript)
        }
    }

    @Test
    fun `ocr-test 412s when the OCR pack is not installed`() = runTest {
        testApplication {
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeDisplays, ocrAvailable = { false }) }

            val response = client.post("/ocr-test") { setBody("""{"displayId":7,"mode":"read_lines"}""") }

            assertEquals(HttpStatusCode.PreconditionFailed, response.status)
            assertTrue(response.bodyAsText().contains("OCR is not installed"))
            assertEquals(null, fakeRunner.startedOnDisplayScript)
        }
    }

    @Test
    fun `ocr-test 409s when an OCR test is already running`() = runTest {
        fakeRunner.running += ".__ocr_test__"
        testApplication {
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeDisplays, ocrAvailable = { true }) }

            val response = client.post("/ocr-test") { setBody("""{"displayId":7,"mode":"read_lines"}""") }

            assertEquals(HttpStatusCode.Conflict, response.status)
        }
    }

    @Test
    fun `vision-test route 409s when a vision test is already running`() = runTest {
        scriptFolder("hello", "log('hi')")
        fakeRunner.running += ".__vision_test__"
        testApplication {
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeDisplays) }

            val response = client.post("/scripts/hello/vision-test") {
                setBody("""{"displayId":7,"image":"button.png","roi":{"x":1,"y":2,"w":3,"h":4},"threshold":0.85}""")
            }

            assertEquals(HttpStatusCode.Conflict, response.status)
        }
    }

    @Test
    fun `vision-test route 404s for an unknown script id`() = runTest {
        testApplication {
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeDisplays) }

            val response = client.post("/scripts/does-not-exist/vision-test") {
                setBody("""{"displayId":7,"image":"button.png","roi":{"x":1,"y":2,"w":3,"h":4},"threshold":0.85}""")
            }

            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    @Test
    fun `run stop route stops everything and is idempotent when idle`() = runTest {
        testApplication {
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeDisplays) }

            val response = client.post("/run/stop")

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals(null, fakeRunner.stoppedScriptId)
        }
    }

    @Test
    fun `run stop route stops only the given script`() = runTest {
        testApplication {
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeDisplays) }

            client.post("/run/stop?script=hello")

            assertEquals("hello", fakeRunner.stoppedScriptId)
        }
    }
}
