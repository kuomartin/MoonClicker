package com.xaxaxax.moonclicker.service

import android.app.AppOpsManager
import android.app.AppOpsManagerHidden
import android.app.AppOpsManagerHidden.opToDefaultMode
import android.app.AppOpsManagerHidden.strOpToOp
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.xaxaxax.moonclicker.engine.state.EngineRunState
import com.xaxaxax.moonclicker.script.LuaScriptRunner
import com.xaxaxax.moonclicker.script.Tier1Env
import dev.rikka.tools.refine.Refine
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `app.mute` 對真的 AppOpsService 與 AudioService。被靜音的是測試 APK 自己：音訊焦點的請求
 * 直接從測試行程發出，看得到系統的實際反應。
 *
 * 焦點請求要先放下 shell 身分（AudioService 對持有 shell 權限的呼叫者不檢查 appops），並讓
 * puppet 在虛擬顯示上前景執行（API 36 起背景行程的焦點請求一律被擋，與 appops 無關）。
 * mode 讀 package 層，與 production 寫入的同一層。
 */
@RunWith(AndroidJUnit4::class)
class AppMutingTest {

    @get:Rule
    val env = Tier1Env()

    private val packageName get() = env.context.packageName
    private val appOps get() = env.context.getSystemService(AppOpsManager::class.java)
    private val appOpsHidden get() = Refine.unsafeCast<AppOpsManagerHidden>(appOps)
    private lateinit var before: Map<String, Int>

    @Before
    fun recordModes() {
        before = modes()
    }

    /** 測試失敗時不能把測試 APK 留在靜音狀態，下一個測試的「原本」會是錯的。 */
    @After
    fun restoreModes() {
        before.forEach { (op, mode) -> appOpsHidden.setMode(strOpToOp(op), Process.myUid(), packageName, mode) }
        env.mutedAppsFile.delete()
    }

    @Test
    fun app_mute_denies_playback_and_focus_then_restores_both() {
        env.launchPuppet(env.createDisplay())
        assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, requestFocus())

        val outcome = LuaScriptRunner(service = env.service, displayId = 0).use {
            it.run("""data.set("ok", app.mute("$packageName", true))""")
        }
        assertEquals(outcome.error, EngineRunState.Finished, outcome.runState)
        assertEquals(true, outcome.data["ok"])
        assertEquals(MUTED, modes())
        assertEquals(AudioManager.AUDIOFOCUS_REQUEST_FAILED, requestFocus())

        assertEquals(true, env.service.setAppMuted(packageName, false))
        assertEquals(before, modes())
        assertEquals(AudioManager.AUDIOFOCUS_REQUEST_GRANTED, requestFocus())
        assertFalse(env.mutedAppsFile.exists())
    }

    /** 第二次靜音若重記原本的 mode，記到的是自己寫的 deny，之後就還原不回去。 */
    @Test
    fun muting_twice_still_restores_the_original_modes() {
        env.service.setAppMuted(packageName, true)
        env.service.setAppMuted(packageName, true)
        env.service.setAppMuted(packageName, false)

        assertEquals(before, modes())
    }

    @Test
    fun an_unknown_package_is_refused_without_a_record() {
        assertFalse(env.service.setAppMuted("com.example.not.installed", true))
        assertFalse(env.mutedAppsFile.exists())
    }

    /** `destroy()` 走這條；`destroy()` 本身會結束行程，測試裡不能呼叫。 */
    @Test
    fun restore_all_unmutes_and_clears_the_record() {
        val muting = newMuting()
        muting.setMuted(packageName, true)

        muting.restoreAll()

        assertEquals(before, modes())
        assertFalse(env.mutedAppsFile.exists())
    }

    /** 行程沒走到 `destroy()` 就結束時，下一個行程照紀錄還原。 */
    @Test
    fun a_new_process_restores_what_the_previous_one_left() {
        newMuting().setMuted(packageName, true)
        assertEquals(MUTED, modes())

        newMuting()

        assertEquals(before, modes())
        assertFalse(env.mutedAppsFile.exists())
    }

    private fun newMuting() = AppMuting(env.context.packageManager, appOpsHidden, env.mutedAppsFile)

    /** package 層的 mode；`checkOp*` 在持有 shell 身分時回的不是這個 package 的值。 */
    private fun modes(): Map<String, Int> {
        val codes = listOf(PLAY_AUDIO, TAKE_AUDIO_FOCUS).associateBy { strOpToOp(it) }
        val entries = appOpsHidden.getOpsForPackage(Process.myUid(), packageName, codes.keys.toIntArray())
            .orEmpty()
            .flatMap { it.ops }
            .associate { it.op to it.mode }
        return codes.entries.associate { (code, op) -> op to (entries[code] ?: opToDefaultMode(code)) }
    }

    private fun requestFocus(): Int {
        val audio = env.context.getSystemService(AudioManager::class.java)
        val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
            .build()
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.dropShellPermissionIdentity()
        try {
            return audio.requestAudioFocus(request).also { audio.abandonAudioFocusRequest(request) }
        } finally {
            automation.adoptShellPermissionIdentity()
        }
    }

    private companion object {
        const val PLAY_AUDIO = "android:play_audio"
        const val TAKE_AUDIO_FOCUS = "android:take_audio_focus"
        val MUTED = mapOf(PLAY_AUDIO to AppOpsManager.MODE_ERRORED, TAKE_AUDIO_FOCUS to AppOpsManager.MODE_IGNORED)
    }
}
