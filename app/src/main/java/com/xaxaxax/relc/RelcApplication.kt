package com.xaxaxax.relc

import android.app.Application
import com.xaxaxax.relc.ui.lua.LuaUiManager
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

@HiltAndroidApp
class RelcApplication : Application() {
    companion object {
        lateinit var instance: RelcApplication
            private set
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())
        // Eagerly registers LuaUiManager.instance as LuaNative's uiSink so
        // native ui.add/update/remove calls are never dropped before any
        // overlay UI has composed.
        LuaUiManager.instance
    }
}
