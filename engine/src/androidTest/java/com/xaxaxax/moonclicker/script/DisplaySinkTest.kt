package com.xaxaxax.moonclicker.script

import android.graphics.PixelFormat
import android.media.ImageReader
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.xaxaxax.moonclicker.DisplaySink
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** client 端 [DisplaySink] 的 attach/close 順序：關掉之後不會再掛上去，detach 只送一次。 */
@RunWith(AndroidJUnit4::class)
class DisplaySinkTest {

    private val reader = ImageReader.newInstance(64, 64, PixelFormat.RGBA_8888, 2)
    private val service = RecordingMoonClickerService()

    @After
    fun tearDown() = reader.close()

    @Test
    fun closeBeforeAttachKeepsItDetached() {
        val sink = DisplaySink(3, reader.surface)

        sink.close()

        assertFalse(sink.attach(service))
        assertTrue(service.calls.isEmpty())
    }

    @Test
    fun closeAfterAttachDetachesOnce() {
        val sink = DisplaySink(3, reader.surface)

        assertTrue(sink.attach(service))
        sink.close()
        sink.close()

        val attach = service.calls.single { it is RecordingMoonClickerService.SinkAttach }
            as RecordingMoonClickerService.SinkAttach
        assertEquals(3, attach.displayId)
        assertEquals(
            listOf(RecordingMoonClickerService.SinkDetach(attach.token)),
            service.calls.filterIsInstance<RecordingMoonClickerService.SinkDetach>(),
        )
    }

    @Test
    fun refusedAttachIsNotDetached() {
        service.mirrorActive = false
        val sink = DisplaySink(3, reader.surface)

        assertFalse(sink.attach(service))
        sink.close()

        assertTrue(service.calls.isEmpty())
    }

    @Test
    fun attachingTwiceIsRefused() {
        val sink = DisplaySink(3, reader.surface)

        assertTrue(sink.attach(service))
        assertFalse(sink.attach(service))

        assertEquals(1, service.calls.count { it is RecordingMoonClickerService.SinkAttach })
    }
}
