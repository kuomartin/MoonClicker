package com.xaxaxax.relc.di

import com.xaxaxax.relc.script.ScriptManager
import com.xaxaxax.relc.script.ScriptRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ScriptModule {

    @Provides
    @Singleton
    fun provideScriptRepository(): ScriptRepository {
        return ScriptRepository()
    }

    @Provides
    @Singleton
    fun provideScriptManager(): ScriptManager {
        return ScriptManager()
    }
}
