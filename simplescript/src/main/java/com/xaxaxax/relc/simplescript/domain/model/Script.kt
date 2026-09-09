package com.xaxaxax.relc.simplescript.domain.model

data class Script(
    val id: Long = 0,
    val name: String,
    val fps: Int = 15,
    val variables: List<Variable> = emptyList(),
    val events: List<Event> = emptyList()
)
