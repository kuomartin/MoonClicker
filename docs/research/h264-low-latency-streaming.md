# H.264 Ultra-Low Latency Streaming Research

## Objective
Investigate how high-performance screen mirroring tools (such as `scrcpy`) achieve sub-50ms latency using Android's `MediaCodec` and WebSocket/TCP streaming, and apply these findings to ReLC's `H264EncoderSink`.

## Findings: Sources of Latency in Android MediaCodec & Web

### 1. Web/MSE Buffering Latency
The most significant source of latency in our stack was the HTML5 `<video>` element via MSE (Media Source Extensions, wrapped by JMuxer). 
- **Cause**: Browsers are designed for VOD (Video on Demand) smooth playback. If frames arrive unevenly, MSE buffers them to prevent stuttering. Once the buffer falls behind the live edge, the browser *will not* fast-forward by default; it continues playing at 1.0x speed, leading to accumulating latency.
- **Solution (Implemented)**: 
  - Aligned JMuxer's initialization `fps` with Android's encoder (60 FPS instead of 30 FPS).
  - Reduced JMuxer's `flushingTime` to `1ms` and enabled `clearBuffer: true`.
  - Implemented an active dynamic catch-up mechanism using `video.playbackRate` (1.1x to 2.0x when buffer exceeds 50ms-300ms) instead of seeking with `currentTime` (which causes keyframe snap artifacts, decoding stalls, and stutters).

### 2. Android `MediaCodec` Pipeline Latency
While Android's `VirtualDisplay` and `MediaCodec` are extremely fast, default encoder configurations can introduce 100-300ms of encoding latency due to look-ahead buffers and power-saving profiles.

Based on `scrcpy`'s source code and Android documentation, the following `MediaFormat` flags are critical for real-time mirroring:

1. **Disable B-Frames (`KEY_MAX_B_FRAMES` = 0)**
   - **Why**: B-frames (Bi-directional predictive frames) require the encoder to buffer and look ahead at future frames before encoding the current one. This breaks real-time linearity.
   - **Fix**: Force `max-bframes` to `0` to ensure a strict I/P frame stream.

2. **Real-time Priority (`KEY_PRIORITY` = 0)**
   - **Why**: By default, `MediaCodec` may run in a best-effort background priority (`1`). 
   - **Fix**: Setting it to `0` hints the hardware codec that the workload is real-time and it should allocate necessary clock speeds.

3. **Low Latency Mode (`KEY_LOW_LATENCY` = 1, API 30+)**
   - **Why**: Introduced in Android 11, this explicitly disables power-saving buffers inside the hardware encoder/decoder pipeline. 
   - **Fix**: Applied for supported SDKs.

4. **Operating Rate (`KEY_OPERATING_RATE` = FPS)**
   - **Why**: Hints the hardware decoder/encoder on the expected frame rate, preventing the SoC from downclocking the media blocks.

## Implementation Applied
The above settings have been integrated into `H264EncoderSink.kt` to ensure the device pushes NALUs down the WebSocket socket immediately after the GPU renders the virtual display.

## References
- [scrcpy screen encoder configuration](https://github.com/Genymobile/scrcpy/blob/master/server/src/main/java/com/genymobile/scrcpy/ScreenEncoder.java)
- [Android MediaFormat API Reference](https://developer.android.com/reference/android/media/MediaFormat)
- [Web MSE Low Latency Techniques](https://developers.google.com/web/updates/2017/09/mse-low-latency)
