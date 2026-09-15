package com.xaxaxax.relc.workbench

import com.xaxaxax.relc.script.Script
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
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
import relc.workbench.StreamEvent

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

    private fun scriptFolder(id: String, mainLua: String): File {
        val dir = temp.newFolder(id)
        File(dir, "main.lua").writeText(mainLua)
        return dir
    }

    private fun zipOf(vararg entries: Pair<String, String>): ByteArray {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return bytes.toByteArray()
    }

    private fun unzip(bytes: ByteArray): Map<String, String> {
        val entries = mutableMapOf<String, String>()
        ZipInputStream(bytes.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                entries[entry.name] = zip.readBytes().decodeToString()
                entry = zip.nextEntry
            }
        }
        return entries
    }

    @Test
    fun `health route returns 200`() = runTest {
        testApplication {
            application { workbenchModule(temp.root, fakeRunner, fakeStream) }

            val response = client.get("/health")

            assertEquals(HttpStatusCode.OK, response.status)
        }
    }

    @Test
    fun `websocket route echoes back what it receives`() = runTest {
        testApplication {
            application { workbenchModule(temp.root, fakeRunner, fakeStream) }
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
            application { workbenchModule(temp.root, fakeRunner, fakeStream) }
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
            application { workbenchModule(temp.root, fakeRunner, fakeStream) }
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
            application { workbenchModule(temp.root, fakeRunner, fakeStream) }
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
            application { workbenchModule(temp.root, fakeRunner, fakeStream) }
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
            application { workbenchModule(temp.root, fakeRunner, fakeStream) }
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
            application { workbenchModule(temp.root, fakeRunner, fakeStream) }

            val response = client.get("/scripts")

            assertEquals(HttpStatusCode.OK, response.status)
            assertTrue(response.bodyAsText().contains("\"id\":\"hello\""))
        }
    }

    @Test
    fun `export route returns a zip of the script folder`() = runTest {
        val dir = scriptFolder("hello", "log('hi')")
        File(dir, "template.png").writeText("fake image bytes")

        testApplication {
            application { workbenchModule(temp.root, fakeRunner, fakeStream) }

            val response = client.get("/scripts/hello/export")

            assertEquals(HttpStatusCode.OK, response.status)
            val entries = unzip(response.bodyAsBytes())
            assertEquals("log('hi')", entries["main.lua"])
            assertEquals("fake image bytes", entries["template.png"])
        }
    }

    @Test
    fun `export route 404s for an unknown script id`() = runTest {
        testApplication {
            application { workbenchModule(temp.root, fakeRunner, fakeStream) }

            val response = client.get("/scripts/does-not-exist/export")

            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    @Test
    fun `import route overwrites the existing script folder in place`() = runTest {
        val dir = scriptFolder("hello", "log('old')")
        File(dir, "old.png").writeText("stale")

        testApplication {
            application { workbenchModule(temp.root, fakeRunner, fakeStream) }

            val response = client.put("/scripts/hello/import") {
                setBody(zipOf("main.lua" to "log('new')"))
            }

            assertEquals(HttpStatusCode.OK, response.status)
            assertEquals("log('new')", File(dir, "main.lua").readText())
            assertTrue(!File(dir, "old.png").exists())
        }
    }

    @Test
    fun `import route rejects an archive with no main lua`() = runTest {
        testApplication {
            application { workbenchModule(temp.root, fakeRunner, fakeStream) }

            val response = client.put("/scripts/hello/import") {
                setBody(zipOf("readme.txt" to "nothing here"))
            }

            assertEquals(HttpStatusCode.BadRequest, response.status)
        }
    }

    @Test
    fun `run route starts the script through the existing runner`() = runTest {
        scriptFolder("hello", "log('hi')")
        testApplication {
            application { workbenchModule(temp.root, fakeRunner, fakeStream) }

            val response = client.post("/scripts/hello/run")

            assertEquals(HttpStatusCode.Accepted, response.status)
            assertEquals("hello", fakeRunner.startedScript?.id)
        }
    }

    @Test
    fun `run route 404s for an unknown script id`() = runTest {
        testApplication {
            application { workbenchModule(temp.root, fakeRunner, fakeStream) }

            val response = client.post("/scripts/does-not-exist/run")

            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    @Test
    fun `run route rejects when a script is already running`() = runTest {
        scriptFolder("hello", "log('hi')")
        fakeRunner.running = true
        testApplication {
            application { workbenchModule(temp.root, fakeRunner, fakeStream) }

            val response = client.post("/scripts/hello/run")

            assertEquals(HttpStatusCode.Conflict, response.status)
            assertEquals(null, fakeRunner.startedScript)
        }
    }
}
