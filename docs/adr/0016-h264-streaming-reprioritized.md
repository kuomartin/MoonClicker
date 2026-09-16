# H264 streaming pipeline reprioritized

The MJPEG streaming strategy initially sufficed but has proven to introduce significant latency and stuttering (lag) when observing automation scripts remotely, degrading the development and monitoring experience. As a result, the H264 streaming pipeline is being reprioritized as a core infrastructure component.

## Architecture

To avoid modifying `RelcV2Service` (the Shizuku-hosted process) and to ensure WebRTC readiness:
1. We will use the Java `MediaCodec` API in the App process (`:engine` module) to construct a `H264EncoderSink`.
2. This sink requests an `InputSurface` from `MediaCodec` and registers it directly to `RelcV2Service.addVirtualDisplaySurface()`.
3. The internal `GlesDistributor` inside `RelcV2Service` seamlessly fans out rendering to this surface via EGL, achieving zero-copy transfer without dragging MediaCodec responsibilities into the native or privileged layers.
4. Output buffers (NALUs) are dequeued using Kotlin Coroutines and streamed via Ktor WebSockets.

This completely supersedes ADR-0004.

## Status

Accepted. Supersedes ADR-0004 as of 2026-09-16.
