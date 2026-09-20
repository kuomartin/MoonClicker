# Vision (`match.*`) and OCR — Implementation Status Research

Researched 2026-09-09 against the repo at commit `f9f26eb` (worktree
`agent-a03fde23f0d25cf7b`). Method: read `ARCHITECTURE.md`,
`目前專案進度.md`, `docs/lua-api.md` in full, then read the native engine
(`app/src/main/cpp/MoonClickerEngine.cpp` / `.h`, `LuaEngine.cpp`), the Kotlin JNI
bridge (`LuaNative.kt`), the two script-runner implementations, build
config (`app/build.gradle.kts`, `app/src/main/cpp/CMakeLists.txt`,
`gradle/libs.versions.toml`), and `git log` over vision/OCR-related paths.

## 1. Summary

- **Vision (`match.*`) is real, not a stub.** The documented API
  (`match.templates`, `match.wait()`) is backed by genuine OpenCV
  (`cv::matchTemplate` with `TM_CCOEFF_NORMED`, ROI cropping, grayscale
  conversion, scaling) running per-frame in `MoonClickerEngine::processFrame`.
  This matches the docs' "框架完成，API 擴展中" (framework done, API
  expanding) characterization — the framework and the two documented
  entry points work; the "expanding" part shows in undocumented extra
  functions (`match.enable/disable/set_enabled`) and a parallel
  `screen.findImage()` helper.
- **OCR is genuinely at zero.** No ML Kit dependency, import, class, or
  even a stub/commented-out reference exists anywhere in the repo outside
  of prose in `ARCHITECTURE.md` / `目前專案進度.md`. "待辦" (TODO) is
  literally true — nothing has been scaffolded.
- **Doc vs. code has mismatches in both directions**, but concentrated in
  the `input.*` module, not in `match.*` itself: `docs/lua-api.md`
  documents `input.tap`, `input.swipePolyline(L1)`, and
  `input.script.down/move/up`, none of which exist in the native Lua
  engine that actually executes `.lua` scripts (`MoonClickerEngine.cpp` only
  binds `input.swipe` and `input.click`). Conversely, the engine exposes
  `match.enable`, `match.disable`, `match.set_enabled`, and a top-level
  `screen.findImage(name)` that `docs/lua-api.md` never mentions.
- **Roadmap/priority**: no explicit statement was found that vision was
  deliberately prioritized ahead of OCR. The only evidence is empirical —
  git history shows a completed, multi-commit vision integration
  (`f24b479` "Integrate OpenCV and implement native template matching")
  from early in the project's history, while OCR/ML Kit never appears in
  any commit message, ever. The docs' "下一步建議" (next-step
  recommendations) list vision API expansion before "補強 ML Kit OCR" —
  ordering, not an explicit priority statement.

## 2. Vision (`match.*`) completion — per function

| Documented in `docs/lua-api.md`? | Function | Implemented (real) | Stub/TODO | Evidence |
|---|---|---|---|---|
| Yes | `match.templates` (table property) | Yes | — | Parsed and consumed in `MoonClickerEngine::` template-refresh lambda, `app/src/main/cpp/MoonClickerEngine.cpp:598-609` (falls back from `config.templates` at 569-596) and the per-entry field parsing (name/target/roi/threshold/grayscale/enabled) at `MoonClickerEngine.cpp:460-561`. Each entry loads a real bitmap via `cv::imread` (line 528) and caches it. |
| Yes | `match.wait()` | Yes | — | `MoonClickerEngine::lua_match_wait`, `MoonClickerEngine.cpp:864-897`. Yields the Lua coroutine (`lua_yield`) when no match is present yet, otherwise builds and returns a real results table populated from `latestResult.matches`, which is itself populated by `processFrame` running `cv::matchTemplate` (`MoonClickerEngine.cpp:687-705`) every frame. Bound at `MoonClickerEngine.cpp:128-132`. |
| No (undocumented) | `match.enable(name)` | Yes | — | `MoonClickerEngine::lua_match_enable`, `MoonClickerEngine.cpp:914-918`, delegates to `lua_match_set_enabled`. Bound at `MoonClickerEngine.cpp:134-136`. |
| No (undocumented) | `match.disable(name)` | Yes | — | `MoonClickerEngine::lua_match_disable`, `MoonClickerEngine.cpp:920-924`. Bound at `MoonClickerEngine.cpp:138-140`. |
| No (undocumented) | `match.set_enabled(name, bool)` | Yes | — | `MoonClickerEngine::lua_match_set_enabled`, `MoonClickerEngine.cpp:899-912`, mutates the live `templates` vector under `resultMutex`. Bound at `MoonClickerEngine.cpp:142-144`. |
| No (undocumented, separate module) | `screen.findImage(name)` | Yes | — | `MoonClickerEngine::lua_screen_findImage`, `MoonClickerEngine.cpp:1190-1214`. Reads the same `latestResult.matches` cache built by the OpenCV template-matching pass; returns `{found=false}` if not present. Bound at `MoonClickerEngine.cpp:122-126`, before the `match` table setup — it is registered as a top-level `screen` global, not part of `match`. |

Supporting evidence that the matching is real OpenCV and not a placeholder:
- Real includes: `#include <opencv2/imgproc.hpp>`, `#include <opencv2/imgcodecs.hpp>` — `MoonClickerEngine.cpp:2-3`.
- Real template matching call with normalized correlation coefficient method, ROI-aware and scale-aware: `MoonClickerEngine.cpp:687-705`.
- Build wiring is real, not vestigial: `find_package(OpenCV REQUIRED)` and OpenCV include/link in `app/src/main/cpp/CMakeLists.txt:5-8,76`; `-DOpenCV_DIR=...` and `jniLibs.directories += "~/OpenCV-android-sdk/sdk/native/libs"` in `app/build.gradle.kts:32,41`.
- Git history shows a dedicated, multi-step OpenCV integration effort: commit `f24b479` "Integrate OpenCV and implement native template matching for image recognition", followed by `d102648` "Remove CV test activity and perform minor code cleanup" and `83dc463` "Implement native-driven display management and blocking template matching for Lua scripts."

## 3. Doc/code mismatches

### Documented but not implemented (or implemented differently)
These are all in the `input` module, not `match` — worth flagging because
they show the same "doc describes intended API, code lags" pattern the
docs admit for vision/OCR, except undocumented here:
- `input.tap(durationMs, x, y, displayId)` — no `lua_input_tap` in
  `MoonClickerEngine.h`/`.cpp`. Only `lua_input_click` exists
  (`MoonClickerEngine.h:117`, bound to Lua field `"click"` at `MoonClickerEngine.cpp:97-98`).
- `input.swipePolyline` / `input.swipePolylineL1` — no such native
  bindings; the engine only exposes `input.swipe`
  (`lua_swipe`, bound at `MoonClickerEngine.cpp:90-94`). `swipePolyline`/`swipePolylineL1`
  exist only in the **Kotlin** `InputController`
  (`app/src/main/java/com/xaxaxax/moonclicker/input/InputController.kt:37-51`)
  used by the separate, non-Lua `SimpleScriptRunner` DSL
  (`app/src/main/java/com/xaxaxax/moonclicker/script/runner/SimpleScriptRunner.kt`),
  not by the native Lua engine that `docs/lua-api.md` describes.
- `input.script.down/move/up` (multi-touch helper) — no `script` sub-table
  or corresponding native functions found anywhere in `MoonClickerEngine.cpp`/`.h`
  or `LuaEngine.cpp`.

Note: `docs/lua-api.md` is explicitly about the Lua API surface, and the
Lua engine that runs `.lua` scripts is `MoonClickerEngine.cpp`, reached via
`NativeLuaScriptRunner.kt:22` → `LuaNative.startEngineWithService`. The
Kotlin `InputController.swipePolyline` is real code, but it backs a
different, JSON-based "SIMPLE" script format
(`SimpleScriptRunner.kt`), not the Lua API the doc claims to describe.

### Implemented but not documented
- `match.enable(name)`, `match.disable(name)`, `match.set_enabled(name, bool)`
  — real, working per-template enable/disable toggles (see table above),
  absent from `docs/lua-api.md` section 4.
- `screen.findImage(name)` — a synchronous, single-template lookup against
  the same match cache; a simpler alternative/precursor to `match.wait()`,
  entirely undocumented, registered as its own `screen` global
  (`MoonClickerEngine.cpp:121-126`).
- `config.templates` / `config.fps` / `config.scale` — an alternate,
  undocumented way to supply templates and tune the matching loop's
  frame rate and downscale factor, checked *before* falling back to
  `match.templates` (`MoonClickerEngine.cpp:569-596`).

## 4. OCR status

Zero code found. Searched the whole repo (both `app/` and `hidden-api/`
Gradle modules, all `.kt`/`.java`/`.cpp`/`.h`/`.gradle.kts`/`.toml` files)
for `mlkit`, `MLKit`, `MlKit`, `TextRecognition`, and `ocr`/`OCR` (case
variants):

- No hits in any source file (`.kt`, `.java`, `.cpp`, `.h`).
- No dependency declared in `app/build.gradle.kts` or
  `gradle/libs.versions.toml`.
- The only hits, at all, for "OCR" or "ML Kit" anywhere in the repository
  are prose in the two progress docs:
  - `ARCHITECTURE.md:78` — "OCR 與 物件偵測等進階能力預留：ML Kit OCR、
    TFLite 模型等可按需求動態下載與裝載" (reserved for future, dynamically
    downloadable).
  - `ARCHITECTURE.md:103` — "OCR（選配）：ML Kit Text Recognition" (listed
    as an optional dependency in an intended dependency list — not an
    actual `build.gradle.kts` entry).
  - `ARCHITECTURE.md:112` — mentions needing a model download/cache
    mechanism for OCR/TFLite/OpenCV models.
  - `目前專案進度.md:19,21,32,38,43` — the P4 section and module table
    marking OCR (ML Kit) status as "待辦" (TODO), and listing "補強 ML Kit
    OCR 的動態下載與版本管理機制" as a next step.
- `git log --all -i --grep="ocr\|mlkit"` returns no commits. Every vision-
  related commit found in the same targeted search
  (`f24b479`, `d102648`, `83dc463`, `5a2a1e9`, etc.) is about OpenCV/
  template matching; none mention OCR.

Conclusion: "待辦" here means literally no scaffolding exists — not even
a placeholder class, an unused dependency line, or commented-out code.
This is a bigger implementation gap than the vision module's "API 擴展中"
framing, which understates how far behind OCR actually is (OCR isn't
"expanding an API", it hasn't started).

## 5. Roadmap / priority findings

No explicit document states "do vision before OCR" as a stated decision
or priority ranking. What exists instead:

- **Ordering by omission in prose**: `目前專案進度.md` section 5
  ("下一步建議") lists, in order: (1) finish P2 H264/LocalSocket
  transport, (2) "擴展 vision.* API，並整合模板比對與簡易的視覺工作流至
  Lua 腳本中" (expand vision API), (3) "補強 ML Kit OCR 的動態下載與版本
  管理機制" (shore up ML Kit OCR). This is a sequential bullet list, not a
  declared priority — but it does put vision expansion ahead of OCR.
- **Empirical evidence from git history**: a complete, dedicated OpenCV
  integration was implemented early (`f24b479` and follow-on commits),
  while OCR has never had a single commit. This is the strongest signal
  that vision was practically prioritized over OCR, even though no commit
  message or doc explicitly frames it as a deliberate sequencing decision.
- **No TODO/FIXME comments** were found near the vision or OCR code paths
  in `MoonClickerEngine.cpp`/`.h` or `LuaEngine.cpp` referencing OCR or expressing
  planned sequencing. The single `TODO` found in the broader search is
  unrelated: `app/src/main/java/com/xaxaxax/moonclicker/lua/LuaNative.kt:34`
  ("TODO: Implement actual notification using appContext"), about
  notifications, not vision/OCR.

**Verdict: no explicit priority ordering was found** beyond the generic
"pending"/"待辦" language in the progress doc and the implicit ordering of
its own bullet list; the only hard evidence for a de facto priority is
that vision shipped and OCR never started.
