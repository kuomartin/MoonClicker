package com.xaxaxax.relc.core

import android.content.Context
import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class AppSettingsTest {

    /** 用一個 Map 模擬 SharedPreferences 的持久化，讓「重建 AppSettings 讀不讀得到」可以被測。 */
    private fun fakePrefs(): SharedPreferences {
        val store = mutableMapOf<String, Boolean>()
        val keySlot = slot<String>()
        val valueSlot = slot<Boolean>()
        val editor = mockk<SharedPreferences.Editor>(relaxed = true)
        every { editor.putBoolean(capture(keySlot), capture(valueSlot)) } answers {
            store[keySlot.captured] = valueSlot.captured
            editor
        }
        // relaxed：AppSettings 建構時還會呼叫 getString（語言、預設啟動頁偏好），這幾個測試
        // 不關心那些欄位，用預設值（null）打發掉就好，不用逐一 stub。
        return mockk(relaxed = true) {
            every { edit() } returns editor
            every { getBoolean(any(), any()) } answers {
                store.getOrDefault(firstArg(), secondArg())
            }
        }
    }

    private fun appSettings(prefs: SharedPreferences) = AppSettings(
        mockk<Context> { every { getSharedPreferences(any(), any()) } returns prefs },
    )

    @Test
    fun `autoStartUserService defaults to enabled when nothing is persisted yet`() {
        assertEquals(true, appSettings(fakePrefs()).autoStartUserService.value)
    }

    @Test
    fun `setAutoStartUserService updates the observable flow immediately`() {
        val settings = appSettings(fakePrefs())

        settings.setAutoStartUserService(false)

        assertFalse(settings.autoStartUserService.value)
    }

    @Test
    fun `a persisted value survives into a freshly constructed AppSettings`() {
        val prefs = fakePrefs()
        appSettings(prefs).setAutoStartUserService(false)

        assertFalse(appSettings(prefs).autoStartUserService.value)
    }
}
