package com.xaxaxax.moonclicker.service

import android.app.AppOpsManager
import android.app.AppOpsManagerHidden
import android.app.AppOpsManagerHidden.opToDefaultMode
import android.app.AppOpsManagerHidden.strOpToOp
import android.content.pm.PackageManager
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import java.io.File

/**
 * 以 appops 靜音單一 app：`PLAY_AUDIO` 不讓它出聲，`TAKE_AUDIO_FOCUS` 不讓它打斷別的 app 的媒體。
 *
 * 靜音狀態屬於這個行程而不是腳本：腳本結束仍維持，直到 [setMuted] 解除或 [restoreAll]（行程停止）。
 * appops 是系統設定、活得比行程久，所以每次改動前都先把原本的 mode 寫進 [recordFile]；行程沒走到
 * [restoreAll] 就結束時，下一個行程建構這個物件時照紀錄還原。
 *
 * 原本的 mode 取 package 層（`getOpsForPackage`）而不是 `checkOp*`：後者回的是 uid 層蓋過後的
 * 有效值，而 `setMode` 寫的是 package 層，拿有效值還原會寫錯層。
 */
internal class AppMuting(
    private val packageManager: PackageManager,
    private val appOps: AppOpsManagerHidden,
    private val recordFile: File,
) {
    /** package → 靜音前各 op 的 package 層 mode。與 [recordFile] 內容一致。 */
    private val originals = mutableMapOf<String, Map<String, Int>>()

    init {
        restoreRecorded()
    }

    /**
     * package 未安裝或 appops 呼叫失敗時回 false。已靜音再靜音、未靜音就解除都是 no-op：
     * 重記原本的 mode 會讀到我們自己寫的值，之後就還原不回去。
     */
    @Synchronized
    fun setMuted(packageName: String, muted: Boolean): Boolean = try {
        val uid = packageManager.getPackageUid(packageName, 0)
        if (muted) mute(packageName, uid) else unmute(packageName, uid)
        true
    } catch (t: Throwable) {
        Timber.w(t, "Unable to set muted=$muted for package '$packageName'")
        false
    }

    /** 還原所有靜音中的 package。還原失敗的保留在紀錄裡，留給下一個行程再試。 */
    @Synchronized
    fun restoreAll() {
        originals.keys.toList().forEach { packageName ->
            try {
                applyModes(packageManager.getPackageUid(packageName, 0), packageName, originals.getValue(packageName))
                originals.remove(packageName)
            } catch (_: PackageManager.NameNotFoundException) {
                originals.remove(packageName)
            } catch (t: Throwable) {
                Timber.w(t, "Unable to restore audio app ops for package '$packageName'")
            }
        }
        runCatching { writeRecord() }.onFailure { Timber.w(it, "Unable to update the muted-app record") }
    }

    private fun mute(packageName: String, uid: Int) {
        if (packageName in originals) return
        originals[packageName] = readModes(uid, packageName)
        // 先寫紀錄再改 mode：崩潰在兩者之間時，寧可下次多還原一次，也不留下沒有紀錄的靜音。
        try {
            writeRecord()
        } catch (t: Throwable) {
            originals.remove(packageName)
            throw t
        }
        try {
            applyModes(uid, packageName, MUTED_MODES)
        } catch (t: Throwable) {
            runCatching { applyModes(uid, packageName, originals.getValue(packageName)) }
                .onFailure { Timber.w(it, "Unable to roll back audio app ops for package '$packageName'") }
                .onSuccess {
                    originals.remove(packageName)
                    writeRecord()
                }
            throw t
        }
    }

    private fun unmute(packageName: String, uid: Int) {
        val modes = originals[packageName] ?: return
        applyModes(uid, packageName, modes)
        originals.remove(packageName)
        writeRecord()
    }

    private fun readModes(uid: Int, packageName: String): Map<String, Int> {
        val codes = MUTED_MODES.keys.associateBy { strOpToOp(it) }
        val entries = appOps.getOpsForPackage(uid, packageName, codes.keys.toIntArray())
            .orEmpty()
            .flatMap { it.ops }
            .associate { it.op to it.mode }
        // package 從沒設過、也沒用過這個 op 時沒有 entry，代表它處於預設值。
        return codes.entries.associate { (code, op) -> op to (entries[code] ?: opToDefaultMode(code)) }
    }

    private fun applyModes(uid: Int, packageName: String, modes: Map<String, Int>) {
        modes.forEach { (op, mode) -> appOps.setMode(strOpToOp(op), uid, packageName, mode) }
    }

    private fun restoreRecorded() {
        if (!recordFile.exists()) return
        try {
            val entries = JSONArray(recordFile.readText())
            for (i in 0 until entries.length()) {
                val entry = entries.getJSONObject(i)
                val packageName = entry.getString("package")
                originals[packageName] = originals[packageName].orEmpty() + (entry.getString("op") to entry.getInt("mode"))
            }
        } catch (t: Throwable) {
            Timber.w(t, "Unreadable muted-app record at $recordFile; discarding it")
        }
        Timber.d("Restoring audio app ops left by a previous process: ${originals.keys}")
        restoreAll()
    }

    private fun writeRecord() {
        if (originals.isEmpty()) {
            recordFile.delete()
            return
        }
        val entries = JSONArray()
        originals.forEach { (packageName, modes) ->
            modes.forEach { (op, mode) ->
                entries.put(JSONObject().put("package", packageName).put("op", op).put("mode", mode))
            }
        }
        recordFile.parentFile?.mkdirs()
        // 先寫暫存檔再 rename，崩潰在寫到一半時不會留下讀不回來的紀錄。
        val temp = File(recordFile.path + ".tmp")
        temp.writeText(entries.toString())
        if (!temp.renameTo(recordFile)) throw IllegalStateException("Cannot write $recordFile")
    }

    companion object {
        /** 系統的 `OPSTR_*` 是 @hide，但字串本身就是 appops 對外的穩定名稱（`appops set` 用的也是它）。 */
        private const val OPSTR_PLAY_AUDIO = "android:play_audio"
        private const val OPSTR_TAKE_AUDIO_FOCUS = "android:take_audio_focus"

        /** `PLAY_AUDIO` 用 deny（ERRORED）與 `appops set ... deny` 一致；音訊焦點用 ignore，請求直接回 FAILED。 */
        private val MUTED_MODES = mapOf(
            OPSTR_PLAY_AUDIO to AppOpsManager.MODE_ERRORED,
            OPSTR_TAKE_AUDIO_FOCUS to AppOpsManager.MODE_IGNORED,
        )
    }
}
