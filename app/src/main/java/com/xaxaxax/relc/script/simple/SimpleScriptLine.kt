package com.xaxaxax.relc.script.simple

/**
 * `verb:repeat:delayBetweenRepeatsMs:delayAfterStepMs:payload`
 *
 * Only the first four `:` characters split the header; the rest is payload (may contain `:`).
 *
 * Payload shapes:
 * - `tap` — `durationMs,x,y`
 * - `swipe` — `durationMs,x1,y1,x2,y2[,x3,y3[,x4,y4 ...]]`
 * - `swipe_raw` — same as `swipe`
 * - `delay` — `delayMs`
 * - `key` — physical key wire name (e.g. BACK)
 * - `text` — literal text (execution currently logs only)
 * - `setDisplay` — display id integer; applies to following steps until another setDisplay
 */
enum class SimpleScriptVerb {
    TAP,
    SWIPE,
    SWIPE_RAW,
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
                "swipe_raw" -> SWIPE_RAW
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

fun ParsedSimpleLine.convertTo(verb: SimpleScriptVerb): ParsedSimpleLine = when (this.verb) {
    verb -> this
    SimpleScriptVerb.SWIPE -> if (verb == SimpleScriptVerb.SWIPE_RAW) this.copy(verb = verb) else performConversion(
        verb
    )

    SimpleScriptVerb.SWIPE_RAW -> if (verb == SimpleScriptVerb.SWIPE) this.copy(verb = verb) else performConversion(
        verb
    )

    else -> performConversion(verb)
}

private fun ParsedSimpleLine.performConversion(verb: SimpleScriptVerb): ParsedSimpleLine {
    var newPayload = defaultPayloadForVerb(verb)
    if (this.verb.hasDuration && verb.hasDuration) {
        val durationStr = this.payload.substringBefore(',')
        newPayload = if (verb == SimpleScriptVerb.DELAY) {
            durationStr
        } else if (newPayload.contains(',')) {
            durationStr + "," + newPayload.substringAfter(',')
        } else {
            durationStr
        }
    }
    return this.copy(verb = verb, payload = newPayload)
}

private val SimpleScriptVerb.hasDuration: Boolean
    get() = when (this) {
        SimpleScriptVerb.TAP,
        SimpleScriptVerb.SWIPE,
        SimpleScriptVerb.SWIPE_RAW,
        SimpleScriptVerb.DELAY -> true

        SimpleScriptVerb.KEY,
        SimpleScriptVerb.TEXT,
        SimpleScriptVerb.SET_DISPLAY -> false
    }

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

data class SimpleTapPayload(
    val durationMs: Long,
    val x: Int,
    val y: Int,
) {
    fun encodeToPayload() = "$durationMs,$x,$y"
}

fun parseTapPayload(payload: String): SimpleTapPayload {
    val p = payload.split(',').map { it.trim() }
    return if (p.size == 2) {
        // Legacy support (optional, but good to have)
        SimpleTapPayload(50L, p[0].toInt(), p[1].toInt())
    } else if (p.size == 3) {
        SimpleTapPayload(p[0].toLong(), p[1].toInt(), p[2].toInt())
    } else {
        error("tap payload: durationMs,x,y — got \"$payload\"")
    }
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
const val SIMPLE_SCRIPT_DEFAULT_STEP_LINE = "tap:1:0:0:50,540,960"

fun SimpleScriptVerb.wireName(): String =
    when (this) {
        SimpleScriptVerb.TAP -> "tap"
        SimpleScriptVerb.SWIPE -> "swipe"
        SimpleScriptVerb.SWIPE_RAW -> "swipe_raw"
        SimpleScriptVerb.DELAY -> "delay"
        SimpleScriptVerb.KEY -> "key"
        SimpleScriptVerb.TEXT -> "text"
        SimpleScriptVerb.SET_DISPLAY -> "setdisplay"
    }

fun defaultPayloadForVerb(verb: SimpleScriptVerb): String =
    when (verb) {
        SimpleScriptVerb.TAP -> "50,540,960"
        SimpleScriptVerb.SWIPE, SimpleScriptVerb.SWIPE_RAW -> "500,640,1060,440,860"
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
