package com.xaxaxax.relc.simplescript.di

import android.content.Context
import androidx.room.Room
import com.xaxaxax.relc.simplescript.data.database.SimpleScriptDatabase
import com.xaxaxax.relc.simplescript.data.repository.ScriptRepository
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideSimpleScriptDatabase(@ApplicationContext context: Context): SimpleScriptDatabase {
        return Room.databaseBuilder(
            context,
            SimpleScriptDatabase::class.java,
            "simplescript_v2_db"
        ).build()
    }

    @Provides
    @Singleton
    fun provideScriptRepository(database: SimpleScriptDatabase): ScriptRepository {
        return ScriptRepository(database)
    }
}
