package com.xaxaxax.relc.di

import android.content.Context
import com.xaxaxax.relc.core.AppSettings
import com.xaxaxax.relc.permission.PermissionManager
import com.xaxaxax.relc.shizuku.ShizukuManager
import com.xaxaxax.relc.shizuku.UserServiceLifecycle
import com.xaxaxax.relc.ui.displaydetail.MirrorFrameSource
import com.xaxaxax.relc.workbench.FrameSource
import com.xaxaxax.relc.workbench.WorkbenchServer
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

    /**
     * [WorkbenchServer] 吃的是 [FrameSource] 介面（見 #73），正式的實作是 [MirrorFrameSource]；
     * 兩邊都是 singleton，鏡像畫面登記進去的擷取來源才會跟 server 看到的是同一份。
     */
    @Provides
    @Singleton
    fun provideFrameSource(mirrorFrameSource: MirrorFrameSource): FrameSource = mirrorFrameSource
}