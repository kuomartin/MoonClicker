package com.xaxaxax.moonclicker.workbench

import com.xaxaxax.moonclicker.script.Script
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
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import moonclicker.workbench.StreamEvent

/** [ScriptSession] 是 Hilt @Singleton、沒有現成的假實作——測試只需要 [ScriptRunner] 這個縮小介面。 */
private class FakeScriptRunner : ScriptRunner {
    var running = false
    var startedScript: Script? = null

    override fun isRunning() = running

    override fun start(script: Script) {
        startedScript = script
    }
}

/** [ScriptEngine] 是全域單例、只有真的原生引擎在跑才會發事件——測試改用可控制的假 flow。 */
private class FakeScriptStream : ScriptStream {
    private val _sharedData = MutableStateFlow<Map<String, Any>>(emptyMap())
    override val sharedData: StateFlow<Map<String, Any>> = _sharedData
    override val logLines: Flow<String> get() = _logLines
    private val _logLines = MutableSharedFlow<String>(extraBufferCapacity = 64)

    fun setData(data: Map<String, Any>) {
        _sharedData.value = data
    }

    fun emitLog(line: String) {
        _logLines.tryEmit(line)
    }
}

private class FakeDisplaySource : DisplaySource {
    var displaysToReturn: List<WorkbenchDisplaySummary>? = null
    var lastToggle: Pair<Int, Boolean>? = null
    var toggleResult: Boolean = true

    override fun getDisplays(): List<WorkbenchDisplaySummary>? = displaysToReturn

    override fun toggleMirror(displayId: Int, enable: Boolean): Boolean {
        lastToggle = displayId to enable
        return toggleResult
    }
}

/** 收一筆二進位 frame，解碼成 [StreamEvent]——跟 WorkbenchServer.kt 產生它用的是同一份 schema。 */
private suspend fun DefaultClientWebSocketSession.receiveStreamEvent(): StreamEvent {
    val frame = incoming.receive() as Frame.Binary
    return StreamEvent.ADAPTER.decode(frame.data)
}

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

            client.webSocket("/") {
                // 連上就會先收到一筆 data 快照（見 #62）——跳過它才是這裡要驗的 echo。
                incoming.receive()

                send(Frame.Text("hello"))
                val reply = incoming.receive() as Frame.Text
                assertEquals("hello", reply.readText())
            }
        }
    }

    @Test
    fun `websocket connect immediately sends the current data snapshot`() = runTest {
        fakeStream.setData(mapOf("status" to "claimed", "count" to 3.0))
        testApplication {
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeDisplays) }
            val client = createClient { install(ClientWebSockets) }

            client.webSocket("/") {
                val event = receiveStreamEvent()

                assertEquals("claimed", event.data_?.get("status"))
            }
        }
    }

    @Test
    fun `websocket streams log lines as they happen`() = runTest {
        testApplication {
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeDisplays) }
            val client = createClient { install(ClientWebSockets) }

            client.webSocket("/") {
                receiveStreamEvent() // 初始 data 快照，見上一個測試。

                fakeStream.emitLog("hello from script")

                val event = receiveStreamEvent()
                assertEquals("hello from script", event.log)
                assertNull(event.data_)
            }
        }
    }

    @Test
    fun `websocket streams data updates as they happen`() = runTest {
        testApplication {
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeDisplays) }
            val client = createClient { install(ClientWebSockets) }

            client.webSocket("/") {
                receiveStreamEvent() // 初始（空）data 快照。

                fakeStream.setData(mapOf("progress" to 0.5))

                val event = receiveStreamEvent()
                assertEquals(0.5, event.data_?.get("progress"))
            }
        }
    }

    @Test
    fun `data values outside Double, String or Boolean are stringified instead of crashing the session`() = runTest {
        // sharedData 的型別是 Map<String, Any>，Double/String/Boolean 只是註解上的約定，
        // 型別系統不保證；Wire 的 Struct 編碼遇到其他型別會丟例外，這裡驗證不會讓整個
        // WebSocket session 崩潰。
        fakeStream.setData(mapOf("count" to 3))
        testApplication {
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeDisplays) }
            val client = createClient { install(ClientWebSockets) }

            client.webSocket("/") {
                val event = receiveStreamEvent()

                assertEquals("3", event.data_?.get("count"))
            }
        }
    }

    @Test
    fun `reconnecting resends the full current data snapshot, not history`() = runTest {
        testApplication {
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeDisplays) }
            val client = createClient { install(ClientWebSockets) }

            client.webSocket("/") {
                receiveStreamEvent() // 第一次連線的初始快照。
                fakeStream.setData(mapOf("status" to "claimed"))
                receiveStreamEvent() // 消費掉這次變動的推播，模擬「這行資料在斷線前已經送過」。
            }

            // 重新連線：不需要重播任何歷史，新連線一開始就該看到目前的完整快照。
            client.webSocket("/") {
                val event = receiveStreamEvent()
                assertEquals("claimed", event.data_?.get("status"))
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

            wsClient.webSocket("/") {
                receiveStreamEvent() // 初始 data 快照。

                client.put("/scripts/hello/files/main.lua") { setBody("log('new')") }

                val event = receiveStreamEvent()
                assertEquals("hello", event.file_change?.script_id)
                assertEquals("main.lua", event.file_change?.path)
                assertEquals(moonclicker.workbench.FileChangeEvent.Kind.CHANGED, event.file_change?.kind)
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
    fun `run route rejects when a script is already running`() = runTest {
        scriptFolder("hello", "log('hi')")
        fakeRunner.running = true
        testApplication {
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeDisplays) }

            val response = client.post("/scripts/hello/run")

            assertEquals(HttpStatusCode.Conflict, response.status)
            assertEquals(null, fakeRunner.startedScript)
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
}
