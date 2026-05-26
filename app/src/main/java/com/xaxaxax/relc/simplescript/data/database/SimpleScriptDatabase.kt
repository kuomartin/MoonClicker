package com.xaxaxax.relc.simplescript.data.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.xaxaxax.relc.simplescript.data.database.entity.*
import com.xaxaxax.relc.simplescript.data.database.dao.*

@Database(
    entities = [
        ScriptEntity::class,
        VariableEntity::class,
        EventEntity::class,
        ConditionEntity::class,
        ActionEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class SimpleScriptDatabase : RoomDatabase() {
    abstract fun scriptDao(): ScriptDao
    abstract fun variableDao(): VariableDao
    abstract fun eventDao(): EventDao
    abstract fun conditionDao(): ConditionDao
    abstract fun actionDao(): ActionDao
}
