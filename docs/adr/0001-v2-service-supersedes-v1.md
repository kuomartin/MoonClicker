# V2 service (native-accelerated) supersedes V1

We replaced `MoonClickerShizukuService` (V1) with `MoonClickerService` (V2) as the active Shizuku-hosted backend, adding native acceleration, a finer-grained input model (multi-touch, interpolated swipes), and the GLES Distributor for multi-client rendering. V1's code remains in the tree but is deprecated — new work targets V2 only.

## Status

Accepted. V1 deprecated as of 2026-09-09.
