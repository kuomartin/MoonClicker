# ReLC Roadmap

本文件記錄 ReLC 的功能發展藍圖與規劃。

---

## 1. Script Workbench 與開發者體驗 (Developer Experience)

- [x] **VS Code 擴充套件**：連線、腳本同步、遠端執行與即時日誌流
- [x] **Lua API Stubs**：自動生成 LuaLS 類型補全提示
- [x] **低延遲畫面鏡射**：Webview H.264 即時螢幕預覽
- [ ] **Workbench 認證授權**：Token / Handshake 安全連線機制
- [ ] **VS Code 即時除錯**：中斷點與變數監看 (Lua Debugger DAP)
- [ ] **視覺輔助標註工具**：畫面框選並直接生成 OpenCV 範本與 ROI 座標

---

## 2. 核心引擎與視覺比對 (Core Engine & Vision)

- [x] **Lua 5.4 內嵌直譯器**：原生 C++ / JNI 綁定
- [x] **OpenCV 4.10 原生加速**：Template Matching 與影像預處理
- [x] **Shizuku / Root 權限橋接**：多 Android 版本系統能力存取
- [ ] **OCR 文字識別**：本機端離線文字辨識
- [ ] **GLES / H.264 串流管線優化**：硬體編碼與低延遲零拷貝傳輸
- [ ] **多螢幕與虛擬顯示**：獨立渲染與觸控事件路由隔離

---

## 3. 使用者體驗與生態 (User Experience & Ecosystem)

- [x] **Compose UI**：Material 3 介面與深色模式
- [x] **腳本管理與範例庫**：內建範例與本地腳本管理
- [ ] **多語言支援 (i18n)**：UI 字串多語系化（英文預設、正體中文）
- [ ] **腳本匯入/匯出**：便捷分享與打包機制
- [ ] **社群腳本市集 (Script Hub)**：腳本探索與分享平台
