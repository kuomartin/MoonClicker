# MoonClicker

English | [正體中文](README.zh-TW.md)

MoonClicker is an Android automation tool driven by Lua scripts. Through [Shizuku](https://shizuku.rikka.app/), it creates virtual displays where target apps run in the background without taking over your screen, injects touch and key events, and recognizes the screen with on-device template matching and OCR. Scripts are written and debugged from VS Code.

## Features

* **Background virtual displays**: run apps on their own virtual display while you keep using the phone; open any display fullscreen to watch or touch it.
* **Lua scripting**: an embedded Lua 5.5 runtime with a linear execution model; `sleep` and `vision.wait` return immediately when you stop a script.
* **On-device vision**: OpenCV template matching and OCR (ONNX Runtime + PP-OCR), both running locally.
* **VS Code Script Workbench**: low-latency H.264 screen mirroring, F5 to run on the device with live logs, PIN pairing, and LuaLS type stubs for completion and checking.

## Requirements

* Android 8.0 (API 27) or later; Android 10 (API 29) or later is recommended, as some features are limited on older versions.
* On Android 17, virtual displays sleep with the main screen, so scripts pause while the screen is off.
* [Shizuku](https://shizuku.rikka.app/) installed and running.
* For writing scripts: VS Code 1.90.0 or later with the `moonclicker-script-workbench` extension.

## Installation

1. Download and install the APK from [Releases](https://github.com/kuomartin/MoonClicker/releases).
2. Make sure Shizuku is running, then open MoonClicker and grant it Shizuku permission.
3. To write scripts, install the VS Code extension from the same release and follow [docs/extension.md](docs/extension.md) (in Traditional Chinese).

## Links

* [Wiki](https://github.com/kuomartin/MoonClicker/wiki): setup and first-script guides.
* [Lua API reference](docs/lua-api.md) (in Traditional Chinese)
* [Example script](examples/youtube-rickroll/)
* [Roadmap](README.zh-TW.md) (in Traditional Chinese)

## License

[MIT License](LICENSE)
