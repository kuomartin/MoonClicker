# v 在 distributor 消除、鏡像不釘面板：真機驗證記錄

支撐 [ADR-0017](../adr/0017-vd-rotation-is-cancelled-at-the-distributor.md) 的調查過程與真機證據。研究日期 2026-09-19，裝置 Pixel 7a（192.168.68.110）與 SM-A217F（192.168.68.111）。

沿用 [vd-rotation-aosp-semantics.md](vd-rotation-aosp-semantics.md) 與 [seamless-rotation-and-surfaceview.md](seamless-rotation-and-surfaceview.md) 已經查過的 AOSP 機制，不重複查證；這篇只記這次新增的、來自真機觀察與程式碼盤點的部分。

---

## 為什麼 distributor 是消 v 的位置，不是 consumer 各自處理

`RelcV2Service.kt` 的 `addVirtualDisplaySurface` 掛上的 surface 一律是 `GlesDistributor` 的下游——鏡像 `TextureView`、`H264EncoderSink`、`NativeImageReader`（vision）全部是它的 sink。盤點 `GlesDistributor.cpp` 發現它的 GL pass 原本是純 pass-through（`VERTEX_SHADER`／`FRAGMENT_SHADER` 沒有任何變換矩陣，`TEX_COORDS` 是寫死的一組），代表在這裡加一個依 `v` 選紋理座標的旋轉，不新增 pass、不新增 buffer copy——這是選它而不是選某個 consumer 的直接理由。

## GL 旋轉方向：真機測出來的，不是推出來的

第一版實作猜「index k 的 `TEX_COORDS_BY_ROTATION[k]` 抵銷 `v=k` 烤進紋理的旋轉」，方向是順時針 k*90。裝上 Pixel 7a 用 `Alto's Adventure`（一個會強制橫向的遊戲）實測，畫面內容轉正了但**差 180 度**——對 `v=1`（90°）來說，順時針 90 跟逆時針 90 剛好差 180，這個現象本身就鎖定了問題：不是幾何算錯，是方向反了。修正為 `rotation.store((4 - v) & 3)`，重裝驗證後畫面正確。

`GlesDistributor.cpp` 裡的 `TEX_COORDS_BY_ROTATION` 表格本身（index k 對映到哪個象限）與這個方向反轉是兩件事：表格是靜態幾何、寫死在陣列裡；反轉只是查表用哪個 k。兩者都已經真機驗證過（見 `GlesDistributor.h` 的註解）。

## `VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT` 的真機證據

之前的研究筆記是讀 AOSP 原始碼推出「WindowManager 會把內容旋轉『進』固定尺寸的 buffer」，這次用 `adb shell dumpsys display` 直接量到了這件事的具體數字：

```
Display 21 (Alto's Adventure), v=1 時：
  mBaseDisplayInfo:     rotation 0, real 1080 x 2400
  mOverrideDisplayInfo: rotation 1, real 2400 x 1080
```

`mOverrideDisplayInfo` 是公開 `Display` API（`getRotation()`／`getRealSize()`）實際回的那份——長寬確實互換了，證實這是系統層級的真實狀態，不是 app 自己在內容裡動手腳。這也排除了另一個假設：「Alto's Adventure 是自己讀 sensor、在固定的 VD rotation 下自行翻轉畫面」——如果是這樣，`Display.getRotation()` 應該全程回同一個值。`mOverrideDisplayInfo.rotation` 真的變了，代表 WindowManager 確實在改這個 VD 的 rotation，遊戲宣告的多半是 `SCREEN_ORIENTATION_SENSOR_LANDSCAPE` 這類會讓系統依裝置傾斜方向決定橫向哪一邊的方向類型。

同一個現象也解釋了「同一支 app、往兩個不同方向轉出橫向再轉正，鏡像畫面卻互為鏡像」：`v` 在兩次操作裡分別鎖定到 `ROTATION_90` 與 `ROTATION_270`（landscape 的兩個合法解），跟主螢幕的 `d` 全程沒變無關——這正是「`d` 不動只有 `v` 動」的穩態案例之一，不是轉場暫態。

## 不釘面板：真機驗證轉場沒有閃動

[ADR-0014](../adr/0014-mirror-is-pinned-to-panel-coordinates.md) 手動反轉 `-d·90` 的動機是避免系統的旋轉轉場閃動，但 `FullscreenDisplayActivity` 早就同時宣告了 `configChanges` 與 `ROTATION_ANIMATION_SEAMLESS`。拿掉手動反轉、只留這兩個機制，在 Pixel 7a 上實測：

- 直向／橫向之間切換：瞬間，沒有閃動或跳動。
- 橫向兩側之間切換：瞬間切換之後跟著 app 自己畫的翻轉動畫——這是內容決定，不是系統轉場的一部分，體驗上可接受。

結論：手動釘住面板是疊加在 `configChanges` + `ROTATION_ANIMATION_SEAMLESS` 之上、經驗證後發現不必要的第三層，直接拿掉，記在 [ADR-0017](../adr/0017-vd-rotation-is-cancelled-at-the-distributor.md)。

**未驗證的部分**：橫向兩側切換時 app 自己的翻轉動畫，若要讓鏡像那端也跟著做一個過渡動畫（而不是硬切），需要額外設計，這裡沒有實作也沒有評估成本，留作未來方向。

## H.264／vscode-extension：確認不需要協定改動

原本預期需要比照 scrcpy 的 session packet 機制，讓 client 知道影格尺寸換了。實測 `vscode-extension` 的實作後發現不需要：它用 `jmuxer` 把 NALU 餵進標準 `<video>` 元素（MSE），`media/mirror.js` 的 `getAspectFit()` 每次都重讀 `frameEl.videoWidth`／`videoHeight`——這是瀏覽器對目前解碼串流的即時回報，由 H.264 SPS 自帶，換解析度時瀏覽器自動更新，不需要任何自訂協定層。

`H264EncoderSink` 建構時吃的 `size = service.getDisplaySurfaceSize(displayId)` 正是這次改語意的同一個呼叫——只要 vscode 是在旋轉「之後」才開新的 WebSocket 連線，H264 那條路徑自動是對的，不需要額外程式碼。唯一沒解的是 WebSocket 連線期間 VD 再旋轉（`MediaCodec` 不會自動 reconfigure），跟 vision／mirror 是同一類已知限制，記在 ADR-0017 的 Consequences。

## 順帶發現：TextureView 擷取路徑的雙重旋轉

盤點所有讀 `TextureView.getBitmap()` 的地方（縮圖快取、workbench 的 MJPEG 端點、裁切擷取）時發現它們都還在對擷取到的 bitmap 呼叫 `rotateBufferBitmap(bitmap, geometry.rotation)`——這是舊架構遺留的步驟：以前 `TextureView` 顯示的是 distributor **沒有**轉正的原始畫面，擷取後要自己轉一次。distributor 現在已經轉正了，這一步變成對已經轉正的畫面再轉一次，是這次改動引入的迴歸，跟即時鏡像／觸控是分開的三個呼叫點，因為這次一開始沒有把它們納入測試範圍才沒被抓到。已在同一輪修掉（`DisplayThumbnailCache.put`、`MirrorFrameSource.Captured`、`FullscreenDisplayActivity` 的裁切擷取，三處都不再呼叫 `rotateBufferBitmap`），`rotateBufferBitmap`／`quarterTurnMatrix`／`rotateQuarterTurn`／`quarterTurnCoefficients` 因此整組變成死碼，一併刪除。

## 順帶評估：issue #40（換成 SurfaceView）

issue #40 記錄的卡點是「旋轉邏輯必須自己在 GLES 層完成」——這正是 ADR-0017 做的事，鏡像的 `MirrorSurface` 現在完全沒有 `graphicsLayer`／`rotationZ`，issue 裡描述的前提已經成立。但盤點後發現issue 沒提到第二個卡點：`FullscreenDisplayActivity.kt` 的擷取路徑（MJPEG 鏡像＋縮圖＋裁切）全部依賴 `TextureView.getBitmap()` 同步拿 bitmap，`SurfaceView` 沒有這個 API，只能改用 `PixelCopy`（非同步、多一層排隊邏輯）。換成 `SurfaceView` 如果只看旋轉那塊確實可行，但會同時逼出這一段沒被算進原 issue 成本估計的重構，值得在真正動工前補上這筆帳。
