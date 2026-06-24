# ReLC Architecture

本文件描述 ReLC 的整體架構、核心模組與資料流，以及與外部元件（如 Shizuku、OpenCV、Lua 引擎等）的介面契約。內容以實作現況為基礎，並提供未來演進的路徑與風險點。

## 功能目標對應關係

下列組合對應到系統的核心能力與元件，方便定位需求實作的範圍與優先順序：

| 組合 | 效果 | 核心元件 |
|------|------|---------|
| 1 | 背景執行 APP | VirtualDisplay + ActivityLaunch |
| 1+2+3 | 監控模式：開 APP 並看畫面 | VirtualDisplay + H264Sink + VideoPlayerUI |
| 1+4 | 操作模式：開 APP 並觸控 | VirtualDisplay + InputController |
| 1+4+5 | 腳本模式：背景自動化 | VirtualDisplay + InputController + ScriptEngine |
| 1+2+5+6 | 視覺模式：影像辨識自動化 | VirtualDisplay + H264Sink + FrameGrabber + VisionEngine + ScriptEngine |

---

## 系統架構總覽

以下為系統核心元件的結構與資料流，涵蓋 App Process 與 Shizuku IPC 的跨進程協作，以及虛擬顯示器與渲染/輸出路徑的組合。

```mermaid
graph TD
  App[App Process (normal uid)]
  UI[UI Layer (Compose)]
  VM[ViewModel Layer]
  SD[DisplaySink (NoOpSink / H264Sink / DirectSink)]
  (Distributor)-->VD[VirtualDisplay]
  AIDL[IRelcShizukuService / IRelcV2Service]
  NIC[InputController]
  Script[Script Engine]
  Vision[VisionEngine(OpenCV)]
  Stream[Streaming Pipeline]

  UI -->|observes| VM
  VM --> Shizuku[Shizuku IPC] --> VD
  VD --> SD
  SD --> Surface[Surface]
  VD --> Stream
  Stream --> Player[Video Player UI / FrameGrabber]
  Script --> VD
  NIC --> Shizuku
  Vision --> Stream
```

注意：此 Diagram 以 Mermeid 語法呈現，實際部署中分散在 Kotlin/Java 與原生層之間，Surface 與 LocalSocket 的實際路徑由虛擬顯示器與分發器共同管理。

## 核心元件與職責

- App Process UI Layer
  - Compose UI 負責顯示設定、虛擬顯示器清單、與腳本執行介面等。
  - 透過 ViewModel 與 Script Engine 的事件串接，提供使用者操作的實時反饋。

- Shizuku IPC 與服務層
  - RelcShizukuService（舊版本）與 RelcV2Service（新版，包含原生加速）負責與系統互動、虛擬顯示器管理、啟動應用、以及輸入事件注入。
  - IRelcShizukuService / IRelcV2Service 為 AIDL 介面契約，暴露虛擬顯示、輸入、啟動等能力。

- Virtual Display 子系統
  - DisplaySink：可插拔輸出端，實作包括 NoOpSink、H264EncoderSink、DirectSink 等，允許在執行期間熱替換輸出端。
  - VirtualDisplayController：生命週期管理，負責建立、附著、替換 sink 與銷毀虛擬顯示器。
  - GLES Distributor：原生 C++/OpenGL 的分發器，用於多客戶端同時渲染虛擬顯示內容，並提供 Surface 作為輸入來源。

- Streaming Pipeline（串流路徑）
  - VirtualDisplay 渲染到 H264EncoderSink，產生 H.264 無封包資料，經 LocalSocketServer/Client 傳輸，供 VideoPlayerUI 與 VisionEngine 使用。
  - 封包格式與流協定（CONFIG/VIDEO/PING）在原生層定義。

- 輸入模組
  - InputController 封裝對 IRelcV2Service 的輸入事件注入（觸控、滑動、按鍵），並支援多點觸控。
  - 多點觸控腳本控制（MultiTouchScriptController）提供腳本驅動的多點事件序列。

- 腳本引擎與 UI 桥接
  - ScriptEngine（Lua 原生引擎）與 ScriptManager/ScriptRunner 負責腳本生命週期。
  - LuaNative 與 LuaUiManager 提供 Lua 腳本與 Compose UI 的動態橋接能力，能動態新增/更新 HUD 與控制元件。

- 視覺辨識與工具
  - VisionEngine（OpenCV）提供模板比對／影像辨識能力，OpenCV 模組由原生層實作並透過 JNI/本地橋接暴露。
  - OCR 與 物件偵測等進階能力預留：ML Kit OCR、TFLite 模型等可按需求動態下載與裝載。

- Overlay UI
  - 懸浮 UI 與控制條等，使用 Compose 實作，與核心功能解耦，提升使用者操作性。

- API 與邊界
  - IRelcShizukuService / IRelcV2Service 作為穩定的 IPC 界面，負責跨進程通訊與虛擬顯示器的管理。
  - DisplaySink 與 VirtualDisplayController 提供清晰的外部 API，便於未來勞動條件下的替換實作。

## 系統資料流與介面契約

- 資料流：UI/UX 操作透過 ScriptEngine 與 InputController 對虛擬顯示器發起操作，虛擬顯示器透過 GLES Distributor 將內容輸出，流向 VideoPlayerUI/FrameGrabber 及 VisionEngine。
- 介面契約：AIDL 介面（IRelcShizukuService、IRelcV2Service）界定顯示、輸入、啟動等核心能力。
- UI 桥接：LuaNative 與 LuaUiManager 負責 Lua 與 Compose UI 的雙向橋接，Lua 腳本可動態操作 HUD、觸控控制與監控介面。

## 版本與相容性重點

- V1 與 V2 的演進：V2 引入原生加速與更細緻的輸入模型（多點觸控、插值式滑動等），同時補充原生層的 GLES Distributor，以提高渲染效率與多客戶端同步能力。
- API 穩定性：目前的 API 設計以模組化、契約導向，未來如需拓展 vision 模組，建議以 match / vision 為獨立模組，提供版本化暴露。
- 相容性風險：Android 版本差異（P、R、TIRAMISU、UPSIDE_DOWN_CAKE 等）對虛擬顯示旗標與系統服務的支援差異，需要持續測試與調整旗標組合。

## 依賴與建置

- Lua 腳本引擎：org.luaj:luaj-jse:3.0.1
- 視覺處理：OpenCV（OpenCV Android SDK）
- OCR（選配）：ML Kit Text Recognition
- Coroutines：kotlinx-coroutines-android
- 原生層：GlesDistributor、RelcEngine、LuaEngine 等多檔案，使用 CMake/NDK 編譯與穩定性調校。

## 風險與待解決事項

- 原生與 Kotlin 的整合穩定性：跨 JNI 與原生函式呼叫的錯誤處理與資源釋放需嚴格管理。
- Heat/性能管控：H264 流與多畫面共享可能會消耗較高的 CPU/GPU 資源，需要動態調整解析度、編碼參數與 sink 的切換策略。
- 腳本生態：Lua API 的穩定演進與版本相容性，需搭配測試覆蓋不同場景。
- 模型與模型下載：OCR/TFLite/OpenCV 模型檔需動態下載與快取機制，避免首開啟時耗時過長。

---
