# OCR 引擎選型 spike

回答 0.3 路線圖 #4：在 OpenCV dnn 與 ML Kit 之間選定 OCR 引擎，結論寫成 ADR。spike 的程式碼只為了量測，留在 `spike/ocr-engine` 分支，不 merge；#5 依 ADR-0018 重新實作。結果見 `docs/research/ocr-engine-selection.md`。

## A. 要回答的問題

| # | 問題 | 判定方式 |
|---|---|---|
| Q1 | 能否在腳本引擎所在行程執行 | `ScriptEngine` 在 app 行程，只有影格來自 UserService；在腳本內實際跑一次辨識，成功回傳文字即通過 |
| Q2 | 能否直接吃 native 影格 | 記錄影格從 `VisionMatcher` 到引擎輸入的路徑與每次複製的成本（ms） |
| Q3 | 中文（含繁體）與英數字辨識品質 | 固定樣本集上的整行正確率與字元錯誤率 |
| Q4 | 回傳格式 | 粒度（區塊／行／字）、每一項是否有位置框與信心度；對照路線圖 #6 的三個腳本需求：找文字取位置、讀 ROI 數值、信心度過濾 |
| Q5 | 延遲 | 單一 ROI（例如 400×80 的數值區）與整張影格的辨識延遲，暖機後取 p50／p95，高階與低階機各一台 |
| Q6 | 大小 | APK 增量（.so 與依賴）＋模型檔大小；模型能否不進 APK、首次使用時下載 |

## B. 候選

| 候選 | 模型 | 執行位置 | 已知風險 |
|---|---|---|---|
| A1. OpenCV 5 dnn ＋ PP-OCRv6 | PP-OCRv6 tiny 與 small 的偵測＋辨識，轉成 ONNX；small 支援繁中、簡中、英文、日文，tiny 不含日文 | native，與 `VisionMatcher` 同一層，直接用 `cv::Mat` | LCNetV4 的 RepDWConv、GELU 與 LightSVTR 的自注意力是否有 OpenCV dnn 不支援的運算子；`libopencv_dnn.a` 42 MB，靜態連結後的實際增量要量 |
| A2. ONNX Runtime ＋ PP-OCRv6 | 同 A1 | native，多連結一個推論 runtime；`cv::Mat` 可直接包成輸入張量 | APK 增量；MAA 在 Android 上刻意排除以 ONNX 為後端的 `libfastdeploy_ppocr.so`（數十 MB），ORT 的實際增量要量 |
| A3. NCNN ＋ PP-OCRv6 | 同 A1，以 pnnx 從 ONNX 轉成 ncnn 格式 | native，多連結 ncnn；`cv::Mat` 可轉成 `ncnn::Mat` | pnnx 轉換 PP-OCRv6 是否成功；det 必須維持 fp32（fp16 會破壞 DBNet 機率圖），rec 需固定輸入形狀 |
| B1. ML Kit Text Recognition v2（bundled） | `text-recognition-chinese` | Kotlin，需要把影格複製成 `Bitmap` 或 `InputImage` | 在 shell uid 行程內初始化（`MlKitContext`、Firebase components）可能失敗；模型進 APK |
| B2. ML Kit Text Recognition v2（unbundled） | `play-services-mlkit-text-recognition-chinese`，模型由 Google Play Services 首次使用時下載 | 同 B1 | 除了 B1 的風險，還要從 shell uid 行程綁定 Google Play Services；沒有 Google Play Services 的裝置無法使用 |

A1、A2、A3 並列，全部實測：三者都在 native 端、吃同一組 ONNX 模型，差別只在推論 runtime，比較的重點是運算子支援、延遲與 APK 增量。B1 在 UserService 內失敗時，B2 大概率也會失敗，但仍實測一次確認。

A3 的依據是 [MAA-Meow](https://github.com/Aliothmoon/MAA-Meow)：它在 Shizuku UserService 內執行 MAA Core，Android 上的 OCR 用 ncnn 跑 PP-OCR（MAA Core 的 `OcrPackNcnn`），桌面版才用 ONNX；轉換流程見其 `scripts/convert_ocr_ncnn.py`（pnnx，rec `inputshape=[1,3,48,320]`、det `inputshape=[1,3,640,640]` 且 fp32，與 onnxruntime 對照 cos=1.0）。這證明 PP-OCR 可以在 Shizuku UserService 行程內以 native 方式執行。MAA 與 MAA-Meow 皆為 AGPL-3.0，本專案為 MIT，只參考做法與轉換參數，不複製程式碼。

不列入：Tesseract（遊戲 UI 字型與背景下辨識品質差，模型大）、Paddle-Lite（A1–A3 已涵蓋同樣模型的 native 路徑，不再引入 Paddle 專屬格式）。

## C. spike 改動

先在電腦上跑：取得 PP-OCRv6 tiny／small 的 ONNX（官方未提供則以 paddle2onnx 轉換），再以 pnnx 轉出 ncnn，分別用 OpenCV 5 Python、onnxruntime Python、ncnn Python 對 7 張樣本跑一次，確認三個 runtime 的運算子支援與辨識結果是否一致。電腦上跑不動的 runtime 不進 Android 階段。

Android 階段：

| 檔案 | 內容 |
|---|---|
| `engine/src/main/cpp/CMakeLists.txt` | `find_package(OpenCV ...)` 加入 `dnn`（A1）；連結 onnxruntime（A2）與 ncnn（A3）的預編譯 Android 函式庫 |
| `engine/src/main/cpp/OcrSpike.cpp` / `.h` | 候選 A：前處理、DB 後處理與 CTC 解碼共用，只有推論呼叫依 runtime 切換；對 `cv::Mat` 的 ROI 跑偵測＋辨識，回傳文字、位置框、信心度與各階段耗時 |
| `engine/build.gradle.kts` | 候選 B1／B2：分別加入 `com.google.mlkit:text-recognition-chinese` 與 `com.google.android.gms:play-services-mlkit-text-recognition-chinese`，一次只開一個 |
| `engine/.../OcrSpikeMlKit.kt` | 候選 B：在 `MoonClickerService` 的 context 下初始化 ML Kit 並辨識 `Bitmap` |
| `LuaBindings.cpp` | 暫時的 `vision._ocr_spike(engine, roi)`，對最新影格跑指定引擎，回傳結果與耗時；spike 專用，不寫進 `docs/lua-api.md` |
| `engine/src/androidTest/.../OcrSampleTest.kt` | 對固定樣本集跑各候選，輸出 Q3 的正確率與 Q5 的延遲；在一般 app 行程執行，作為 Q1 的對照組 |
| 樣本集 | `~/Downloads/Sample` 的貓咪大戰爭截圖（2400×1080），每張附標準答案；放在 `androidTest` assets |

模型檔在 spike 期間用 `adb push` 推到裝置上，不處理下載機制；Q6 另外記錄每個候選能否首次使用時下載。

## D. 產出

| 產出 | 內容 |
|---|---|
| `docs/research/ocr-engine-selection.md` | Q1–Q6 的量測數據與比較表 |
| `docs/adr/0018-ocr-engine.md` | 選定的引擎、支援語言、模型交付方式；取代 ADR-0003 中「OCR 未承諾」的部分 |
| 路線圖 #6 | 依 Q4 補上 Lua API 的命中結果欄位 |

## E. 測試環境與樣本

| 項目 | 內容 |
|---|---|
| 高階機 | Pixel 7a |
| 低階機 | Samsung Galaxy A21s |
| 模型交付 | 隨 APK 發布可接受；首次使用時下載是加分項 |
| 樣本 | 7 張，涵蓋三類文字：黑底白字的系統對話框（繁中＋英數混排）、帶描邊與底圖的遊戲按鈕文字（`請點這裡!`、`Servant強襲!`）、遊戲自有的七段式數字字型（XP `6355241`、罐頭數 `7602`、剩餘天數） |

標準答案先由我依截圖標註，再請你確認。七段式數字是最可能失敗的一類，但也正是「讀取數值」這個需求最常遇到的情境。
