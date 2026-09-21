# 「建 VD 立刻 startActivity」是不是一個時序競爭：查證結果

研究問題：延續 `docs/research/scrcpy-vs-moonclicker-ignore-orientation-request.md`
TL;DR 第 6 點留下的缺口——MoonClicker（與 scrcpy）都是「建完 VD 立刻
`startActivity`，沒有任何顯式等待」，這是不是一個時序競爭：VD 剛建立時方向是
`ROTATION_0`，如果第一次 Activity resolve configuration（進而凍結
`AppCompatDisplayInsets`，見前一份筆記 Q3）發生在 VD 真正轉到目標方向
（例如 landscape）**之前**，會不會凍結到錯的尺寸，之後才需要使用者手動點
restart 按鈕修正。本文查 AOSP `android-15.0.0_r20` 的
`RootWindowContainer.java`／`TaskLaunchParamsModifier.java`／
`ActivityStarter.java`，與 scrcpy 同一批筆記共用的 commit
`19c1261d2e2cbf2b5e6a71a8b64cc1dd3ede06ac` 的
`NewDisplayCapture.java`／`Device.java`／`Server.java`／`Controller.java`，
比對 MoonClicker `MoonClickerService.kt`（`createVirtualDisplay()`、
`launchViaActivityTaskManager()`）與
`FullscreenDisplayViewModel.kt`（`launchApp()`）。

研究日期 2026-09-21。

**先澄清一件事**：commit `19c1261d2e2cbf2b5e6a71a8b64cc1dd3ede06ac` 本身是一個
文件變更（`doc/video.md` 提到 VP8/VP9），**不是**某種「修 race」的 fix
commit——它只是既有筆記固定引用的 tag/commit，本文延續同一個錨點方便交叉引用，
不代表這個 commit 的 diff 本身跟本題有關（已用 `git show --stat` 核對，
只改了一個檔案、三行）。

**沒有驗證的部分**：`TaskLaunchParamsModifier`／`ActivityStarter`
的原始碼是透過 WebFetch 對 googlesource `?format=TEXT` 的 base64 內容做摘要式
提問取得，這個管道在複雜方法（`onCalculate()` 這種數百行、多分支的函式）上
給出的摘要在本次查證中出現至少一次自我矛盾（見 Q2 但書），**沒有辦法在本次
預算內取得可逐行核對、可信賴的完整原始碼引用**。這一節的結論標注為
「未能確認」，不是「查無此事」。

---

## TL;DR

1. **scrcpy 端確實有一個真實存在、有原始碼佐證的時序協調機制——但它協調的是
   兩個獨立命令之間的順序，不是「等 WMS 算完 bounds」。**
   `Controller.getStartAppDisplayId()`（`Controller.java:817-835`）在
   `--new-display` 模式下會 `waitDisplayData(1000)`，等 capture 執行緒透過
   `onNewVirtualDisplay()` callback（`Controller.java:167-176`）回報
   `virtualDisplayId` 之後才把它交給 `Device.startApp()`。**這個等待存在的
   原因是「control channel 收到 `TYPE_START_APP` 訊息的執行緒」跟「建立 VD
   的 capture 執行緒」是兩條不同執行緒，而 VD 建立本身要等
   `SurfaceEncoder`／`MediaCodec` 的輸入 surface 準備好才會發生
   （`NewDisplayCapture.start()` 只有在拿到 `surface` 參數後才呼叫
   `startNew()`），不是瞬間完成的**——等的是「displayId 存不存在」，
   不是「displayId 存在之後，WMS 有沒有把它的 bounds/orientation 算完」。
2. **MoonClicker 的呼叫鏈裡沒有這種「displayId 是否存在」的競爭。**
   `MoonClickerService.createVirtualDisplay()`（`:582-620`）呼叫的
   `dm.createVirtualDisplay(...)`（`:708`）是同步的 AIDL/binder 呼叫，
   回傳時 `vd.display?.displayId`（`:610`）已經是有效值——這一步跟
   `FullscreenDisplayViewModel.launchApp()`（`:138-145`）是**先後兩次獨立的
   binder 呼叫**（先建立、拿到 `displayId` 回傳值，UI 再用這個已知的
   `displayId` 呼叫 `launchInDisplay`），中間沒有 callback、沒有跨執行緒的
   非同步通知要等。**scrcpy 要等的那個東西（「displayId 何時變得可知」）
   在 MoonClicker 這裡从一開始就不是問題——`createVirtualDisplay()`
   回傳之時，displayId 保證已經存在。**
3. **AOSP 端確認了一件事：`DisplayContent` 是懶建立的，而且是同步的。**
   `RootWindowContainer.getDisplayContentOrCreateLocked(int displayId)`
   （tag `android-15.0.0_r20`）邏輯是：先查已有的 `DisplayContent`，沒有就用
   `mDisplayManager.getDisplay(displayId)` 直接查、非 null 就地
   `new DisplayContent(display, this, mDeviceStateController)` 建一個——
   **這代表就算 WMS 自己那條非同步的 `onDisplayAdded()` callback 還沒跑到，
   `ActivityStarter` 第一次查這個 displayId 時，也會用當下
   `Display` 物件的資料（寬高、density——這些在
   `createVirtualDisplay()` 呼叫時就已經當參數傳進去、同步生效）就地建好，
   不會因為「WMS 還沒來得及處理」而查到 `null` 或退回預設顯示器。**
   查無「`getDisplayContentOrCreateLocked` 找不到就 fallback 到 default
   display」的分支——找不到的唯一結果是回傳 `null`，呼叫端要自己處理。
4. **本文沒有確認、也沒有推翻「`TaskLaunchParamsModifier` 算 bounds 時讀到的
   `orientation` 是不是 VD 剛建立時的 `ROTATION_0`，而不是 app 宣告後才會有的
   `landscape`」這個假說。** 這是前一份筆記留下的缺口，本文嘗試查
   `TaskLaunchParamsModifier.java`／`ActivityStarter.java`，但透過 WebFetch
   取得的摘要在描述 `onCalculate()`／`getTaskBounds()` 的分支條件時出現自我
   矛盾（先说「resolvedMode 不是 FREEFORM 也不是 FULLSCREEN 就 return」，
   結論卻寫成「FULLSCREEN 会 explicitly return」——這兩句在邏輯上互斥，
   代表這次摘要不可靠），**本文選擇不採信這次摘要的具體結論，誠實標注為
   「未能在本次預算內取得可信賴的原始碼引用」，而不是硬套一個沒有把握的
   答案。**
5. **對「根因是不是時序競爭」的誠實回答：分兩層。**
   - scrcpy 有一個**真實、已查證**的時序協調（等 displayId 變得可知），
     但這個協調要解決的問題在 MoonClicker 的同步呼叫鏈裡本來就不存在——
     **這一層不能拿來當作「MoonClicker 也該加等待」的理由。**
   - 前一份筆記提出的「VD orientation 收斂 vs. Activity 第一次凍結
     `AppCompatDisplayInsets`」時序假說，**本文沒有找到能一鎚定音的原始碼
     證據**（見上一點）——既沒有被推翻，也沒有被證實，維持「未找到佐證」
     的狀態，需要另一輪針對 `TaskLaunchParamsModifier.java`／
     `ActivityRecord.java` 的逐行原始碼比對（不透過摘要式 WebFetch，
     改用能完整讀取原始檔案內容的方式）才能真正回答。

---

## Q1. scrcpy 的等待機制：等的是什麼

`server/src/main/java/com/genymobile/scrcpy/control/Controller.java`：

```java
// :166-176
@Override
public void onNewVirtualDisplay(int virtualDisplayId, PositionMapper positionMapper) {
    DisplayData data = new DisplayData(virtualDisplayId, positionMapper);
    DisplayData old = this.displayData.getAndSet(data);
    if (old == null) {
        // The very first time the Controller is notified of a new virtual display
        synchronized (displayDataAvailable) {
            displayDataAvailable.notify();
        }
    }
}
```

```java
// :817-835
private int getStartAppDisplayId() {
    if (displayId != Device.DISPLAY_ID_NONE) {
        return displayId;
    }
    // Mirroring a new virtual display id (using --new-display-id feature)
    try {
        // Wait for at most 1 second until a virtual display id is known
        DisplayData data = waitDisplayData(1000);
        if (data != null) {
            return data.virtualDisplayId;
        }
    } catch (InterruptedException e) {
        // do nothing
    }
    return Device.DISPLAY_ID_NONE;
}
```

`onNewVirtualDisplay` 是在 `NewDisplayCapture.start()`
（`video/NewDisplayCapture.java:281-284`）裡、拿到
`vd.getDisplay().getDisplayId()` 之後同步呼叫的：

```java
// NewDisplayCapture.java:275-284
if (virtualDisplay == null) {
    startNew(surface);
} else {
    virtualDisplay.setSurface(surface);
}
if (vdListener != null) {
    PositionMapper positionMapper = PositionMapper.create(videoSize, eventTransform, displaySize);
    vdListener.onNewVirtualDisplay(virtualDisplay.getDisplay().getDisplayId(), positionMapper);
}
```

`start(Surface surface)` 要等 `SurfaceEncoder` 把 `MediaCodec` 的輸入
surface 準備好才會被呼叫（`SurfaceEncoder`／`SurfaceCapture` 的生命週期，
`Server.java:139-159` 把 `surfaceCapture` 交給 `SurfaceEncoder` 之後才
`asyncProcessor.start()`）——這代表「VD 什麼時候真正建立」跟「client
何時發出 `TYPE_START_APP` 控制訊息」完全是兩條獨立時間線，`Controller`
收到 `TYPE_START_APP` 訊息（`Controller.java:410-411`
`startAppAsync(msg.getText())`）的那個執行緒（讀 control channel 的執行緒）
跟 capture 執行緒是分開的。**`waitDisplayData` 等的就是這兩條時間線的交會
點——displayId 何時變得可知——不是等 WMS 把這個 displayId 對應的
`DisplayContent`/bounds/orientation 算完。** 一旦
`vd.getDisplay().getDisplayId()` 這行執行到，`displayId` 本身在
DisplayManagerService 那邊已經是完全註冊好的值（`createNewVirtualDisplay`
是同步呼叫，`server/.../wrappers/DisplayManager.java` 只是反射出一個能呼叫
非 mirror 簽章的 `DisplayManager` 實例，最終呼叫的還是公開、同步的
`DisplayManager.createVirtualDisplay(...)`）。

## Q2. AOSP：`DisplayContent` 何時、如何被建立

`services/core/java/com/android/server/wm/RootWindowContainer.java`
（tag `android-15.0.0_r20`，透過 `?format=TEXT` 取得並解碼核對）：

```java
DisplayContent displayContent = getDisplayContent(displayId);
if (displayContent != null) {
    return displayContent;
}
final Display display = mDisplayManager.getDisplay(displayId);
if (display == null) {
    return null;
}
displayContent = new DisplayContent(display, this, mDeviceStateController);
addChild(displayContent, POSITION_BOTTOM);
return displayContent;
```

（方法名 `getDisplayContentOrCreateLocked`，內容經 WebFetch 對 base64
原始碼解碼後逐字引用；由於是透過摘要式管道取得而非直接
`Read` 完整檔案，行號本文沒有核實，**標注為單一來源、未交叉核對**。）

這段邏輯本身能回答的問題只有「`ActivityStarter` 找不到現成的
`DisplayContent` 時會不會就地建一個」——答案是會，而且用的是當下
`mDisplayManager.getDisplay(displayId)` 查到的 `Display` 物件（其
`DisplayInfo` 在 `createVirtualDisplay()` 呼叫當下就已經帶入呼叫端傳入的
width/height/density，是同步生效的資料）。**這排除了「因為 WMS 的
`onDisplayAdded` callback 還沒跑到，所以 `ActivityStarter` 查不到這個
display、只好 fallback 到別的 display」這個特定假說**——沒有查到這種
fallback 分支。

**這段查證沒有回答、也回答不了**前一份筆記留下的問題：這個 `Display`
物件當下的 `getRotation()` 是不是已經反映了 app 的方向宣告。`Display`
物件本身是即時反映 `LogicalDisplay` 當下狀態的一個 handle，而
`LogicalDisplay` 的 `orientation`（見前一份筆記
`scrcpy-new-display-vs-am-start-orientation.md` Q1 查過的
`configureDisplayLocked()`）是不是已經從 `ROTATION_0` 轉到 app 要的方向，
**取決於方向仲裁這一整條鏈跑到哪一步，而不是取決於 `DisplayContent` 是不是
已經建立**——就算 `DisplayContent` 一定會被同步就地建立，它建立當下讀到的
`orientation` 有沒有轉正,是另一個完全獨立的問題,本文沒有查到。

## Q3. `TaskLaunchParamsModifier`／`ActivityStarter`：未能確認的部分

本文嘗試對 `TaskLaunchParamsModifier.java` 提問「`onCalculate()` 對
FULLSCREEN task 的 bounds 計算是不是直接讀 `TaskDisplayArea`
當下的 bounds，有沒有任何等待/重試」，取得的回覆裡出現自相矛盾的敘述
（先引用一段程式碼顯示「`resolvedMode` 不是 `FREEFORM` 也不是
`FULLSCREEN` 就提早 return」，卻又把結論寫成「FULLSCREEN 會被這段
explicit return 擋下」——這兩句話邏輯上互斥：如果 `FULLSCREEN` 是這個
if 判斷式排除在外的兩個值之一，代表 `FULLSCREEN` **不會**觸發這個
提早 return）。**這代表這次透過 WebFetch 取得的摘要不可信賴，本文決定
不採用它的任何具體結論**（包含行號、變數名、控制流程），也不會拿一個
自己都不信任的引用去支撐「根因確定是時序競爭」這種判斷。

**誠實的狀態**：`TaskLaunchParamsModifier`／`ActivityStarter`
對「FULLSCREEN task 的初始 bounds 到底讀哪個時間點的 `TaskDisplayArea`
狀態」這個具體問題，本文**沒有**拿到可信賴的原始碼引用，無法確認、
也無法推翻時序競爭假說。需要的下一步：直接用 `Read`（而非摘要式
`WebFetch`）取得 `TaskLaunchParamsModifier.java` 完整內容——可以用
`git clone --depth 1 -b android-15.0.0_r20` 對應的 AOSP mirror
（例如 `https://android.googlesource.com/platform/frameworks/base`
本身允許 shallow clone 特定 tag,如同本文對 scrcpy 的做法）取得可以直接
`grep`/`Read` 的本地檔案,逐行核對,而不是靠摘要工具轉述。

---

## 對 MoonClicker 的意涵

### 確定可以下的結論

1. **不需要模仿 scrcpy 的 `waitDisplayData()`。** 那個等待解決的是
   scrcpy 架構特有的「兩條執行緒各自推進、displayId 何時變得可知」問題。
   MoonClicker `MoonClickerService.createVirtualDisplay()`
   （`:582-620`）是單一同步呼叫,回傳時 `displayId`
   （`vd.display?.displayId`,`:610`）保證已經是 `DisplayManagerService`
   註冊好的值,呼叫端（`FullscreenDisplayViewModel` 或其他 caller）
   再用這個已知值呼叫 `launchInDisplay()`——**這兩步之間沒有 scrcpy
   那種「displayId 還不知道」的窗口,加等待邏輯solve的是一個不存在的問題。**
2. **`RootWindowContainer.getDisplayContentOrCreateLocked` 的懶建立
   機制代表「displayId 剛回傳、`DisplayContent` 可能還沒建」這個具體假說
   查無實據**——`ActivityStarter` 第一次查詢時就會同步就地建好,用的是
   當下就已經同步生效的 `Display` 資料。**如果 letterbox 症狀真的是時序
   造成的,問題不在「`DisplayContent` 存不存在」這一層,而更可能在
   Q3 沒查到答案的那一層（bounds/orientation 的計算讀到的是哪個時間點的
   值）。**

### 不確定、需要下一輪查證才能下結論的部分

3. **本文沒有辦法確認或推翻「VD 剛建立時 orientation 還是
   `ROTATION_0`,Activity 第一次凍結 `AppCompatDisplayInsets`
   如果剛好發生在這個時間點,會凍結到直向尺寸」這個具體時序假說。**
   這是唯一真正切題、可能解釋 letterbox 症狀的時序候選,但
   `TaskLaunchParamsModifier`／`ActivityStarter`
   的原始碼細節本文這次沒能可靠取得。**在有更可靠的原始碼引用之前,
   不建議往 `MoonClickerService.kt` 加任何「等待 orientation 收斂再
   startActivity」的程式碼**——前一份筆記已經指出這裡有雞生蛋問題
   （VD 方向仲裁本身要靠 app 先跑起來才會觸發,`freezeDisplayRotation`
   對 fixed-orientation app 無效）,貿然加等待可能只是換一種方式踩坑,
   在查清楚 `TaskLaunchParamsModifier` 的實際行為之前,任何具體改法
   都缺乏原始碼依據。
4. **下一步建議**：用 `git clone --depth 1 --branch android-15.0.0_r20
   https://android.googlesource.com/platform/frameworks/base` 取得可以
   直接 `Read`/`grep` 的本地檔案,針對
   `TaskLaunchParamsModifier.onCalculate()`、
   `ActivityStarter.computeLaunchingTaskFlags()`／
   `getLaunchDisplayArea()`（或同等方法,需先確認 API 15 的實際方法名）
   逐行核對,同時搭配真機 `adb shell dumpsys activity activities`
   在「剛建 VD 立刻啟動」與「等 1 秒再啟動」兩種情境下比對
   `TaskInfo` 是否進入 `sizeCompat`,才能真正把 Q3 的缺口補上。
   **本文明確不冒充已經查到這一層**——這是與前一份筆記一致的立場:
   查得到的部分給結論,查不到的部分明確標注「未找到佐證」。

---

## 引用來源索引

scrcpy,`https://github.com/Genymobile/scrcpy`,commit
`19c1261d2e2cbf2b5e6a71a8b64cc1dd3ede06ac`（本地 `git clone --depth 1`
後用 `git fetch --depth 2` 取得此 commit,`git show --stat` 確認其 diff
只改 `doc/video.md` 三行,與本題無因果關係,僅作既有筆記共用的引用錨點)：

- `server/src/main/java/com/genymobile/scrcpy/control/Controller.java`
  (`onNewVirtualDisplay()`:166-176,`getStartAppDisplayId()`/
  `waitDisplayData()`:817-855,`startApp()`:774-815)——本文用 `Read`
  直接讀取 clone 下來的檔案,逐行核對。
- `server/src/main/java/com/genymobile/scrcpy/video/NewDisplayCapture.java`
  (`start()`:266-285,`startNew()`:211-264)——同上,`Read` 直接核對。
- `server/src/main/java/com/genymobile/scrcpy/Server.java`
  (`asyncProcessor` 生命週期:100-195)——同上。

AOSP,`https://android.googlesource.com/platform/frameworks/base`,
tag `android-15.0.0_r20`：

- `services/core/java/com/android/server/wm/RootWindowContainer.java`
  (`getDisplayContentOrCreateLocked`)——透過 WebFetch 對 `?format=TEXT`
  摘要式提問取得,**單一來源、未交叉核對**。
- `services/core/java/com/android/server/wm/TaskLaunchParamsModifier.java`、
  `services/core/java/com/android/server/wm/ActivityStarter.java`——
  嘗試透過同一管道查證,**取得的摘要自相矛盾,本文未採信任何具體結論**,
  列為本文最大的缺口。

MoonClicker（工作樹目前版本）：

- `engine/src/main/java/com/xaxaxax/moonclicker/MoonClickerService.kt`
  (`createVirtualDisplay()`:582-620,`launchViaActivityTaskManager()`:768-782)
- `app/src/main/java/com/xaxaxax/moonclicker/ui/displaydetail/FullscreenDisplayViewModel.kt`
  (`launchApp()`:138-145)

交叉引用：本文延續
`docs/research/scrcpy-vs-moonclicker-ignore-orientation-request.md`
TL;DR 第 6 點與
`docs/research/scrcpy-new-display-vs-am-start-orientation.md`
提出的時序假說,是這兩份筆記留下缺口的後續查證,**沒有把缺口補完**,
缺口本身在本文 Q3 有明確記錄。
