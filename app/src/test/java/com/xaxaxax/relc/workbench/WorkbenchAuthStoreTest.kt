package com.xaxaxax.relc.workbench

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WorkbenchAuthStoreTest {
    private val context: Context = mockk(relaxed = true)
    private val prefs: SharedPreferences = mockk(relaxed = true)
    private val editor: SharedPreferences.Editor = mockk(relaxed = true)

    private val stringSetSlot = slot<Set<String>>()
    private var storedTokens = mutableSetOf<String>()

    @Before
    fun setup() {
        every { context.getSharedPreferences(any(), any()) } returns prefs
        every { prefs.edit() } returns editor
        every { editor.putBoolean(any(), any()) } returns editor
        every { editor.putStringSet(any(), capture(stringSetSlot)) } answers {
            storedTokens = stringSetSlot.captured.toMutableSet()
            editor
        }
        every { prefs.getStringSet(any(), any()) } answers { storedTokens }
        every { prefs.getBoolean(any(), true) } returns true
    }

    @Test
    fun `pairing mode generates 6 digit PIN and activates pairing`() {
        val store = WorkbenchAuthStore(context)
        assertFalse(store.isPairingActive.value)

        val pin = store.startPairingMode()

        assertEquals(6, pin.length)
        assertTrue(pin.all { it.isDigit() })
        assertTrue(store.isPairingActive.value)
        assertEquals(pin, store.pairingPin.value)
    }

    @Test
    fun `pairWithPin with correct PIN issues valid token and stops pairing`() {
        val store = WorkbenchAuthStore(context)
        val pin = store.startPairingMode()

        val result = store.pairWithPin(pin)

        assertTrue(result is PairResult.Success)
        val token = (result as PairResult.Success).token
        assertTrue(token.isNotBlank())
        assertTrue(store.isValidToken(token))
        assertFalse(store.isPairingActive.value)
    }

    @Test
    fun `pairWithPin with incorrect PIN fails and locks after 3 attempts`() {
        val store = WorkbenchAuthStore(context)
        val pin = store.startPairingMode()

        val wrongPin = if (pin == "000000") "111111" else "000000"

        val res1 = store.pairWithPin(wrongPin)
        assertEquals(PairResult.InvalidPin, res1)

        val res2 = store.pairWithPin(wrongPin)
        assertEquals(PairResult.InvalidPin, res2)

        val res3 = store.pairWithPin(wrongPin)
        assertTrue(res3 is PairResult.LockedOut)
        assertFalse(store.isPairingActive.value)
    }

    @Test
    fun `revokeAllTokens clears all tokens`() {
        val store = WorkbenchAuthStore(context)
        val pin = store.startPairingMode()
        val res = store.pairWithPin(pin) as PairResult.Success
        val token = res.token

        assertTrue(store.isValidToken(token))

        store.revokeAllTokens()

        assertFalse(store.isValidToken(token))
    }
}
