# OCR 用 ONNX Runtime 跑 PP-OCRv6 small，runtime 與模型首次使用時下載

OCR 以 ONNX Runtime 的 CPU EP 執行 PP-OCRv6 small 的偵測與辨識模型。runtime 與模型不進 APK：首次使用時從 `kuomartin/MoonClicker-ocr-pack` 的 GitHub Release 下載 OCR 套件（arm64 約 38 MB），逐檔驗證雜湊後放進 `filesDir`，以 `dlopen` 載入。理由：三台實測機上 ONNX Runtime 的整張辨識都最快、ROI 讀數在最快之列，官方 `.so` 可直接使用，也沒有 OpenMP 的執行緒限制；唯一劣勢是體積，延後下載後不再影響 APK。數據見 `docs/research/ocr-engine-selection.md`。

本 ADR 取代 ADR-0003 中「OCR 未承諾」的部分。

## Considered Options

- **NCNN**：官方版的 libomp 在第一條推論執行緒結束後，之後的推論都會 abort，而腳本每次執行都是新執行緒；自行以 `NCNN_SIMPLEOMP` 編譯可避開，但只剩單執行緒可用，整張辨識慢 1.2～1.6 倍。
- **OpenCV 5 dnn**：不需新依賴，但低階機上整張辨識慢 1.3 倍，且官方 SDK 缺符號，要長期帶著替代實作。
- **ML Kit Text Recognition v2**：遊戲畫面上準確率遠低於 PP-OCRv6（ROI 讀數 2/28 對 26/28）。
- **ONNX Runtime 的 XNNPACK EP**：辨識模型會 crash；只用在偵測模型反而更慢。
- **PP-OCRv6 tiny**：字典缺常見繁體字。

## Consequences

- 最佳執行緒數因機型而異，設錯慢 2～3 倍：首次使用時校準，設定頁可手動調整並重新測試。
- `.so` 只能放 `filesDir`：外部儲存被 linker namespace 拒絕，`/data/local/tmp` 無法映射可執行區段。
- 經 GitHub Releases 發布不受 Google Play「從 Play 以外下載可執行程式碼」的政策約束；若上架 Play，要改用動態功能模組。
- OCR 跑在 app 行程（腳本引擎所在），影格來自 UserService。
- 讀數值走 ROI 直接辨識、不經偵測：七段式數字先偵測會被切碎。ROI 補邊要複製邊緣，取 ROI 外的像素會把邊框讀成字。
- 低階機整張辨識約 2.3 秒：等文字時要限定 ROI 或拉長輪詢間隔。
