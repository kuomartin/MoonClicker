package com.xaxaxax.relc.simplescript.data.database.dao

import androidx.room.*
import com.xaxaxax.relc.simplescript.data.database.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ScriptDao {
    @Query("SELECT * FROM script")
    fun getAllScripts(): Flow<List<ScriptEntity>>

    @Query("SELECT * FROM script WHERE id = :scriptId")
    suspend fun getScriptById(scriptId: Long): ScriptEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertScript(script: ScriptEntity): Long

    @Delete
    suspend fun deleteScript(script: ScriptEntity): Int
}

@Dao
interface VariableDao {
    @Query("SELECT * FROM variable WHERE script_id = :scriptId")
    suspend fun getVariablesForScript(scriptId: Long): List<VariableEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVariable(variable: VariableEntity): Long

    @Delete
    suspend fun deleteVariable(variable: VariableEntity): Int
}

@Dao
interface EventDao {
    @Query("SELECT * FROM event WHERE script_id = :scriptId")
    suspend fun getEventsForScript(scriptId: Long): List<EventEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEvent(event: EventEntity): Long

    @Delete
    suspend fun deleteEvent(event: EventEntity): Int
}

@Dao
interface ConditionDao {
    @Query("SELECT * FROM condition WHERE event_id = :eventId")
    suspend fun getConditionsForEvent(eventId: Long): List<ConditionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertCondition(condition: ConditionEntity): Long

    @Delete
    suspend fun deleteCondition(condition: ConditionEntity): Int
}

@Dao
interface ActionDao {
    @Query("SELECT * FROM action WHERE event_id = :eventId ORDER BY order_index ASC")
    suspend fun getActionsForEvent(eventId: Long): List<ActionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAction(action: ActionEntity): Long

    @Delete
    suspend fun deleteAction(action: ActionEntity): Int
}
