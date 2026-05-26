package com.xaxaxax.relc.simplescript.data.repository

import androidx.room.withTransaction
import com.xaxaxax.relc.simplescript.data.database.SimpleScriptDatabase
import com.xaxaxax.relc.simplescript.domain.model.*
import com.xaxaxax.relc.simplescript.data.mapper.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class ScriptRepository(private val db: SimpleScriptDatabase) {

    fun getAllScripts(): Flow<List<Script>> {
        // Note: Map operation within flow cannot run suspend directly without flatMapLatest or map returning Flow,
        // but since we only need a basic list update, we'll keep it simple for now,
        // or actually, Room allows relational queries. Let's fix this naive implementation by returning Flow of Scripts.
        // For simplicity, we just fetch entities here and assume children are fetched on demand if needed,
        // or we use a proper relations POJO. For now let's just map the root entities.
        return db.scriptDao().getAllScripts().map { scriptEntities ->
            scriptEntities.map { entity ->
                entity.toDomain(emptyList(), emptyList()) // Needs a relations class to be reactive on children
            }
        }
    }

    suspend fun getScriptWithChildren(scriptId: Long): Script? {
        val scriptEntity = db.scriptDao().getScriptById(scriptId) ?: return null

        val variables = db.variableDao().getVariablesForScript(scriptId).map { it.toDomain() }

        val eventEntities = db.eventDao().getEventsForScript(scriptId)
        val events = eventEntities.map { eventEntity ->
            val conditions = db.conditionDao().getConditionsForEvent(eventEntity.id).map { it.toDomain() }
            val actions = db.actionDao().getActionsForEvent(eventEntity.id).map { it.toDomain() }
            eventEntity.toDomain(conditions, actions)
        }

        return scriptEntity.toDomain(variables, events)
    }

    suspend fun saveScript(script: Script) {
        db.withTransaction {
            val scriptId = db.scriptDao().insertScript(script.toEntity())

            script.variables.forEach { variable ->
                db.variableDao().insertVariable(variable.toEntity(scriptId))
            }

            script.events.forEach { event ->
                val eventId = db.eventDao().insertEvent(event.toEntity(scriptId))

                event.conditions.forEach { condition ->
                    db.conditionDao().insertCondition(condition.toEntity(eventId))
                }

                event.actions.forEachIndexed { index, action ->
                    db.actionDao().insertAction(action.toEntity(eventId, index))
                }
            }
        }
    }
}
