package com.xaxaxax.relc.script

import com.xaxaxax.relc.IRelcShizukuService
import com.xaxaxax.relc.display.VirtualDisplayController
import com.xaxaxax.relc.input.InputController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import org.luaj.vm2.LuaValue
import org.luaj.vm2.Varargs
import org.luaj.vm2.lib.OneArgFunction
import org.luaj.vm2.lib.ThreeArgFunction
import org.luaj.vm2.lib.TwoArgFunction
import org.luaj.vm2.lib.VarArgFunction
import org.luaj.vm2.lib.jse.JsePlatform
import timber.log.Timber

class ScriptEngine(
    private val service: IRelcShizukuService,
    private val onLog: (String) -> Unit
) {
    private val globals = JsePlatform.standardGlobals()
    private val inputController = InputController(service)

    // For now, we only support one display via this engine instance for simplicity,
    // though the API allows passing IDs.
    private val vdController = VirtualDisplayController(service)

    init {
        setupApi()
    }

    private fun readDisplayId(): Int = globals.get("displayId").optint(0)

    private fun setupApi() {
        // --- display ---
        val displayLib = LuaValue.tableOf()
        displayLib.set("launch", object : TwoArgFunction() {
            override fun call(pkg: LuaValue, id: LuaValue): LuaValue {
                val result = service.launchInDisplay(pkg.tojstring(), id.toint())
                return valueOf(result)
            }
        })
        // TODO: display.create, display.destroy
        globals.set("display", displayLib)

        /** Lua may assign `displayId = n`; injected APIs read it for input. */
        globals.set("displayId", LuaValue.valueOf(0))

        // --- input ---
        val inputLib = LuaValue.tableOf()
        inputLib.set("tap", object : ThreeArgFunction() {
            override fun call(arg1: LuaValue, arg2: LuaValue, arg3: LuaValue): LuaValue {
                inputController.tap(
                    arg1.tolong(),
                    arg2.toint(),
                    arg3.toint(),
                    readDisplayId(),
                )
                return NIL
            }
        })
        /** `durationMs, x1, y1, x2, y2, ...` — total arg count is odd (>= 5). Uses L2 arc length. */
        inputLib.set("swipe", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val (duration, pts) = parseSwipePolylineArgs(args, "input.swipe")
                inputController.swipePolyline(duration, pts, readDisplayId())
                return NIL
            }
        })
        /** Same args as `swipe`; uses Manhattan (L1) arc length — see [InputController.swipePolylineL1]. */
        inputLib.set("swipeL1", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val (duration, pts) = parseSwipePolylineArgs(args, "input.swipeL1")
                inputController.swipePolylineL1(duration, pts, readDisplayId())
                return NIL
            }
        })
        inputLib.set("down", object : ThreeArgFunction() {
            override fun call(arg1: LuaValue, arg2: LuaValue, arg3: LuaValue): LuaValue {
                inputController.script.down(
                    arg1.toint(),
                    arg2.tofloat(),
                    arg3.tofloat(),
                    readDisplayId(),
                )
                return NIL
            }
        })
        inputLib.set("move", object : ThreeArgFunction() {
            override fun call(arg1: LuaValue, arg2: LuaValue, arg3: LuaValue): LuaValue {
                inputController.script.move(
                    arg1.toint(),
                    arg2.tofloat(),
                    arg3.tofloat(),
                    readDisplayId(),
                )
                return NIL
            }
        })
        inputLib.set("up", object : OneArgFunction() {
            override fun call(arg1: LuaValue): LuaValue {
                inputController.script.up(arg1.toint(), readDisplayId())
                return NIL
            }
        })
        globals.set("input", inputLib)

        // --- utils ---
        globals.set("sleep", object : OneArgFunction() {
            override fun call(ms: LuaValue): LuaValue {
                Thread.sleep(ms.tolong())
                return NIL
            }
        })

        globals.set("log", object : OneArgFunction() {
            override fun call(msg: LuaValue): LuaValue {
                val s = msg.tojstring()
                Timber.d("[Lua] $s")
                onLog(s)
                return NIL
            }
        })
    }

    /** Parses `durationMs, x1, y1, ...` from Lua (odd nargs ≥ 5). */
    private fun parseSwipePolylineArgs(args: Varargs, apiName: String): Pair<Long, List<Pair<Int, Int>>> {
        val n = args.narg()
        require(n % 2 == 1 && n >= 5) {
            "$apiName(durationMs, x1, y1, ...) needs odd arg count >= 5"
        }
        val duration = args.arg(1).tolong()
        val pts = ArrayList<Pair<Int, Int>>()
        var j = 2
        while (j < n) {
            pts.add(args.arg(j).toint() to args.arg(j + 1).toint())
            j += 2
        }
        require(pts.size >= 2) { "$apiName needs at least two x,y points after duration" }
        return duration to pts
    }

    suspend fun executeIfPresent(script: String?) {
        if (script.isNullOrBlank()) return
        execute(script)
    }

    suspend fun execute(script: String): LuaValue? = withContext(Dispatchers.IO) {
        try {
            runInterruptible {
                val chunk = globals.load(script)
                chunk.call()
            }
        } catch (e: Exception) {
            if (e is CancellationException || e is InterruptedException || e.cause is InterruptedException) {
                Timber.d("Script execution cancelled")
                throw CancellationException("Script cancelled")
            }
            Timber.e(e, "Script execution failed")
            onLog("Error: ${e.message}")
            throw e
        }
    }
}
