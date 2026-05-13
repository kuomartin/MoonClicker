package com.xaxaxax.relc.script.simple

/**
 * `verb:repeat:delayBetweenRepeatsMs:delayAfterStepMs:payload`
 *
 * Only the first four `:` characters split the header; the rest is payload (may contain `:`).
 *
 * Payload shapes:
 * - `tap` — `x,y`
 * - `swipe` — `durationMs,x1,y1,x2,y2[,x3,y3[,x4,y4 ...]]`
 * - `delay` — `delayMs`
 * - `key` — physical key wire name (e.g. BACK)
 * - `text` — literal text (execution currently logs only)
 * - `setDisplay` — display id integer; applies to following steps until another setDisplay
 */
enum class SimpleScriptVerb {
    TAP,
    SWIPE,
    DELAY,
    KEY,
    TEXT,
    SET_DISPLAY,
    ;

    companion object {
        fun parse(raw: String): SimpleScriptVerb {
            return when (raw.trim().lowercase()) {
                "tap" -> TAP
                "swipe" -> SWIPE
                "delay" -> DELAY
                "key" -> KEY
                "text" -> TEXT
                "setdisplay" -> SET_DISPLAY
                else -> throw IllegalArgumentException("Unknown SIMPLE verb: $raw")
            }
        }
    }
}

data class ParsedSimpleLine(
    val verb: SimpleScriptVerb,
    val repeatCount: Int,
    val delayBetweenRepeatsMs: Long,
    val delayAfterStepMs: Long,
    val payload: String,
)

fun parseSimpleScriptLine(line: String): ParsedSimpleLine {
    val t = line.trim()
    require(t.isNotEmpty()) { "empty SIMPLE step line" }
    val parts = t.split(':', limit = 5)
    require(parts.size == 5) {
        "SIMPLE step must be verb:repeat:between:after:payload — got ${parts.size} segments"
    }
    val verb = SimpleScriptVerb.parse(parts[0])
    val repeat = parts[1].toIntOrNull() ?: error("repeat must be int")
    require(repeat >= 1) { "repeat must be >= 1" }
    val between = parts[2].toLongOrNull() ?: error("delayBetweenRepeatsMs must be a number")
    val after = parts[3].toLongOrNull() ?: error("delayAfterStepMs must be a number")
    return ParsedSimpleLine(verb, repeat, between, after, payload = parts[4])
}

fun parseTapPayload(payload: String): Pair<Int, Int> {
    val p = payload.split(',', limit = 3)
    require(p.size == 2) { "tap payload: x,y — got \"$payload\"" }
    return p[0].trim().toInt() to p[1].trim().toInt()
}

fun parseSwipePayload(payload: String): SimpleSwipePayload {
    val parts = payload.split(',').map { it.trim() }.filter { it.isNotEmpty() }
    require(parts.size >= 5 && parts.size % 2 == 1) {
        "swipe payload: durationMs,x1,y1,x2,y2[,x3,y3,...] — got ${parts.size} value(s)"
    }
    val durationMs = parts[0].toLongOrNull() ?: error("swipe durationMs must be a number")
    val points = ArrayList<Pair<Int, Int>>((parts.size - 1) / 2)
    var i = 1
    while (i < parts.size) {
        val x = parts[i].toIntOrNull() ?: error("swipe x must be int")
        val y = parts[i + 1].toIntOrNull() ?: error("swipe y must be int")
        points.add(x to y)
        i += 2
    }
    require(points.size >= 2) { "swipe needs at least two points" }
    return SimpleSwipePayload(durationMs = durationMs, points = points)
}

data class SimpleSwipePayload(
    val durationMs: Long,
    val points: List<Pair<Int, Int>>,
)

/** Default line for new steps in the editor (`tap` center-ish). */
const val SIMPLE_SCRIPT_DEFAULT_STEP_LINE = "tap:1:0:0:540,960"

fun SimpleScriptVerb.wireName(): String =
    when (this) {
        SimpleScriptVerb.TAP -> "tap"
        SimpleScriptVerb.SWIPE -> "swipe"
        SimpleScriptVerb.DELAY -> "delay"
        SimpleScriptVerb.KEY -> "key"
        SimpleScriptVerb.TEXT -> "text"
        SimpleScriptVerb.SET_DISPLAY -> "setdisplay"
    }

fun defaultPayloadForVerb(verb: SimpleScriptVerb): String =
    when (verb) {
        SimpleScriptVerb.TAP -> "540,960"
        SimpleScriptVerb.SWIPE -> "500,0,0,100,100"
        SimpleScriptVerb.DELAY -> "1000"
        SimpleScriptVerb.KEY -> "BACK"
        SimpleScriptVerb.TEXT -> ""
        SimpleScriptVerb.SET_DISPLAY -> "0"
    }

fun SimpleSwipePayload.encodeToPayload(): String =
    buildString {
        append(durationMs)
        for ((x, y) in points) {
            append(',')
            append(x)
            append(',')
            append(y)
        }
    }

fun ParsedSimpleLine.encodeToLine(): String {
    val v = verb.wireName()
    val r = repeatCount.coerceAtLeast(1)
    return "$v:$r:$delayBetweenRepeatsMs:$delayAfterStepMs:$payload"
}
