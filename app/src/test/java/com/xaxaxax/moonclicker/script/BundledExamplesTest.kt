package com.xaxaxax.moonclicker.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class BundledExamplesTest {

    private fun zipOf(vararg entries: Pair<String, String>): ByteArrayInputStream {
        val bytes = ByteArrayOutputStream()
        ZipOutputStream(bytes).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return ByteArrayInputStream(bytes.toByteArray())
    }

    @Test
    fun `reads name, description and uniqueId from script json`() {
        val example = BundledExamples.read(
            "youtube-rickroll",
            zipOf(
                "main.lua" to "log('hi')",
                "script.json" to """{"uniqueId":"youtube-rickroll","name":"YouTube Rickroll","description":"Plays a video","display":{"width":720,"height":1280,"densityDpi":320}}""",
            ),
        )

        assertEquals("youtube-rickroll", example.assetName)
        assertEquals("YouTube Rickroll", example.name)
        assertEquals("Plays a video", example.description)
        assertEquals("youtube-rickroll", example.uniqueId)
    }

    @Test
    fun `falls back to the asset name without script json`() {
        val example = BundledExamples.read("plain", zipOf("main.lua" to "log('hi')"))

        assertEquals("plain", example.name)
        assertNull(example.description)
        assertNull(example.uniqueId)
    }

    @Test
    fun `falls back to the asset name when script json is malformed`() {
        val example = BundledExamples.read("broken", zipOf("script.json" to "{not json", "main.lua" to ""))

        assertEquals("broken", example.name)
    }
}
