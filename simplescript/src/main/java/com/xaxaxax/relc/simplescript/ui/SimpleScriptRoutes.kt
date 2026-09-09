package com.xaxaxax.relc.simplescript.ui

import kotlinx.serialization.Serializable

@Serializable
object SimpleScriptsRoute

@Serializable
data class SimpleScriptEditorRoute(val scriptId: Long)

@Serializable
data class SimpleEventEditorRoute(val eventIndex: Int)

@Serializable
data class SimpleConditionEditorRoute(val eventIndex: Int, val conditionIndex: Int)

@Serializable
data class SimpleActionEditorRoute(val eventIndex: Int, val actionIndex: Int)
