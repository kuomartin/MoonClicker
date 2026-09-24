# OCR 引擎選型

對應計畫 `docs/plans/ocr-engine-spike-plan.md`。文中引用的量測程式（`tools/ocr-spike/`、`engine/src/main/cpp/OcrSpike.cpp`、`NativeOcrSpikeTest`、`MlKitOcrSpikeTest`）只存在於 `spike/ocr-engine` 分支，不進 master。已完成電腦端、Pixel 7a（Tensor G2）、Galaxy Note20 Ultra（Snapdragon 865）與 Galaxy A21s（低階，Exynos 850）。

## 結論

- 模型用 PP-OCRv6 small。tiny 的字典缺常見繁體字，排除；ML Kit 在遊戲畫面上準確率遠低於 PP-OCRv6（整行 25/79 對 72/79，ROI 讀數 2/28 對 26/28），排除。
- runtime 不隨 APK 發布，與模型一起在首次使用時下載到 `filesDir` 後 `dlopen`（三台實測可行，見「執行期載入」）。APK 大小因此不列入選型，只看效能。
- 選定 ONNX Runtime（ADR-0018）。效能以各 runtime 在各機型的最佳執行緒數比較（見「執行緒與 fp16 掃描」）：ONNX Runtime 在三台上整張辨識都最快或並列最快，ROI 讀數也在最快之列；OpenCV dnn 與 NCNN（SIMPLEOMP）都有至少一台明顯落後。ONNX Runtime 官方 `.so` 可直接下載使用，不必自行編譯，也沒有 OpenMP 的執行緒限制。
- 最佳執行緒數因機型而異（Pixel 7a 2 條、Note20 Ultra 與 A21s 4 條），固定錯了會慢到 2 倍以上，正式實作需要依機型決定執行緒數。
- NCNN 官方版靜態連結的 libomp 有一條硬限制：第一條呼叫過 OpenMP 的執行緒結束後，行程內任何推論都會 abort；自行以 `NCNN_SIMPLEOMP` 編譯可消除限制（三台 144 次實驗全數通過），但多執行緒會慢 2～4 倍，只剩單執行緒可用。
- fp16（僅辨識模型）不影響準確率，NCNN 單執行緒下的 ROI 讀數快將近一倍。
- 讀數值走「ROI 直接辨識」：七段式數字在 ROI 直接辨識下 26/28、錢包數值全對；先偵測再辨識會把數字切碎並插入小數點（18/28）。ROI 補邊必須複製邊緣而非取 ROI 外的真實像素，否則邊框線會被讀成多出來的字。
- 腳本引擎（`ScriptEngine`）執行在 app 行程，只有影格來自 UserService；OCR 因此跑在一般 app 行程。
- 低階機上整張辨識約 2.3 秒，不適合每幀輪詢；等文字時需要限定 ROI 或搭配輪詢間隔參數。

## 準確率

樣本：`~/Downloads/Sample` 16 張 2400×1080 截圖（貓咪大戰爭 14 張、Alto 系列 2 張），標準答案 `tools/ocr-spike/ground_truth.json`：79 行文字、28 個 ROI 讀數。

| 引擎 | 整行完全正確 | 整行平均 CER | ROI 讀數 |
|---|---|---|---|
| PP-OCRv6 small（三個 runtime，電腦與 Pixel 7a 相同） | 72/79 | 0.02 | 26/28 |
| PP-OCRv6 tiny（電腦） | 38/79 | 0.154 | 19/28 |
| ML Kit Text Recognition v2 中文（bundled，Pixel 7a） | 25/79 | 0.237 | 2/28 |

Pixel 7a 上的 ROI 讀數實測為 23/28；差的 3 筆來自當時的補邊錯誤（取了 ROI 外的卡片邊框），把裝置端存下的裁切圖拿回電腦跑，輸出逐字相同，確認推論一致。補邊改為 `BORDER_REPLICATE | BORDER_ISOLATED` 後，A21s 上三個 runtime 皆為 26/28，與電腦端相同。

ML Kit 的典型錯誤：漏掉按鈕上的 `OK`、`關`→`開`、七段式數字 `7602`→`1602-`、`13500`→`1B500`。unbundled 版（B2）與 bundled 版用同一模型，只差交付方式，未另測。

## 延遲與大小

### Pixel 7a

4 執行緒。ROI 讀數每個 ROI 重複 5 次、去掉第一次；整張為偵測（長邊 960）加逐行辨識。

以 instrumented test（`NativeOcrSpikeTest#measure`）跑 3 輪，每輪獨立行程、輪換 backend 順序（cv→ort→ncnn、ncnn→cv→ort、ort→ncnn→cv）、輪間冷卻 60 秒；表中為 3 輪的中位數，各輪之間差距在 ±10% 內，排名三輪一致。

| runtime | ROI 讀數 p50／p95 | 整張 p50／p95 | 偵測 p50 | 載入 | 原生函式庫增量（stripped） | 壓縮後 |
|---|---|---|---|---|---|---|
| OpenCV 5 dnn | 43／92 ms | 1164／1477 ms | 343 ms | 188 ms | +9.4 MB | +2.6 MB |
| ONNX Runtime 1.30 | 61／113 ms | 1822／2132 ms | 591 ms | 239 ms | +33.0 MB（官方 AAR 的 `libonnxruntime.so`） | +12.4 MB |
| NCNN 20260526 | 51／101 ms | 1561／1916 ms | 525 ms | 102 ms | +4.9 MB | +2.2 MB |
| ML Kit（bundled） | 317／386 ms | 546／658 ms | — | 1534 ms | 未量 | 未量 |

同一台機器在腳本引擎內（app 行程，MoonClicker UI 在前景）量得的數字與上表不同：整張 ONNX Runtime 998 ms、NCNN 1150 ms、OpenCV dnn 1166 ms，兩輪之間波動約 ±30%。高階機上三者的差距小於環境造成的變動，不足以作為選型依據。ML Kit 一列為腳本引擎外的 instrumented test 單輪結果。

### Galaxy A21s

Exynos 850，條件與上表相同（3 輪中位數）；三輪之間差距在 ±3% 內。

| runtime | ROI 讀數 p50／p95 | 整張 p50／p95 | 偵測 p50 | 載入 |
|---|---|---|---|---|
| OpenCV 5 dnn | 90／196 ms | 2724／3529 ms | 740 ms | 444 ms |
| ONNX Runtime 1.30 | 81／154 ms | 2329／2898 ms | 766 ms | 634 ms |
| NCNN 20260526 | 74／135 ms | 1974／2514 ms | 650 ms | 138 ms |

### Galaxy Note20 Ultra

SM-N9810，Snapdragon 865，Android 13；條件相同（3 輪中位數）。

| runtime | ROI 讀數 p50／p95 | 整張 p50／p95 | 偵測 p50 | 載入 |
|---|---|---|---|---|
| OpenCV 5 dnn | 42／83 ms | 1068／1364 ms | 269 ms | 163 ms |
| ONNX Runtime 1.30 | 24／49 ms | 663／796 ms | 202 ms | 204 ms |
| NCNN 20260526 | 39／58 ms | 608／752 ms | 201 ms | 64 ms |

量測前定的 OpenCV dnn 採用門檻：準確率相同、ROI 讀數 p50 ≤ 100 ms、ROI 讀數不超過 NCNN 的 1.25 倍、整張不超過 NCNN 的 1.4 倍（A21s 上的落差）。前三項通過，整張為 1.76 倍，未通過。

低階機上整張辨識約 2 秒，不適合每幀輪詢；`vision.wait` 等文字時需要搭配路線圖的輪詢間隔參數，或限定 ROI。

### 大小

三個 PP-OCRv6 runtime 共同的額外成本：OpenCV 5 把 `minAreaRect`、`getPerspectiveTransform` 移到 `geometry` 模組，連同前後處理程式碼使 `libmoonclicker_native.so` 從 8.5 MB 增加到 11.5 MB（壓縮後 +1.4 MB）。遊戲文字幾乎都是水平的，正式實作可改用軸對齊框，省掉 `geometry`。

模型：det 9.9 MB、rec 21.2 MB（ONNX 與 NCNN 格式大小相同，壓縮後合計約 26 MB）。

## 執行緒與 fp16 掃描

每台 12 組設定：OpenCV dnn、ONNX Runtime 各 1／2／4 執行緒；NCNN（`NCNN_SIMPLEOMP` 自行編譯版）1／2／4 執行緒與只用大核（`set_cpu_powersave(2)`，執行緒數為大核數）；NCNN 辨識模型 fp16 搭配 1 執行緒與只用大核。每台 3 輪、輪換順序、每輪獨立行程、輪間冷卻 60 秒；表中為中位數。A21s 的 spike 程式刻意不釋放各組實例，第 1～3 輪各有最後一兩組因記憶體不足被 lowmemorykiller 終止，缺的組另以獨立行程補跑。程式：`tools/ocr-spike/run_sweep.sh`、`sweep_summary.py`。

所有組合在三台上的準確率都是 ROI 讀數 26/28、整行 72/79，包括 fp16。

各 runtime 在各機型的最佳設定（ROI 讀數 p50／整張 p50）：

| runtime | Pixel 7a | Note20 Ultra | Galaxy A21s |
|---|---|---|---|
| ONNX Runtime | 35 ms／1.11 s（2 條） | 31 ms／0.74 s（4 條） | 80 ms／2.33 s（4 條） |
| OpenCV dnn | 43 ms／1.42 s（2 條） | 37 ms／0.89 s（4 條） | 108 ms／3.00 s（4 條） |
| NCNN SIMPLEOMP fp16 | 33 ms／1.19 s（1 條） | 33 ms／1.20 s（大核） | 109 ms／2.72 s（大核） |
| NCNN SIMPLEOMP fp32 | 69 ms／1.55 s（大核） | 60 ms／1.74 s（1 條） | 149 ms／3.43 s（大核） |
| 參考：NCNN 官方版（libomp）4 條 fp32，前一輪量測 | 51 ms／1.56 s | 39 ms／0.61 s | 74 ms／1.97 s |

- 最佳執行緒數因機型不同：ONNX Runtime 在 Pixel 7a 上 4 條比 2 條慢 2.1 倍，在 A21s 上 1 條比 4 條慢 3.1 倍。
- NCNN SIMPLEOMP 多執行緒一律變慢（2～4 倍），因為它的執行緒池以 mutex／condition variable 派工、不自旋等待；只用大核在大核數大於 1 時同樣落入這條路徑。
- NCNN 官方版（libomp）多執行緒在 Note20 Ultra 與 A21s 上是整張辨識最快的組合，但帶有執行緒限制；它搭配 fp16 的組合未量測。
- Pixel 7a 各輪之間電池溫度從 38.6°C 升到 43.1°C，個別輪次的整張辨識波動較大；中位數可參考。

## ONNX Runtime XNNPACK EP

官方 AAR 內建 XNNPACK EP。依 ORT 文件的建議用法：ORT 本身 intra-op 1 條、關閉自旋，平行化交給 XNNPACK 的執行緒池。

- 辨識模型開 XNNPACK 必定 SIGSEGV：ORT 內部讀過自己配置的中間張量尾端（讀取位址落在頁面邊界）；把輸入緩衝區尾端加大後仍然發生，非呼叫端問題。
- 只讓偵測模型走 XNNPACK、辨識維持 CPU EP，Pixel 7a 與 Note20 Ultra 各 3 輪（A21s 未測）：

| 設定 | Pixel 7a 整張 p50 | Note20 Ultra 整張 p50 |
|---|---|---|
| CPU EP，最佳執行緒數 | 1136 ms（2 條） | 670 ms（4 條） |
| 偵測走 XNNPACK，同樣執行緒數 | 1304 ms | 938 ms |

整張辨識反而變慢 1.2～1.4 倍，準確率不變。ROI 讀數只用辨識模型，兩組設定相同。

## 首次下載大小（arm64）

| 檔案 | 原始 | zip | xz |
|---|---|---|---|
| `libonnxruntime.so` | 33.0 MB | 12.4 MB | 7.8 MB |
| 偵測模型 | 9.9 MB | 7.5 MB | 7.4 MB |
| 辨識模型 | 21.2 MB | 18.5 MB | 18.2 MB |
| 字典 | 0.07 MB | 0.04 MB | 0.01 MB |
| 合計 | 64.1 MB | 38.4 MB | 33.4 MB |

## 執行期載入

不連結 ORT 的建置（APK 內沒有 `libonnxruntime.so`），把官方 `libonnxruntime.so` 1.30.0 放在不同位置後 `dlopen`，經 `OrtGetApiBase` 取 C API，載入 PP-OCRv6 偵測模型並推論一次。`libonnxruntime.so` 只依賴 libc／libm／libdl／liblog／libandroid，且以 16 KB 分頁對齊。

| 位置 | Pixel 7a（Android 17 beta） | Note20 Ultra（Android 13） | A21s（LineageOS，Android 16） |
|---|---|---|---|
| app `filesDir` | 成功 | 成功 | 成功 |
| `/sdcard/Android/data/<pkg>/files` | 失敗：linker namespace 不允許 | 未測 | 未測 |
| `/data/local/tmp` | 失敗：無法映射可執行區段 | 未測 | 未測 |

測試 APK 與正式 app 的 targetSdk 同為 37。以 GitHub Releases 發布不受 Google Play「從 Play 以外下載可執行程式碼」的政策約束；若日後上架 Play，需改用 Play 動態功能模組交付。

## 整合上的坑

| runtime | 問題 | 處理 |
|---|---|---|
| OpenCV 5 dnn | 官方 Android SDK 5.0.0 的 `libopencv_dnn.a` 內嵌 MLAS，但缺 `MlasHGemmSupported`，直接連結失敗 | 自行定義回傳 false 的替代函式，dnn 改走 fp32；正式採用需保留此 workaround 或自行編譯 OpenCV |
| ONNX Runtime | 官方 AAR 是完整版，arm64 單一 `.so` 32 MB；C++ API 需要例外 | 縮小需自行編譯 minimal build |
| NCNN | 第一條呼叫過 OpenMP 的執行緒結束後，任何執行緒再推論都會在 `__kmp_affinity_initialize` abort；`ncnn` target 的介面選項帶 `-fno-rtti -fno-exceptions`，會關掉整個函式庫的例外；`NCNN_SIMPLEOMP` 版的介面要求連結 Android 上不存在的 `libpthread` | OCR 固定在與行程同壽命的工作執行緒，或改用 `NCNN_SIMPLEOMP`；清除 target 的 `INTERFACE_COMPILE_OPTIONS`；從介面移除 `pthread` |
| NCNN（Python） | `ncnn.Mat` 不複製 numpy 緩衝區，暫存陣列被回收後輸入變成垃圾 | 輸入陣列保留到 `extract` 結束 |
| 共通 | `copyMakeBorder` 作用在 ROI 子矩陣時會取 ROI 外的真實像素補邊 | 加 `BORDER_ISOLATED` |

## NCNN 執行緒限制

ncnn-20260526-android 靜態連結 libomp（`-fopenmp -static-openmp`）。在 Pixel 7a 與 A21s 上，以 `NativeOcrSpikeTest#threadCase` 逐一執行下列案例，每個案例用獨立的 `am instrument`（獨立行程）跑 3 次，`num_threads` 為 4 與 1 各一組，共 96 次；兩台、兩種執行緒數、每次重複結果完全一致。程式見 `OcrSpike.cpp` 的 `runThreadCase`、`tools/ocr-spike/run_thread_cases.sh`。

| 案例 | 情境 | 結果 |
|---|---|---|
| 0 | A 建立 Net 並推論 | 通過 |
| 1 | A 建立並推論；A 還活著時，B 推論同一個 Net | 通過 |
| 2 | A 建立並推論後結束；B 推論同一個 Net | abort |
| 3 | A 建立並推論後結束；B 建立全新的 Net 再推論 | abort |
| 4 | A 建立並推論後停住不結束；B 建立全新的 Net 再推論 | 通過 |
| 5 | 三代執行緒依序各建各的 Net、推論、結束 | 第二代 abort |
| 6 | A、B 同時各自持有 Net 並推論 | 通過 |
| 7 | 固定一條工作執行緒持有 Net；三代呼叫端執行緒送工作後結束 | 通過 |

abort 一律發生在 `__kmpc_fork_call` → `__kmp_parallel_initialize` → `__kmp_affinity_initialize` 的斷言。

- 限制與 Net 屬於哪條執行緒無關（案例 1、3、4），關鍵是**第一條呼叫過 OpenMP 的執行緒是否已結束**（案例 2、3、5）。推測 libomp 在這條執行緒結束時關閉 runtime，下一次呼叫重新初始化時 affinity 狀態未重設而斷言失敗。
- `num_threads = 1` 同樣 abort：NCNN 在單執行緒下仍會進入 OpenMP runtime，降執行緒數不能繞過。
- 呼叫端執行緒可以任意建立與結束，只要推論固定交給同一條長壽的工作執行緒（案例 7）。腳本引擎每次執行都是新執行緒，所以 OCR 不能直接在腳本執行緒上跑。
- 以 `NCNN_SIMPLEOMP=ON` 自行編譯 NCNN（NCNN 內建的簡化 OpenMP runtime，不連結 libomp）後，同一組案例在三台上 144 次全數通過，限制消失；代價見「執行緒與 fp16 掃描」。`NCNN_SIMPLEOMP` 版的原生函式庫比官方版小 0.83 MB（壓縮後 0.31 MB）。

## small 的錯誤

| 類型 | 例子 | 對策 |
|---|---|---|
| 小字的 `/` 被讀成 `1` | 全畫面偵測時 `228000/228000` → `2280001228000`；同一 ROI 直接辨識正確 | 讀數用 ROI 直接辨識 |
| 七段式數字被切碎 | 偵測＋辨識把 `1050元` 讀成 `10.50元` | 同上 |
| ROI 直接辨識仍錯 | `375元` → `375完`、`750元` → `C750元`（ROI 左緣含卡片邊框） | ROI 框緊一點；腳本端只取數字 |
| 框外雜訊 | `合作紀念禮包` → `合作紀念禮包唱`、`SKIP` → `SKIPH`（圖示被讀成字） | 找文字時用包含比對而非全等 |
| 小字漏字 | `LEVEL 6` → `LEVEL` | 未處理 |

## 環境

| 項目 | 內容 |
|---|---|
| 模型 | Hugging Face `PaddlePaddle/PP-OCRv6_{tiny,small}_{det,rec}_onnx` 官方 ONNX；NCNN 由 pnnx 20260526 轉換（det `inputshape=[1,3,640,640] inputshape2=[1,3,960,448]`、rec `inputshape=[1,3,48,320] inputshape2=[1,3,48,640]`，皆 fp32） |
| 電腦端 | opencv-python-headless 5.0.0.93、onnxruntime 1.30.0、ncnn 1.0.20260526；`tools/ocr-spike/spike.py` |
| 裝置端 | Pixel 7a、Galaxy Note20 Ultra、Galaxy A21s；效能以 `tools/ocr-spike/run_bench.sh` 量測；OpenCV Android SDK 5.0.0、onnxruntime-android 1.30.0、ncnn-20260526-android（CPU）、ML Kit `text-recognition-chinese` 16.0.1；Lua 綁定 `vision._ocr_spike`（`engine/src/main/cpp/OcrSpike.cpp`），ML Kit 以 instrumented test（`MlKitOcrSpikeTest`）量測，A21s 以 `NativeOcrSpikeTest` 執行 Lua 量測腳本；`tools/ocr-spike/make_device_script.py`、`eval_device.py` |
| 前後處理 | det 長邊縮到 960、ImageNet 正規化、DB 後處理（thresh 0.2、box_thresh 0.45、unclip 1.4）；rec 高 48、寬依比例、CTC greedy 解碼 |
