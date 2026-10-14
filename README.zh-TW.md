# MoonClicker

[English](README.md) | 正體中文

MoonClicker 是一款專為 Android 設計的遠端 Lua 控制與背景自動化工具：透過 [Shizuku](https://shizuku.rikka.app/) 建立獨立的虛擬顯示器（Virtual Display），在不佔用實體螢幕的情況下於背景執行目標應用程式、注入觸控/按鍵事件，並透過線性執行的 Lua 腳本驅動流程，支援基於 OpenCV 的本機端高效影像模板比對與 VS Code 遠端開發調試。

## 🌟 核心特色

* **背景虛擬螢幕（Virtual Displays）**：
  * 利用 Shizuku 建立獨立虛擬顯示器，讓遊戲或 App 在背景全速運作，不干擾手機前景使用。
  * 支援全螢幕預覽與觸控。

* **Lua 腳本引擎**：
  * 內嵌原生 C++ Lua 5.5 執行環境，採線性執行模型。
  * 支援直覺的中斷與停止機制（`sleep`、`vision.wait` 會在使用者停止時即刻釋放）。
  * 同時執行多支腳本：每個顯示器各跑一支，不同顯示器之間並行；每支腳本有各自的通知、日誌與資料，可分別從 App、通知或 VS Code 停止。

* **本機端影像與文字辨識**：
  * 原生 C++ 整合 OpenCV 模板比對。
  * 支援多目標與局部比對。
  * 本機端離線 OCR（PP-OCR + ONNX Runtime）。

* **VS Code 腳本工作台遠端開發**：
  * 低延遲 H.264 即時螢幕鏡射預覽。
  * F5 一鍵 Push & Run，直接在裝置上執行並依腳本即時查看日誌。
  * PIN 碼配對的安全連線認證。
  * 自動產生 LuaLS 型別補全提示，撰寫腳本時有語法提示與檢查。

## 📱 系統需求

* **Android 版本**：
  * **推薦使用：Android 10 (API 29) 以上**
  * 最低支援：Android 8.0 (API 27)，功能受限。
  * Android 17：虛擬顯示器與主螢幕一起休眠。主螢幕關閉期間，上面的 App 會停止、不回應輸入，腳本仍在執行但做不了事。開啟「設定 → 實驗性功能 → 以 VirtualDevice 建立虛擬顯示」（Android 13 以上）後，新建立的虛擬顯示器不隨主螢幕休眠。
* **Shizuku**：
  * 裝置需安裝並啟動 [Shizuku](https://shizuku.rikka.app/)。
* **電腦端（可選，用於腳本開發）**：
  * VS Code 1.90.0+，或 VSCodium、Cursor 等使用 Open VSX 的編輯器
  * [Open VSX](https://open-vsx.org/extension/kuomartin/moonclicker-script-workbench) 上的 MoonClicker 腳本工作台擴充套件，與 App 的版本號前兩碼（x.y）需相同

## 🚀 快速開始

從安裝到跑起範例腳本、寫第一支腳本的完整步驟，見 [Wiki](https://github.com/kuomartin/MoonClicker/wiki/Home)。

> **目前的限制**：一般使用者現階段主要能使用背景執行功能（例如背景播放、掛機）；撰寫 Lua 腳本需要電腦端搭配 VS Code Extension，或直接匯入已完成的腳本檔案。

### 安裝與授權

1. 至 [MoonClicker Releases](https://github.com/kuomartin/MoonClicker/releases) 下載並安裝 apk。
2. 確保 Shizuku 已在裝置上啟動。
3. 開啟 MoonClicker，授予 Shizuku 權限。

### 背景執行 App

切換至`顯示器`頁面，建立並進入一個虛擬顯示器。App 啟動後退出虛擬顯示器操作介面，App 將在背景持續執行。

### 撰寫第一份 Lua 腳本

在 MoonClicker 中，一份腳本就是一個資料夾，包含 `main.lua` 與可選的 `script.json`，
打包成 zip 檔後，從`腳本`頁面匯入。

```lua
-- main.lua
log("Hello, MoonClicker!")
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

閱讀文檔以了解更多
* [Lua API 參考手冊](docs/lua-api.md)
* [VS Code Extension](docs/extension.md)

## 🗺️ Roadmap

### 1. 開發者體驗

- [x] **VS Code 擴充套件**：連線、腳本同步、遠端執行與即時日誌流
- [x] **Lua API Stubs**：自動生成 LuaLS 類型補全提示
- [x] **低延遲畫面鏡射**：Webview H.264 即時螢幕預覽
- [x] **Workbench 認證授權**：Token / Handshake 安全連線機制
- [x] **視覺輔助標註工具**：在鏡像畫面框選 ROI 存成模板，並即時測試命中位置與信心度
- [x] **擴充套件英文化與上架 Open VSX**
- [ ] **REPL**：獨立於腳本的持久 session，VS Code 與 MCP 共用
- [ ] **MCP server**：內建在 Workbench，讓 AI agent 操控與觀察裝置
- [ ] **VS Code 鏡像畫面注入點擊**：點擊轉 tap、拖曳轉 swipe

### 2. 核心引擎與視覺比對

- [x] **Lua 5.5 內嵌直譯器**：原生 C++ / JNI 綁定
- [x] **OpenCV 原生加速**：Template Matching 與影像預處理
- [x] **Shizuku / Root 權限橋接**：多 Android 版本系統能力存取
- [x] **GLES / H.264 串流管線優化**：硬體編碼與低延遲零拷貝傳輸
- [x] **OCR 文字識別**：本機端離線文字辨識（PP-OCRv6 + ONNX Runtime，首次使用時下載）
- [x] **同時執行多支腳本**：每個顯示器各跑一支，各自的 Lua state、通知、日誌與資料
- [x] **以 VirtualDevice 建立虛擬顯示（實驗性）**：Android 13 以上，虛擬顯示器不隨主螢幕休眠
- [ ] **View tree**：在虛擬顯示器上取得 UI 階層，供 Lua 與 MCP 取用
- [ ] **物件偵測（YOLO）**：補足模板比對無法處理的變形、旋轉與光影變化
- [ ] **多視窗焦點管理**：多個虛擬顯示同時運作時的視窗焦點與輸入路由

### 3. 使用者體驗與生態

- [x] **Compose UI**：Material 3 介面與深色模式
- [x] **腳本管理與範例**：內建範例與本地腳本管理
- [x] **背景 app 靜音**：由 Lua 腳本逐一控制 app 的聲音與音訊焦點，避免干擾前景使用
- [x] **多語言支援 (i18n)**：UI 字串多語系化（英文、正體中文）
- [x] **腳本匯出**：將腳本與模板打包成 zip 匯出
- [ ] **社群腳本市集 (Script Hub)**：腳本探索與分享平台

## 📄 開源授權

本專案採用 [MIT License](LICENSE) 開源授權。
