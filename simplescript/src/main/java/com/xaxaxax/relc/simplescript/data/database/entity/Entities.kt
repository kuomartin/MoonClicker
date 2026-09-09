package com.xaxaxax.relc.simplescript.data.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.ColumnInfo

@Entity(tableName = "script")
data class ScriptEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val fps: Int
)

@Entity(tableName = "variable")
data class VariableEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "script_id") val scriptId: Long,
    val name: String,
    val type: String, // INT, FLOAT
    @ColumnInfo(name = "initial_value") val initialValue: Float
)

@Entity(tableName = "event")
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "script_id") val scriptId: Long,
    val name: String,
    @ColumnInfo(name = "enabled_on_start") val enabledOnStart: Boolean,
    @ColumnInfo(name = "condition_operator") val conditionOperator: String // AND, OR
)

@Entity(tableName = "condition")
data class ConditionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "event_id") val eventId: Long,
    val type: String, // TEMPLATE_MATCH, VARIABLE, TIMER
    // TemplateMatch fields
    @ColumnInfo(name = "img_path") val imgPath: String? = null,
    val threshold: Float? = null,
    // Variable fields
    @ColumnInfo(name = "variable_a") val variableA: String? = null,
    val operator: String? = null,
    val target: String? = null,
    // Timer fields
    val duration: Float? = null,
    val unit: String? = null
)

@Entity(tableName = "action")
data class ActionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "event_id") val eventId: Long,
    val type: String, // CLICK, SWIPE, WAIT, SET_VARIABLE, SET_EVENT
    @ColumnInfo(name = "order_index") val orderIndex: Int,

    // Serialized params (JSON) is simpler, or we can flatten them
    @ColumnInfo(name = "params_json") val paramsJson: String
)
