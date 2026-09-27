package com.xaxaxax.moonclicker.di

import android.content.Context
import com.xaxaxax.moonclicker.core.AppSettings
import com.xaxaxax.moonclicker.ocr.OcrPack
import com.xaxaxax.moonclicker.ocr.OcrPackInstaller
import com.xaxaxax.moonclicker.permission.PermissionManager
import com.xaxaxax.moonclicker.shizuku.ShizukuManager
import com.xaxaxax.moonclicker.shizuku.UserServiceLifecycle
import com.xaxaxax.moonclicker.workbench.WorkbenchServer
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
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

    /** 套件必須在 filesDir：`libonnxruntime.so` 放外部儲存會被 linker namespace 拒絕（ADR-0018）。 */
    @Provides
    @Singleton
    fun provideOcrPackInstaller(@ApplicationContext context: Context) = OcrPackInstaller(
        root = File(context.filesDir, "ocr"),
        downloadDir = context.cacheDir,
        pack = OcrPack.forThisProcess(),
        unsupportedAbi = OcrPack.processAbi(),
    )
}
