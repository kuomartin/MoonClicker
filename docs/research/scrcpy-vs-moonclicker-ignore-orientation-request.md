# scrcpy 為什麼沒有 letterbox：`ignoreOrientationRequest` 假說的查證與推翻

研究問題：MoonClicker 把橫向 Unity 遊戲（`com.noodlecake.altosadventure`，見
`docs/examples/Alto's Adventure/`）啟動進自建 VD（帶
`PUBLIC | PRESENTATION | OWN_CONTENT_ONLY | SUPPORTS_TOUCH | ROTATES_WITH_CONTENT`）後，
畫面被 letterbox；只有點一下 Android 12+ 的 size-compat「回覆最佳化」（letterbox
restart）按鈕，內容才會正確填滿橫向畫面。同一支 App、同樣的 flag 組合，
scrcpy 4.1 的 `--new-display=1080x2400 --start-app=...` 卻沒有這個問題。
背景假說（見任務提示）認為根因是某種 per-display 的「忽略方向請求」語意
（`DisplayWindowSettings`/`setIgnoreOrientationRequest`）。本文逐項查證這個假說，
**結論是：查無此事——`ignoreOrientationRequest` 在兩邊都是預設值 `false`，
與 `ROTATES_WITH_CONTENT`、與 display 是否 trusted 都無關。** 真正對得上「letterbox +
按鈕修好」這個症狀的機制是 Activity 層級的 **Size Compat Mode**
（`ActivityRecord.shouldCreateAppCompatDisplayInsets()` /
`AppCompatSizeCompatModePolicy`），這與 `DisplayWindowSettings` 完全是兩條不同的程式碼路徑。
scrcpy 與 MoonClicker 為什麼在同一個機制下走向不同結果，本文查到能查到的地方，
查不到的地方明確標注「未找到佐證，以下為推論」。

研究日期 2026-09-21。

**方法**：scrcpy 原始碼讀 `https://github.com/Genymobile/scrcpy`，commit
`19c1261d2e2cbf2b5e6a71a8b64cc1dd3ede06ac`（`v4.1-1-g19c1261`），與既有筆記
`scrcpy-rotation-handling.md`、`scrcpy-new-display-vs-am-start-orientation.md`
同一個 commit，方便交叉引用；用 `raw.githubusercontent.com` 直接抓檔案比對，
不透過任何中介摘要。AOSP 原始碼讀
`https://android.googlesource.com/platform/frameworks/base`，固定在 tag
`android-15.0.0_r20`（與既有筆記 `scrcpy-new-display-vs-am-start-orientation.md`
同一個 tag），用 `?format=TEXT`（base64）直接抓完整檔案內容逐行核對，不透過摘要工具，
避免大檔案被截斷或被二手轉述。下文引用格式為 `檔名:行號`，行號對應上述 tag/commit；
GitHub 與 googlesource 都支援 `#L<n>` 錨點，本文的連結一律帶行號錨點。
MoonClicker 端讀 `engine/src/main/java/com/xaxaxax/moonclicker/MoonClickerService.kt`
（本次研究當下的工作樹版本，非某個 commit——這是活的原始碼，行號可能之後會漂）。

**沒有驗證的部分**：沒有在真機上重現「MoonClicker letterbox、scrcpy 不 letterbox」
這組對照實驗，也沒有實際跑過 scrcpy。本文查的是「兩邊呼叫的 API 與 AOSP 對這些
API 的處理邏輯，字面上有沒有差異」，不是「量出兩邊實際行為不同」的實測報告。
第 4 節結尾有一段本文查不到的部分，明確標注。

---

## TL;DR

1. **`ignoreOrientationRequest` 的預設值是 `false`，而且與 display 是不是
   virtual/trusted 無關——查證結論，不是假說。**
   `DisplayWindowSettings.applyRotationSettingsToDisplayLocked()` 讀到
   `settings.mIgnoreOrientationRequest == null` 時，三元運算式直接給 `false`
   （見 Q2），沒有任何分支依 display 的 trusted/virtual/public 屬性給不同預設值。
   `DisplayArea.getIgnoreOrientationRequest()` 的欄位
   `mSetIgnoreOrientationRequest` 是 Java `boolean` 欄位，未賦值前就是 `false`。
   **兩層防線都指向同一個答案：新建的 VD，不管是 MoonClicker 建的還是 scrcpy
   建的，一開始都不忽略 app 的方向請求。**
2. **`VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT` 與 `ignoreOrientationRequest`
   是兩條完全獨立的程式碼路徑，互不設定對方。** 唯一的交集是：在 Android 15+，
   `!shouldRotateWithContent()`（也就是**沒有**這個 flag）是
   `DisplayRotation.mDefaultFixedToUserRotation` 那個 OR 運算式裡的一項
   （見 Q2 `DisplayRotation.java:447`）——這是 `isFixedToUserRotation`，
   不是 `ignoreOrientationRequest`，兩者是 `rotationForOrientation()` 裡先後
   兩個獨立的閘門（`vd-rotation-aosp-semantics.md` Q2 已經查過這條鏈，本文重新
   核對了一次，結論一致）。MoonClicker 與 scrcpy 都有帶
   `ROTATES_WITH_CONTENT`，所以兩邊都不會被這條推去 fixed-to-user。
   **這個假說在兩邊都不成立，不能解釋兩邊行為的差異。**
3. **scrcpy 原始碼裡完全沒有呼叫 `setIgnoreOrientationRequest`、
   `IWindowManager.setIgnoreOrientationRequest`，或任何 `DisplayWindowSettings`
   相關的方法。** 全樹（`server/src/main/java/com/genymobile/scrcpy/**`）搜尋
   `IgnoreOrientation` 沒有任何命中（見 Q1）。scrcpy 依賴的完全是
   framework 的預設行為，沒有主動去關閉任何 display 的方向仲裁。
   **背景假說的第一問「scrcpy 是否明確呼叫 `setIgnoreOrientationRequest`
   或依賴某種預設值差異」——答案是兩者都不是：它依賴的預設值（`false`）
   MoonClicker 這邊同樣拿得到，不存在「差異」。**
4. **真正對得上「letterbox，點一下 restart 按鈕就好」這個症狀的機制，
   是 Activity 層級的 Size Compat Mode，不是 display 層級的方向仲裁。**
   `ActivityRecord.shouldCreateAppCompatDisplayInsets()`
   （`ActivityRecord.java:8371`）在 app **不可 resize 且宣告固定方向或固定
   長寬比**時回 `true`；一旦回 `true`，`AppCompatSizeCompatModePolicy`
   會在 Activity 第一次 resolve configuration 時把當下的
   `AppCompatDisplayInsets`（螢幕尺寸、density）**凍結**下來，之後除非整個
   Activity 被重建，這份凍結的尺寸不會重算（`updateAppCompatDisplayInsets()`
   開頭就是「已經有就直接 return」，見 Q3）。如果凍結當下的容器尺寸與
   之後 WindowManager 實際給這個 Task 的尺寸對不上，就進入
   `isInSizeCompatModeForBounds() == true`，畫面被縮放/置中貼進去（letterbox），
   `TaskInfo` 上報這個狀態給 WM Shell 畫出 restart 按鈕；點下去
   （`clearSizeCompatMode()`）清掉凍結值、Activity 重建，用當下**正確**的容器
   尺寸重新凍結一次，於是填滿。**這整條鏈的每一步都不看
   `ignoreOrientationRequest`，也不看 VD 是不是 trusted、是不是 virtual**
   （`AppCompatDisplayInsets.java` 全文沒有 `isTrusted`/`VirtualDisplay`
   相關字樣，見 Q3）。
5. **scrcpy 呼叫的 API（`DisplayManager.createVirtualDisplay` 的 flags、
   `ActivityOptions.setLaunchDisplayId`）跟 MoonClicker 現在的做法，
   字面上是同一組。** 兩邊都不設 `setLaunchWindowingMode`；VD flag 只有
   MoonClicker 的 `VIRTUAL_DISPLAY_FLAG_TRUSTED` 是**條件式**給
   （持有 `ADD_TRUSTED_DISPLAY` 才給，見 Q4），scrcpy 是**無條件**給
   （API 33+ 直接加，沒有任何權限檢查或 try/catch，見 Q1）——
   這是本文找到的唯一一條「flag 組合可能不同」的具體差異，但 Size Compat
   Mode 的判斷邏輯完全不看 trusted（見上一點），所以**沒有原始碼證據支持
   這條差異就是letterbox 症狀的根因**，只能列為「值得排除」的候選項。
6. **本文查不到的部分（未找到佐證，以下為推論）**：scrcpy 與 MoonClicker
   啟動同一支 app 到條件幾乎相同的 VD 上，為什麼一個會落入 Size Compat Mode
   一個不會，AOSP 原始碼本身沒有告訴我們「這次啟動會不會撞到」的判斷式
   之外的東西——`shouldCreateAppCompatDisplayInsets()` 只問 app 的
   `ActivityInfo`（resizeable / fixed orientation / aspect ratio），這些是
   **app 端的靜態宣告，不因呼叫者是 scrcpy 還是 MoonClicker 而不同**。
   真正决定「這次算出來的 bounds 對不對」的，是 Activity 第一次
   `resolveOverrideConfiguration` 那個時間點，`DisplayContent` 的方向/尺寸
   有沒有已經完成從 portrait（VD 建立時的預設 `ROTATION_0`）轉到 landscape
   ——這是一個**時序**問題，不是一個**旗標**問題。本文沒有找到能證實
   「scrcpy 的呼叫順序保證了這個時序、MoonClicker 的沒有」的原始碼證據：
   兩邊都是「建 VD → 立刻 `startActivity`」，沒有任何顯式等待或回讀
   rotation 的步驟（見 Q1、Q4）。可能的候選解釋列在第 4 節結尾，
   但都沒有查到能一鎚定音的原始碼。

---

## Q1. scrcpy 原始碼裡有沒有 `setIgnoreOrientationRequest` 或等價呼叫

### 全樹搜尋：零命中

用 `raw.githubusercontent.com` 抓下 commit `19c1261` 的 `server/` 樹（經
GitHub Trees API 列出 90 個 `.java` 檔，逐一抓取比對）搜尋
`IgnoreOrientation`、`DisplayWindowSettings`、`setFixedToUserRotation`：
只有 `Device.java` 裡跟 `freezeDisplayRotation`/`thawDisplayRotation` 相關的
呼叫（見 `scrcpy-rotation-handling.md` Q4，同一個 commit，已經查過），
**沒有任何一處出現 `setIgnoreOrientationRequest` 或
`DisplayWindowSettings` 字樣。**

`server/src/main/java/com/genymobile/scrcpy/device/Device.java`
（<https://github.com/Genymobile/scrcpy/blob/19c1261d2e2cbf2b5e6a71a8b64cc1dd3ede06ac/server/src/main/java/com/genymobile/scrcpy/device/Device.java#L298-L321>）：

```java
public static void startApp(String packageName, int displayId, boolean forceStop) {
    PackageManager pm = FakeContext.get().getPackageManager();

    Intent launchIntent = getLaunchIntent(pm, packageName);
    if (launchIntent == null) {
        Ln.w("Cannot create launch intent for app " + packageName);
        return;
    }

    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

    Bundle options = null;
    if (Build.VERSION.SDK_INT >= AndroidVersions.API_26_ANDROID_8_0) {
        ActivityOptions launchOptions = ActivityOptions.makeBasic();
        launchOptions.setLaunchDisplayId(displayId);
        options = launchOptions.toBundle();
    }

    ActivityManager am = ServiceManager.getActivityManager();
    if (forceStop) {
        am.forceStopPackage(packageName);
    }
    am.startActivity(launchIntent, options);
}
```

**只設 `setLaunchDisplayId`，不設 `setLaunchWindowingMode`，不碰任何方向/
orientation 相關的 API。** `forceStop` 是不是為真，取決於呼叫端在
`--start-app=<pkg>` 前面有沒有加 `+` 前綴——

`server/src/main/java/com/genymobile/scrcpy/control/Controller.java`
（<https://github.com/Genymobile/scrcpy/blob/19c1261d2e2cbf2b5e6a71a8b64cc1dd3ede06ac/server/src/main/java/com/genymobile/scrcpy/control/Controller.java#L774-L814>）：

```java
private void startApp(String name) {
    boolean forceStopBeforeStart = name.startsWith("+");
    if (forceStopBeforeStart) {
        name = name.substring(1);
    }
    ...
    Device.startApp(app.getPackageName(), startAppDisplayId, forceStopBeforeStart);
}
```

任務背景給的指令是 `--start-app=<pkg>`（沒有 `+`），所以 scrcpy 這次啟動
**也沒有** `forceStopPackage`——這條路徑跟 MoonClicker 的
`launchViaActivityTaskManager()`（下方 Q4）在「要不要先強制停止舊進程」
這件事上是一致的，不是差異點。

### VD 建立時的 flag：與既有筆記一致，重新核對過一次

`server/src/main/java/com/genymobile/scrcpy/video/NewDisplayCapture.java`
（<https://github.com/Genymobile/scrcpy/blob/19c1261d2e2cbf2b5e6a71a8b64cc1dd3ede06ac/server/src/main/java/com/genymobile/scrcpy/video/NewDisplayCapture.java#L211-L233>，
與 `scrcpy-new-display-vs-am-start-orientation.md` Q1 引用同一段）：

```java
public void startNew(Surface surface) {
    try {
        int flags = VIRTUAL_DISPLAY_FLAG_PUBLIC
                | VIRTUAL_DISPLAY_FLAG_PRESENTATION
                | VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                | VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH
                | VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT;
        ...
        if (Build.VERSION.SDK_INT >= AndroidVersions.API_33_ANDROID_13) {
            flags |= VIRTUAL_DISPLAY_FLAG_TRUSTED
                    | VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP
                    | VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED
                    | VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED;
            if (Build.VERSION.SDK_INT >= AndroidVersions.API_34_ANDROID_14) {
                flags |= VIRTUAL_DISPLAY_FLAG_OWN_FOCUS
                        | VIRTUAL_DISPLAY_FLAG_DEVICE_DISPLAY_GROUP;
            }
        }
        VirtualDisplay vd = ServiceManager.getDisplayManager()
                .createNewVirtualDisplay("scrcpy", displaySize.getWidth(), displaySize.getHeight(), dpi, surface, flags);
```

**注意：`VIRTUAL_DISPLAY_FLAG_TRUSTED` 這裡是無條件加的，沒有任何權限檢查、
沒有 try/catch fallback。** 如果呼叫端（shell UID）沒有
`ADD_TRUSTED_DISPLAY`，這行會直接丟 `SecurityException`，整個 VD 建立失敗、
scrcpy 無法運作——也就是說 scrcpy 的整套設計**假設**跑在有這個權限的環境下，
不像 MoonClicker 是「先試特權旗標，被拒絕再退回基本旗標」（見 Q4）。
這是本文找到的唯一一條「兩邊實際跑起來 flag 組合可能不同」的具體差異，
但如 TL;DR 第 5 點所述，Size Compat Mode 的判斷邏輯不看 trusted，
所以查不到這條差異跟症狀的因果關係。

---

## Q2. AOSP：`ignoreOrientationRequest` 的預設值與觸發條件

### 預設值：`false`，且與 trusted/virtual 無關——查兩層，結論一致

**第一層：持久化設定的讀取。**
`services/core/java/com/android/server/wm/DisplayWindowSettings.java`
（tag `android-15.0.0_r20`，
<https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r20/services/core/java/com/android/server/wm/DisplayWindowSettings.java#373>）：

```java
void applyRotationSettingsToDisplayLocked(@NonNull DisplayContent dc) {
    final DisplayInfo displayInfo = dc.getDisplayInfo();
    final SettingsProvider.SettingsEntry settings = mSettingsProvider.getSettings(displayInfo);

    final boolean ignoreOrientationRequest = settings.mIgnoreOrientationRequest != null
            ? settings.mIgnoreOrientationRequest : false;
    dc.setIgnoreOrientationRequest(ignoreOrientationRequest);

    dc.getDisplayRotation().resetAllowAllRotations();
}
```

`settings.mIgnoreOrientationRequest` 的型別是裝箱的 `Boolean`
（`DisplayWindowSettings.java:490`：`Boolean mIgnoreOrientationRequest;`），
一個全新建立、從未被 `wm set-ignore-orientation-request` 設過的 VD，
它的持久化 entry 裡這個欄位就是 `null`——三元運算式直接落到 `false`。
**這段程式碼從頭到尾沒有任何一行檢查 `displayInfo.flags`、
`Display.FLAG_TRUSTED`、`DisplayDeviceInfo.FLAG_PRIVATE`、或
`VIRTUAL_DISPLAY_FLAG_*` 系列常數。** 不管是 MoonClicker 的 VD、scrcpy 的
VD，還是任何其他 app 建的 VD，第一次走到這個方法，`ignoreOrientationRequest`
一律是 `false`。

**第二層：這個值最終落腳的欄位，同樣沒有 trusted 分支。**
`services/core/java/com/android/server/wm/DisplayArea.java`
（同一個 tag，
<https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r20/services/core/java/com/android/server/wm/DisplayArea.java#96>）：

```java
protected boolean mSetIgnoreOrientationRequest;
...
boolean getIgnoreOrientationRequest() {
    // Adding an exception for when ignoreOrientationRequest is overridden at runtime for all
    // DisplayArea-s. For example, this is needed for the Kids Mode ...
    return mSetIgnoreOrientationRequest && !mWmService.isIgnoreOrientationRequestDisabled();
}
```

`mSetIgnoreOrientationRequest` 是普通 Java `boolean` 欄位，未賦值前的
語言層級預設值就是 `false`；`isIgnoreOrientationRequestDisabled()` 是一個
**全域**開關（Kids Mode 用的那種），同樣不分 display。兩層查證都指向同一個
答案：**新建的 VD，不管是不是 trusted、是不是 virtual，
`ignoreOrientationRequest` 一律從 `false` 起跳。**

### `ROTATES_WITH_CONTENT` 與 `ignoreOrientationRequest`：兩條不相交的路徑

`services/core/java/com/android/server/wm/DisplayRotation.java`
（同一個 tag，
<https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r20/services/core/java/com/android/server/wm/DisplayRotation.java#447>）：

```java
mDefaultFixedToUserRotation =
        (isCar || isTv || mService.mIsPc
                || mDisplayContent.isPublicSecondaryDisplayWithDesktopModeForceEnabled()
                || !mDisplayContent.shouldRotateWithContent())
        && !"true".equals(SystemProperties.get("config.override_forced_orient"));
```

`shouldRotateWithContent()` 就是
`(mDisplayInfo.flags & Display.FLAG_ROTATES_WITH_CONTENT) != 0`
（`DisplayContent.java:6680`，<https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r20/services/core/java/com/android/server/wm/DisplayContent.java#6680>）。
MoonClicker 與 scrcpy 的 VD 都帶了 `ROTATES_WITH_CONTENT`，所以
`!shouldRotateWithContent()` 為 `false`，不會把
`mDefaultFixedToUserRotation` 推成 `true`（除非撞到車機/電視/PC 桌面模式
這幾個特例，跟本案無關）。

**這條式子影響的是 `isFixedToUserRotation()`，不是
`ignoreOrientationRequest`。** 兩者在 `rotationForOrientation()` 裡是**先後
兩個獨立的 if**——`vd-rotation-aosp-semantics.md` Q1/Q2 已經查過這條鏈
（`DisplayRotation.java` 的 `isFixedToUserRotation()` 先擋一次，
`shouldIgnoreOrientationRequest()` 是完全不同的方法、讀完全不同的欄位）。
本文重新核對過 `DisplayArea.shouldIgnoreOrientationRequest()`
（<https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r20/services/core/java/com/android/server/wm/DisplayArea.java#244-256>），
它讀的是 `getIgnoreOrientationRequest()`（上面查過的那個，只受
`DisplayWindowSettings` 持久化值與全域 Kids Mode 開關影響），
**沒有任何程式碼路徑讓 `ROTATES_WITH_CONTENT` 反過來設定
`ignoreOrientationRequest`。兩者是互不知道對方存在的兩塊邏輯。**

### 結論：背景假說在字面上不成立

任務背景假說的表述是「WindowManagerService 對這個 VD 有某種
per-display『忽略方向請求』語意，把它 letterbox」。查證結果是：
`ignoreOrientationRequest` 在 MoonClicker 的 VD 上是 `false`（沒有任何
程式碼把它設成別的值——MoonClicker 全樹搜尋
`setIgnoreOrientationRequest`／`DisplayWindowSettings` 零命中，見 Q4），
`false` 代表「**不**忽略」，也就是 WindowManager **會**依 app 宣告的
`landscape` 去轉這個 VD 的方向（這件事 `vd-rotation-aosp-semantics.md`
Q2 已經用 `DisplayRotation.rotationForOrientation()` 的
`SCREEN_ORIENTATION_LANDSCAPE` 分支查證過）。**如果這條路徑是唯一的
把關者，MoonClicker 的 VD 本來就應該轉成功、長寬交換、內容填滿——
不應該出現 letterbox。** 會出現 letterbox，代表卡住的地方不在這裡，
而在 Q3 查到的 Activity 層級機制。

---

## Q3. 真正對得上症狀的機制：Size Compat Mode 與 restart 按鈕

### 觸發條件：app 的靜態宣告，不是 display 的 flag

`services/core/java/com/android/server/wm/ActivityRecord.java`
（同一個 tag，
<https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r20/services/core/java/com/android/server/wm/ActivityRecord.java#8371-8406>）：

```java
boolean shouldCreateAppCompatDisplayInsets() {
    if (mAppCompatController.getAppCompatAspectRatioOverrides().hasFullscreenOverride()) {
        return false;
    }
    switch (supportsSizeChanges()) {
        case SIZE_CHANGES_SUPPORTED_METADATA:
        case SIZE_CHANGES_SUPPORTED_OVERRIDE:
            return false;
        case SIZE_CHANGES_UNSUPPORTED_OVERRIDE:
            return true;
        default:
            // Fall through
    }
    final TaskDisplayArea tda = getTaskDisplayArea();
    if (inMultiWindowMode() || (tda != null && tda.inFreeformWindowingMode())) {
        final ActivityRecord root = task != null ? task.getRootActivity() : null;
        if (root != null && root != this && !root.shouldCreateAppCompatDisplayInsets()) {
            return false;
        }
    }
    return !isResizeable() && (info.isFixedOrientation() || hasFixedAspectRatio())
            && isActivityTypeStandardOrUndefined();
}
```

**`!isResizeable() && info.isFixedOrientation()`**——一個宣告
`android:screenOrientation="landscape"` 又沒有宣告
`android:resizeableActivity="true"`（多數老 Unity 遊戲的典型狀態，
包含 Alto's Adventure 這類 targetSdk 較舊、專門鎖死橫向的休閒遊戲）就會
命中這條，回傳 `true`。**這個判斷式完全只讀這個 Activity 的
`ActivityInfo`，不讀 `DisplayContent`、不讀 VD 的任何 flag、不讀
`isTrusted()`。** 也就是說：同一支 APK 的這個布林值，在 MoonClicker 的 VD
上跟在 scrcpy 的 VD 上算出來**必然相同**——這不是差異點的候選。

### 一旦命中，尺寸只凍結一次

`services/core/java/com/android/server/wm/AppCompatSizeCompatModePolicy.java`
（同一個 tag，
<https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r20/services/core/java/com/android/server/wm/AppCompatSizeCompatModePolicy.java#338-343>）：

```java
void updateAppCompatDisplayInsets() {
    if (getAppCompatDisplayInsets() != null
            || !mActivityRecord.shouldCreateAppCompatDisplayInsets()) {
        // The override configuration is set only once in size compatibility mode.
        return;
    }
    ...
    mAppCompatDisplayInsets =
            new AppCompatDisplayInsets(mActivityRecord.mDisplayContent, mActivityRecord, ...);
}
```

第一次呼叫時，`AppCompatDisplayInsets` 建構子（
<https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r20/services/core/java/com/android/server/wm/AppCompatDisplayInsets.java>）
把**當下**的 `DisplayContent` 尺寸/insets 抄一份存起來。
`getAppCompatDisplayInsets() != null` 之後，**這個方法直接 return**——
不管 `DisplayContent` 之後怎麼變（包含之後才完成的 VD 旋轉），
這份凍結值不會自動更新。

`isInSizeCompatModeForBounds()`
（`AppCompatSizeCompatModePolicy.java:376-425`）拿凍結值算出來的
`appBounds` 跟當下容器的 `containerBounds` 一比對，尺寸對不上就回
`true`——這就是畫面被縮放/置中貼進一塊尺寸不符的容器裡（letterbox）的
直接原因。

**`AppCompatDisplayInsets.java` 全文（234 行）沒有出現
`isTrusted`、`VirtualDisplay`、`ROTATES_WITH_CONTENT`
或任何 display-flag 相關字樣**——這個機制對「這是不是一個 VD、
trusted 與否」完全無感，純粹是「凍結時的尺寸」對「現在的尺寸」。

### restart 按鈕：`clearSizeCompatMode()`

`ActivityRecord.java:8226`（`reportDescendantOrientationChangeIfNeeded()`
內的註解，
<https://android.googlesource.com/platform/frameworks/base/+/refs/tags/android-15.0.0_r20/services/core/java/com/android/server/wm/ActivityRecord.java#8226>）：

> WM Shell can show additional UI elements, e.g. a restart button for size
> compat mode so ensure that WM Shell is called when an activity becomes
> visible.

`AppCompatSizeCompatModePolicy.clearSizeCompatMode()`
（`AppCompatSizeCompatModePolicy.java:171-181`）：

```java
void clearSizeCompatMode() {
    clearSizeCompatModeAttributes();
    final int activityType = mActivityRecord.getActivityType();
    final Configuration overrideConfig = mActivityRecord.getRequestedOverrideConfiguration();
    overrideConfig.unset();
    overrideConfig.windowConfiguration.setActivityType(activityType);
    mActivityRecord.onRequestedOverrideConfigurationChanged(overrideConfig);
}
```

`clearSizeCompatModeAttributes()` 把 `mAppCompatDisplayInsets` 設回
`null`（`AppCompatSizeCompatModePolicy.java:157-169`）——下一次
`updateAppCompatDisplayInsets()` 就會用**當下**（已經轉正的）容器尺寸
重新凍結一次。這正是使用者觀察到「點一下 restart 按鈕，內容就正確填滿」
的完整原始碼因果鏈，且明確標注是 **WM Shell 的 UI**（不在 `frameworks/base`
這個 repo 裡，本文沒有去追那個 repo 的按鈕繪製條件——但觸發它的狀態變數
`inSizeCompatMode()`/`isInSizeCompatModeForBounds()` 已經查到）。

### 值得留意但本文沒有查到答案的旁證

`ActivityRecord.setRequestedOrientation()`
（`ActivityRecord.java:8169-8180`）裡有這段：

```java
// This is necessary in order to avoid going into size compat mode when the orientation
// change request comes from the app
if (getRequestedConfigurationOrientation(false, requestedOrientation)
            != getRequestedConfigurationOrientation(false /*forDisplay */)) {
    mAppCompatController.getAppCompatSizeCompatModePolicy().clearSizeCompatModeAttributes();
}
```

這段註解本身承認「**app 自己**呼叫 `setRequestedOrientation()` 改方向」
是一個已知的、框架自己都要主動避開的 Size Compat Mode 觸發源——如果
Alto's Adventure 是在 Activity 啟動之後才動態呼叫
`setRequestedOrientation(LANDSCAPE)`（而不是純靠 manifest 靜態宣告），
就會经過這一段程式碼；但這段程式碼本身就是「避開」的邏輯，理論上不會
造成 letterbox，除非時序上它在 `clearSizeCompatModeAttributes()` 之前
就已經有一次 `updateAppCompatDisplayInsets()` 用錯的尺寸跑過。
**這一段本文沒有走到底——不確定 Alto's Adventure 實際上是宣告
`screenOrientation="landscape"` 還是動態呼叫 `setRequestedOrientation`，
需要看該 APK 的 manifest 或用 `dumpsys package` 查，本文沒有那支 APK
可以查。未找到佐證，以下為推論**：如果是動態呼叫，時序敏感度會比純
manifest 宣告更高，可能是「MoonClicker 撞到、scrcpy 沒撞到」的其中一個
候選解釋。

---

## Q4. MoonClicker 本地原始碼比對

全部讀自
`engine/src/main/java/com/xaxaxax/moonclicker/MoonClickerService.kt`
（工作樹目前版本）。

### VD 建立：`createVirtualDisplay()`，flags 與 scrcpy 同構

```kotlin
// :127-137
const val SUPPORTED_FLAGS =
    DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR or
            DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL or
            DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS

const val ADD_FLAGS = DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_PUBLIC or
        DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
        DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
        DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH or
        DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT
```

跟 scrcpy `NewDisplayCapture.startNew()` 的基本盤（`PUBLIC | PRESENTATION |
OWN_CONTENT_ONLY | SUPPORTS_TOUCH | ROTATES_WITH_CONTENT`）逐位元相同。

```kotlin
// :631-652
private fun privilegedFlags(): Int {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return 0
    var f = DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED
    if (holds(ADD_TRUSTED_DISPLAY)) {
        f = f or DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_TRUSTED or
                DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP
        if (holds(ADD_ALWAYS_UNLOCKED_DISPLAY)) {
            f = f or DisplayManagerHidden.VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            f = f or ADD_FLAGS_34
        }
    }
    return f
}
```

跟 scrcpy 差異在這裡：**scrcpy 無條件加 `TRUSTED`（賭 shell UID 有
`ADD_TRUSTED_DISPLAY`，沒有就整個 VD 建立失敗），MoonClicker
先查 `holds(ADD_TRUSTED_DISPLAY)`，沒有就整組特權旗標都不給，退回
`createDisplay(... baseFlags)`（`:603-604`）**：

```kotlin
val vd = createDisplay(dm, name, width, height, densityDpi, sourceSurface, privilegedFlags)
    ?: createDisplay(dm, name, width, height, densityDpi, sourceSurface, baseFlags)
```

**這代表如果 MoonClicker 跑的環境沒有 `ADD_TRUSTED_DISPLAY`，它建出來的
VD 會是 untrusted，而 scrcpy 在同樣環境下根本建不起來（直接
`SecurityException`）——兩邊在「VD 建不建得起來」這件事上不是同一組
前提，但**如 Q3 所述，Size Compat Mode 的判斷邏輯不讀 `isTrusted()`，
所以即使真的是 untrusted 也沒有原始碼路徑能解釋 letterbox。
這條差異記錄在案，但沒有因果證據。**

### Context / calling identity：`fakeDisplayContext`，與 scrcpy 的 `FakeContext` 同構

```kotlin
// :250-254
private val fakeDisplayContext = object : ContextWrapper(context) {
    override fun getPackageName(): String = callerPackage
    override fun getOpPackageName(): String = callerPackage
    override fun getApplicationContext(): Context = this
}
```

```kotlin
// :1023-1027
private fun buildDisplayManagerForVirtualDisplay(): DisplayManager {
    val ctor = DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
    ctor.isAccessible = true
    return ctor.newInstance(fakeDisplayContext)
}
```

`callerPackage` 預設是 `"com.android.shell"`（建構子預設參數，
`:72`）。跟 scrcpy 的 `FakeContext.get()`（`wrappers/DisplayManager.java`，
既有筆記已查過）是同一個手法：偽造呼叫端身分為 shell，讓
`DisplayManager.createVirtualDisplay()` 走公開簽章但拿到 shell 的權限。
**這條路徑兩邊一致，不是差異點。**

### 啟動 Activity：只設 `setLaunchDisplayId`，與 scrcpy 一致

```kotlin
// :768-782
private fun launchViaActivityTaskManager(packageName: String, displayId: Int): Boolean {
    val intent = context.packageManager.getLaunchIntentForPackage(packageName) ?: return false
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    val options = ActivityOptions.makeBasic()
    Refine.unsafeCast<ActivityOptionsHidden>(options).setLaunchDisplayId(displayId)
    return try {
        val result = Workaround.startActivity(intent, options, callerPackage)
        ...
    }
}
```

全檔案搜尋 `windowingMode`、`setLaunchWindowingMode`、
`setIgnoreOrientationRequest`、`DisplayWindowSettings`：**零命中**——
包含 `app/src/main/java/com/xaxaxax/moonclicker/script/ScriptSession.kt`、
`ui/displays/DisplaysViewModel.kt`、`shizuku/Utils.kt`、
`Workaround.kt` 在內的全部相關檔案，都沒有出現這幾個字樣。
**MoonClicker 沒有主動呼叫、也沒有註解掉任何 `setIgnoreOrientationRequest`
或 `DisplayWindowSettings` 相關的程式碼——這件事在程式碼裡是「完全不存在」，
不是「存在但被關掉」。**

### 沒有查到的部分

MoonClicker 建 VD 之後到呼叫 `launchInDisplay()` 之間，程式碼裡沒有任何
等待 `DisplayInfo.getRotation()` 收斂、或等待第一次 `onDisplayChanged`
的邏輯（`startRotationTracking()` 只是註冊監聽器，不會阻塞
`createVirtualDisplay()` 的回傳，見 `:231-244`）——這與 scrcpy
`Device.startApp()` 沒有等待邏輯是一致的（見 Q1）。**兩邊在「建完 VD
立刻開 app，不等 rotation 收斂」這件事上做法相同**，所以這不能解釋
TL;DR 第 6 點提到的時序假說——如果真的是時序問題，兩邊踩中的機率照理說
應該接近，除非 Alto's Adventure 本身的啟動路徑（例如 Unity 引擎的
`screenOrientation` 動態設定時機）跟别的 app 不同。**這部分本文沒有查到
决定性的原始碼證據，留在 TL;DR 第 6 點的「未找到佐證，以下為推論」。**

---

## Q5. Texture 尺寸 log：`1080x2400` → `2400x1080` 證明的是什麼

這一節與既有筆記 `scrcpy-new-display-vs-am-start-orientation.md` Q3
使用同一個 commit，本文獨立重新抓取 `app/src/texture.c` 與
`app/src/screen.c` 核對過一次，結論一致，這裡摘要重述並附上本次驗證的
permalink。

`app/src/texture.c`
（<https://github.com/Genymobile/scrcpy/blob/19c1261d2e2cbf2b5e6a71a8b64cc1dd3ede06ac/app/src/texture.c#L85-L86>）：

```c
LOGV("Creating new texture: size=%" PRIu16 "x%" PRIu16 " color_space=%d "
     "color_range=%d", size.width, size.height, color_space, color_range);
```

這是唯一一處帶「Texture」字樣的 log，等級是 `LOGV`（verbose），格式是
`Creating new texture: size=WxH color_space=... color_range=...`，
跟使用者貼出的 `INFO: Texture: 1080x2400` **字面上對不起來**（等級不同、
沒有 `size=` 前綴、沒有 `color_space`/`color_range`）。本文用
`raw.githubusercontent.com` 重新抓過這個檔案（240 行）跟既有筆記的引用
逐字核對一致，**沒有找到使用者那行 log 的逐字出處**，可能是不同版本、
不同 wrapper、或終端機重新格式化過。

但**這個 texture 尺寸從何而來**是可以獨立確認的：client 端收到新尺寸的
frame → 觸發新 texture 建立；frame 尺寸的變化源頭串回
`scrcpy-rotation-handling.md` Q3 已查證的機制（`DisplayMonitor` 偵測
`DisplayProperties` 變化 → capture reset → `SurfaceEncoder` 用新的
`capture.getSize()` 重新 configure）。`1080x2400 → 2400x1080` 這組數字
在量級與方向上，與 Q2 這裡查到的 `LogicalDisplay`/`DisplayContent`
寬高交換（VD 從 `ROTATION_0` 轉到 `ROTATION_90`/`270`）完全吻合。

**這行 log 證明的是「scrcpy 的輸出影格尺寸換了」，不直接證明
「letterbox 沒有發生」。** 影格尺寸換成 2400×1080，代表 VD 的
`DisplayContent`/`LogicalDisplay` 這一層已經完成旋轉與寬高交換——這是
**display 層級**的旋轉是否成功的證據。而任務背景描述的「letterbox」
症狀（本文 Q3 查到）發生在**更上面一層**：即使 `DisplayContent` 的
容器尺寸已經是正確的 2400×1080，**Activity 自己**如果凍結了錯誤的
`AppCompatDisplayInsets`，畫面內容仍然會被縮放置中貼進這個正確尺寸的
容器裡（視覺上看起來像黑邊，但根因不是容器尺寸錯,而是 Activity 內部的
座標系統凍結錯了）。**這行 texture log 沒有辦法用來判斷 scrcpy 那邊
有沒有發生 Q3 描述的 Activity 層級 letterbox**——它量的是 encoder
輸入尺寸，不是 Activity 內容有沒有被二次縮放進那塊尺寸裡。
本文沒有找到能直接觀察「scrcpy 那支 Activity 是否進入
`inSizeCompatMode()`」的證據來源（scrcpy 是黑盒操作目標 app，不會去讀
目標 app 的 `TaskInfo`），**這部分是本文查不到的：未找到佐證。**

---

## 對 MoonClicker 的意涵（synthesis）

### 逐點對照

| 項目 | scrcpy 4.1（commit `19c1261`） | MoonClicker（工作樹現況） | 差異？ |
| --- | --- | --- | --- |
| `setIgnoreOrientationRequest` 呼叫 | 無（全樹零命中） | 無（全樹零命中） | **沒有差異** |
| VD 建立時 `ignoreOrientationRequest` 有效值 | `false`（AOSP 預設，Q2） | `false`（同一份 AOSP 邏輯） | **沒有差異** |
| VD flags 基本盤 | `PUBLIC\|PRESENTATION\|OWN_CONTENT_ONLY\|SUPPORTS_TOUCH\|ROTATES_WITH_CONTENT` | 同左（`ADD_FLAGS`） | **沒有差異** |
| `VIRTUAL_DISPLAY_FLAG_TRUSTED`（API 33+） | 無條件加（沒有就整個失敗） | 條件式加（`holds(ADD_TRUSTED_DISPLAY)`，沒有就退回非 trusted） | **有差異**，但查無因果 |
| `setLaunchWindowingMode` | 未使用 | 未使用 | **沒有差異** |
| `setLaunchDisplayId` | 使用（hidden API） | 使用（hidden API，透過 Refine） | **沒有差異** |
| calling identity | `FakeContext` 偽裝 shell | `fakeDisplayContext` 偽裝 shell（`callerPackage` 預設 `com.android.shell`） | **沒有差異** |
| 建 VD 後到 startActivity 之間是否等待 rotation 收斂 | 否 | 否 | **沒有差異** |
| `forceStopPackage` 是否呼叫 | 否（本案指令沒有 `+` 前綴） | 否（Q+ 路徑不呼叫） | **沒有差異** |

**逐點對照的結果是：本文能查到的所有維度，scrcpy 與 MoonClicker 的做法
幾乎逐位元相同；唯一查到的字面差異（`TRUSTED` 旗標是否條件式）
在 Q3 查過的 Size Compat Mode 判斷邏輯裡找不到任何依賴 `isTrusted()`
的分支，所以沒有原始碼證據能把這條差異接到症狀上。**

### 明確回答任務要求的兩個問題

1. **scrcpy 是否明確呼叫 `setIgnoreOrientationRequest`，或依賴某種
   預設值/trusted-display 差異？**

   **都不是。** scrcpy 原始碼裡沒有這個呼叫（Q1，全樹搜尋零命中）。
   它依賴的「預設值」（`ignoreOrientationRequest == false`）不是
   scrcpy 專屬的優勢——MoonClicker 的 VD 拿到的是**同一個**預設值
   （Q2，`DisplayWindowSettings.applyRotationSettingsToDisplayLocked()`
   對所有新建 display 一視同仁）。也沒有 trusted-display 差異：
   `DisplayArea.getIgnoreOrientationRequest()` 的邏輯完全不檢查
   display 是否 trusted（Q2）。**`ignoreOrientationRequest` 假說
   在原始碼層級被推翻——它不是本案的根因，因為它在兩邊的值相同，
   不能解釋兩邊行為不同。**

2. **MoonClicker 的程式碼要加什麼 API 呼叫/flag，才能複製 scrcpy 的
   非-letterbox 行為？**

   **本文沒有找到這樣一個 API 呼叫或 flag。** 因為查證下來，letterbox
   的症狀（畫面被縮放/置中貼進容器、點 restart 按鈕修好）對應的是
   Q3 查到的 **Activity 層級 Size Compat Mode**，這個機制：

   - 只讀被啟動 app 的 `ActivityInfo`（resizeable / fixed orientation /
     aspect ratio），跟呼叫端傳給 `createVirtualDisplay()` 或
     `ActivityOptions` 的任何參數**無關**。
   - `AppCompatDisplayInsets` 只在第一次 resolve configuration 時凍結
     一次，之後不會自動跟著 `DisplayContent` 的尺寸變化重算，除非
     Activity 被整個重建（`clearSizeCompatMode()`）。

   也就是說，**如果 letterbox 真的是 Size Compat Mode**，MoonClicker
   這邊沒有 display 建立時的 flag、也沒有 `ActivityOptions` 的參數
   能預先避免它——**唯一在 `frameworks/base` 這份原始碼裡查到、
   確實能清掉這個狀態的動作，是讓 Activity 走一次
   `clearSizeCompatMode()` 的路徑**，也就是使用者現在手動點的那個
   restart 按鈕做的事。可能的程式化替代方案（本文只列出方向，
   **沒有**在原始碼或真機上驗證過效果，屬於推論）：

   - **在 VD 已經轉到目標方向、尺寸穩定之後才 `startActivity`**，
     而不是建完 VD 立刻啟動——讓 Activity 第一次
     `updateAppCompatDisplayInsets()` 凍結到的就是正確尺寸，
     從源頭避免需要 restart。做法：`startRotationTracking()`
     已經在監聽 `onDisplayChanged`（`MoonClickerService.kt:231-244`），
     可以在 `launchInDisplay()` 前插入一次「等到
     `DisplayInfo.getSize()`/`getRotation()` 符合預期方向」的等待，
     但**這解不解決問題本文沒有驗證過**——因為 VD 剛建立時是
     `ROTATION_0`，「符合預期方向」需要先有一個 app 在跑才會觸發
     WindowManager 去轉它（`vd-rotation-aosp-semantics.md` Q2：
     方向仲裁是被動的，由 app 宣告驅動，不會無緣無故自己轉），
     這裡有一個雞生蛋的問題，本文沒有查到 AOSP 端有沒有「不用先跑
     app 就能讓 VD 轉到某個方向」的機制（`freezeDisplayRotation`
     可以主動設 user rotation，但那是給 `unspecified` orientation
     的 app 用的 fallback，見 `vd-rotation-aosp-semantics.md` Q2 表格，
     對 `landscape` 這種固定方向的 app 沒有效果——它的
     `preferredRotation` 會被 `rotationForOrientation()` 的
     `SCREEN_ORIENTATION_LANDSCAPE` 分支直接覆蓋）。
   - **在偵測到 `inSizeCompatMode()`（或等價徵象）後，程式化模擬
     restart 按鈕的動作。** 但 `clearSizeCompatMode()` 是
     `@hide` 的 package-private 方法（在
     `com.android.server.wm` 套件內，連 `@SystemApi` 都不是），
     一般 app 或 Shizuku shell 進程都**沒有直接呼叫它的路徑**——
     使用者點的那個按鈕背後,真正觸發的很可能是
     `ActivityTaskManager`/`IActivityClientController` 上某個公開或
     半公開的「restart top activity」入口（WM Shell 的
     CompatUIController 呼叫的那個 API），**本文沒有去追
     `frameworks/base` 之外的 WM Shell 原始碼**（不在
     `services/core/java/com/android/server/wm` 底下,是獨立的
     `libs/WindowManager/Shell` 模組），所以查不到這個入口的確切
     簽章。**這是本文最大的一個缺口：沒有找到「不靠使用者點按鈕，
     程式化清除 Size Compat Mode」的 API，需要另外去查
     `frameworks/base/libs/WindowManager/Shell` 或
     `ActivityTaskManager`/`IActivityClientController` 的
     `restartActivityProcessIfVisible` 一類方法（此為推測的方法名,
     未經原始碼核實）。**

### 誠實的但書

本文推翻了任務背景假說（`ignoreOrientationRequest`／
`DisplayWindowSettings` 是根因），並且用原始碼找到一個邏輯上更貼合症狀
描述（letterbox + restart 按鈕修好）的機制（Size Compat Mode）。
但本文**沒有**：

- 在真機上確認 MoonClicker 啟動 Alto's Adventure 時，`TaskInfo` 上
  `topActivityInSizeCompat`（或等價欄位，本文沒有查到 AOSP 15
  `TaskInfo` 這個欄位的確切名稱與位置）是不是真的被設為 `true`。
- 查過 Alto's Adventure 的 `AndroidManifest.xml`，確認它是
  `resizeableActivity="false"` 加 `screenOrientation="landscape"`
  的靜態宣告，還是動態呼叫 `setRequestedOrientation()`。
- 查過 `frameworks/base` 之外的 WM Shell 原始碼，確認 restart 按鈕
  背後呼叫的確切 API 簽章。
- 解釋清楚「scrcpy 跑同一支 app 到條件幾乎相同的 VD 上為什麼沒有
  同樣進入 Size Compat Mode」——本文查到的所有維度兩邊都相同，
  唯一的差異（TRUSTED 旗標）查無因果，**這個問題留在
  TL;DR 第 6 點的「未找到佐證，以下為推論」，是本文誠實承認查不到的
  部分，不是被忽略的部分。**

下一步如果要把這個缺口補上，建議的路徑（本文範圍之外）：在真機上對
MoonClicker 啟動的 Alto's Adventure 跑
`adb shell dumpsys activity activities | grep -A5 altosadventure`
確認是否真的處於 size-compat（`dumpsys` 輸出通常會印
`app=... sizeCompat` 或類似字樣），並且對照同一台機器上用 scrcpy
啟動的同一支 app 是否沒有——這能直接證實或推翻 Q3 的假說，
是本文缺的那塊真機證據。

---

## 引用來源索引

scrcpy，`https://github.com/Genymobile/scrcpy`，commit
`19c1261d2e2cbf2b5e6a71a8b64cc1dd3ede06ac`（`v4.1-1-g19c1261`），
本文用 `api.github.com/repos/.../git/trees` 列出 `server/` 樹下全部 90 個
`.java` 檔，逐一以 `raw.githubusercontent.com` 抓取後 grep：

- `server/src/main/java/com/genymobile/scrcpy/device/Device.java`
  （`startApp()`）
- `server/src/main/java/com/genymobile/scrcpy/control/Controller.java`
  （`startApp(String)`，`forceStopBeforeStart` 判斷）
- `server/src/main/java/com/genymobile/scrcpy/video/NewDisplayCapture.java`
  （`startNew()`，VD flags）
- `app/src/texture.c`（`LOGV("Creating new texture: ...")`）
- `app/src/screen.c`（texture 建立的呼叫路徑，交叉核對用）

AOSP，`https://android.googlesource.com/platform/frameworks/base`，
tag `android-15.0.0_r20`，本文用 `?format=TEXT`（base64）抓取完整檔案
內容逐行核對：

- `services/core/java/com/android/server/wm/DisplayWindowSettings.java`
  （`applyRotationSettingsToDisplayLocked()`，`SettingsEntry.mIgnoreOrientationRequest`）
- `services/core/java/com/android/server/wm/DisplayArea.java`
  （`mSetIgnoreOrientationRequest`、`getIgnoreOrientationRequest()`、
  `shouldIgnoreOrientationRequest()`）
- `services/core/java/com/android/server/wm/DisplayContent.java`
  （`shouldRotateWithContent()`、`setIgnoreOrientationRequest()`、
  `isTrusted()`）
- `services/core/java/com/android/server/wm/DisplayRotation.java`
  （`mDefaultFixedToUserRotation`、`isFixedToUserRotation()`）
- `services/core/java/com/android/server/wm/ActivityRecord.java`
  （`shouldCreateAppCompatDisplayInsets()`、`inSizeCompatMode()`、
  `setRequestedOrientation()`、`reportDescendantOrientationChangeIfNeeded()`）
- `services/core/java/com/android/server/wm/AppCompatSizeCompatModePolicy.java`
  （`updateAppCompatDisplayInsets()`、`isInSizeCompatModeForBounds()`、
  `clearSizeCompatMode()`）
- `services/core/java/com/android/server/wm/AppCompatDisplayInsets.java`
  （凍結值本身，確認無 trusted/VD 相關邏輯）
- `services/core/java/com/android/server/wm/RootWindowContainer.java`、
  `services/core/java/com/android/server/wm/WindowContainer.java`、
  `services/core/java/com/android/server/wm/WindowManagerService.java`
  （讀過，用於排除 `mSetIgnoreOrientationRequest`/`shouldIgnoreOrientationRequest`
  定義在別處的可能性，確認最終落腳在 `DisplayArea.java`）

MoonClicker（本次研究當下的工作樹版本）：

- `engine/src/main/java/com/xaxaxax/moonclicker/MoonClickerService.kt`
  （`createVirtualDisplay()`、`privilegedFlags()`、`fakeDisplayContext`、
  `buildDisplayManagerForVirtualDisplay()`、`launchViaActivityTaskManager()`、
  `launchOrMoveViaActivityManager()`）
- `app/src/main/java/com/xaxaxax/moonclicker/script/ScriptSession.kt`、
  `app/src/main/java/com/xaxaxax/moonclicker/ui/displays/DisplaysViewModel.kt`、
  `app/src/main/java/com/xaxaxax/moonclicker/shizuku/Utils.kt`、
  `engine/src/main/java/com/xaxaxax/moonclicker/Workaround.kt`
  （grep 確認 `windowingMode`/`setIgnoreOrientationRequest`/
  `DisplayWindowSettings` 零命中）

交叉引用：本文與 `docs/research/scrcpy-rotation-handling.md`、
`docs/research/vd-rotation-aosp-semantics.md`、
`docs/research/scrcpy-new-display-vs-am-start-orientation.md`
共用同一批 commit/tag，可互相對照。**本文明確修正
`scrcpy-new-display-vs-am-start-orientation.md` 的結論**：該文把
「橫向 App 填滿」完全歸因於 `VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT`
這一個 flag（其 TL;DR 第 5 點：「MoonClicker 要複製 scrcpy 的做法，
缺的就是一個 flag」）——這個結論在 MoonClicker **已經有**這個 flag
（`MoonClickerService.kt:137`，且該文寫作當時很可能還不知道
MoonClicker 已經加上了）卻仍然 letterbox 的現況下已被證明不完整：
`ROTATES_WITH_CONTENT` 能解釋「VD 這個 display 本身會不會轉、
長寬會不會交換」（Q1/Q2 的消費者 A/B,`vd-rotation-aosp-semantics.md`
已查證），但不能解釋「Activity 內容有沒有被 Size Compat Mode 凍結在
錯誤尺寸上」——這是本文新查到的、該文沒有觸及的第三層機制。
