# Lua (luaj) as the embedded automation scripting engine

We embedded Lua via `org.luaj:luaj-jse` as the user-facing automation language, rather than exposing Kotlin/JVM scripting or a custom DSL directly. The entire `match.*`/`input.*`/`display.*` API surface, script lifecycle callbacks (`on_start`, `on_tick`), and the LuaUiManager bridge are built around it — swapping the engine would mean rewriting that whole surface, not just the interpreter.

## Status

Accepted.
