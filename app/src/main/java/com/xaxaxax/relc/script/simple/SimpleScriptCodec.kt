package com.xaxaxax.relc.script.simple

import kotlinx.serialization.json.Json

object SimpleScriptCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    fun emptyBodyJson(): String = json.encodeToString(SimpleScriptBodyJson.serializer(), SimpleScriptBodyJson())

    fun encode(body: SimpleScriptBodyJson): String = json.encodeToString(SimpleScriptBodyJson.serializer(), body)

    fun decode(text: String): SimpleScriptBodyJson =
        json.decodeFromString(SimpleScriptBodyJson.serializer(), text)

    fun decodeOrNull(text: String): SimpleScriptBodyJson? = runCatching { decode(text) }.getOrNull()
}
