package com.xaxaxax.relc.simplescript.data.database.dao

import androidx.room.*
import com.xaxaxax.relc.simplescript.data.database.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ScriptDao {
    @Query("SELECT * FROM script")
    fun getAllScripts(): Flow<List<ScriptEntity>>

    @Query("SELECT * FROM script WHERE id = :scriptId")
    fun getScriptById(scriptId: Long): ScriptEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertScript(script: ScriptEntity): Long

    @Delete
    fun deleteScript(script: ScriptEntity)
}

@Dao
interface VariableDao {
    @Query("SELECT * FROM variable WHERE script_id = :scriptId")
    fun getVariablesForScript(scriptId: Long): List<VariableEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertVariable(variable: VariableEntity): Long

    @Delete
    fun deleteVariable(variable: VariableEntity)

    @Query("DELETE FROM variable WHERE script_id = :scriptId")
    fun deleteVariablesByScriptId(scriptId: Long)
}

@Dao
interface EventDao {
    @Query("SELECT * FROM event WHERE script_id = :scriptId")
    fun getEventsForScript(scriptId: Long): List<EventEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertEvent(event: EventEntity): Long

    @Delete
    fun deleteEvent(event: EventEntity)

    @Query("DELETE FROM event WHERE script_id = :scriptId")
    fun deleteEventsByScriptId(scriptId: Long)
}

@Dao
interface ConditionDao {
    @Query("SELECT * FROM condition WHERE event_id = :eventId")
    fun getConditionsForEvent(eventId: Long): List<ConditionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertCondition(condition: ConditionEntity): Long

    @Delete
    fun deleteCondition(condition: ConditionEntity)

    @Query("DELETE FROM condition WHERE event_id = :eventId")
    fun deleteConditionsByEventId(eventId: Long)
}

@Dao
interface ActionDao {
    @Query("SELECT * FROM action WHERE event_id = :eventId ORDER BY order_index ASC")
    fun getActionsForEvent(eventId: Long): List<ActionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAction(action: ActionEntity): Long

    @Delete
    fun deleteAction(action: ActionEntity)

    @Query("DELETE FROM action WHERE event_id = :eventId")
    fun deleteActionsByEventId(eventId: Long)
}
