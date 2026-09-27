package com.xaxaxax.moonclicker.di

import android.content.Context
import com.xaxaxax.moonclicker.permission.PermissionManager
import com.xaxaxax.moonclicker.shizuku.ShizukuManager
import com.xaxaxax.moonclicker.shizuku.UserServiceAutoStopper
import com.xaxaxax.moonclicker.shizuku.UserServiceForegroundLease
import com.xaxaxax.moonclicker.shizuku.displayInfoList
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton


@Module
@InstallIn(SingletonComponent::class)
object CoreModule {
    @Provides
    @Singleton
    fun provideShizukuManager(@ApplicationContext context: Context) = ShizukuManager(context)

    @Provides
    @Singleton
    fun provideUserServiceAutoStopper(shizukuManager: ShizukuManager) = UserServiceAutoStopper(
        leases = shizukuManager.leases,
        serviceFlow = shizukuManager.serviceFlow,
        hasActiveDisplays = { service -> service.displayInfoList().any { it.isManaged || it.isMirrorActive } },
        stop = shizukuManager::stopUserService,
    )

    @Provides
    @Singleton
    fun provideUserServiceForegroundLease(shizukuManager: ShizukuManager) =
        UserServiceForegroundLease(shizukuManager.leases)

    @Provides
    @Singleton
    fun providePermissionManager(@ApplicationContext context: Context) = PermissionManager(context)
}