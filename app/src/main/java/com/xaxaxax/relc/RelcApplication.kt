package com.xaxaxax.relc

import android.app.Application
import com.xaxaxax.relc.notification.ScriptStatusNotifier
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class RelcApplication : Application() {
    companion object {
        lateinit var instance: RelcApplication
            private set
    }

    @Inject
    lateinit var scriptStatusNotifier: ScriptStatusNotifier

    override fun onCreate() {
        instance = this
        super.onCreate()
        if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())
        // Mirrors running scripts into the status notification for the whole process
        // lifetime — this is the only surface that shows a script is running while the
        // user is in another app.
        scriptStatusNotifier.start()
    }
}
