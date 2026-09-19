# VD 的方向在 distributor 消除，鏡像跟著視窗自然旋轉

VD 帶 `VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT` 時，WindowManager 會把內容轉進固定尺寸的 buffer；`GlesDistributor` 原本原封不動轉發這個 buffer，consumer（鏡像、H.264、vision）因此都要自己知道並抵銷這個旋轉（`v`）。`v` 現在改在 `GlesDistributor` 的輸出端消除：它的 GL pass 本來就在每一條輸出路徑上，加一個依 `v` 選紋理座標的變換不新增 pass、不新增 buffer copy。consumer 因此一律收到已經轉正的影格，只需要知道影格尺寸，不需要知道「旋轉」這回事。

鏡像原本另外手動反轉 `-d·90`（`d` = 主螢幕 rotation）把自己釘在面板座標，理由是避免系統的旋轉轉場動畫閃動。但 `FullscreenDisplayActivity` 早就同時宣告 `configChanges` 與 `ROTATION_ANIMATION_SEAMLESS`，真機驗證後發現這兩個機制本身就吃下了轉場成本，手動釘住是疊加在其上、不必要的第三層，直接拿掉。鏡像現在跟著視窗自然旋轉，letterbox 只需要知道 `v` 轉正後的內容自然尺寸，不再需要知道 `d`。兩項真機驗證過程見 [研究筆記](../research/vd-rotation-cancelled-at-distributor.md)。

## Consequences

`getDisplaySurfaceSize` 的語意從「建立時的 surface 尺寸常數」改成「該 VD 目前的影格尺寸」，隨 `v` 互換長寬——仍由服務一次宣告（[ADR-0012](0012-surface-size-is-owned-not-derived.md) 的原則不變）。呼叫端（`AImageReader`、`MirrorSurface`、`H264EncoderSink`）都只在**啟動／連線當下**讀一次，VD 中途再旋轉不會自動重建，這是已知限制，三者同一個根因，沒有分開處理。

`VisionMatcher` 的 `setRotation`／`logicalToFrame`／`frameToLogical` 三組換算與模板旋轉整段拿掉：影格即邏輯空間，[ADR-0013](0013-templates-are-logical-space.md) 的對外契約不變，只是不再需要引擎轉模板去對齊。`:app` 的 `touchTransform` 同樣只剩縮放，不用反轉 `d` 或疊 `v`。`H264EncoderSink`／vscode-extension 不需要協定層面的尺寸宣告：client 走標準 MSE，解析度由瀏覽器從 H.264 SPS 直接讀。

`TextureView` 擷取路徑（縮圖、workbench MJPEG、裁切）原本會對擷取到的 bitmap 額外轉正一次，現在來源已經是轉正後的內容，該步驟拿掉；`rotateBufferBitmap`／`quarterTurnMatrix`／`rotateQuarterTurn`／`quarterTurnCoefficients` 因此整組變成死碼，一併移除。

## Status

Accepted，已實作並在 Pixel 7a／SM-A217F 真機驗證：`GlesDistributor` 的旋轉方向、不釘面板的轉場、`Tier1SpikeTest` 15 條（含 landscape／reverse-landscape 下的 vision/tap/ROI round trip）、vscode-extension 端對端播放全部驗證過。Supersedes [ADR-0014](0014-mirror-is-pinned-to-panel-coordinates.md) as of 2026-09-19。
