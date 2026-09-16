# Research: MediaCodec Java vs NDK (AMediaCodec) in ReLC

## Question
Does implementing `MediaCodec` in C++ (`AMediaCodec`) provide any tangible benefits over using the Java `MediaCodec` API, given that `GlesDistributor` can already accept a Java `Surface` natively?

## Findings

1. **Surface Passing is Zero-Overhead**: 
   The current `GlesDistributor` implementation uses `ANativeWindow_fromSurface` (see `GlesDistributor.cpp:153`). This function simply unwraps the underlying `IGraphicBufferProducer` from the Java `Surface` object. Once passed into EGL via `eglCreateWindowSurface`, the rendering pipeline is 100% native.
   **Conclusion**: There is no performance penalty or frame copying when passing a Java `MediaCodec.createInputSurface()` down to `GlesDistributor`.

2. **The Output Routing Problem**:
   The encoded H.264 NALU (Network Abstraction Layer Unit) buffers must ultimately be sent over the network. ReLC's networking stack (`WorkbenchServer`) is built in Kotlin using Ktor WebSockets.
   - **Java MediaCodec**: Kotlin dequeues the `ByteBuffer` directly from the Codec and writes it to Ktor. No JNI boundary is crossed during output.
   - **NDK AMediaCodec**: C++ dequeues the buffer, but must invoke a JNI callback to pass the byte array up to Kotlin for Ktor to send. This *adds* a JNI crossing for every single frame.

3. **Lifecycle Management**:
   While C++ offers "pure native locality" for the EGL/Codec lifecycle, the Java `MediaCodec` lifecycle is well-understood and fits naturally into Kotlin Coroutines (`produce` / `flow`).

## Conclusion
The user's intuition is correct. Using `AMediaCodec` in C++ actually **degrades** architecture by introducing an unnecessary JNI callback for the output buffers. The Java `MediaCodec` API is the superior choice here: it allows zero-copy input (via `Surface`) and native Kotlin integration for the output (via Ktor), perfectly bridging the C++ GLES fan-out with the Kotlin network stack.
