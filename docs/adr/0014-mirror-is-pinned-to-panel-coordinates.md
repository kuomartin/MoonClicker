# 鏡像釘在面板座標，不隨視窗旋轉

鏡像在面板座標中的淨變換是 `(d − v)·90`（`d` = display 0 的 rotation，`v` = 虛擬顯示的 rotation），目前只因為兩者各自朝同一個感測器讀數收斂才會歸零，所以旋轉過場期間會出現 ±90 的甩動。我們把反轉的依據從 `v` 改成 `d`，讓淨變換由構造恆為 0——view 階層不再隨旋轉移動，唯一會動的是虛擬顯示自己的內容，由它自己的 WindowManager 以自己的動畫轉。這推翻了 [#17](https://github.com/kuomartin/ReLC/issues/17) 建立的方向鏈後兩環：`FollowDisplayRotation` 與 `SensorRotationDriver` 隨之刪除，並改用 `ROTATION_ANIMATION_SEAMLESS` 讓視窗旋轉不再截圖做動畫。

「淨變換由構造恆為 0」這句話當時就不成立：撐起 `v ≡ d` 的正是本 ADR 一併刪掉的那兩環，拿掉之後 `v` 只由 VD 裡 app 宣告的方向決定、`d` 只由面板與使用者的旋轉鎖定決定，兩者不再有任何耦合。決定本身（鏡像釘在面板、view 層只補償 `d`、不要感測器鏈）不變，改為由 [ADR-0017](0017-vd-rotation-is-cancelled-at-the-distributor.md) 在 `v` 的源頭消除它來成立。

## Status

Superseded by [ADR-0018](0018-mirror-follows-the-window-instead-of-pinning-to-it.md) as of 2026-09-19：真機驗證後發現手動釘住面板是不必要的，`configChanges` + `ROTATION_ANIMATION_SEAMLESS` 本身就吃下了轉場成本。前提條件、AOSP 依據與 SurfaceView 為何不可行，仍見 [seamless rotation 與 SurfaceView 的旋轉限制](../research/seamless-rotation-and-surfaceview.md)；前提曾於同日由 [ADR-0017](0017-vd-rotation-is-cancelled-at-the-distributor.md) 修正過一次，決定本身隨後才被 ADR-0018 推翻。
