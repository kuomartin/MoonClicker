package com.xaxaxax.moonclicker.script

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xaxaxax.moonclicker.engine.ReplResult
import com.xaxaxax.moonclicker.engine.ReplSession
import com.xaxaxax.moonclicker.engine.ReplStatus
import com.xaxaxax.moonclicker.engine.ReplTarget
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** REPL 的執行模型：state 跨段保留、`local` 不保留、中斷只停當段。Tier 0，不碰真的服務。 */
@RunWith(AndroidJUnit4::class)
class ReplSessionTest {

    private lateinit var dir: File
    private lateinit var repl: ReplSession

    @Before
    fun open() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        dir = File(context.cacheDir, "repl-${System.nanoTime()}").apply { mkdirs() }
        repl = ReplSession.open(
            context,
            RecordingMoonClickerService(),
            ReplTarget(displayId = 0, baseDir = dir, hasVision = false),
            onNotify = { _, _ -> },
            onOpenUri = {},
        ).getOrThrow()
    }

    @After
    fun close() {
        repl.close()
        dir.deleteRecursively()
    }

    private fun eval(code: String): ReplResult = runBlocking { withTimeout(10_000) { repl.eval(code) } }

    @Test
    fun an_expression_returns_its_values() {
        assertEquals(ReplResult(ReplStatus.OK, "3\thi"), eval("1 + 2, 'hi'"))
        assertEquals("月😀", eval("'月😀'").output)
    }

    @Test
    fun a_statement_returns_nothing() {
        assertEquals(ReplResult(ReplStatus.OK, ""), eval("x = 1"))
    }

    @Test
    fun globals_persist_and_locals_do_not() {
        eval("counter = 41")
        eval("local hidden = 1")
        assertEquals("42", eval("counter + 1").output)
        assertEquals("nil", eval("hidden").output)
    }

    @Test
    fun an_unfinished_chunk_is_incomplete_not_an_error() {
        assertEquals(ReplStatus.INCOMPLETE, eval("for i = 1, 3 do").status)
        assertEquals(ReplStatus.ERROR, eval("this is not lua").status)
    }

    @Test
    fun a_runtime_error_keeps_the_session() {
        eval("kept = true")
        val result = eval("error('boom')")
        assertEquals(ReplStatus.ERROR, result.status)
        assertTrue(result.output, result.output.contains("boom"))
        assertEquals("true", eval("kept").output)
    }

    @Test
    fun interrupting_stops_only_the_running_chunk() = runBlocking {
        eval("kept = 'yes'")
        val sleeping = async { repl.eval("sleep(60000)") }
        delay(300)
        repl.interrupt()
        assertEquals(ReplStatus.INTERRUPTED, withTimeout(5_000) { sleeping.await() }.status)

        val looping = async { repl.eval("while true do end") }
        delay(300)
        repl.interrupt()
        assertEquals(ReplStatus.INTERRUPTED, withTimeout(5_000) { looping.await() }.status)

        assertEquals("yes", eval("kept").output)
    }

    @Test
    fun log_lines_are_streamed() = runBlocking {
        val line = async { withTimeout(5_000) { repl.logLines.first() } }
        delay(100)
        eval("log('hello', 1)")
        assertEquals("hello\t1", line.await())
    }

    @Test
    fun require_resolves_from_the_base_dir() {
        File(dir, "helpers.lua").writeText("return { answer = 42 }")
        assertEquals("42", eval("require('helpers').answer").output)
    }

    @Test
    fun closing_completes_a_pending_eval() = runBlocking {
        val sleeping = async { repl.eval("sleep(60000)") }
        delay(300)
        repl.close()
        assertEquals(ReplStatus.CLOSED, withTimeout(5_000) { sleeping.await() }.status)
    }
}
