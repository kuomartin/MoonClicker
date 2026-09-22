package com.xaxaxax.moonclicker.script

import java.io.File

/**
 * 把一段「使用者可控」的相對路徑安全地解到 [root] 底下的 [File]，擋掉逃出 [root] 的
 * entry（`..`、絕對路徑等）——跟 [ScriptArchive] 匯入 zip 時擋 zip slip 是同一套防禦，
 * 抽出來給單檔案 API 共用，不要兩邊各寫一份。
 */
object SafePath {
    fun resolve(root: File, relativePath: String): File? {
        val target = File(root, relativePath)
        val rootPrefix = root.canonicalPath + File.separator
        val targetCanonical = target.canonicalPath
        if (targetCanonical != root.canonicalPath && !targetCanonical.startsWith(rootPrefix)) {
            return null
        }
        return target
    }
}
