package com.xaxaxax.moonclicker.script

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class ScriptStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun script(id: String, meta: String? = null, withMain: Boolean = true): File {
        val dir = temp.newFolder(id)
        if (withMain) File(dir, Script.MAIN_FILE).writeText("log('hi')")
        if (meta != null) File(dir, Script.META_FILE).writeText(meta)
        return dir
    }

    @Test
    fun `folder with main lua is a script even without metadata`() {
        script("plain")

        val scripts = ScriptStore.scan(temp.root)

        assertEquals(1, scripts.size)
        assertEquals("plain", scripts[0].id)
        assertEquals("plain", scripts[0].name)   // 沒有 meta 就退回資料夾名
        assertEquals("", scripts[0].description)
        assertNull(scripts[0].display)
        assertNull(scripts[0].uniqueId)   // 缺 uniqueId：可見但不可執行
    }

    @Test
    fun `a valid uniqueId is surfaced`() {
        script("daily", """{"uniqueId":"daily-checkin"}""")

        assertEquals("daily-checkin", ScriptStore.scan(temp.root).single().uniqueId)
    }

    @Test
    fun `an invalid uniqueId is treated as missing`() {
        script("daily", """{"uniqueId":"Has Spaces!"}""")

        assertNull(ScriptStore.scan(temp.root).single().uniqueId)
    }

    @Test
    fun `metadata supplies name description and display config`() {
        script(
            "daily",
            """{"name":"自動簽到","description":"每天點簽到","uniqueId":"daily-checkin",
               "display":{"width":1080,"height":2400,"densityDpi":440}}"""
        )

        val found = ScriptStore.scan(temp.root).single()

        assertEquals("自動簽到", found.name)
        assertEquals("每天點簽到", found.description)
        assertNotNull(found.display)
        assertEquals("daily-checkin", found.display!!.name)   // 顯示器名稱是 uniqueId，不是 meta.name——不能跟別的腳本撞名
        assertEquals(1080, found.display.width)
        assertEquals(2400, found.display.height)
        assertEquals(440, found.display.densityDpi)
    }

    @Test
    fun `display config is dropped without a valid uniqueId`() {
        script(
            "daily",
            """{"name":"自動簽到","display":{"width":1080,"height":2400,"densityDpi":440}}"""
        )

        assertNull(ScriptStore.scan(temp.root).single().display)   // 沒有穩定 id 就不建虛擬顯示——反正也不可執行
    }

    @Test
    fun `omitting display means the script targets the physical screen`() {
        script("input-only", """{"name":"只做點擊"}""")

        assertNull(ScriptStore.scan(temp.root).single().display)
    }

    @Test
    fun `broken metadata does not hide the script or its siblings`() {
        script("broken", "{ this is not json")
        script("healthy", """{"name":"好的"}""")

        val scripts = ScriptStore.scan(temp.root)

        assertEquals(2, scripts.size)
        // 壞掉的那份仍然列得出來、跑得動，只是退回資料夾名。
        assertEquals("broken", scripts.first { it.id == "broken" }.name)
        assertEquals("好的", scripts.first { it.id == "healthy" }.name)
    }

    @Test
    fun `unknown metadata fields are ignored rather than rejecting the file`() {
        script("forward-compatible", """{"name":"新版","somethingNew":42}""")

        assertEquals("新版", ScriptStore.scan(temp.root).single().name)
    }

    @Test
    fun `a folder without main lua is not a script`() {
        script("assets-only", withMain = false)

        assertTrue(ScriptStore.scan(temp.root).isEmpty())
    }

    @Test
    fun `loose files in the root are ignored`() {
        File(temp.root, "notes.txt").writeText("hello")

        assertTrue(ScriptStore.scan(temp.root).isEmpty())
    }

    @Test
    fun `scripts are listed in case-insensitive name order`() {
        script("b", """{"name":"beta"}""")
        script("a", """{"name":"Alpha"}""")

        assertEquals(listOf("Alpha", "beta"), ScriptStore.scan(temp.root).map { it.name })
    }
}
