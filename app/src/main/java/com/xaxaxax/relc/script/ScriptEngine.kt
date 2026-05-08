package com.xaxaxax.relc.script

import com.xaxaxax.relc.IRelcShizukuService
import com.xaxaxax.relc.core.DisplayConfig
import com.xaxaxax.relc.display.VirtualDisplayController
import com.xaxaxax.relc.input.InputController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.luaj.vm2.LuaValue
import org.luaj.vm2.lib.LibFunction
import org.luaj.vm2.lib.OneArgFunction
import org.luaj.vm2.lib.ThreeArgFunction
import org.luaj.vm2.lib.TwoArgFunction
import org.luaj.vm2.lib.VarArgFunction
import org.luaj.vm2.lib.ZeroArgFunction
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
                return LuaValue.valueOf(result)
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
            override fun invoke(args: org.luaj.vm2.Varargs): org.luaj.vm2.Varargs {
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

    suspend fun execute(script: String) = withContext(Dispatchers.IO) {
        try {
            val chunk = globals.load(script)
            chunk.call()
        } catch (e: Exception) {
            Timber.e(e, "Script execution failed")
            onLog("Error: ${e.message}")
            throw e
        }
    }
}
