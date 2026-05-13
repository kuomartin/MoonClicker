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

        // --- input ---
        val inputLib = LuaValue.tableOf()
        inputLib.set("tap", object : ThreeArgFunction() {
            override fun call(arg1: LuaValue, arg2: LuaValue, arg3: LuaValue): LuaValue {
                inputController.tap(arg1.toint(), arg2.toint(), arg3.toint())
                return NIL
            }
        })
        inputLib.set("swipe", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val x1 = args.arg(1).toint()
                val y1 = args.arg(2).toint()
                val x2 = args.arg(3).toint()
                val y2 = args.arg(4).toint()
                val duration = args.arg(5).tolong()
                val id = args.arg(6).toint()
                inputController.swipe(x1, y1, x2, y2, duration, id)
                return NIL
            }
        })
        inputLib.set("down", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val id = args.arg(1).toint()
                val x = args.arg(2).tofloat()
                val y = args.arg(3).tofloat()
                val displayId = args.arg(4).toint()
                inputController.script.down(id, x, y, displayId)
                return NIL
            }
        })
        inputLib.set("move", object : VarArgFunction() {
            override fun invoke(args: Varargs): Varargs {
                val id = args.arg(1).toint()
                val x = args.arg(2).tofloat()
                val y = args.arg(3).tofloat()
                val displayId = args.arg(4).toint()
                inputController.script.move(id, x, y, displayId)
                return NIL
            }
        })
        inputLib.set("up", object : TwoArgFunction() {
            override fun call(arg1: LuaValue, arg2: LuaValue): LuaValue {
                inputController.script.up(arg1.toint(), arg2.toint())
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
