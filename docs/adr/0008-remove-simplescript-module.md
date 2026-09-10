# Remove the :simplescript module entirely, rather than keep it as a dormant future-execution target

The `:simplescript` Gradle module (Room-backed `Script`/`Event`/`Action`/`Condition`/`Variable` domain model, `ScriptListScreen`/`ScriptEditorScreen`/`EventEditorScreen`/`PointConfigScreen`, `ScriptCompiler`, and the "Simple V2" top-level tab) is deleted wholesale, along with its two call sites in `:app` (`RelcNavGraph`'s top-level nav host and `FullscreenDisplayActivity`'s embedded `simpleScriptNavGraph` bottom sheet). Its only production entry points were the removed Overlay UI ([ADR-0007](0007-drop-lua-overlay-ui-for-status-notification.md)) and this embedded editor, and there were no real user scripts to migrate.

This is a deliberately blunt call: earlier the same day, `FullscreenDisplayViewModel.runScript` was wired up to compile a `simplescript.*` `Script` via `ScriptCompiler` and run it through `ScriptManager` (see [ADR-0006](0006-module-split-and-engine-facade.md)'s follow-up note) — so at the moment of deletion the module was not dead code, it was a working (if UI-only-reachable) feature. We removed it anyway rather than keep it dormant, because its sole remaining entry point (`DisplayDetailScreen`'s script picker) is itself being replaced by the Scripts/Displays navigation rework tracked separately, and carrying a second parallel script-authoring stack (Room-backed visual builder alongside the JSON-backed `script.simple.*` step editor already in `:app`) through that rework was judged not worth the cost. `DisplayDetailScreen`'s "Open Fullscreen" button now targets a fixed `default_script` directory with no picker; choosing/running a script on a virtual display has no replacement UI until the navigation rework lands.

Not touched: `app/.../script/simple/*` (`SimpleScriptCodec`/`SimpleScriptLine`/`SimpleScriptModels`) and the `ui/scriptdetail/component/Simple*Form.kt` screens are a separate, still-active non-Lua scripting mode (`ScriptConfig.Simple`) in the main script editor — despite the name collision, they share no code with `:simplescript` and were out of scope.

## Status

Accepted.
