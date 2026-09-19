# 鏡像跟著視窗自然旋轉，不再釘住面板

[ADR-0014](0014-mirror-is-pinned-to-panel-coordinates.md) 手動把鏡像反轉 `-d·90`，讓它在面板座標中釘死，理由是避免系統的旋轉轉場動畫造成閃動。但那條轉場動畫的成本，`FullscreenDisplayActivity` 早就同時宣告了 `configChanges="orientation|screenSize|..."`（不重建 Activity）與 `WindowManager.LayoutParams.rotationAnimation = ROTATION_ANIMATION_SEAMLESS`（AOSP 的 `SeamlessRotator` 在轉場期間凍結畫面、resize 完成才放開，見 [seamless rotation 研究筆記](../research/seamless-rotation-and-surfaceview.md)）——這兩個機制本來就該吃下轉場成本，手動釘住面板是疊加在它們之上的第三層，而不是唯一解法。

真機驗證（2026-09-19，Pixel 7a，`Alto's Adventure`）：拿掉手動反轉、只留 `configChanges` + `ROTATION_ANIMATION_SEAMLESS`，直向／橫向之間的切換是瞬間的，沒有閃動；橫向兩側之間切換也是瞬間切換，唯一看得到的動畫是 app 自己畫的翻轉——那是內容決定，不是系統轉場，不在這份 ADR 要解決的問題範圍內。手動釘住面板因此是不必要的：拿掉它，鏡像改成跟著視窗自然旋轉，letterbox 只需要知道 [ADR-0017](0017-vd-rotation-is-cancelled-at-the-distributor.md) 轉正後的內容自然尺寸（`v` 決定要不要互換長寬），不再需要知道 `d`。

## Consequences

`Viewport` 的 letterbox 呼叫端不再傳入 `d`，`viewRotationDegrees`／`unrotatedWidth`／`unrotatedHeight` 這組「反向旋轉」用途的欄位在鏡像這條路徑上不再被使用；`Viewport` 本身仍然可能被 `CropSession` 這類還沒跟進的呼叫端使用，改動範圍限定在 `VirtualDisplayMirror.kt`。

`touchTransform` 還沒跟著改——它假設的座標系是「先反轉 d 回到 buffer 原始像素，再疊上 v」，這個假設在拿掉手動反轉之後已經不成立，會算出錯的觸控座標。這是下一輪要處理的項目，這份 ADR 的 Consequences 記一筆，不在這裡修。

橫向兩側之間切換時 app 自己的翻轉動畫，體驗上可以接受，列為未來可以加強的方向（例如鏡像這邊也對尺寸變化加一層過渡動畫），不是這份 ADR 要解決的問題。

## Status

Accepted。Supersedes [ADR-0014](0014-mirror-is-pinned-to-panel-coordinates.md) as of 2026-09-19.
