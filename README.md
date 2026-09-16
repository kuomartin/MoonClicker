# ReLC (Remote Lua Control)

ReLC 是一款專為 Android 設計的遠端 Lua 控制與背景自動化工具：透過 [Shizuku](https://shizuku.rikka.app/) 建立獨立的虛擬顯示器（Virtual Display），在不佔用實體螢幕的情況下於背景執行目標應用程式、注入觸控/按鍵事件，並透過線性執行的 Lua 腳本驅動流程，支援基於 OpenCV 的本機端高效影像模板比對與 VS Code 遠端開發調試。

---

## 🌟 核心特色

* **背景虛擬螢幕（Virtual Displays）**：
  * 利用 Shizuku 建立獨立虛擬顯示器，讓遊戲或 App 在背景全速運作，不干擾手機前景使用。
  * 支援全螢幕預覽與即時觸控互動。

* **現代化 Lua 腳本引擎**：
  * 內嵌原生 C++ Lua 5.5 執行環境，採線性執行模型（直觀的從上到下執行，無複雜 tick loop 或回呼巢狀）。
  * 支援直覺的中斷與停止機制（`sleep`、`vision.wait` 會在使用者停止時即刻釋放）。

* **本機端 OpenCV 影像辨識（Vision Matching）**：
  * 原生 C++ 整合 OpenCV 模板比對（`TM_CCOEFF_NORMED`），按需執行、極致省電。
  * 支援自動旋轉對齊（Logical Space 座標系統）、ROI 局部比對與多目標等待（`vision.wait_any`）。

* **Script Workbench 遠端開發體驗（VS Code Extension）**：
  * 裝置端內嵌高效能 Ktor HTTP & WebSocket 伺服器，配合 PIN 碼配對安全認證。
  * 搭配專用 VS Code 擴充套件（`relc-script-workbench`）：
    * 一鍵雙向推送/拉取腳本資料夾。
    * F5 一鍵 Push & Run 即時在手機上執行除錯。
    * 即時低延遲 H.264 螢幕鏡像預覽與即時畫面拖曳裁切模板（自動產生 ROI 資訊）。
    * 完整的 Lua Language Server（LuaLS）語法提示與自動補全。

---

## 📱 系統需求

* **Android 版本**：
  * 最低支援：Android 8.0 (API 27)
  * **推薦使用：Android 10 (API 29) 以上**（自 Android 10 起支援多顯示器輸入注入 `MotionEvent.setDisplayId`）
* **Shizuku**：
  * 裝置需安裝並啟動 [Shizuku](https://shizuku.rikka.app/)（支援無線 ADB 或 Root 模式）。
* **電腦端（可選，用於腳本開發）**：
  * VS Code 1.90.0+
  * `relc-script-workbench` 擴充套件

---

## 🚀 快速開始

### 1. 安裝與授權
1. 下載並安裝最新版 `ReLC.apk`。
2. 確保 Shizuku 已在裝置上啟動。
3. 開啟 ReLC，授予 Shizuku 權限與通知權限。

### 2. 撰寫第一份 Lua 腳本
在 ReLC 中，一份腳本就是一個資料夾（存放於 `Android/data/com.xaxaxax.relc/files/scripts/<script_name>/`），包含 `main.lua` 與可選的 `script.json`：

```lua
-- main.lua
log("Hello, ReLC!")
log("Screen size:", screen.width, screen.height)

-- 啟動目標 App
app.launch("com.example.app")

-- 等待畫面上的按鈕出現
local btn = vision.wait({ image = "login_btn.png" }, 10000)
if btn then
    input.tap(btn.cx, btn.cy)
    log("已點擊登入按鈕")
end

sleep(1000)
data.set("status", "執行完成")
```

### 3. 使用 VS Code Workbench 連線開發
1. 在 ReLC App 的 **Settings** 頁面開啟 **Script Workbench**，畫面會顯示本機 IP、Port 與 6 位數配對 PIN 碼。
2. 在 VS Code 安裝 `relc-script-workbench.vsix`。
3. 點擊側邊欄 **ReLC Scripts** -> **Connect to Device**，輸入裝置 IP 與 PIN 碼完成配對。
4. 即可在 VS Code 內即時編輯、按 `F5` 部署執行，或開啟 **Realtime Mirror** 進行畫面即時預覽與標記裁切模板。

---

## 📚 文件導覽

* [Lua API 參考手冊](docs/lua-api.md)：完整的全域函式、`screen`、`vision`、`input`、`app`、`device`、`data` API 說明與語法範例。
* [領域模型與詞彙表](CONTEXT.md)：專案核心領域概念定義（Virtual Display, Viewport, Surface Space vs Logical Space, Workbench 等）。
* [架構決策紀錄 (ADR)](docs/adr/)：歷次重大架構演進與決策記錄。
* [範例腳本](docs/examples/)：包含 Hello World 與各類操作範例。

---

## 🛠️ 本地編譯建置

本專案使用 Gradle 模組化架構，包含 C++ NDK 原生模組：

### 前置需求
* Android SDK (compileSdk 37) & NDK
* CMake 3.22.1+
* OpenCV Android SDK 4.x（預設路徑為 `~/OpenCV-android-sdk` 或透過環境變數 `OPENCV_ANDROID_SDK_DIR` 指定）

### 建置指令
```bash
# 編譯 Debug APK
./gradlew assembleDebug

# 編譯 Release APK
./gradlew assembleRelease

# 打包 VS Code 擴充套件
cd vscode-extension
npm install
npm run package
```

---

## 📄 開源授權

本專案採用開源授權，詳見 [LICENSE](LICENSE)（若有的話）。
