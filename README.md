# MoonClicker

English | [正體中文](README.zh-TW.md)

MoonClicker is an Android automation tool driven by Lua scripts. Through [Shizuku](https://shizuku.rikka.app/), it creates virtual displays where target apps run in the background without taking over your screen, injects touch and key events, and recognizes the screen with on-device template matching and OCR. Scripts are written and debugged from VS Code.

## Features

* **Background virtual displays**: run apps on their own virtual display while you keep using the phone; open any display fullscreen to watch or touch it.
* **Lua scripting**: an embedded Lua 5.5 runtime with a linear execution model; `sleep` and `vision.wait` return immediately when you stop a script.
* **Concurrent scripts**: each display runs its own script, so scripts on different displays run side by side; each one has its own notification, logs, and data, and can be stopped from the app, its notification, or VS Code.
* **On-device vision**: OpenCV template matching and OCR (ONNX Runtime + PP-OCR), both running locally.
* **VS Code Script Workbench**: low-latency H.264 screen mirroring, F5 to run on the device with live logs per script, PIN pairing, and LuaLS type stubs for completion and checking.

## Requirements

* Android 8.0 (API 27) or later; Android 10 (API 29) or later is recommended, as some features are limited on older versions.
* On Android 17, virtual displays sleep with the main screen. While the screen is off, apps on them stop and ignore input, so a running script keeps going but can't get anything done. Turning on **Settings → Experimental → Create virtual displays through VirtualDevice** (Android 13 or later) keeps new virtual displays awake.
* [Shizuku](https://shizuku.rikka.app/) installed and running.
* For writing scripts: VS Code 1.90.0 or later, or an editor using Open VSX such as VSCodium or Cursor, with the MoonClicker Script Workbench extension.

## Installation

1. Download and install the APK from [Releases](https://github.com/kuomartin/MoonClicker/releases).
2. Make sure Shizuku is running, then open MoonClicker and grant it Shizuku permission.
3. To write scripts, install MoonClicker Script Workbench from [Open VSX](https://open-vsx.org/extension/kuomartin/moonclicker-script-workbench) and follow [docs/extension.md](docs/extension.md) (in Traditional Chinese). VS Code can't search Open VSX, so download the `.vsix` there and run **Extensions: Install from VSIX...**. The app and the extension must share the same major and minor version (x.y).

## Links

* [Wiki](https://github.com/kuomartin/MoonClicker/wiki): setup and first-script guides.
* [Lua API reference](docs/lua-api.md) (in Traditional Chinese)
* [Example script](examples/youtube-rickroll/)
* [Roadmap](https://github.com/kuomartin/MoonClicker/milestones)

## License

[MIT License](LICENSE)
