# 鏡像釘在面板座標，不隨視窗旋轉

鏡像在面板座標中的淨變換是 `(d − v)·90`（`d` = display 0 的 rotation，`v` = 虛擬顯示的 rotation），目前只因為兩者各自朝同一個感測器讀數收斂才會歸零，所以旋轉過場期間會出現 ±90 的甩動。我們把反轉的依據從 `v` 改成 `d`，讓淨變換由構造恆為 0——view 階層不再隨旋轉移動，唯一會動的是虛擬顯示自己的內容，由它自己的 WindowManager 以自己的動畫轉。這推翻了 [#17](https://github.com/kuomartin/ReLC/issues/17) 建立的方向鏈後兩環：`FollowDisplayRotation` 與 `SensorRotationDriver` 隨之刪除，並改用 `ROTATION_ANIMATION_SEAMLESS` 讓視窗旋轉不再截圖做動畫。

## Status

Accepted。前提條件、AOSP 依據與 SurfaceView 為何不可行，見 [seamless rotation 與 SurfaceView 的旋轉限制](../research/seamless-rotation-and-surfaceview.md)。
