# OCR 用 ONNX Runtime 跑 PP-OCRv6 small，runtime 與模型首次使用時下載

OCR 以 ONNX Runtime（官方 Android `libonnxruntime.so`，CPU EP）執行 PaddleOCR 的 PP-OCRv6 small 偵測與辨識模型。runtime 與模型都不進 APK：首次使用 OCR 時從本專案的 GitHub Release 下載一包（arm64 約 38 MB，解壓後約 64 MB），驗證雜湊後放進 app 的 `filesDir`，以 `dlopen` 載入並經 `OrtGetApiBase` 取得 C API。理由：在 Pixel 7a、Note20 Ultra、Galaxy A21s 上各 runtime 以各自最佳執行緒數比較時，ONNX Runtime 的整張辨識三台皆最快、ROI 讀數在最快之列，官方 `.so` 可直接使用、不必自行編譯，也沒有 OpenMP 的執行緒限制；它的體積（壓縮後約 12 MB）是唯一劣勢，而延後下載讓 APK 大小不再受 OCR 影響。量測數據見 `docs/research/ocr-engine-selection.md`。

本 ADR 取代 ADR-0003 中「OCR 未承諾」的部分；ADR-0003 的模板比對決定不變。

## Considered Options

- **NCNN**：官方 Android 版靜態連結 libomp，第一條呼叫過 OpenMP 的執行緒結束後，行程內任何推論都會 abort，而腳本引擎每次執行都是新執行緒。以 `NCNN_SIMPLEOMP` 自行編譯可消除限制，但多執行緒變慢 2～4 倍，只剩單執行緒，整張辨識在多核機上落後 ONNX Runtime 1.2～1.6 倍。
- **OpenCV 5 dnn**：不需新依賴，但速度隨 SoC 差異很大（A21s 上整張比 ONNX Runtime 慢 1.3 倍），且官方 Android SDK 5.0.0 的靜態 dnn 缺 `MlasHGemmSupported` 符號，需長期帶著替代實作。
- **ML Kit Text Recognition v2**：遊戲畫面上準確率遠低於 PP-OCRv6（整行 25/79 對 72/79，ROI 讀數 2/28 對 26/28）。
- **ONNX Runtime 的 XNNPACK EP**：辨識模型一開就在 ORT 內部越界讀取而 SIGSEGV；只套用在偵測模型時整張辨識反而變慢 1.2～1.4 倍。
- **PP-OCRv6 tiny**：字典缺常見繁體字（貓、獎、遊、請等）。

## Consequences

- 最佳執行緒數因機型而異（Pixel 7a 2 條、Note20 Ultra 與 A21s 4 條），設錯會慢 2～3 倍。首次使用時校準（試 1／2／4 條取最快），結果可在設定中手動調整，並提供重新測試。
- 下載的 `.so` 只能放在 `filesDir`：`/sdcard/Android/data/<pkg>/files` 被 linker namespace 拒絕，`/data/local/tmp` 無法映射可執行區段。
- 以 GitHub Releases 發布不受 Google Play「從 Play 以外下載可執行程式碼」的政策約束；若日後上架 Play，交付方式要改成 Play 動態功能模組。
- 每支 ABI 各一包；x86_64 的 `libonnxruntime.so` 較大（原始 39.3 MB）。
- OCR 跑在 app 行程（腳本引擎所在），影格來自 UserService。
- 讀數值走 ROI 直接辨識、不經偵測：七段式數字直接辨識 26/28，先偵測會被切碎（18/28）。ROI 補邊要複製邊緣（`BORDER_REPLICATE | BORDER_ISOLATED`），取 ROI 外的真實像素會把邊框讀成多出來的字。
- 低階機整張辨識約 2.3 秒，等文字需限定 ROI 或搭配輪詢間隔。
