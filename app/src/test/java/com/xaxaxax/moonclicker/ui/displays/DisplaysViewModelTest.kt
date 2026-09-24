package com.xaxaxax.moonclicker.ui.displays

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplaysViewModelTest {

    @Test
    fun `no ids removed when current list is a superset`() {
        assertEquals(emptySet<Int>(), computeRemovedDisplayIds(listOf(1, 2), listOf(1, 2, 3)))
    }

    @Test
    fun `ids missing from the current list are removed`() {
        assertEquals(setOf(2), computeRemovedDisplayIds(listOf(1, 2, 3), listOf(1, 3)))
    }

    @Test
    fun `empty previous list removes nothing`() {
        assertEquals(emptySet<Int>(), computeRemovedDisplayIds(emptyList(), listOf(1)))
    }

    @Test
    fun `empty current list removes everything`() {
        assertEquals(setOf(1, 2), computeRemovedDisplayIds(listOf(1, 2), emptyList()))
    }

    private val physical = DisplayCardInfo(displayId = 0, width = 1080, height = 2400, densityDpi = 420, isPhysical = true)
    private val managed = DisplayCardInfo(displayId = 5, width = 1080, height = 2400, densityDpi = 420, isManaged = true)
    private val external = DisplayCardInfo(displayId = 9, width = 1080, height = 2400, densityDpi = 420)

    @Test
    fun `only a virtual display not created by the service is external`() {
        assertFalse(physical.isExternal)
        assertFalse(managed.isExternal)
        assertTrue(external.isExternal)
    }

    @Test
    fun `physical and external displays need a mirror, managed displays do not`() {
        assertTrue(physical.needsMirror)
        assertFalse(managed.needsMirror)
        assertTrue(external.needsMirror)
    }

    @Test
    fun `external displays are hidden unless enabled`() {
        val all = listOf(physical, managed, external)
        assertEquals(listOf(physical, managed), visibleDisplays(all, showExternal = false))
        assertEquals(all, visibleDisplays(all, showExternal = true))
    }
}
