# Remove the :simplescript module entirely, rather than keep it as a dormant future-execution target

`:simplescript` (Room-backed script model, its editor screens, `ScriptCompiler`, the "Simple V2" tab) is deleted wholesale rather than kept dormant, even though at the moment of deletion it was a working feature and not dead code. Its only entry points were the removed Overlay UI ([ADR-0007](0007-drop-lua-overlay-ui-for-status-notification.md)) and an embedded editor whose host screen is itself being replaced ([issue #5](https://github.com/kuomartin/MoonClicker/issues/5)); carrying a second parallel script-authoring stack through that rework was judged not worth the cost.

## Status

Accepted.
