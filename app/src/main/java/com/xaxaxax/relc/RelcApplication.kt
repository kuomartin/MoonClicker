package com.xaxaxax.relc

import android.app.Application
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class RelcApplication : Application() {
    companion object {
        lateinit var instance: RelcApplication
            private set
    }
    override fun onCreate() {
        instance = this
        super.onCreate()
        if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())
    }
}
