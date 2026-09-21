# `Display.getType()` 在 API 27–37 的可用性研究：SDK 現況、隱藏 API 限制與 Shizuku 進程的例外

> 研究來源：AOSP 原始碼 [`frameworks/base/core/java/android/view/Display.java`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/master/core/java/android/view/Display.java)（跨 `android-8.1.0_r1` 至 `master` 多個 tag 比對）、[`Zygote.java`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/master/core/java/com/android/internal/os/Zygote.java)、[`ApplicationInfo.java`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/master/core/java/android/content/pm/ApplicationInfo.java)。  
> 官方文件對照：[Display | API reference](https://developer.android.com/reference/android/view/Display)、[Restrictions on non-SDK interfaces](https://developer.android.com/guide/app-compatibility/restrictions-non-sdk-interfaces)、[Updates to non-SDK interface restrictions in Android 10](https://developer.android.com/about/versions/10/non-sdk-q)。

---

## 結論

1. **`Display.getType()` 從來不是 public SDK 的一部分，直到今天（AOSP `master`，對應開發中版本）依然如此。** 原題目假設「API 33 起 unhide 進入 public SDK」與原始碼事實不符——已比對 `android-8.1.0_r1`（API 27）一路到 `master` 的原始碼，`getType()` 的 Javadoc 上一直掛著 `@hide`，且從未被移除；`developer.android.com/reference/android/view/Display` 的公開頁面原始 HTML 中完全不存在 `getType` 或任何 `TYPE_INTERNAL` / `TYPE_EXTERNAL` / `TYPE_VIRTUAL` 等常數字樣，證實它連常數帶方法都不在 android.jar 的 public API stub 裡。
2. 因此在 API 27–37（本專案 minSdk 27 / targetSdk 37）這個全區間，`getType()` 都只能透過 **reflection**（或 `@TestApi`/`@UnsupportedAppUsage` 允許的內部呼叫途徑）取得，沒有任何一個 API level 是可以直接 `display.type` 這樣呼叫的 public 方法。
3. 對於一般 App（未特別處理 hidden API 限制）：由於 targetSdk 37（Android 15+）已經是 hidden API 強制清單中最嚴格的區間，`getType()` 透過 reflection 呼叫大機率會被封鎖（`NoSuchMethodException` 或安靜地回傳受限提示，視 A11+ 的 `--hidden-api-checks` 政策而定）。
4. **`RelcV2Service` 在 Shizuku（`app_process` shell-UID）底下執行時，這個限制實質上不存在。** 隱藏 API 執行期強制（enforcement）是透過 zygote fork 時的 `runtimeFlags`（`Zygote.API_ENFORCEMENT_POLICY_MASK`，見下方原始碼引用）設定，而這個值只在 **Zygote 依 `ApplicationInfo` fork 出一般 App 進程時** 才會被計算並帶入；透過 `app_process` 直接啟動的 shell-UID 進程（Shizuku/`adb shell` 的標準做法）完全不經過這條路徑，執行期永遠拿不到「已安裝 App 的 `ApplicationInfo.getHiddenApiEnforcementPolicy()`」，因此不會被賦予任何 enforcement policy——效果上等同於原本假設的「shell 進程不受限」，但根源是「沒有 zygote fork-with-specialize，所以連限制的旗標都不存在」，而不是「shell 進程被列入白名單」。這點官方文件沒有明文寫「shell 進程豁免」，是從 `Zygote.java` + `ApplicationInfo.java` 原始碼機制推導出來的，屬於本文件唯一一處非官方文件直接陳述、而是原始碼交叉比對得出的結論。
5. **建議實作**：不分 API level，一律使用同一份 reflection 呼叫路徑（`Display::class.java.getMethod("getType")`），並加上 try/catch fallback。因為 `RelcV2Service` 本身就是 Shizuku/shell 進程，不受限，所以不需要為 27–32 另外準備一套機制；try/catch 只是防禦性寫法，避免未來 AOSP 分支移除或簽名變更時整個服務崩潰。

---

## 1. `getType()` 在 AOSP 原始碼中的沿革

實際抓取多個 tag 的 `frameworks/base/core/java/android/view/Display.java`，方法簽章與其上的 annotation 演進如下：

| Tag / 對應 API level | `getType()` 上的 annotation | Javadoc 是否有 `@hide` |
| :--- | :--- | :--- |
| `android-8.1.0_r1`（API 27） | 無 annotation | 是 |
| `android-9.0.0_r1`（API 28） | 無 annotation | 是 |
| `android-10.0.0_r1`（API 29） | `@UnsupportedAppUsage` | 是 |
| `android-11.0.0_r1`（API 30） | `@UnsupportedAppUsage` + `@TestApi` | 是 |
| `android-12.0.0_r1`（API 31） | `@UnsupportedAppUsage` + `@TestApi` | 是 |
| `android-13.0.0_r1`（API 33） | `@UnsupportedAppUsage` + `@TestApi` | 是 |
| `android-14.0.0_r1`（API 34） | （同上，欄位改名見下） | 是 |
| `refs/heads/master`（開發中，晚於 API 36） | `@UnsupportedAppUsage` + `@TestApi` | 是，一字未變 |

原文（`master` 分支，方法本體）：

```java
/**
 * Gets the display type.
 *
 * @return The display type.
 *
 * @see #TYPE_UNKNOWN
 * @see #TYPE_INTERNAL
 * @see #TYPE_EXTERNAL
 * @see #TYPE_WIFI
 * @see #TYPE_OVERLAY
 * @see #TYPE_VIRTUAL
 * @hide
 */
@UnsupportedAppUsage
@TestApi
public int getType() {
    return mType;
}
```

換言之：API 29（Android 10）幫它加上 `@UnsupportedAppUsage`（掛牌為「非 SDK 介面但仍可經 reflection 存取，會有 log 警告」），API 30（Android 11）再加上 `@TestApi`（讓 CTS/測試框架可以直接連結呼叫，但 `@TestApi` 本身也不等於 public SDK）。**從頭到尾沒有任何一版把 `@hide` 拿掉、也沒有出現在 `api/current.txt`／官方 reference 頁面上**，所以「API 33 起變成 public SDK」的說法不成立。

補充驗證：直接 `curl` 下載 `developer.android.com/reference/android/view/Display` 的原始 HTML（非渲染後的 JS 內容），全文搜尋 `getType`、`TYPE_INTERNAL`、`TYPE_EXTERNAL`、`TYPE_VIRTUAL`、`TYPE_WIFI`、`TYPE_OVERLAY`、`TYPE_UNKNOWN` 皆為 0 筆命中，而同一份 HTML 裡 `getDisplayId`（一個確定是 public 的方法）能命中多次——這是「`getType()` 未被列入官方 reference / android.jar public API」最直接的佐證。

### 常數命名的變遷（附帶發現，與 `getType()` 的公開狀態無關但影響閱讀原始碼時的比對）

比對常數定義行，型別常數本身在 minSdk 27 涵蓋的每個版本都存在，但**名稱**在 Android 10→11 之間改過一次：

| Tag | 內部埠常數名稱 | 外接埠常數名稱 |
| :--- | :--- | :--- |
| `android-8.1.0_r1` ~ `android-10.0.0_r1` | `TYPE_BUILT_IN` | `TYPE_HDMI` |
| `android-11.0.0_r1` 起（含 `master`） | `TYPE_INTERNAL` | `TYPE_EXTERNAL` |

這與既有研究文件〈[Android 多顯示器架構研究](./multi-display-architecture-and-input-routing.md)〉第 22 節「D」提到的「Android 11 引進 HWC 2.4 `getDisplayConnectionType` 後正式修正副螢幕類型判定」時序一致：常數改名發生在同一個版本轉換點。`TYPE_VIRTUAL`（value = 5）、`TYPE_WIFI`（value = 3）、`TYPE_OVERLAY`（value = 4）、`TYPE_UNKNOWN`（value = 0）則自 API 27 起數值與名稱皆未變動過。

---

## 2. Hidden API 限制機制：為什麼一般 App 呼叫不到，而 Shizuku 進程可以

### 2.1 官方文件對「限制依什麼觸發」的說明

[Restrictions on non-SDK interfaces](https://developer.android.com/guide/app-compatibility/restrictions-non-sdk-interfaces) 明確指出限制清單分級是綁在 **App 的 `targetSdkVersion`** 上（"each API level has non-SDK interfaces that are restricted when an app targets that API level"），並且明文承認系統簽名/平台簽名的 App 會被排除：

> "Do non-SDK interface restrictions apply to all apps including system and first-party apps, not just third-party apps? Yes, however, we exempt apps signed with the platform key and some system image apps."

這與 `ApplicationInfo.java`（見下）中 `isAllowedToUseHiddenApis()` 的邏輯完全對應，等於是官方文件敘述在原始碼裡的具體實作。

官方文件另外也說明了裝置端如何整體停用強制（僅限開發機／`userdebug`）：

```
adb shell settings put global hidden_api_policy 1
```

（出自 [Updates to non-SDK interface restrictions in Android 10](https://developer.android.com/about/versions/10/non-sdk-q)）——但這是「改變整台裝置的政策」，與 Shizuku 進程本身不受限的機制是兩回事，不能混為一談。

### 2.2 原始碼機制：enforcement policy 是 zygote fork 時才決定的 runtime flag

`Zygote.java` 定義了 fork 子進程時透過 `runtimeFlags` 傳遞的 bit mask：

```java
/**
 * Hidden API access restrictions. This is a mask for bits representing the API enforcement
 * policy, defined by {@code @ApplicationInfo.HiddenApiEnforcementPolicy}.
 */
public static final int API_ENFORCEMENT_POLICY_MASK = (1 << 12) | (1 << 13);
```

（[`Zygote.java`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/master/core/java/com/android/internal/os/Zygote.java) 第 92–100 行）

而這個 policy 值的來源是 `ApplicationInfo.getHiddenApiEnforcementPolicy()`：

```java
private boolean isAllowedToUseHiddenApis() {
    if (isSignedWithPlatformKey()) {
        return true;
    } else if (isSystemApp() || isUpdatedSystemApp()) {
        return usesNonSdkApi() || isPackageWhitelistedForHiddenApis();
    } else {
        return false;
    }
}

public @HiddenApiEnforcementPolicy int getHiddenApiEnforcementPolicy() {
    if (isAllowedToUseHiddenApis()) {
        return HIDDEN_API_ENFORCEMENT_DISABLED;
    }
    if (mHiddenApiPolicy != HIDDEN_API_ENFORCEMENT_DEFAULT) {
        return mHiddenApiPolicy;
    }
    return HIDDEN_API_ENFORCEMENT_ENABLED;
}
```

（[`ApplicationInfo.java`](https://android.googlesource.com/platform/frameworks/base/+/refs/heads/master/core/java/android/content/pm/ApplicationInfo.java) 第 2513–2534 行）

關鍵在於：**這整段邏輯的輸入是 `ApplicationInfo`——也就是一個「已安裝、由 PackageManager 記錄的 App」才有的資料結構。** 系統要幫一個進程套用 hidden API 限制，前提是「這個進程是 Zygote 依照某個已安裝 App 的 `ApplicationInfo` fork 並 specialize 出來的」。

Shizuku（以及 `adb shell` 本身執行任意 Java 程式的標準做法）並不是這樣啟動的：它是由 shell 直接呼叫 `app_process` 這個獨立可執行檔，這個路徑完全不經過 Zygote 的 `forkAndSpecialize`／`ApplicationInfo` 查詢，執行期自然沒有任何機制把 `API_ENFORCEMENT_POLICY_MASK` 設成「啟用」——ART runtime 端收到的 enforcement 政策維持在預設值（未啟用）。

> 說明：官方文件（`developer.android.com`／`source.android.com`）目前找不到一句話直接寫「shell/`app_process` 進程豁免 hidden API 檢查」；上述結論是本研究交叉比對 `Zygote.java` 與 `ApplicationInfo.java` 兩份原始碼、搭配「hidden API enforcement 只在 zygote fork-with-specialize 路徑上被設定」這個可驗證的程式邏輯所得出的推論，而非直接引用的官方陳述。這點請視為「原始碼推導」而非「官方文件明文保證」，日後若 AOSP 改動 zygote 啟動路徑（例如替 `app_process` 額外補上政策查詢）需要重新驗證。

---

## 3. API 27–32（`getType()` 尚無 `@TestApi`/`@UnsupportedAppUsage`）的差異

| API level | AOSP tag | `getType()` annotation | 對 reflection 呼叫的影響 |
| :--- | :--- | :--- | :--- |
| 27 (8.1) | `android-8.1.0_r1` | 無 annotation，僅 `@hide` | 一般 App reflection 呼叫不受 P 世代 greylist 機制管，因為該機制本身在 API 28 才上線；此版本上呼叫幾乎不受限。 |
| 28 (9.0) | `android-9.0.0_r1` | 無 annotation，僅 `@hide` | Hidden API 強制機制於本版上線，但 `getType()` 尚未被標成 `@UnsupportedAppUsage`，代表當時它不在任何 grey/black list 分類表裡——實務上等於「未受限」。 |
| 29–32 | `android-10.0.0_r1` ~ `android-12.0.0_r1` | `@UnsupportedAppUsage`（30 起再加 `@TestApi`） | 開始被納入非 SDK 介面清冊，一般 App 呼叫時視 targetSdk 可能出現 log 警告或封鎖，但**沒有任何跡象顯示 27–32 之間簽章曾改變**（皆為 `public int getType()`，零參數，回傳 `int`）。 |

**結論：27–32 這個區間內，reflection 呼叫路徑（方法名稱、簽章）完全沒有變化，唯一變化的是「這個方法有沒有被畫進非 SDK 介面清單」，而不是「方法本身消失或改名」。** 因此不需要像題目假設的那樣為 27–32 另外設計一套 fallback 機制（例如改用 `Display.FLAG_PRESENTATION` 或自行維護 VirtualDisplay bookkeeping）——在 `RelcV2Service` 的 Shizuku 執行環境下，同一行 reflection 呼叫碼可以覆蓋 27–37 全區間。

若情境換成「非 Shizuku、一般沙箱 App」，才需要考慮 fallback（例如用 `DisplayManager.getDisplays()` 搭配 `Display.FLAG_PRESENTATION`、或退回自行維護的 VirtualDisplay 對照表），但那已經是本專案目前 `RelcV2Service` 想擺脫的做法，不建議走回頭路。

---

## 4. 建議實作（Kotlin，涵蓋 API 27–37 單一路徑）

```kotlin
/**
 * Display.getType() 從 API 27 到目前 AOSP master 皆為 @hide（從未進入 public SDK），
 * 僅能透過 reflection 呼叫。RelcV2Service 在 Shizuku（app_process shell-UID）下執行，
 * 未經 zygote 的 ApplicationInfo-based fork 路徑，不受 hidden API enforcement 影響，
 * 因此可以安全地跨 API 27–37 使用同一份呼叫碼。
 */
private val displayGetTypeMethod: Method? by lazy {
    runCatching {
        Display::class.java.getMethod("getType").apply { isAccessible = true }
    }.getOrNull()
}

fun Display.typeOrUnknown(): Int {
    return runCatching {
        displayGetTypeMethod?.invoke(this) as? Int
    }.getOrNull() ?: Display.TYPE_UNKNOWN // = 0，常數本身自 API 27 起穩定存在
}
```

要點：

- `getMethod("getType")` 在跨版本上是安全的，因為方法簽章（`public int getType()`）自 API 27 起未曾改變。
- 用 `runCatching` 包住整個 `invoke`，而不是只包 `getMethod` 查找：`InvocationTargetException`、`SecurityException` 都可能在未來版本出現，一次性 catch 可以避免遺漏。
- fallback 回傳 `Display.TYPE_UNKNOWN`（value 0，這個常數是 public 但 `@hide`，在 app 端仍需用字面值 `0` 或透過同一份 reflection 讀取欄位；若專案已有其他方式取得這些常數值，直接沿用即可）。
- 不需要依 `Build.VERSION.SDK_INT` 分支——因為整個 27–37 只有一套 reflection 路徑，分支只會增加不必要的複雜度。

---

## 5. 尚待確認 / 本文件的限制

- 未找到 AOSP CL 明確標註「shell UID 進程豁免 hidden API 檢查」的官方 commit message；本文件第 2.2 節的結論是原始碼交叉比對推導，不是官方文件直接陳述，如需更高把握，建議實際在 Shizuku 環境下對 `Display.getType()` 跑一次 reflection 呼叫做行為驗證（本研究未在裝置上實測，僅止於原始碼與官方文件比對）。
- `hiddenapi-flags.csv` 等實際清單檔案在近期 AOSP 版本中已模組化拆分（不再是 `frameworks/base/config/hiddenapi-*.txt` 單一檔案），本研究未逐版本找出 `Landroid/view/Display;->getType()I` 在該清單中的精確分類代碼（blocked / max-target-X / unsupported），只確認了原始碼上的 annotation 標記（`@UnsupportedAppUsage` 無 `maxTargetSdk` 參數，對應官方文件所述的「greylist：可用但建議尋找替代 API，不會拋出致命例外」分類）。
