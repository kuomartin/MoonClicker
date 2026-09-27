# OCR 實作計畫（issue #118）

目標：依 ADR-0018，在 `:engine` 的 `libmoonclicker_native.so` 內加上 PP-OCRv6 small 推論（ONNX Runtime 以 `dlopen` 載入），OCR 套件首次使用時下載並驗證雜湊，執行緒數首次使用時校準。Lua API（`VisionRequest.text`、`vision.read`）屬於 issue #119，本計畫只提供 native 能力與驗證用的 androidTest。

## A. OCR 套件（發布端，repo `kuomartin/MoonClicker-ocr-pack`）

套件與 app 的唯一接點是 B1 的 URL 與 SHA-256；套件改版頻率遠低於 app，打包與發布放在自己的 repo。

| # | 位置（ocr-pack repo） | 內容 | 理由 |
|---|---|---|---|
| A1 | `build.sh` | 從 Maven Central 下載 `onnxruntime-android` 1.30.0 AAR 取出各 ABI 的 `libonnxruntime.so`；從 Hugging Face `PaddlePaddle/PP-OCRv6_small_{det,rec}_onnx` 取 `inference.onnx`；字典從 rec 的 `inference.yml` 的 `PostProcess.character_dict` 抽成 `dict.txt`；附 ORT（MIT）與 PP-OCR（Apache-2.0）授權檔；每個 ABI 打一包 `ocr-pack-<abi>.zip`，輸出 SHA-256 | 可重現；來源與 spike 相同，準確率數據直接沿用 |
| A2 | 套件內容 | `libonnxruntime.so`、`det.onnx`、`rec.onnx`、`dict.txt`、`LICENSES/`、`pack.json`（套件版本、ORT 版本、各檔 SHA-256） | `pack.json` 讓 app 解壓後可逐檔再驗一次，也記錄版本供升級判斷 |
| A3 | 壓縮格式 | zip | `java.util.zip` 內建；xz 省 5 MB（38.4→33.4 MB）但要多一個依賴 |
| A4 | 發布位置 | 該 repo 的 GitHub Release，tag `v1`、`v2`…，不跟 app 版本走 | 套件內容不隨 app 發版而變，不必每次重傳 38 MB；app 升級時也不必重新下載 |
| A5 | `.github/workflows/release.yml` | 推 `v*` tag 時跑 A1，把各 ABI 的 zip 與 `SHA256SUMS` 上傳到該 tag 的 draft release | 套件由 CI 產出，雜湊可追溯 |
| A6 | ABI | arm64-v8a、x86_64；x86 不提供 | x86 只用於 API 27–29 模擬器；OCR 在 x86 回報「此 ABI 不支援 OCR」 |

## B. 下載與安裝（app 端，`:engine`）

| # | 位置 | 內容 | 理由 |
|---|---|---|---|
| B1 | `engine/…/ocr/OcrPack.kt` | 常數：套件版本、各 ABI 的 URL 與整包 SHA-256（寫死在程式碼） | 雜湊與 app 一起簽章，下載來源被換掉也驗得出來 |
| B2 | `engine/…/ocr/OcrPackInstaller.kt` | `HttpURLConnection` 下載到 `cacheDir`、回報進度、整包 SHA-256 比對、解壓到 `filesDir/ocr/<版本>.tmp/`、逐檔依 `pack.json` 驗雜湊、`rename` 成 `filesDir/ocr/<版本>/`、刪除舊版本目錄 | ADR-0018：`.so` 只能放 `filesDir`；先寫暫存目錄再 rename，中斷不會留下半套 |
| B3 | 狀態 | `StateFlow<OcrPackState>`：`NotInstalled`／`Downloading(bytes, total)`／`Installed(version, dir)`／`Failed(reason)`／`Unsupported(abi)` | 設定頁與腳本執行路徑看同一個狀態 |
| B4 | 觸發時機 | 設定頁的「下載 OCR 元件」按鈕；腳本呼叫 OCR 時若未安裝，Lua 端報錯並指向設定頁 | 38 MB 下載不在背景自動發生；腳本執行緒不能跳 UI |
| B5 | 取消與重試 | 下載中可取消；失敗保留原因並可重試 | — |

## C. Native 推論（`:engine` 的 cpp）

| # | 位置 | 內容 | 理由 |
|---|---|---|---|
| C1 | `engine/src/main/cpp/third_party/onnxruntime/` | 放 ORT 1.30.0 的 `onnxruntime_c_api.h`、`onnxruntime_cxx_api.h`、`onnxruntime_cxx_inline.h`、`onnxruntime_float16.h` 與 LICENSE | 只需標頭；不連結 ORT，APK 內沒有 `libonnxruntime.so` |
| C2 | `OrtLoader.{h,cpp}` | `dlopen(<dir>/libonnxruntime.so)` → `dlsym("OrtGetApiBase")` → `GetApi(ORT_API_VERSION)` → `Ort::InitApi(api)`；編譯時定義 `ORT_API_MANUAL_INIT`；行程內只載入一次，失敗原因字串化 | C++ wrapper 比直接用 C API 少一半樣板；`dlopen` 不 `dlclose`，ORT 內部有 static 狀態 |
| C3 | `Ocr.{h,cpp}` | 從 spike 的 `OcrSpike.cpp` 移植 ORT 路徑：`recognize(const cv::Mat &bgr)`（ROI 直接辨識）與 `detectAndRecognize(const cv::Mat &bgr)`（整張）；CPU EP、`SetIntraOpNumThreads(threads)`、`SetInterOpNumThreads(1)` | 其他 runtime、NCNN、XNNPACK 分支全部不搬 |
| C4 | 框的形狀 | 偵測後處理改用軸對齊框：`boundingRect` → unclip（兩邊各加 d）→ 直接裁切；不用 `minAreaRect`／`warpPerspective` | 遊戲文字幾乎都水平；省掉 OpenCV 5 的 `geometry` 模組（`.so` +3 MB）。E2 若顯示準確率下降，退回 `minAreaRect` 並接受 `geometry` |
| C5 | 色彩 | 影格是 RGBA（`NativeImageReader`），進 OCR 前 `cvtColor(RGBA2BGR)` | PP-OCR 以 BGR 訓練 |
| C6 | ROI 補邊 | ROI 直接辨識前 `copyMakeBorder(pad=8, BORDER_REPLICATE \| BORDER_ISOLATED)` | ADR-0018 Consequences：取 ROI 外真實像素會把邊框讀成字 |
| C7 | 結果 | `struct OcrLine { std::string text; float confidence; cv::Rect box; }`，座標為輸入影像座標，由呼叫端加回 ROI 偏移；整張結果依 y、x 排序 | 影格已是邏輯空間，加回偏移後可直接給 `input.tap` |
| C8 | 生命週期 | `ScriptRuntime` 在第一次 OCR 呼叫時才載入 `Ocr`，腳本結束時釋放；套件路徑與執行緒數由 `nativeStart` 傳入 | 不用 OCR 的腳本不付載入成本（A21s 約 0.6 秒）；ORT 無 OpenMP 限制，直接在腳本執行緒上推論 |
| C9 | 例外 | ORT 的 `Ort::Exception` 在 binding 邊界轉成 Lua error | Lua 以 `longjmp` 收錯，C++ 例外不能穿過 Lua 堆疊 |

## D. 執行緒數校準

| # | 位置 | 內容 | 理由 |
|---|---|---|---|
| D1 | JNI `OcrNative.nativeCalibrate(dir): IntArray` | 以固定形狀的假輸入（det 1×3×448×960、rec 1×3×48×320）對 1／2／4 條各跑 1 次暖機＋3 次，取「det＋rec」中位數最小者，回傳各組耗時 | 推論耗時只取決於張量形狀，不取決於內容；不必隨套件帶校準圖片 |
| D2 | 時機 | 安裝完成後自動跑一次；設定頁「重新測試」再跑；跑的時候顯示進度 | ADR-0018：設錯慢 2～3 倍 |
| D3 | 儲存 | `AppSettings.ocrThreads`（`Auto(校準值)` 或手動 1／2／4）；以 `ScriptRun.ocrThreads` 帶進引擎 | `:engine` 不讀 `:app` 的設定，沿用 `ScriptRun` 傳參的做法 |
| D4 | 衝突 | 腳本執行中不能校準（按鈕停用） | 同時推論會互相拖慢，量到的數字沒有意義 |

## E. 設定頁與驗證

| # | 位置 | 內容 |
|---|---|---|
| E1 | `SettingsScreen` 新增「OCR」區塊 | 狀態列（未安裝／下載中 x%／已安裝 版本／失敗 原因／不支援）、下載／取消／刪除、執行緒數（自動（n 條）／1／2／4）、重新測試（顯示各組耗時）；中英文字串 |
| E2 | `engine/src/androidTest/…/ocr/OcrAccuracyTest.kt` | 讀 `/data/local/tmp/ocr-samples/`（spike 的 16 張截圖＋`ground_truth.json`，手動 push，不進 repo）與已安裝的套件；ROI 讀數與整行完全正確數不低於研究文件（26/28、72/79）；樣本或套件不存在時 `assume` 跳過 |
| E3 | `engine/src/androidTest/…/ocr/OcrLatencyTest.kt` | 同一組樣本量 ROI 讀數與整張的 p50／p95，以校準出的執行緒數跑；結果印 logcat，不斷言數值 |
| E4 | 實機 | A21s 跑 E2、E3，與研究文件的 A21s 表比對（ROI p50 約 80 ms、整張 p50 約 2.3 s）；結果貼在 PR |
| E5 | JVM 單元測試 | `OcrPackInstaller`：雜湊不符、解壓中斷、`pack.json` 逐檔不符、舊版本清除 |

## F. 文件

| # | 檔案 | 改動 |
|---|---|---|
| F1 | `CONTEXT.md` | 新增 **OCR Pack** 詞條 |
| F2 | `docs/research/ocr-engine-selection.md` | 若 C4 的軸對齊框改變準確率，補上數字 |
| F3 | About 頁的開源授權 | 列出 ONNX Runtime、PP-OCRv6 |
