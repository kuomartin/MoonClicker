package com.xaxaxax.relc.workbench

import com.xaxaxax.relc.script.Script
import io.ktor.client.plugins.websocket.DefaultClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.prepareGet
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.readFully
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertArrayEquals
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

/**
 * 假的 mirror frame 來源。[keepStreamOpen] 模擬真正鏡像串流「持續送新畫面、不會自己結束」
 * 的特性——送完固定的 fake bytes 後改成定期重送最後一幀，直到收集者被取消。這是刻意的：
 * route 偵測斷線純粹靠「寫入失敗」（見 WorkbenchServer.kt），如果 fake source 送完就掛著
 * 不再寫，伺服器永遠不會再嘗試寫入，也就永遠不會發現對面已經斷線——跟真正的鏡像串流
 * 持續送新畫面才會自然踩到斷線寫入失敗是同一件事。[activeCollectors] 讓測試觀察對應
 * displayId 目前有幾個正在收集中的 flow，斷線後應該歸零。
 */
private class FakeFrameSource(
    private val displays: Map<Int, List<ByteArray>>,
    private val keepStreamOpen: Boolean = false,
) : FrameSource {
    private val collectors = ConcurrentHashMap<Int, AtomicInteger>()

    fun activeCollectors(displayId: Int): Int = collectors[displayId]?.get() ?: 0

    override fun frames(displayId: Int): Flow<ByteArray>? {
        val frames = displays[displayId] ?: return null
        return flow {
            collectors.getOrPut(displayId) { AtomicInteger(0) }.incrementAndGet()
            try {
                frames.forEach { emit(it) }
                if (keepStreamOpen) {
                    while (true) {
                        delay(20)
                        emit(frames.last())
                    }
                }
            } finally {
                collectors.getOrPut(displayId) { AtomicInteger(0) }.decrementAndGet()
            }
        }
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
    private val fakeFrames = FakeFrameSource(displays = emptyMap())

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
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeFrames) }

            val response = client.get("/health")

            assertEquals(HttpStatusCode.OK, response.status)
        }
    }

    @Test
    fun `websocket route echoes back what it receives`() = runTest {
        testApplication {
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeFrames) }
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
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeFrames) }
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
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeFrames) }
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
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeFrames) }
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
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeFrames) }
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
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeFrames) }
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
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeFrames) }

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
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeFrames) }

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
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeFrames) }

            val response = client.get("/scripts/does-not-exist/export")

            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    @Test
    fun `import route overwrites the existing script folder in place`() = runTest {
        val dir = scriptFolder("hello", "log('old')")
        File(dir, "old.png").writeText("stale")

        testApplication {
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeFrames) }

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
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeFrames) }

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
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeFrames) }

            val response = client.post("/scripts/hello/run")

            assertEquals(HttpStatusCode.Accepted, response.status)
            assertEquals("hello", fakeRunner.startedScript?.id)
        }
    }

    @Test
    fun `run route 404s for an unknown script id`() = runTest {
        testApplication {
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeFrames) }

            val response = client.post("/scripts/does-not-exist/run")

            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    @Test
    fun `run route rejects when a script is already running`() = runTest {
        scriptFolder("hello", "log('hi')")
        fakeRunner.running = true
        testApplication {
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeFrames) }

            val response = client.post("/scripts/hello/run")

            assertEquals(HttpStatusCode.Conflict, response.status)
            assertEquals(null, fakeRunner.startedScript)
        }
    }

    @Test
    fun `templates route writes the template image and roi into templates json`() = runTest {
        val dir = scriptFolder("hello", "log('hi')")
        testApplication {
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeFrames) }

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
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeFrames) }

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
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeFrames) }

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
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeFrames) }

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
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeFrames) }

            val response = client.put("/scripts/hello/templates/button.png?x=1&y=2&w=3&h=4") {
                setBody("png bytes".toByteArray())
            }

            assertEquals(HttpStatusCode.BadRequest, response.status)
            assertTrue(!File(dir, "button.png").exists())
        }
    }

    @Test
    fun `mirror route streams fake frames as a multipart boundary response`() = runTest {
        val frame1 = "frame-one".toByteArray()
        val frame2 = "frame-two".toByteArray()
        val frames = FakeFrameSource(displays = mapOf(0 to listOf(frame1, frame2)))
        testApplication {
            application { workbenchModule(temp.root, fakeRunner, fakeStream, frames) }

            val response = client.get("/mirror/0")

            assertEquals(HttpStatusCode.OK, response.status)
            val contentType = response.headers[HttpHeaders.ContentType].orEmpty()
            assertTrue(contentType.startsWith("multipart/x-mixed-replace"))
            val boundary = contentType.substringAfter("boundary=")
            val expected = multipartFrame(boundary, frame1) + multipartFrame(boundary, frame2)
            assertArrayEquals(expected, response.bodyAsBytes())
        }
    }

    @Test
    fun `mirror route 404s for an unknown displayId`() = runTest {
        // fakeFrames 沒有註冊任何 displayId——沒有真正的擷取路徑（見 #76）之前，
        // WorkbenchServer 的正式產線也是這個狀態：一律回 404，不是遺漏。
        testApplication {
            application { workbenchModule(temp.root, fakeRunner, fakeStream, fakeFrames) }

            val response = client.get("/mirror/0")

            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    @Test
    fun `mirror route cancels the frame collection job when the client disconnects`() = runTest {
        val frame = "frame".toByteArray()
        // keepStreamOpen=true：模擬真正的鏡像串流，送完目前手上的幀後掛著不結束，
        // 逼真地重現「只有斷線才會清理」這件事，而不是巧合地自然跑完。
        val frames = FakeFrameSource(displays = mapOf(0 to listOf(frame)), keepStreamOpen = true)
        testApplication {
            application { workbenchModule(temp.root, fakeRunner, fakeStream, frames) }

            // 外層 withTimeoutOrNull 逾時會取消整段（包含底層 HTTP 連線）——這就是要驗證
            // 的「client 斷線」路徑，不另外 launch 一個 job（testApplication 內部走
            // real-time dispatcher，另開 job 容易撞上跟外層 runTest 虛擬時間對不上的問題）。
            withTimeoutOrNull(1_000) {
                client.prepareGet("/mirror/0").execute { response ->
                    // 讀到第一個 byte 代表伺服器端已經開始收集（FakeFrameSource 在第一次
                    // emit 前就先讓 activeCollectors 加一），確定不是巧合地還沒訂閱就斷線。
                    response.bodyAsChannel().readFully(ByteArray(1))
                    assertEquals(1, frames.activeCollectors(0))
                    awaitCancellation()
                }
            }

            // 斷線後伺服器端的 write 會失敗，讓 frames.collect 拋出並跑 finally——
            // 用短輪詢等它發生，而不是假設它跟 client 端取消同時完成。
            withTimeout(5_000) {
                while (frames.activeCollectors(0) != 0) delay(10)
            }
        }
    }
}

/** 依 WorkbenchServer.kt 的 mirror route 格式組出一個 multipart part，供測試驗證用。 */
private fun multipartFrame(boundary: String, frame: ByteArray): ByteArray =
    "--$boundary\r\nContent-Type: image/jpeg\r\nContent-Length: ${frame.size}\r\n\r\n".toByteArray() +
        frame +
        "\r\n".toByteArray()
