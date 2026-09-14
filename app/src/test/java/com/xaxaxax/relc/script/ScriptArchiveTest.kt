package com.xaxaxax.relc.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ScriptArchiveTest {

    @get:Rule
    val temp = TemporaryFolder()

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

    private fun import(bytes: ByteArray, name: String = "pack.zip") =
        ScriptArchive.import(ByteArrayInputStream(bytes), temp.root, name)

    @Test
    fun `imports a flat archive`() {
        val result = import(zipOf("main.lua" to "log('hi')", "btn.png" to "not really a png"))

        val imported = result as ScriptArchive.ImportResult.Imported
        assertEquals("pack", imported.dir.name)
        assertEquals("log('hi')", File(imported.dir, "main.lua").readText())
        assertTrue(File(imported.dir, "btn.png").isFile)
    }

    @Test
    fun `unwraps the single top-level folder most zip tools add`() {
        val result = import(zipOf("my-script/main.lua" to "log('hi')", "my-script/a.png" to "x"))

        val imported = result as ScriptArchive.ImportResult.Imported
        assertTrue(File(imported.dir, "main.lua").isFile)
        assertFalse(File(imported.dir, "my-script").exists())
    }

    @Test
    fun `rejects an archive with no main lua`() {
        val result = import(zipOf("readme.txt" to "nothing here"))

        assertTrue(result is ScriptArchive.ImportResult.Failed)
        // 失敗不能留下半個資料夾。
        assertEquals(emptyList<File>(), temp.root.listFiles()!!.toList())
    }

    @Test
    fun `rejects zip slip paths that escape the destination`() {
        val result = import(zipOf("main.lua" to "log('hi')", "../../evil.lua" to "pwned"))

        assertTrue(result is ScriptArchive.ImportResult.Failed)
        assertEquals(emptyList<File>(), temp.root.listFiles()!!.toList())
        assertFalse(File(temp.root.parentFile, "evil.lua").exists())
    }

    @Test
    fun `confines absolute entry paths inside the script folder`() {
        val result = import(zipOf("main.lua" to "log('hi')", "/etc/relc-evil.lua" to "pwned"))

        // `File(parent, "/etc/x")` 接在 parent 底下而不是寫到根目錄，所以絕對路徑被中和
        // 而不是被拒絕——結果同樣安全，但匯入會成功，這正是這裡要釘住的行為。
        val imported = result as ScriptArchive.ImportResult.Imported
        assertTrue(File(imported.dir, "etc/relc-evil.lua").isFile)
        assertFalse(File("/etc/relc-evil.lua").exists())
    }

    @Test
    fun `importing twice keeps both instead of overwriting`() {
        import(zipOf("main.lua" to "first"))
        val second = import(zipOf("main.lua" to "second")) as ScriptArchive.ImportResult.Imported

        assertEquals("pack-2", second.dir.name)
        assertEquals("first", File(temp.root, "pack/main.lua").readText())
        assertEquals("second", File(second.dir, "main.lua").readText())
    }

    @Test
    fun `export then import round-trips the folder`() {
        val dir = temp.newFolder("original")
        File(dir, "main.lua").writeText("log('round trip')")
        File(dir, "assets").mkdirs()
        File(dir, "assets/btn.png").writeText("image bytes")
        val script = Script.from(dir, null)

        val bytes = ByteArrayOutputStream().also { ScriptArchive.export(script, it) }.toByteArray()
        dir.deleteRecursively()

        val imported = import(bytes, "original.zip") as ScriptArchive.ImportResult.Imported
        assertEquals("log('round trip')", File(imported.dir, "main.lua").readText())
        assertEquals("image bytes", File(imported.dir, "assets/btn.png").readText())
    }

    @Test
    fun `replace overwrites the existing folder in place instead of creating a new id`() {
        import(zipOf("main.lua" to "first", "old.png" to "stale"))

        val result = ScriptArchive.replace(
            ByteArrayInputStream(zipOf("main.lua" to "second")),
            temp.root,
            "pack",
        )

        val replaced = result as ScriptArchive.ImportResult.Imported
        assertEquals("pack", replaced.dir.name)
        assertEquals("second", File(temp.root, "pack/main.lua").readText())
        assertFalse(File(temp.root, "pack/old.png").exists())
        assertEquals(listOf("pack"), temp.root.listFiles()!!.map { it.name })
    }

    @Test
    fun `replace rejects an archive with no main lua and leaves the existing folder untouched`() {
        import(zipOf("main.lua" to "first"))

        val result = ScriptArchive.replace(
            ByteArrayInputStream(zipOf("readme.txt" to "nothing here")),
            temp.root,
            "pack",
        )

        assertTrue(result is ScriptArchive.ImportResult.Failed)
        assertEquals("first", File(temp.root, "pack/main.lua").readText())
    }

    @Test
    fun `sanitizes unsafe names into folder ids`() {
        assertEquals("my-script", ScriptArchive.sanitizeId("my script.zip"))
        assertEquals("script", ScriptArchive.sanitizeId("../../.zip"))
        assertEquals("a_b-c", ScriptArchive.sanitizeId("/path/to/a_b c.zip"))
    }
}
