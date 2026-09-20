package com.xaxaxax.moonclicker.ui.displays

import org.junit.Assert.assertEquals
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
}
