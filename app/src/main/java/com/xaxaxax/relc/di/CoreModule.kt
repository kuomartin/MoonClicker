package com.xaxaxax.relc.di

import android.content.Context
import com.xaxaxax.relc.core.AppSettings
import com.xaxaxax.relc.permission.PermissionManager
import com.xaxaxax.relc.shizuku.ShizukuManager
import com.xaxaxax.relc.shizuku.UserServiceLifecycle
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
    fun provideUserServiceLifecycle(shizukuManager: ShizukuManager, appSettings: AppSettings) =
        UserServiceLifecycle(shizukuManager, appSettings)

    @Provides
    @Singleton
    fun providePermissionManager(@ApplicationContext context: Context) = PermissionManager(context)
}