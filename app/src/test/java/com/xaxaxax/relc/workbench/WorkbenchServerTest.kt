package com.xaxaxax.relc.workbench

import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class WorkbenchServerTest {
    @Test
    fun `health route returns 200`() = runTest {
        testApplication {
            application { workbenchModule() }

            val response = client.get("/health")

            assertEquals(HttpStatusCode.OK, response.status)
        }
    }

    @Test
    fun `websocket route echoes back what it receives`() = runTest {
        testApplication {
            application { workbenchModule() }
            val client = createClient { install(ClientWebSockets) }

            client.webSocket("/") {
                send(Frame.Text("hello"))
                val reply = incoming.receive() as Frame.Text
                assertEquals("hello", reply.readText())
            }
        }
    }
}
