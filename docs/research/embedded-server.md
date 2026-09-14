# Embedded server for the VS Code Script Workbench — technology research

Researched 2026-09-15 for issue #49 (child of #47), against primary sources (vendor
docs, GitHub repos' own README/releases, developer.android.com). No blog posts cited
except where explicitly noted as corroborating, non-authoritative context.

## Conclusion

- **Server library: Ktor Server with the CIO engine** (`io.ktor:ktor-server-core`,
  `ktor-server-cio`, `ktor-server-websockets`), as a plain JVM artifact running inside
  the `:app` process — not a second execution path, just an HTTP/WS front end that
  calls into the existing `ScriptSession`.
- **Lifecycle: explicit user-toggled foreground service**, type `connectedDevice`,
  started only when the developer turns on "Script Workbench" in a settings screen,
  independent of whether any script is currently running. It composes with, but is
  not driven by, `ScriptSession.state`.
- **Discovery for milestone 1: manual IP:port entry with in-app QR code.** No NSD/mDNS
  for milestone 1; revisit if a later milestone needs zero-typing pairing on networks
  where the QR/IP path proves annoying.

## 1. Embedded HTTP+WebSocket server

### Ktor Server

Ktor's own server-engines documentation lists four engines — Netty, Jetty, Tomcat,
CIO — and scopes their platform support explicitly: Netty/Jetty/Tomcat are JVM-only,
while **CIO is the only engine available across JVM, Native, GraalVM, JavaScript, and
WasmJs**, because it is written in pure Kotlin with no servlet-container or native
dependency (https://ktor.io/docs/server-engines.html). Ktor's own platform matrix does
not enumerate "Android" as a distinct Kotlin/Native target for the *server* module —
this is expected and not a blocker: Android apps run Kotlin/JVM bytecode on ART, so
the plain `jvm` artifacts of `ktor-server-core`/`ktor-server-cio` execute on Android
exactly as any other JVM library does; only Netty/Jetty/Tomcat are disqualified,
because they pull in servlet-container or native-transport code paths (epoll/kqueue,
`sun.misc.Unsafe`-adjacent tricks) that are unreliable or absent under ART. This
matches the working example in `zahidaz/android-http-server`
(https://github.com/zahidaz/android-http-server), a sample Android app demonstrating
an embedded HTTP server using Ktor's CIO engine specifically because CIO has no native
transport dependency.

Current stable release, per the project's own GitHub releases page
(https://github.com/ktorio/ktor/releases): **3.5.2** (published 2026-07-31), on a
cadence of a minor release roughly every 1–2 months with patch releases in between —
an actively maintained, fast-moving project.

`ktor-server-websockets` is a first-party module (same repo, same release train), and
Ktor's WebSocket routing (`webSocket("/path") { ... }`) runs inside a suspend
coroutine scope per connection: an `incoming: ReceiveChannel<Frame>` and
`outgoing: SendChannel<Frame>` are handed to you, so pushing state to a connected VS
Code client is literally `scope.launch { scriptSession.state.collect { outgoing.send(...) } }`
— no callback bridging, no manual thread management. This is the best fit of any
candidate for a 100%-coroutine/StateFlow codebase.

No authoritative APK-size/method-count figure for `ktor-server-cio` specifically was
found in Ktor's own docs or issue tracker (a client-engine performance thread,
https://github.com/ktorio/ktor/issues/1509, discusses runtime performance, not size).
Treat this as an open unknown to verify with a spike (add the dependency, run
`./gradlew :app:assembleRelease` with R8, diff APK/method count) before committing —
do not take a third-party number on faith here.

### NanoHTTPD

The project's own GitHub repo (https://github.com/NanoHttpd/nanohttpd) is not
archived and has commit/issue activity into 2025, but its own README states the
project is "currently in the process of stabilizing NanoHTTPD from the many pull
requests and feature requests that were integrated over the last few months" — i.e.
it self-describes as pre-release/unstable rather than a finished, versioned product.
Its Maven Central releases stopped at 2.3.1 (2016); development since then has lived
unreleased on `master`. WebSocket support exists via the `nanohttpd-websocket` module
(`NanoWSD`), demonstrated with a `DebugWebSocketServer` echo sample, but it is a
thin, callback-based (`onOpen`/`onMessage`/`onClose` overrides on a per-connection
object), pre-coroutine-era API — every inbound frame needs manual bridging into a
`Flow`/`Channel` to fit this codebase's patterns. Given Ktor is actively released and
composes natively with coroutines, NanoHTTPD is dominated by Ktor/CIO for this use
case; it was historically attractive mainly for its small size and zero dependencies,
which matters less here since CIO is also pure-Kotlin and dependency-light.

### Other candidates considered and rejected

- **Java-WebSocket (TooTallNate/Java-WebSocket)**: actively maintained, latest release
  1.6.0 (2024-12-15) per the repo's own releases page
  (https://github.com/TooTallNate/Java-WebSocket/releases). WebSocket-only — no HTTP
  routing/file-serving, so the Script Folder sync (listing/uploading `main.lua`,
  `script.json`, template images) would need a second, hand-rolled HTTP layer next to
  it. Callback-based (`onOpen`/`onMessage`/`onClose`), same coroutine-bridging tax as
  NanoHTTPD, for less functionality. Not recommended.
- **http4k**: pure Kotlin/JVM toolkit, and forum/community reports (not http4k's own
  docs) describe it being made to run on Android by picking a JVM-only backend such as
  `http4k-server-ktorcio` instead of Jetty/Undertow (per community write-up
  https://hackmd.io/@me9fEnRuR229q78l9MQQMQ/S1CvtzRCr — cited as corroborating
  context only, not primary). Its own site (https://www.http4k.org/) targets
  server-side JVM use cases and doesn't document Android as a target at all. Using it
  here would mean routing through Ktor CIO anyway as the underlying engine, with an
  extra abstraction layer bought for no benefit specific to this app. Not recommended.
- **AndroidAsync (koush/AndroidAsync)**: the repo (https://github.com/koush/AndroidAsync)
  is not formally archived and still receives occasional issues (e.g. opened
  2025-07-03), but its last published package release was October 2019 — six years
  without a release is a strong abandonment signal even absent an "Archived" banner.
  NIO- and callback-based, predates Kotlin coroutines entirely. Not recommended.
- **Plain `java.nio`/`ServerSocket`**: would mean hand-writing HTTP/1.1 parsing, an
  HTTP upgrade handshake, and RFC 6455 WebSocket framing from scratch — pure
  maintenance liability with no upside over Ktor CIO, which already provides all of
  this on top of the same non-blocking NIO primitives. Not recommended.

### Recommendation

Use **Ktor Server + CIO engine + ktor-server-websockets**, as a plain JVM dependency
inside `:app` (not `:engine` — it never touches native/JNI, it only calls the
`ScriptSession`/`ScriptEngine` facade per CONTEXT.md's module boundary). It is the
only candidate that is simultaneously actively released, has first-party HTTP+WS in
one library, and composes with `StateFlow`/coroutines with no bridging code — which
directly serves the "drive runs through the existing `ScriptSession`, don't build a
second execution path" constraint, since the natural implementation is a route
handler that calls `scriptSession.start(...)` and a WebSocket handler that collects
`scriptSession.state`.

## 2. Server lifecycle

### Why not "only while a Scripts screen is foregrounded"

A VS Code dev session is explicit, developer-initiated, and expected to outlive
backgrounding the app or the screen locking — the same category of long-lived,
user-visible background work Android's foreground service framework exists for, not
an activity-scoped resource. Tying the server to `Activity.onStart`/`onStop` would
disconnect it the moment the developer alt-tabs to their editor, which is the primary
expected interaction pattern (developer works in VS Code, glances at the phone
occasionally).

### Foreground service type

Android's own foreground-service-types documentation
(https://developer.android.com/develop/background-work/services/fgs/service-types)
defines `connectedDevice` (manifest `android:foregroundServiceType="connectedDevice"`,
permission `FOREGROUND_SERVICE_CONNECTED_DEVICE`) for "interactions with an external
device that requires... a network connection," which is exactly this case. Its
runtime prerequisite is satisfied by declaring `CHANGE_WIFI_STATE` (needed anyway if
a multicast lock is ever added later) or `CHANGE_NETWORK_STATE` in the manifest. Since
Android 14 (API 34), the platform requires every foreground service to declare a type
in the manifest and request its associated permission, and Play Console additionally
requires declaring the FGS type's justification under Policy > App content — both are
one-time setup costs, not per-run friction.

### Recommended shape

- A `ScriptWorkbenchServer` (or similar) lives in `:app`, started/stopped from a
  foreground service (`ScriptWorkbenchService`), not from `ScriptSession` — it is a
  sibling concern, not a subordinate of script execution. `ScriptSession` remains the
  sole owner of `EngineStateRepository`/native execution; the server only observes
  `ScriptSession.state` and calls `ScriptSession.start()`/`stop()`, matching the
  existing single-execution-path constraint.
- Explicit start/stop: a switch in a Script Workbench settings screen calls
  `startForegroundService` — the server keeps running (and the notification stays
  posted) until the developer flips the switch off, or the OS force-stops the app.
  This mirrors `ScriptStatusNotifier`'s "one persistent notification, one explicit
  action" precedent, but as its own notification/channel, since the two are
  independent (a script can run with the workbench off — triggered from the phone
  UI — and the workbench can be on with no script running).
- The service should surface connection state (listening address:port, connected
  client, last sync) the same way `ScriptSession` surfaces run state: a
  `StateFlow<WorkbenchState>` the settings screen and notification both collect, kept
  separate from `ScriptSessionState` so the two concerns don't get tangled into one
  God-object — consistent with `EngineStateRepository`'s documented boundary of not
  yet aggregating unrelated state sources for the sake of aggregating.
- Shizuku connection state is a separate axis: the workbench server does not need
  Shizuku to serve file sync (`ScriptStore` reads/writes plain files), only to
  actually *run* a script (`ScriptSession.start` already fails gracefully via
  `shizukuManager.withService` if Shizuku isn't connected — the server just surfaces
  that same failure over the wire, it doesn't need its own Shizuku lifecycle
  handling).

## 3. Discovery for milestone 1

Three options, compared against Android's own current NSD documentation
(https://developer.android.com/develop/connectivity/wifi/use-nsd) and the local
network permission page (https://developer.android.com/privacy-and-security/local-network-permission):

- **Manual IP:port entry**: zero new permissions, zero new failure modes, works on
  every network configuration (including ones with AP/client isolation or mDNS
  reflectors disabled, which are common on eduroam-style and some mesh Wi-Fi
  networks). Cost: the developer has to look up the phone's IP once per session/per
  network change.
- **In-app QR code**: same reliability profile as manual entry (it just encodes
  `ws://<ip>:<port>` or similar into a scannable code shown on the phone screen), but
  removes the "type an IP by hand" friction — the VS Code extension can offer a
  "scan to pair" flow. No new Android permission beyond what's already implied by
  running the server.
- **mDNS/NSD via `NsdManager`**: Android's own docs confirm `NsdManager` is the
  supported API (`registerService`/`discoverService`/`resolveService`), but note two
  concrete milestone-1-relevant costs: (1) apps targeting Android 13+ (API 33) must
  hold `NEARBY_WIFI_DEVICES` for local network service discovery/registration to work
  reliably (https://developer.android.com/privacy-and-security/local-network-permission
  — the local network permission page documents this as the mechanism replacing the
  old implicit local-network access); (2) receiving multicast (mDNS) traffic requires
  a `WifiManager.MulticastLock`
  (https://developer.android.com/reference/android/net/wifi/WifiManager.MulticastLock),
  acquired and released around the discovery window, or advertised services are
  invisible on many devices. On top of the required permission/lock, mDNS on Android
  has a documented history of flakiness — e.g. `NsdManager` intermittently reports a
  service lost and immediately found again, and a long-standing platform bug returns
  empty TXT record data (both noted in community-maintained workaround libraries, not
  Google's own docs, but consistent with Android's own docs never promising exactly-
  once or low-latency discovery semantics).

### Recommendation

For milestone 1 — single developer, their own network, no auth — **manual IP:port
entry with an in-app QR code as the ergonomic default** is right-sized: it needs no
extra runtime permission, has no network-topology-dependent failure mode, and a QR
scan is materially no slower than mDNS auto-discovery in the one-device-one-network
case, since there is nothing to disambiguate. mDNS/NSD adds a runtime permission
prompt, a multicast lock to manage correctly around the service lifecycle, and
real reliability variance across devices/networks — a cost worth paying only once
there's an actual multi-device or "find any ReLC phone on this network" scenario
(e.g. a later milestone with multiple developers/phones on a shared network), at
which point NSD becomes a legitimate secondary discovery path *alongside* — not
instead of — manual/QR entry as the reliable fallback.
