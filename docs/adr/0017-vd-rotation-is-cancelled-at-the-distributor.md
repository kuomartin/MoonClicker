# VD 的方向在 distributor 消除，consumer 只知道影格尺寸

[ADR-0014](0014-mirror-is-pinned-to-panel-coordinates.md) 把鏡像的反向旋轉依據從 `v`（VD 自己的 rotation）改成 `d`（主螢幕 rotation），理由是淨變換 `(d − v)·90` 會由構造恆為 0；但保證 `v ≡ d` 的，正是同一份 ADR 一併刪掉的 `FollowDisplayRotation` 與 `SensorRotationDriver`。兩者脫鉤之後，VD 裡宣告 `landscape` 的 app 會讓 `v` 自己轉而 `d` 不動——使用者關閉自動旋轉時必然如此——淨變換永久停在 ±90，鏡像整個歪著顯示。這是穩態，不是轉場暫態。

修正不放在 consumer 的算式裡。`v` 由 `GlesDistributor` 在輸出時消除：它的 GL pass 本來就在每一條輸出路徑上（`addVirtualDisplaySurface` 掛上的 surface 一律是它的下游），而該 pass 目前是純 pass-through，加一個變換矩陣不新增 pass、也不新增 buffer copy。consumer（Fullscreen 鏡像、`H264EncoderSink`、frame capture、vision）因此一律收到已經擺正的影格，只需要知道影格尺寸，不需要知道「旋轉」這回事。

ADR-0014 的決定本身保留：鏡像仍然釘在面板座標，view 層仍然只補償 `d`。差別在於它不再依賴任何「`v` 會等於 `d`」的假設——consumer 眼中的 `v` 恆為 0，由構造成立，而不是靠兩條路徑各自收斂湊出來。`d` 的補償留在 `:app`，因為只有面板需要它；H.264 那一側沒有面板，套用 `d` 沒有意義。

## Consequences

影格尺寸隨 `v` 交換長寬，`AImageReader` 因此必須在 VD 旋轉時以新尺寸重開。`getDisplaySurfaceSize` 的量從「建立時的 surface 尺寸」變成「影格目前交付的尺寸」，仍由服務宣告——[ADR-0012](0012-surface-size-is-owned-not-derived.md) 的原則不變：那個尺寸由擁有者一次給出，呼叫端不從（邏輯尺寸, rotation）回推。

`VisionMatcher` 的 `setRotation` 與 `logicalToFrame`／frameToLogical 兩組換算失去作用對象：影格即邏輯空間。[ADR-0013](0013-templates-are-logical-space.md) 的對外契約不變，但引擎不再需要為了對齊影格而旋轉模板。`:app` 的 `touchTransform` 同樣少掉 `v` 那一段。

## Status

Accepted，尚未實作。修正 ADR-0014 的前提，保留其決定。
