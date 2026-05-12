package com.xaxaxax.relc.di

import com.xaxaxax.relc.shizuku.ShizukuManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ShizukuModule {
    @Provides
    @Singleton
    fun provideShizukuManager(): ShizukuManager {
        return ShizukuManager().apply {
            startListening()
        }
    }
}
