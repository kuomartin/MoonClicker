# Seamless rotation 與 SurfaceView 的旋轉限制

研究問題：能不能讓鏡像 view 相對**物理面板座標**完全不動，同時讓 MainDisplay（連同導航欄）
照常旋轉？研究日期 2026-09-14。

**方法**：讀 AOSP `aosp-mirror/platform_frameworks_base` 的 `android15-release` 分支
（API 35）。引用格式為 `檔名:行號`，行號對應撰寫當日該分支的 HEAD；分支會隨 QPR 更新，
若對不上請以引用的符號名為準。

沒有真機驗證。`(d − v)` 的 transient 是幾何推論，不是量測到的現象。

---

## TL;DR

1. 可行。鏡像在面板座標中的淨變換是 `(d − v)·90`（`d` = display 0 rotation，
   `v` = VD rotation）。反轉的依據從 `v` 改成 `d`，它就恆為 0。
2. 光在 view 階層反轉不夠——系統預設會截圖整個畫面做旋轉動畫。
   `ROTATION_ANIMATION_SEAMLESS` 才能避開，它的 javadoc 逐字就是這個情境。
3. seamless 是 hint，系統每次旋轉自行評估八項前提並自動退回 CROSSFADE／ROTATE。
   **不需要寫 fallback**，但也**查不到走了哪條路**。
4. SurfaceView 吃不到旋轉的前提到 Android 15 仍然成立：它的 surface 矩陣把切變項寫死為
   `0f`。TextureView 必須留著。

---

## 幾何：為什麼兩個旋轉會抵銷

`MirrorSurface`（`VirtualDisplayMirror.kt:158`）在 **view 空間**套
`rotationZ = -v·90`，而 view 空間本身已被系統轉了 `d`。面板座標中的淨變換因此是
`(d − v)·90`，`v == d` 時為恆等。

目前的鏈確實會收斂到 `v == d`（感測器 → `v`，再由 `v` → `requestedOrientation` → `d`），
所以**穩態早就是對的**。但沒有任何一行程式碼要求兩者相等，它們只是各自朝同一個感測器讀數
收斂——這是湊出來的性質，不是結構保證的。

transient 期間 `(d − v) = ±90`，鏡像整個甩過去再甩回來。兩者的落地方式還不對稱：
`v` 經由 `onDisplayChanged` 瞬間生效，`d` 要走系統數百毫秒的旋轉動畫。

## `ROTATION_ANIMATION_SEAMLESS` 的語意

`core/java/android/view/WindowManager.java:3985-3992`：

> Value for {@link #rotationAnimation} to specify seamless rotation mode.
> This works like JUMPCUT but will fall back to CROSSFADE if rotation
> can't be applied without pausing the screen. For example, this is ideal
> for Camera apps which don't want the viewfinder contents to ever rotate
> or fade (and rather to be seamless) but also don't want ROTATION_ANIMATION_JUMPCUT
> during app transition scenarios where seamless rotation can't be applied.

「don't want the viewfinder contents to ever rotate or fade」正是鏡像的需求。

欄位本身（`:3993-4006`）另有一層前提：

> This only has an affect if the incoming and outgoing topmost
> opaque windows have the #FLAG_FULLSCREEN bit set and are not covered
> by other windows. All other situations default to the
> {@link #ROTATION_ANIMATION_ROTATE} behavior.

### 機制

`services/core/java/com/android/server/wm/WindowState.java:678-685`：

> During seamless rotation we have two phases, first the old window contents
> are rotated to look as if they didn't move in the new coordinate system. Then we
> have to freeze updates to this layer (to preserve the transformation) until
> the resize actually occurs. This is true from when the transformation is set
> and false until the transaction to resize is sent.

`WindowState.seamlesslyRotateIfAllowed()`（:878-919）建立 `SeamlessRotator` 並呼叫
`unrotate(transaction, this)` 對 window surface 套反向變換，讓它**物理上不動**；
透過 `applyWithNextDraw(mSeamlessRotationFinishedConsumer)` 在 app 下一次重繪時釋放。

## 八項前提

`services/core/java/com/android/server/wm/DisplayRotation.shouldRotateSeamlessly()`
（:748-792）逐次旋轉重新評估：

1. top fullscreen opaque window 必須存在且為 `mCurrentFocus`（:755-758）
2. 該 window 的 `rotationAnimation == ROTATION_ANIMATION_SEAMLESS`（:762）
3. 非 multi-window（:762）
4. 非 animating（:763）
5. `canRotateSeamlessly()`：導航欄能換邊，或 `config_allowSeamlessRotationDespiteNavBarMoving`
   為真（手勢導航）（:767、:793-798）——**device-dependent**
6. activity bounds 必須 `matchParentBounds()`（:771-774）
7. display 上不能有 PINNED task，也不能有 **system alert window**（:778-781）
8. 前一次 seamless rotation 尚未完成時拒絕（:786-789）

`hasTopFixedRotationLaunchingApp()` 為真時直接回 `true`，跳過其餘檢查（:750-753）。

任一不滿足即退回 CROSSFADE 或 ROTATE，呼叫端不需要也無法介入。

## SurfaceView 為何吃不到旋轉

`core/java/android/view/SurfaceView.onSetSurfacePositionAndScale()`（:1512-1518）：

```java
transaction.setPosition(surface, positionLeft, positionTop);
transaction.setMatrix(surface, postScaleX /*dsdx*/, 0f /*dtdx*/,
        0f /*dtdy*/, postScaleY /*dsdy*/);
```

`dtdx`、`dtdy` 是**寫死的 `0f`**——純軸對齊縮放加平移，沒有旋轉也沒有切變。

`mScreenRect`（:1172-1175）取自 `mWindowSpaceLeft/Top` 加 `getWidth()/getHeight()`，
是**佈局邊界**而非 view 的繪製變換。class javadoc（:85-88）自己承認：

> The transparent region that makes the surface visible is based on the
> layout positions in the view hierarchy. If the post-layout transform
> properties are used to draw a sibling view on top of the SurfaceView, the
> view may not be properly composited with the surface.

所以 `VirtualDisplayMirror.kt:116` 那條註解的前提到今天仍然成立。

唯一能讓 SurfaceView 成立的路是把旋轉移到 producer 側：VD → 中間 SurfaceTexture →
GL pass → SurfaceView。scrcpy 正是這樣做（`AffineOpenGLFilter`/`OpenGLRunner`，見
[scrcpy 的旋轉處理](scrcpy-rotation-handling.md)），但它的下游是編碼器、根本沒有 view
可以轉。多一個 GPU pass 加一次 buffer copy 去換掉 TextureView 隱含的那次 copy，划不來。

## 對 ReLC 的意涵

反轉依據改用 `d` 之後：

- **`v` 怎麼變、何時變，view 階層都不動。** 唯一會動的是 buffer 裡的內容，由 VD 自己的
  WindowManager 以自己的動畫轉。一次旋轉、一個動畫。
- **兩件事變成獨立的。** 綁死它們的是 `FollowDisplayRotation`；它一走，「系統 UI 方向」
  與「VD 方向＋輸入」互不相干。
- **`SensorRotationDriver` 可以整個刪掉。** 它存在的唯一理由是 `setRequestedOrientation`
  鎖住 display 0 後形成死結（`OrientationChain.kt:29-31`）。鎖解除後 `d` 重新是合法來源，
  順帶白拿系統的遲滯與使用者的旋轉鎖定設定。
- 需要 `configChanges="orientation|screenSize|screenLayout|smallestScreenSize"`
  避免 Activity 重建——seamless 期間 layer 是凍結的，重建會讓那個停格變得可見。
- **前提 7 要先確認**：全螢幕鏡像期間若會疊任何 system alert window，seamless 直接失效。

### 輸入

鏡像釘住面板後，正立的是面板空間而非 view 空間，`TouchForwarder` 必須與 TextureView
放進同一個釘住面板的容器，縮放-only 的映射才繼續成立。

transient 期間 `v` 是舊的，此時的點擊會被映射到過時的方向。scrcpy 的做法是每個 pointer
event 帶著產生時的 frame size，`PositionMapper.map()` 對不上就回 `null` 直接丟棄。
照抄，但戳記要含 rotation 而不只是 size——scrcpy 只戳 size，正方形顯示會漏掉。
