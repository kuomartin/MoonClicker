package com.xaxaxax.relc.core

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import java.util.Locale

/**
 * Android 13 以下沒有系統級 per-app language，且我們的 Activity 都是 ComponentActivity
 * 沒有 AppCompatActivity 那套 attachBaseContext 相容機制，只能自己包一層 Context。
 */
object AppLocale {
    /** tag 為空字串（跟隨系統）時原樣回傳 base，不做任何事。 */
    fun wrap(base: Context, tag: String): Context {
        if (tag.isEmpty()) return base
        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocales(LocaleList(locale))
        return base.createConfigurationContext(config)
    }
}
