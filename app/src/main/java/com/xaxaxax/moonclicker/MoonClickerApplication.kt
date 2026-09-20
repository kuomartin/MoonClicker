package com.xaxaxax.moonclicker

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class MoonClickerApplication : Application() {
    companion object {
        lateinit var instance: MoonClickerApplication
            private set
    }
    override fun onCreate() {
        instance = this
        super.onCreate()
        if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())
    }
}
