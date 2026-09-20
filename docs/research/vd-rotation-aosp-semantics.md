# VirtualDisplay 旋轉：AOSP 語意與可用 API

Research note for [#10](https://github.com/kuomartin/MoonClicker/issues/10)（地圖 [#9](https://github.com/kuomartin/MoonClicker/issues/9) 的子票）。
研究日期 2026-09-10。

**方法**：全部讀 AOSP 原始碼（`aosp-mirror/platform_frameworks_base` 的
`android11-release` / `android12-release` / `android13-release` /
`android14-release` / `android15-release` / `android16-release` 分支，
即 API 30/31/33/34/35/36），逐條追到定義該行為的那一行。
下文引用格式為 `分支:檔名:行號`。行號對應撰寫當日各 release 分支的 HEAD；
分支會隨 QPR 更新，若對不上請以引用的符號名（方法/欄位）為準。

沒有真機驗證。所有標記為「**待 #11 實測**」的項目都必須在硬體上確認。

---

## TL;DR

1. `VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT` **不是旋轉的來源**，但在
   **Android 15+ 它是旋轉的必要條件**（沒有它，WindowManager 會把該 VD
   預設為 fixed-to-user-rotation，不再為內容旋轉）。在 Android 11–14 它
   純粹是「結果的通道」。MoonClicker 有設這個旗標，所以兩個世代都站得住。
2. 一個 `screenOrientation="landscape"` 的 app 放進 VD，WindowManager
   **會**旋轉該 VD，而且邏輯尺寸是**交換長寬**（`logicalWidth`/`logicalHeight`
   互換），不是維持不變後 letterbox。
3. 主動旋轉的正解是
   `IWindowManager.freezeDisplayRotation(displayId, rotation[, caller])`，
   需要 `android.permission.SET_ORIENTATION`（`signature|recents`），
   **`com.android.shell` 有這個權限**，所以 Shizuku 進程可以呼叫。
   簽章在 Android 15 多了一個 `String caller` 參數 —— 這是唯一的破壞性變更。
4. 可以。`VIRTUAL_DISPLAY_FLAG_PUBLIC` 的 VD 對所有 UID 可見，
   `onDisplayChanged` 是**廣播給全部已註冊的 listener**，不分建立者；
   `DisplayInfo.equals()` 有比 `rotation`，所以旋轉一定會觸發。
   另外還有一條更直接的路：`IWindowManager.watchRotation(watcher, displayId)`，
   API 30–36 簽章不變且**不需要任何權限**。

---

## Q1. `VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT` 的精確語意

### 官方文件字面

`android15-release:core/java/android/hardware/display/DisplayManager.java:375-391`：

> Virtual display flag: Indicates that the orientation of this display device is coupled to
> the orientation of its associated logical display.
> The flag should not be set when the physical display is mounted in a fixed orientation
> such as on a desk. Without this flag, display manager will apply a coordinate transformation
> such as a scale and translation to letterbox or pillarbox format under the assumption that
> the physical orientation of the display is invariant. With this flag set, the content will
> rotate to fill in the space of the display, as it does on the internal device display.

這段話說的是「device orientation 耦合到 logical display 的 orientation」——
方向從 logical display 流向 device，不是反過來。

### 傳遞鏈

`VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT`（`1 << 7`，`@hide` + `@SystemApi`，
Android 15 起被 `@FlaggedApi(FLAG_VDM_PUBLIC_APIS)` 標記）
→ `VirtualDisplayAdapter` 轉成 `DisplayDeviceInfo.FLAG_ROTATES_WITH_CONTENT`
（`android15-release:VirtualDisplayAdapter.java:512-513`）
→ 之後有**兩個**互相獨立的消費者。

### 消費者 A：LogicalDisplay —— 「結果的通道」

`android15-release:services/core/java/com/android/server/display/LogicalDisplay.java:686-700`：

```java
// Set the orientation.
// The orientation specifies how the physical coordinate system of the display
// is rotated when the contents of the logical display are rendered.
int orientation = Surface.ROTATION_0;
if ((displayDeviceInfo.flags & DisplayDeviceInfo.FLAG_ROTATES_WITH_CONTENT) != 0
            || mAlwaysRotateDisplayDeviceEnabled) {
    orientation = displayInfo.rotation;
}
// Apply the physical rotation of the display device itself.
orientation = (orientation + displayDeviceInfo.rotation) % 4;
```

`orientation` 接著被拿去算 `mTempDisplayRect`（把 layer stack 投影到實體
surface 的那個矩形），也就是 VD 的輸出 Surface 上實際畫出來的變換。

- **沒有旗標**：`orientation` 恆為 `ROTATION_0`。就算 logical display 已經轉到
  `ROTATION_90`（長寬已交換），輸出的變換不轉，於是那塊 2400×1080 的內容會被
  **等比縮進**原本 1080×2400 的 surface 裡（就是 javadoc 說的 letterbox/pillarbox）。
- **有旗標**：`orientation = displayInfo.rotation`，輸出跟著轉，內容填滿 surface。

這條在 API 30–36 全部存在，程式碼幾乎逐字相同
（`android11-release:LogicalDisplay.java:405-411`、
`android13-release:LogicalDisplay.java:556-562`、
`android14-release:LogicalDisplay.java:645-651`）。
Android 15/16 多了 `|| mAlwaysRotateDisplayDeviceEnabled`（一個 aconfig
開關；註解明說「FLAG_ROTATES_WITH_CONTENT is now handled in DisplayContent.
When the flag mAlwaysRotateDisplayDeviceEnabled is removed, we should also
remove this check for ROTATES_WITH_CONTENT here and always set the
orientation.」——長期方向是這裡不再看旗標）。

### 消費者 B：DisplayRotation —— 旗標同時是「能不能轉」的閘門（僅 Android 15+）

`android15-release:services/core/java/com/android/server/wm/DisplayContent.java:6628-6630`：

```java
boolean shouldRotateWithContent() {
    return (mDisplayInfo.flags & Display.FLAG_ROTATES_WITH_CONTENT) != 0;
}
```

唯一的呼叫點在 `android15-release:DisplayRotation.java:445-453`（`configure()` 內）：

```java
mDefaultFixedToUserRotation =
        (isCar || isTv || mService.mIsPc || mDisplayContent.forceDesktopMode()
                || !mDisplayContent.shouldRotateWithContent())
        && !"true".equals(SystemProperties.get("config.override_forced_orient"));
```

`mDefaultFixedToUserRotation == true` ⇒ `isFixedToUserRotation()` 回 true
（`DisplayRotation.java:1008-1020`）⇒
`rotationForOrientation()` 直接 `return mUserRotation`（`:1253-1254`），
而且 `DisplayContent.handlesOrientationChangeFromDescendant()` 回 false
（`DisplayContent.java:1714-1717`），**app 宣告方向不會讓這個 display 轉**。

**版本差異（重要）**：`|| !mDisplayContent.shouldRotateWithContent()` 這一項
只存在於 `android15-release` 與 `android16-release`。
`android11/12/13/14-release` 的同一行只有
`(isCar || isTv || mService.mIsPc || forceDesktopMode)`
（`android11-release:DisplayRotation.java:335-336`、
`android13-release:DisplayRotation.java:348-349`、
`android14-release:DisplayRotation.java:433-434`）。
Android 16 又把 `forceDesktopMode()` 換成
`isPublicSecondaryDisplayWithDesktopModeForceEnabled()`
（`android16-release:DisplayRotation.java:431-434`）。

### 結論

> 它是「結果的通道」，**不是**旋轉的來源 —— 它自己不會造成任何旋轉。
> 但在 Android 15+ 它**同時是**「WindowManager 願不願意為內容旋轉這個 display」
> 的前置條件。所以「只是結果的通道」這個說法在 API 35+ 是不完整的。

MoonClicker 有設這個旗標（`app/src/main/java/com/xaxaxax/moonclicker/MoonClickerShizukuService.kt:50`），
所以在兩個世代都在正確的一邊。**不要拿掉它。**

---

## Q2. app 宣告方向時，WindowManager 會不會為它旋轉 VD？長寬會不會交換？

**會轉，而且長寬會交換。** 這是這張票最關鍵的一條。

### 會不會轉

App 改變 requested orientation → `DisplayContent.onDescendantOrientationChanged()`
→ `updateOrientation()` → `DisplayRotation.rotationForOrientation(orientation, lastRotation)`。

`android15-release:DisplayRotation.java:1243-1436` 的關鍵三步：

1. `if (isFixedToUserRotation()) return mUserRotation;`（`:1253-1254`）
   —— 見 Q1，VD 有 `ROTATES_WITH_CONTENT` 就不會卡在這。
2. 非預設 display 的偏好旋轉（`:1282-1285`）：
   ```java
   if (!isDefaultDisplay) {
       // For secondary displays we ignore things like displays sensors, docking mode and
       // rotation lock, and always prefer user rotation.
       preferredRotation = mUserRotation;
   }
   ```
   **這一行直接證實了地圖前提 2 的核心**：VD 完全不吃感測器。
3. 依 requested orientation 分派（`:1387-1436`）：
   ```java
   case ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE:
       // Return landscape unless overridden.
       if (isLandscapeOrSeascape(preferredRotation)) {
           return preferredRotation;
       }
       return mLandscapeRotation;
   ...
   default:
       // For USER, UNSPECIFIED, NOSENSOR, SENSOR and FULL_SENSOR,
       // just return the preferred orientation we already calculated.
       if (preferredRotation >= 0) {
           return preferredRotation;
       }
       return Surface.ROTATION_0;
   ```

`mLandscapeRotation` 由 `DisplayRotation.configure(width, height)` 依 VD 的基底
尺寸決定（`:400-422`）：直立形狀的 VD（1080×2400）⇒ `mLandscapeRotation = ROTATION_90`。

所以：

| App 宣告的 orientation | 新的 VD rotation |
| --- | --- |
| `landscape` | `mLandscapeRotation`（直立 VD 上是 `ROTATION_90`） |
| `portrait` | `mPortraitRotation`（直立 VD 上是 `ROTATION_0`） |
| `unspecified` / `user` / `fullSensor`（Keep 這類） | `mUserRotation`（新建 VD 預設 `ROTATION_0`，永遠不變） |

**Keep 的實測現象在原始碼裡完全成立**：Keep 是 `unspecified`，走 `default` 分支，
拿到 `preferredRotation = mUserRotation = ROTATION_0`，於是 VD 永遠停在 0。
手機的感測器只驅動 display 0，因為第 2 步明文忽略 secondary display 的感測器。

另一個閘門是 `shouldIgnoreOrientationRequest`（`DisplayContent.java:1714-1717`）。
VD 的預設值是 **false**（尊重 app 的方向請求）：
`android15-release:DisplayWindowSettings.java:341-348`
`settings.mIgnoreOrientationRequest != null ? ... : false`。
（可用 `wm set-ignore-orientation-request -d <id> true` 改掉。）

### 長寬交換還是 letterbox

**交換。** `android15-release:DisplayContent.java:2267-2282`（`updateDisplayAndOrientation`）：

```java
final boolean rotated = (rotation == ROTATION_90 || rotation == ROTATION_270);
final int dw = rotated ? mBaseDisplayHeight : mBaseDisplayWidth;
final int dh = rotated ? mBaseDisplayWidth : mBaseDisplayHeight;
...
mDisplayInfo.logicalWidth = dw;
mDisplayInfo.logicalHeight = dh;
```

（`:2424-2433` 有一份給 `computeScreenConfiguration` 用的相同邏輯。）

也就是說一個 1080×2400 的 VD 轉到 `ROTATION_90` 之後，
`DisplayInfo.logicalWidth/logicalHeight` 變成 **2400×1080**。
Letterbox 只發生在**沒有** `ROTATES_WITH_CONTENT` 的情況（Q1 消費者 A），
而且是發生在 display device 這一層，不是 logical display。

### 對 `Viewport` 的直接後果

- VD 的邏輯尺寸**不是常數**。任何快取 `getDisplaySize()` 一次就當永久值的做法都是錯的。
- 旋轉之後 `getDisplaySize()` 回傳的就是已經交換過的長寬（見 Q4：
  `Display.getSize()`/`DisplayInfo` 都取自同一份 `logicalWidth/Height`）。
  所以 `Viewport` 只要吃「當下的 VD 邏輯寬高」，**不需要另外再乘一個旋轉矩陣**
  來換算尺寸；rotation 只影響輸入座標的軸向對應與呈現時的取向。
- 有 `ROTATES_WITH_CONTENT` 時，輸出到 SurfaceView 的影格已經是「轉正的」
  2400×1080，不是「1080×2400 裡塞著一塊橫的東西」。**待 #11 實測確認**
  ——這是整條鏈上最值得親眼看一次的一步。

---

## Q3. 主動設定某個 display 的 rotation：路徑、簽章、權限、版本差異

### 3.1 `IWindowManager.freezeDisplayRotation` / `thawDisplayRotation`（**推薦**）

`core/java/android/view/IWindowManager.aidl`，`@hide`，無 `@UnsupportedAppUsage`
（⇒ 對一般 app 是 blocklist；Shizuku 進程另計，見 3.6）。

| API level | 簽章 |
| --- | --- |
| 30 (A11) | `void freezeDisplayRotation(int displayId, int rotation);`<br>`void thawDisplayRotation(int displayId);` |
| 31 (A12) | 同上（`android12-release:IWindowManager.aidl:314,322`） |
| 33 (A13) | 同上（`android13-release:IWindowManager.aidl:325,333`） |
| 34 (A14) | 同上（`android14-release:IWindowManager.aidl:329,337`） |
| **35 (A15)** | **`void freezeDisplayRotation(int displayId, int rotation, String caller);`**<br>**`void thawDisplayRotation(int displayId, String caller);`**（`android15-release:IWindowManager.aidl:356,364`） |
| 36 (A16) | 同 A15（`android16-release:IWindowManager.aidl:395,403`） |

**Android 15 加了 `String caller`（純除錯字串，只進 `mRotationHistory`）。
這是 API 30–36 之間唯一的簽章破壞性變更。** 實作上要按 `SDK_INT` 分岔呼叫。

`rotation` 取 `Surface.ROTATION_0/90/180/270`，或 `-1` 表示「凍結在當前 rotation」。

**權限**：`android15-release:WindowManagerService.java`（`freezeDisplayRotation`）：

```java
if (!checkCallingPermission(android.Manifest.permission.SET_ORIENTATION, "freezeRotation()")) {
    throw new SecurityException("Requires SET_ORIENTATION permission");
}
```

`SET_ORIENTATION` 的 protectionLevel 是 `signature|recents`
（`android15-release:core/res/AndroidManifest.xml:5706-5707`），
而 **`com.android.shell` 有宣告 `<uses-permission android:name="android.permission.SET_ORIENTATION" />`**
（`android15-release:packages/Shell/AndroidManifest.xml:178`）。
Shizuku 的 user service 跑在 shell UID（2000），因此**這條路對 MoonClicker 是通的**。

**語意**：`freezeDisplayRotation` → `DisplayRotation.freezeRotation(rotation, caller)`
→ `setUserRotation(USER_ROTATION_LOCKED, rotation, caller)`（`DisplayRotation.java:986-993`）。
對非預設 display，`useDefaultSettingsProvider()` 回 false（`:389` 回 `isDefaultDisplay`），
所以它**不會**寫 `Settings.System.USER_ROTATION`（那是 display 0 專用），而是直接改
`mUserRotationMode` / `mUserRotation` 並寫進 `DisplayWindowSettings`
（`DisplayRotation.java:946-983`）。**改到 display 0 的風險為零。**

而 `mUserRotation` 正是 Q2 第 2 步裡 secondary display 的 `preferredRotation`
—— 所以對 `unspecified` 的 app（Keep），設 user rotation 就是唯一能讓它轉的手段，
**而且對宣告了固定方向的 app 不會生效**（Q2 第 3 步會覆蓋掉）。這正好是我們要的行為。

**shell 等價指令**（`android15-release:WindowManagerShellCommand.java:438-476`）：

```sh
wm user-rotation -d <displayId> lock 1     # freezeDisplayRotation(displayId, 1, ...)
wm user-rotation -d <displayId> free       # thawDisplayRotation(displayId, ...)
wm user-rotation -d <displayId>            # 讀回目前值（A15+ 用 getDisplayUserRotation）
```

**#11 可以在寫任何程式碼之前先用這三行驗證整條假設。**

### 3.2 `IWindowManager.freezeRotation` / `thawRotation`（**不要用**）

`void freezeRotation(int rotation)`（A11–A14）→ `void freezeRotation(int rotation, String caller)`（A15+）。
帶 `@UnsupportedAppUsage`（greylist）。但 javadoc 明說等價於對
`Display.DEFAULT_DISPLAY` 呼叫 `freezeDisplayRotation` —— **它動的是 display 0**，
會真的轉使用者的手機螢幕（走 `useDefaultSettingsProvider()` 寫
`Settings.System.ACCELEROMETER_ROTATION` / `USER_ROTATION`）。與本案無關。

### 3.3 `IWindowManager.setFixedToUserRotation(int displayId, int fixedToUserRotation)`

API 30–36 簽章不變。取值 `IWindowManager.FIXED_TO_USER_ROTATION_{DEFAULT,DISABLED,ENABLED,IF_NO_AUTO_ROTATION}`。
shell：`wm fixed-to-user-rotation -d <id> [enabled|disabled|default|if_no_auto_rotation]`。

用途是**覆寫** Q1 消費者 B 那個預設值：
- `DISABLED` ⇒ 強制讓 display 聽 app 的方向請求（即使沒有 `ROTATES_WITH_CONTENT`）。
- `ENABLED` ⇒ 強制只聽 user rotation，忽略所有 app 的固定方向宣告。

**這是一個有意思的第三條路**：把 VD 設成 `FIXED_TO_USER_ROTATION_ENABLED`，
就能讓「主動設定」對**所有** app 一致生效（連宣告 landscape 的遊戲也被壓成我們設的方向）。
是否要這樣做是設計決策，不在本票範圍；但 API 存在且對 shell UID 可用。
權限與 3.1 相同：`WindowManagerService.setFixedToUserRotation` 開頭同樣是
`checkCallingPermission(SET_ORIENTATION, "setFixedToUserRotation()")`，
不通過就丟 `SecurityException`（`android15-release:WindowManagerService.java`）。

### 3.4 `IWindowManager.setIgnoreOrientationRequest(int displayId, boolean)`

**Android 13 (API 33) 才加入**；`android11-release`/`android12-release` 的 aidl 裡沒有
（`android13-release:IWindowManager.aidl:351`）。A13–A16 簽章不變。
shell：`wm set-ignore-orientation-request -d <id> true|false`。
設 true ⇒ 整個 display 無視所有 app 的固定方向宣告（Q2 的第一個閘門）。
權限同樣是 `SET_ORIENTATION`（`WindowManagerService.setIgnoreOrientationRequest`）。

### 3.5 改尺寸（不是改 rotation）的路徑

- **`VirtualDisplay.resize(int width, int height, int densityDpi)`** —— public API，
  API 21 起簽章不變（`core/java/android/hardware/display/VirtualDisplay.java:95`）。
  只有 VD 的**擁有者**能呼叫（要拿得到 `VirtualDisplay` 物件，即 MoonClicker 的 Shizuku 進程），
  DMS 端 `resizeVirtualDisplay` 用 `callback.asBinder()` 認身分，不做權限檢查
  （`android15-release:DisplayManagerService.java:4130-4142`）。
  **這改的是 base display size，不是 rotation。** 若地圖裡「主動 resize VD」那條要走，
  這就是 API；MoonClicker 的 `IMoonClickerService` 目前沒有對應方法，需要新增。
- `IWindowManager.setForcedDisplaySize(int displayId, int w, int h)` /
  `clearForcedDisplaySize(int displayId)` —— 簽章 API 30–36 不變，但
  **Android 15 起加了 `@EnforcePermission("WRITE_SECURE_SETTINGS")` 註解**
  （`android15-release:IWindowManager.aidl:125-128`；A11–A14 沒有這個註解，
  權限檢查在 WMS 方法體內）。`com.android.shell` 有 `WRITE_SECURE_SETTINGS`
  （`packages/Shell/AndroidManifest.xml:150`）。等價於 `wm size`。對 VD 而言
  `VirtualDisplay.resize()` 比較乾淨。

### 3.6 `WindowManagerGlobal` 與 hidden API

`WindowManagerGlobal.getWindowManagerService()` 只是拿到同一個 `IWindowManager`
的 proxy，沒有額外能力；跨進程呼叫時應該直接用
`IWindowManager.Stub.asInterface(ServiceManager.getService("window"))`，
Shizuku 場景下再包一層 `ShizukuBinderWrapper`。

Hidden API 限制：`freezeDisplayRotation` / `setFixedToUserRotation` /
`setIgnoreOrientationRequest` 都沒有 `@UnsupportedAppUsage`，對一般 app 進程是
blocklist。MoonClicker 的 Shizuku user service 跑在 shell 起的 `app_process` 裡，
且 `MoonClickerShizukuService.init` 已經在呼叫 `LSPass.addHiddenApiExemptions(...)`
（`MoonClickerShizukuService.kt:65-75`）—— 若新增這條路，記得把
`Landroid/view/IWindowManager` 也加進豁免清單。**待 #11 實測確認**。

### 3.7 AIDL 建議

`IMoonClickerService` 需要新增大致如下的三個方法（實作在 Shizuku 側按 `SDK_INT` 分岔）：

```
boolean setDisplayRotation(int displayId, int rotation) = 302;   // freezeDisplayRotation
boolean clearDisplayRotation(int displayId) = 303;               // thawDisplayRotation
int getDisplayRotation(int displayId) = 304;                     // DisplayInfo.rotation
```

（編號只是佔位，實際定案在 #16。）

---

## Q4. 跨進程的 `DisplayListener.onDisplayChanged`

**答案：可以，而且 rotation 變化一定會觸發。**

### 事件是廣播的，不看建立者

DMS 端：logical display 有任何變動 → `handleLogicalDisplayChangedLocked()`
→ `sendDisplayEventIfEnabledLocked(display, EVENT_DISPLAY_CHANGED)`
（`android15-release:DisplayManagerService.java:2099-2110`）
→ `deliverDisplayEvent(displayId, uids, event)`（`:3172-3195`）。
派送迴圈是：

```java
for (int i = 0; i < count; i++) {
    if (uids == null || uids.contains(mCallbacks.valueAt(i).mUid)) {
        mTempCallbacks.add(mCallbacks.valueAt(i));
    }
}
```

display 事件走的路徑 `uids == null`（`uids` 只有 display group / topology 事件才用），
所以**每一個註冊過的進程都會收到**，與誰建立這個 VD 完全無關。
唯一的 server 端過濾是各 callback 自己的 events mask
（`CallbackRecord.shouldSendEvent`，`:3768-3790`），對應
`registerDisplayListener` 時給的 `EVENT_FLAG_DISPLAY_CHANGED`。

### 可見性由 `FLAG_PUBLIC` 決定

client 端 `DisplayManagerGlobal.handleDisplayEvent()` 先取
`info = getDisplayInfoLocked(displayId)`，再把 `info` 交給每個 listener；
`EVENT_DISPLAY_CHANGED` 的分支是：

```java
if (info != null && (forceUpdate || !info.equals(mDisplayInfo))) {
    mDisplayInfo.copyFrom(info);
    mListener.onDisplayChanged(displayId);
}
```

`info` 來自 `DisplayManagerService.getDisplayInfoInternal(displayId, callingUid)`
（`:1244-1258`），它只在 `info.hasAccess(callingUid)` 時回傳非 null。
`Display.hasAccess`（`android15-release:core/java/android/view/Display.java:1919-1926`）：

```java
return (flags & Display.FLAG_PRIVATE) == 0
        || uid == ownerUid
        || uid == Process.SYSTEM_UID
        || uid == 0
        || DisplayManagerGlobal.getInstance().isUidPresentOnDisplay(uid, displayId);
```

而 `VIRTUAL_DISPLAY_FLAG_PUBLIC` 的作用正是**不要**設 `FLAG_PRIVATE`
（`android15-release:VirtualDisplayAdapter.java:477-478`：
`if ((mFlags & VIRTUAL_DISPLAY_FLAG_PUBLIC) == 0) { mInfo.flags |= DisplayDeviceInfo.FLAG_PRIVATE ... }`）。
MoonClicker 一律加上 `VIRTUAL_DISPLAY_FLAG_PUBLIC`（`MoonClickerShizukuService.kt:46`），
所以 `hasAccess` 對任何 UID 都回 true，`info` 永遠非 null。
（順帶一提：即使是 private VD，只要 app 進程有 Activity 跑在上面，
`isUidPresentOnDisplay` 也會回 true。）

### rotation 算不算「changed」

`onDisplayChanged` 的觸發條件是 `!info.equals(mDisplayInfo)`，
而 `DisplayInfo.equals()` 比的欄位裡明確包含
`rotation`、`logicalWidth`、`logicalHeight`、`appWidth`、`appHeight`
（`android15-release:core/java/android/view/DisplayInfo.java:420-445`）。
旋轉必然同時改動 `rotation` 與（90/270 時）`logicalWidth/Height`，**一定會觸發**。

WM 端的回寫鏈也完整：`DisplayContent` 算完新 rotation
→ `DisplayManagerInternal.setDisplayInfoOverrideFromWindowManager(displayId, info)`
（`DisplayManagerService.java:4831-4833` → `:898-903`
→ `LogicalDisplay.setDisplayInfoOverrideFromWindowManagerLocked` `:330`）
→ 回傳 true 時觸發 `handleLogicalDisplayChangedLocked` → 事件送出。

### 更直接的替代路徑：`IWindowManager.watchRotation`

```java
int watchRotation(IRotationWatcher watcher, int displayId);   // 回傳當下的 rotation
void removeRotationWatcher(IRotationWatcher watcher);
```

**API 30–36 簽章完全不變**（`android11-release:IWindowManager.aidl:274,281` …
`android16-release:IWindowManager.aidl:344,351`），而且
`WindowManagerService.watchRotation` **沒有任何權限檢查**
（`android15-release:WindowManagerService.java`，方法體只有查 `DisplayContent` 是否存在，
不存在則丟 `IllegalArgumentException`）。回呼是
`IRotationWatcher.onRotationChanged(int rotation)`，只給 rotation、不給尺寸。

兩者取捨：`DisplayListener` 拿得到完整 `DisplayInfo`（含交換後的長寬，這正是
`Viewport` 需要的），`watchRotation` 語意更窄但更即時且零權限。
**建議用 `DisplayListener`**（事件驅動，不需要輪詢），
`watchRotation` 留作交叉驗證或 debug 用。

### 結論

回授機制**可以是事件驅動的，不需要輪詢**。
`DisplayManager.registerDisplayListener(listener, handler)` 在 `:app` 進程就能收到
Shizuku 進程建立的 public VD 的 `onDisplayChanged`。
唯一的實務風險是 `isUidCached(uid)`（`DisplayManagerService.java:3162-3169`）：
進到 cached 狀態的 app 進程會把事件排隊到變回非 cached 才送。
`FullscreenDisplayActivity` 在前景，不受影響。**待 #11 實測確認**。

---

## 對地圖 #9 已定案前提的裁決

### 確認（confirmed）

- **前提 2 的前半 —— 「VD 沒有感測器，不會自己旋轉」**：**確認，而且是明文**。
  `DisplayRotation.java:1282-1285`：
  「For secondary displays we ignore things like displays sensors, docking mode and
  rotation lock, and always prefer user rotation.」
- **前提 2 的二分法**：**確認**。宣告固定方向的 app 會讓 VD 轉
  （`rotationForOrientation` 的 `LANDSCAPE`/`PORTRAIT` 分支）；
  `unspecified` 的 app 拿到的是 `mUserRotation`，新建 VD 為 `ROTATION_0`，
  所以「永遠不會轉」——除非有人主動設 user rotation。
  Keep 的實測現象與原始碼完全吻合。
- **前提 3 —— 「單一驅動源：手機方向，我們主動設定 VD 的 rotation」**：**確認可行**，
  且有具體 API（`freezeDisplayRotation`）與具體權限依據（shell 持有 `SET_ORIENTATION`）。
  `DisplayListener` 作為回授也成立（Q4）。
- **前提 5 —— `Viewport` 同一份幾何服務呈現與反向映射**：本票沒有任何發現與之衝突。
  Q2 反而強化了它：VD 邏輯尺寸會在旋轉時交換，兩份幾何更容易走散。

### 推翻 / 需要修正（overturned）

- **前提 2 的括號說明 —— 「`VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT` 是結果的通道，
  不是旋轉的來源」**：**部分推翻**。
  「不是旋轉的來源」正確；「只是結果的通道」在 **Android 15 (API 35) 以後不成立** ——
  它同時是 `mDefaultFixedToUserRotation` 的一項，沒有它 WindowManager 就**不會**
  為 app 宣告的方向旋轉這個 VD。實務結論不變（MoonClicker 本來就有設），
  但地圖的措辭應改成「結果的通道，**且在 API 35+ 是被動旋轉的必要條件**」。
- **地圖「Not yet specified」裡的「主動 resize VD —— 若只改 rotation 導致內容在 VD 內被
  letterbox 成細條」**：**這個顧慮基於錯誤的模型，可以劃掉**。
  只改 rotation 會讓 logical 長寬**交換**（`DisplayContent.java:2271-2282`），
  不會產生細條。1080×2400 的 VD 轉到 90 度就是一個 2400×1080 的邏輯顯示器。
  真正需要 resize 的情境是「想要不同的長寬比」，不是「旋轉」。

### 仍需真機驗證（→ #11）

1. `wm user-rotation -d <vdId> lock 1` 是否真的讓 Keep 在 VD 內重排版成橫向，
   且 `DisplayInfo.logicalWidth/Height` 交換成 2400×1080。**這一條驗完，整張圖就解鎖了。**
2. 有 `ROTATES_WITH_CONTENT` 時，送進 SurfaceView 的影格是否已經是轉正的
   2400×1080（而不是 1080×2400 內含一塊橫向內容）。
3. `:app` 進程的 `DisplayListener.onDisplayChanged` 是否真的在上述旋轉時被呼叫，
   以及與 WM 實際完成旋轉之間的延遲。
4. Shizuku 進程呼叫 `IWindowManager.freezeDisplayRotation` 是否會被 hidden API
   限制擋下（`LSPass.addHiddenApiExemptions` 是否需要加 `Landroid/view/IWindowManager`）。
5. MoonClicker 的 VD 在 API 33+ 帶著 `VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP`，
   是否影響上述任何一條（原始碼上看不出關聯，但沒有排除）。
6. 目標裝置若開了開發者選項「強制外部顯示器使用桌面模式」
   （`mForceDesktopModeOnExternalDisplays`），VD 會被強制 fixed-to-user-rotation
   （`DisplayRotation.java:445-453`）。應在 #11 確認測試機沒開這個選項。

---

## 引用來源索引

全部來自 `https://github.com/aosp-mirror/platform_frameworks_base`
（AOSP 官方 mirror），對應分支 `android{11,12,13,14,15,16}-release`。
上游同一份檔案也可在 `https://cs.android.com/android/platform/superproject/main/+/main:frameworks/base/...` 檢視。

- `core/java/android/hardware/display/DisplayManager.java`
- `core/java/android/hardware/display/DisplayManagerGlobal.java`
- `core/java/android/hardware/display/VirtualDisplay.java`
- `core/java/android/view/IWindowManager.aidl`
- `core/java/android/view/Display.java`
- `core/java/android/view/DisplayInfo.java`
- `core/res/AndroidManifest.xml`
- `packages/Shell/AndroidManifest.xml`
- `services/core/java/com/android/server/display/DisplayManagerService.java`
- `services/core/java/com/android/server/display/LogicalDisplay.java`
- `services/core/java/com/android/server/display/VirtualDisplayAdapter.java`
- `services/core/java/com/android/server/wm/DisplayContent.java`
- `services/core/java/com/android/server/wm/DisplayRotation.java`
- `services/core/java/com/android/server/wm/DisplayWindowSettings.java`
- `services/core/java/com/android/server/wm/WindowManagerService.java`
- `services/core/java/com/android/server/wm/WindowManagerShellCommand.java`
