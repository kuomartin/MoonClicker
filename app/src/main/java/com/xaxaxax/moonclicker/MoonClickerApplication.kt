package com.xaxaxax.moonclicker

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import com.xaxaxax.moonclicker.shizuku.UserServiceAutoStopper
import com.xaxaxax.moonclicker.shizuku.UserServiceForegroundLease
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class MoonClickerApplication : Application() {
    companion object {
        lateinit var instance: MoonClickerApplication
            private set
    }

    @Inject
    lateinit var foregroundLease: UserServiceForegroundLease

    /** 注入即開始運作；放在這裡是為了讓它在每個 App 行程裡都存在，包括沒有 UI 的那種。 */
    @Inject
    lateinit var autoStopper: UserServiceAutoStopper

    override fun onCreate() {
        instance = this
        super.onCreate()
        if (BuildConfig.DEBUG) Timber.plant(Timber.DebugTree())
        ProcessLifecycleOwner.get().lifecycle.addObserver(foregroundLease)
    }
}
