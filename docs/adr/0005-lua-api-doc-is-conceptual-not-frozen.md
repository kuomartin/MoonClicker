# `docs/lua-api.md` is conceptual, not a compatibility contract

`docs/lua-api.md` describes the intended shape of the Lua scripting API, not a frozen public contract. The API is still actively changing (see `docs/research/vision-ocr-status.md` for concrete drift already found between the doc and `RelcEngine.cpp`), and function names, signatures, or entire modules may be renamed, removed, or restructured as the engine evolves. Don't treat the doc as something existing scripts are guaranteed to keep working against — update it freely to match reality rather than preserving a stale API for compatibility's sake.

## Status

Accepted.
