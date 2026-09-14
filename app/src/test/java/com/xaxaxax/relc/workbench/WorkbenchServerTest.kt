package com.xaxaxax.relc.workbench

import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
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
}
