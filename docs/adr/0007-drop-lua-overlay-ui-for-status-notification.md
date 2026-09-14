# Drop the Lua-driven Overlay UI platform in favour of a status Notification

ReLC is not a platform for scripts that define their own UI. The overlay-window mechanism, the engine-side `ui` Lua table and the `on_event` callback are removed, and run status lives in a plain system Notification that needs no window permission at all. The overlay was never core to what ReLC does — run scripts against a virtual display — and it cost a bespoke JSON UI schema plus a second Compose rendering path.

## Consequences

The removed AccessibilityService incidentally kept the process alive; a plain `setOngoing(true)` notification does not, so a long run can now be killed while the user is in another app. Giving script execution a real process anchor is a separate decision, deliberately not made here.

## Status

Accepted.
