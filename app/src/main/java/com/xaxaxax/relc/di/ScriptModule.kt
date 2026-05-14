package com.xaxaxax.relc.di

import android.content.Context
import com.xaxaxax.relc.script.ScriptManager
import com.xaxaxax.relc.script.ScriptRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ScriptModule {

    @Provides
    @Singleton
    fun provideScriptRepository(@ApplicationContext context: Context): ScriptRepository {
        return ScriptRepository(context)
    }

    @Provides
    @Singleton
    fun provideScriptManager(): ScriptManager {
        return ScriptManager()
    }
}
