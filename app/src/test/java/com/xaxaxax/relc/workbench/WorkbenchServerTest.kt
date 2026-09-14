package com.xaxaxax.relc.workbench

import com.xaxaxax.relc.script.Script
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
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** [ScriptSession] 是 Hilt @Singleton、沒有現成的假實作——測試只需要 [ScriptRunner] 這個縮小介面。 */
private class FakeScriptRunner : ScriptRunner {
    var running = false
    var startedScript: Script? = null

    override fun isRunning() = running

    override fun start(script: Script) {
        startedScript = script
    }
}

class WorkbenchServerTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val fakeRunner = FakeScriptRunner()

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
            application { workbenchModule(temp.root, fakeRunner) }

            val response = client.get("/health")

            assertEquals(HttpStatusCode.OK, response.status)
        }
    }

    @Test
    fun `websocket route echoes back what it receives`() = runTest {
        testApplication {
            application { workbenchModule(temp.root, fakeRunner) }
            val client = createClient { install(ClientWebSockets) }

            client.webSocket("/") {
                send(Frame.Text("hello"))
                val reply = incoming.receive() as Frame.Text
                assertEquals("hello", reply.readText())
            }
        }
    }

    @Test
    fun `scripts route lists script folders on device`() = runTest {
        scriptFolder("hello", "log('hi')")
        testApplication {
            application { workbenchModule(temp.root, fakeRunner) }

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
            application { workbenchModule(temp.root, fakeRunner) }

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
            application { workbenchModule(temp.root, fakeRunner) }

            val response = client.get("/scripts/does-not-exist/export")

            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    @Test
    fun `import route overwrites the existing script folder in place`() = runTest {
        val dir = scriptFolder("hello", "log('old')")
        File(dir, "old.png").writeText("stale")

        testApplication {
            application { workbenchModule(temp.root, fakeRunner) }

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
            application { workbenchModule(temp.root, fakeRunner) }

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
            application { workbenchModule(temp.root, fakeRunner) }

            val response = client.post("/scripts/hello/run")

            assertEquals(HttpStatusCode.Accepted, response.status)
            assertEquals("hello", fakeRunner.startedScript?.id)
        }
    }

    @Test
    fun `run route 404s for an unknown script id`() = runTest {
        testApplication {
            application { workbenchModule(temp.root, fakeRunner) }

            val response = client.post("/scripts/does-not-exist/run")

            assertEquals(HttpStatusCode.NotFound, response.status)
        }
    }

    @Test
    fun `run route rejects when a script is already running`() = runTest {
        scriptFolder("hello", "log('hi')")
        fakeRunner.running = true
        testApplication {
            application { workbenchModule(temp.root, fakeRunner) }

            val response = client.post("/scripts/hello/run")

            assertEquals(HttpStatusCode.Conflict, response.status)
            assertEquals(null, fakeRunner.startedScript)
        }
    }
}
