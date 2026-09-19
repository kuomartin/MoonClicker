# ReLC (Remote Lua Control)

ReLC 是一款專為 Android 設計的遠端 Lua 控制與背景自動化工具：透過 [Shizuku](https://shizuku.rikka.app/) 建立獨立的虛擬顯示器（Virtual Display），在不佔用實體螢幕的情況下於背景執行目標應用程式、注入觸控/按鍵事件，並透過線性執行的 Lua 腳本驅動流程，支援基於 OpenCV 的本機端高效影像模板比對與 VS Code 遠端開發調試。

## 🌟 核心特色

* **背景虛擬螢幕（Virtual Displays）**：
  * 利用 Shizuku 建立獨立虛擬顯示器，讓遊戲或 App 在背景全速運作，不干擾手機前景使用。
  * 支援全螢幕預覽與觸控。

* **Lua 腳本引擎**：
  * 內嵌原生 C++ Lua 5.5 執行環境，採線性執行模型。
  * 支援直覺的中斷與停止機制（`sleep`、`vision.wait` 會在使用者停止時即刻釋放）。
  * VS Code Extension 遠端開發。

* **本機端 OpenCV 影像辨識**：
  * 原生 C++ 整合 OpenCV 模板比對。
  * 支援多目標與局部比對。

## 📱 系統需求

* **Android 版本**：
  * **推薦使用：Android 10 (API 29) 以上**
  * 最低支援：Android 8.0 (API 27)，功能受限。
* **Shizuku**：
  * 裝置需安裝並啟動 [Shizuku](https://shizuku.rikka.app/)。
* **電腦端（可選，用於腳本開發）**：
  * VS Code 1.90.0+
  * `relc-script-workbench` 擴充套件

## 🚀 快速開始

> ~~一點都不快速~~
>
> 目前，ReLC對於一般使用者來說，只有單純的背景執行功能（Youtube 背景播放、單純掛機）。
> Lua 腳本開發則需要電腦端支援，或是匯入以完成的腳本檔案。

### 安裝與授權

1. 至 [ReLC Releases](https://github.com/kuomartin/ReLC/releases) 下載並安裝apk。
2. 確保 Shizuku 已在裝置上啟動。
3. 開啟 ReLC，授予 Shizuku 權限。

### 背景執行APP

切換至`顯示器`頁面，建立並進入一個虛擬顯示器。APP啟動後退出虛擬顯示器操作界面，APP將在背景持續執行。

### 撰寫第一份 Lua 腳本

在 ReLC 中，一份腳本就是一個資料夾，包含 `main.lua` 與可選的 `script.json`，
打包成zip檔後，從`腳本`頁面匯入。

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

閱讀文檔以了解更多
* [Lua API 參考手冊](docs/lua-api.md)
* [VS Code Extension](docs/extension.md)

## 🗺️ Roadmap

### 1. 開發者體驗

- [x] **VS Code 擴充套件**：連線、腳本同步、遠端執行與即時日誌流
- [x] **Lua API Stubs**：自動生成 LuaLS 類型補全提示
- [x] **低延遲畫面鏡射**：Webview H.264 即時螢幕預覽
- [x] **Workbench 認證授權**：Token / Handshake 安全連線機制
- [ ] **視覺輔助標註工具**：畫面框選並直接生成 OpenCV 範本與座標

### 2. 核心引擎與視覺比對

- [x] **Lua 5.5 內嵌直譯器**：原生 C++ / JNI 綁定
- [x] **OpenCV 4.10 原生加速**：Template Matching 與影像預處理
- [x] **Shizuku / Root 權限橋接**：多 Android 版本系統能力存取
- [x] **GLES / H.264 串流管線優化**：硬體編碼與低延遲零拷貝傳輸
- [ ] **OCR 文字識別**：本機端離線文字辨識
- [ ] **多螢幕與虛擬顯示**：獨立渲染與觸控事件路由隔離(Android 11+)

### 3. 使用者體驗與生態

- [x] **Compose UI**：Material 3 介面與深色模式
- [ ] **腳本管理與範例**：內建範例與本地腳本管理
- [ ] **多語言支援 (i18n)**：UI 字串多語系化（英文預設、正體中文）
- [ ] **腳本匯入/匯出**：便捷分享與打包機制
- [ ] **社群腳本市集 (Script Hub)**：腳本探索與分享平台

## 📄 開源授權

本專案採用 [MIT License](LICENSE) 開源授權。
