# MoonClicker Script Workbench

Write, sync and debug [MoonClicker](https://github.com/kuomartin/MoonClicker) Lua scripts on an Android device from VS Code.

## Features

- **Folder sync**: each script on the device is a subfolder of a local folder you choose. Changes on either side sync while connected; conflicting edits keep the local version and save the device version as a `.device` file.
- **Run and stop**: press F5 in a script's `main.lua` to save, sync and run it on the device, and Shift+F5 to stop. Several scripts can run at once on different displays.
- **Workbench panel**: live low-latency mirror of any display that you can click and drag to tap and swipe on the device, with script logs and `data.set` values per script, template capture and cropping, and live template and OCR tests that generate Lua snippets.
- **REPL**: press Shift+Enter in a Lua editor to run the selection (or the current line) in a REPL on the device, or run **Open REPL** to type into it. Globals persist until you reset or close it; Ctrl+C interrupts only the running code, and Ctrl+D on an empty line closes the REPL. Up to four REPLs can be open on the device; **Open REPL** can also attach to one that is already open, such as one an AI agent is using, and shows what it runs.
- **MCP server config**: run **Copy MCP Server Config** to copy the app's built-in MCP server address and token for an AI agent.
- **Lua type hints**: the scripts folder gets a `.luarc.json` that loads the MoonClicker API definitions for the Lua language server.

## Getting started

1. In the MoonClicker app, open Settings → About and tap the version 7 times to enable developer options, then turn on Script Workbench and pairing mode under Developer options.
2. In VS Code, open the **MoonClicker Scripts** view and choose **Connect to Device**. Devices on the same network are found via mDNS; you can also enter `ip:port` manually and pair with the PIN shown on the device.
3. Choose a local folder for the device's scripts the first time you connect.

The app and the extension must use the same Workbench protocol version; update the older one if VS Code reports a mismatch.

## Exclude files

Put a `.moonclickerignore` (same syntax as `.gitignore`) in a script folder to keep files from being pushed to the device.

## Languages

English and Traditional Chinese, following the VS Code display language.
