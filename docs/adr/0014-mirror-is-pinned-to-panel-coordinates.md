# 鏡像釘在面板座標，不隨視窗旋轉

自動旋轉的過場會卡。原因不在任何一環太慢，而在鏡像的視覺路徑上**有兩個旋轉**。

設 `d` 為 display 0 的 rotation、`v` 為虛擬顯示的 rotation。`MirrorSurface`
（`VirtualDisplayMirror.kt:158`）在 **view 空間**套 `rotationZ = -v·90`，而 view 空間
本身已被系統轉了 `d`。鏡像在**面板座標**中的淨變換因此是：

```
(d − v) · 90
```

`v == d` 時它是恆等——鏡像完全不動。目前的鏈確實會收斂到 `v == d`
（感測器 → `v`，再由 `v` → `requestedOrientation` → `d`），所以**穩態早就是對的**。
問題在 transient：`d` 與 `v` 在不同時刻落地，中間 `(d − v) = ±90`，鏡像整個甩過去再甩回來。
而且兩者的落地方式還不對稱——`v` 經由 `onDisplayChanged` 瞬間生效，`d` 要走系統數百毫秒的
旋轉動畫。

這個性質是**湊出來的**而不是寫進結構的：沒有任何一行程式碼要求 `d` 與 `v` 相等，
它們只是各自朝同一個感測器讀數收斂。

## 決定

反轉的依據從 `v` 改成 `d`。鏡像在面板座標中的變換恆為 0，**由構造保證**而非由收斂保證。

`v` 怎麼變、何時變，view 階層都不動。唯一會動的是 buffer 裡的內容，而那是虛擬顯示自己的
WindowManager 在轉、有它自己的動畫。一次旋轉、一個動畫，跟轉一支真手機一樣。

畫面對不對：釘住面板的鏡像顯示的是 buffer 的面板方向，buffer 內容轉了 `v`，使用者的上方
在 `d`——所以 `v == d` 時正立。穩態正確，transient 期間看到的是虛擬顯示自己的旋轉動畫，
那正是我們要的。

## 這讓兩件事變成獨立的

把 `d` 與 `v` 綁死的是 `FollowDisplayRotation`。它一走，功能就能拆成互不相干的兩塊：

**系統 UI 方向**——不鎖 `requestedOrientation`，導航欄與周邊 chrome 自己轉。
純粹的體感改善，desync 也看不見，因為鏡像不參與。需要
`configChanges="orientation|screenSize|screenLayout|smallestScreenSize"` 避免 Activity 重建。

**虛擬顯示方向與輸入**——`SensorRotationDriver` 可以整個刪掉。它存在的唯一理由是
`setRequestedOrientation` 鎖住 display 0 之後形成死結（`OrientationChain.kt:29-31`）；
鎖解除後 display 0 的 rotation 重新是合法來源，直接 `d → v`。`quantizeOrientation`、
`BOUNDARY_MARGIN_DEGREES`、`isAutoRotateEnabled` 一併刪除——系統的遲滯與使用者的
旋轉鎖定設定本來就是免費的。

這推翻了 [#17](https://github.com/kuomartin/ReLC/issues/17) 建立的四環鏈中的後兩環。
該票當時就指出「兩環之間必然有時間差」，並把它當成需要緩解的既成事實；本 ADR 的立場是
那個時間差不該存在於鏡像的視覺路徑上。

## TextureView 留著

重構後仍然需要一個真正的 view 層旋轉——window 就是被系統轉了 `d`，要讓 view 在面板座標
中不動就只能反轉回去。差別只在依據從 `-v·90` 換成 `-d·90`。

`VirtualDisplayMirror.kt:116` 那條註解的前提到 Android 15 仍然成立。
`SurfaceView.onSetSurfacePositionAndScale()`
（`android15-release:core/java/android/view/SurfaceView.java:1512-1518`）組出來的矩陣是
`setMatrix(surface, postScaleX, 0f /*dtdx*/, 0f /*dtdy*/, postScaleY)`——切變項寫死為
`0f`，純軸對齊縮放。`mScreenRect`（:1172-1175）取自佈局邊界而非繪製變換。class javadoc
（:85-88）自己也承認 post-layout transform 下 surface 可能合成不正確。

唯一能讓 SurfaceView 成立的路是把旋轉移到 producer 側：虛擬顯示 → 中間 SurfaceTexture
→ GL pass → SurfaceView。scrcpy 正是這樣做（`AffineOpenGLFilter`/`OpenGLRunner`，見
[scrcpy 筆記](../research/scrcpy-rotation-handling.md)），但它的下游是編碼器、根本沒有
view 可以轉。拿一個額外的 GPU pass 加一次 buffer copy 去換掉 TextureView 隱含的那次
copy，划不來。TextureView 的代價（多一次複製、沒有獨立硬體圖層、功耗與延遲較差）由旋轉
需求壓過。

## seamless 的 fallback 不歸我們管

光在 view 階層反轉還不夠：`d` 改變時系統預設會截圖整個畫面做旋轉動畫，鏡像會跟著被動畫掉。
`window.attributes.rotationAnimation = ROTATION_ANIMATION_SEAMLESS`
（`android15-release:core/java/android/view/WindowManager.java:3985-3992`）的 javadoc
逐字就是這個情境：「ideal for Camera apps which don't want the viewfinder contents to
ever rotate or fade」。機制在
`services/core/java/com/android/server/wm/WindowState.java:678-685`——`SeamlessRotator`
對 window surface 套反向變換讓它物理上不動，等 app 下一次重繪才釋放。

它是 hint。`DisplayRotation.shouldRotateSeamlessly()`（:748-792）每次旋轉重新評估全部
條件（fullscreen opaque 且為 current focus、非 multi-window、activity bounds 須
match parent、display 上無 system alert window 與 PINNED task、導航欄能換邊或手勢導航），
不滿足就自己退回 CROSSFADE 或 ROTATE。**不需要寫任何動畫 fallback**，渲染正確性也不用
分支——反轉的依據是 `d`，兩條路徑上都對。

代價有三個：

- **查不到走了哪條路。** 沒有公開 API 告訴你這次旋轉是不是 seamless，品質隨裝置與當下
  狀態靜靜浮動，無法 log 也無法針對性調整。
- **seamless 期間 layer 是凍結的**（`WindowState.java:678-685`）。旋轉後第一幀若慢，
  使用者看到的是釘在原地的舊畫面而不是動畫。這是 `configChanges` 必須加的另一個理由。
- **連續快轉會退化**（`DisplayRotation.java:786-789`）：前一次 seamless 未完成前會拒絕
  下一次。來回快翻手機時掉回動畫，無解。

## 輸入要帶方向戳記

鏡像釘住面板之後，正立的是面板空間而非 view 空間，所以 `TouchForwarder` 必須與
TextureView 放進同一個釘住面板的容器，縮放-only 的映射才繼續成立。

另外 transient 期間 `v` 是舊的，此時的點擊會被映射到過時的方向。scrcpy 的做法是每個
pointer event 都帶著產生時的 frame size，`PositionMapper.map()` 對不上就回 `null`
直接丟棄。這裡照抄，但戳記要包含 rotation 而不只是 size——scrcpy 只戳 size，正方形顯示
會漏掉。

## 根因尚未量測

`(d − v)` 的 transient 是推論，不是觀測。動手前應先以 log timestamp 或 systrace 確認那
段 ±90 真的存在且長度符合預期。本 ADR 的結構論證（兩個旋轉應該只剩一個）不依賴那個量測，
但「卡頓確實由此而來」依賴。

## Status

Accepted。與 [ADR-0012](0012-surface-size-is-owned-not-derived.md) 同向——該 ADR 消滅了
「一個事實兩個來源」，本 ADR 消滅「一個旋轉兩個執行者」。

一手來源：[scrcpy 的旋轉處理](../research/scrcpy-rotation-handling.md)、
[VirtualDisplay 旋轉的 AOSP 語意](../research/vd-rotation-aosp-semantics.md)。

[#23](https://github.com/kuomartin/ReLC/issues/23)（沒有人擁有虛擬顯示的「期望旋轉」）
的範圍因此縮小：`d` 成為 `v` 的唯一來源後，`SensorRotationDriver` 連同那個行程級的
`rotationScope` 都會消失，剩下的只有「顯示器裡的 app 覆蓋了旋轉然後自己結束」那條窄路徑。
