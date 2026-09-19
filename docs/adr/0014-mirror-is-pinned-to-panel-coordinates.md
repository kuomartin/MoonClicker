# 鏡像釘在面板座標，不隨視窗旋轉

鏡像在面板座標中的淨變換是 `(d − v)·90`（`d` = display 0 的 rotation，`v` = 虛擬顯示的 rotation）。我們把反轉的依據從 `v` 改成 `d`，理由是當時認為兩者會收斂一致、淨變換因此恆為 0，順勢刪掉了撐起這個收斂關係的 `FollowDisplayRotation` 與 `SensorRotationDriver`，改用 `ROTATION_ANIMATION_SEAMLESS` 讓視窗旋轉不再截圖做動畫。

## Status

Superseded by [ADR-0017](0017-vd-rotation-is-cancelled-at-the-distributor.md) as of 2026-09-19：`v ≡ d` 的前提不成立（兩者被上述那次改動徹底解耦），且真機驗證後發現手動釘住面板本身也是不必要的一層。詳見 ADR-0017 與 [seamless rotation 與 SurfaceView 的旋轉限制](../research/seamless-rotation-and-surfaceview.md)。
