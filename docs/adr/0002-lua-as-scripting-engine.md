# Lua as the embedded automation scripting engine

We embedded Lua as the user-facing automation language, rather than exposing Kotlin/JVM scripting or a custom DSL directly. The entire `match.*`/`input.*`/`display.*` API surface, script lifecycle callbacks (`on_start`, `on_tick`), and the LuaUiManager bridge are built around it — swapping the engine would mean rewriting that whole surface, not just the interpreter.

The interpreter itself is a custom-embedded native Lua 5.5.0 (`app/src/main/cpp/lua/lua-5.5.0/`, driven by `RelcEngine.cpp`/`LuaEngine.cpp` via JNI), not the `org.luaj:luaj-jse` JVM implementation this ADR originally named — the JVM library was never actually adopted; this corrects the record to match what shipped. The decision this ADR captures (Lua as the language, and the API surface built around it) still stands; only the interpreter identity was wrong.

## Status

Accepted. Corrected 2026-09-09 to reflect the actual native JNI implementation — see [ADR-0006](0006-module-split-and-engine-facade.md).
