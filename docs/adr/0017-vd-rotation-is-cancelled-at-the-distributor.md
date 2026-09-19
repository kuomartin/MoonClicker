# VD 的方向在 distributor 消除，consumer 只知道影格尺寸

[ADR-0014](0014-mirror-is-pinned-to-panel-coordinates.md) 把鏡像的反向旋轉依據從 `v`（VD 自己的 rotation）改成 `d`（主螢幕 rotation），理由是淨變換 `(d − v)·90` 會由構造恆為 0；但保證 `v ≡ d` 的，正是同一份 ADR 一併刪掉的 `FollowDisplayRotation` 與 `SensorRotationDriver`。兩者脫鉤之後，VD 裡宣告 `landscape` 的 app 會讓 `v` 自己轉而 `d` 不動——使用者關閉自動旋轉時必然如此——淨變換永久停在 ±90，鏡像整個歪著顯示。這是穩態，不是轉場暫態。

修正不放在 consumer 的算式裡。`v` 由 `GlesDistributor` 在輸出時消除：它的 GL pass 本來就在每一條輸出路徑上（`addVirtualDisplaySurface` 掛上的 surface 一律是它的下游），而該 pass 目前是純 pass-through，加一個變換矩陣不新增 pass、也不新增 buffer copy。consumer（Fullscreen 鏡像、`H264EncoderSink`、frame capture、vision）因此一律收到已經擺正的影格，只需要知道影格尺寸，不需要知道「旋轉」這回事。

本 ADR 當時保留 ADR-0014「鏡像釘在面板、view 層補償 `d`」的決定，只修正它的前提；那個決定後來被 [ADR-0018](0018-mirror-follows-the-window-instead-of-pinning-to-it.md) 推翻（真機驗證發現手動釘住面板是不必要的）。跟本 ADR 有關、且不受 ADR-0018 影響的部分不變：consumer 眼中的 `v` 恆為 0，是由 distributor 這一層構造成立，不是靠兩條路徑各自收斂湊出來；H.264 那一側沒有面板，從來就不需要知道 `d`。

## Consequences

影格尺寸隨 `v` 交換長寬。`getDisplaySurfaceSize` 的量從「建立時的 surface 尺寸」變成「該 VD 目前的影格尺寸」，仍由服務一次宣告——[ADR-0012](0012-surface-size-is-owned-not-derived.md) 的原則不變：那個尺寸由擁有者給出，呼叫端不從（邏輯尺寸, rotation）回推。呼叫端各自只在**啟動當下**讀一次：鏡像的 `MirrorSurface`、`ScriptEngine.start()` 建的 `AImageReader` 都是如此，VD 中途再旋轉不會讓它們自動重開——這是已知限制，`AImageReader` 中途旋轉的重開支援沒有實作，見下方 Status 的範圍說明。

`VisionMatcher` 的 `setRotation` 與 `logicalToFrame`／`frameToLogical` 兩組換算已經整段拿掉：影格即邏輯空間。[ADR-0013](0013-templates-are-logical-space.md) 的對外契約不變，但引擎不再需要為了對齊影格而旋轉模板；`rotation` 改成建構子傳入的常數，只用來回答 Lua 的 `screen.rotation`，跟座標換算無關。`:app` 的 `touchTransform` 同樣少掉 `v` 那一段（跟 ADR-0018 的 `d` 一起消失，現在只剩縮放）。

## Status

Accepted，已實作並在 Pixel 7a 真機驗證：`GlesDistributor` 的旋轉方向、`Tier1SpikeTest` 的 15 條測試（含 landscape／reverse-landscape 下的 vision/tap/ROI round trip）全過。範圍不含腳本執行期間 VD 再旋轉的處理——`AImageReader` 與 `H264EncoderSink` 都還是只在啟動當下讀一次尺寸，中途旋轉不會重建，留給後續。修正 ADR-0014 的前提，保留其決定（該決定後被 [ADR-0018](0018-mirror-follows-the-window-instead-of-pinning-to-it.md) 推翻）。
