# 虛擬顯示改用 uniqueId 識別，同名 resize 取代銷毀重建

現況：`ScriptSession.resolveDisplay` 用 `matchesSize`（尺寸）找現有虛擬顯示沿用——不同腳本要求同尺寸會被導去同一個顯示器（共用問題）。改用 `display#name`（來自 `MoonClickerService.createVirtualDisplay` 的 `name` 參數）當識別鍵，`name` 的來源從 `meta.name ?: dir.name`（會撞名）換成 `uniqueId`（持久、保證格式合法、已有 import 撞號檢查）。

名稱對得上但尺寸/densityDpi 不同時，用 `VirtualDisplay.resize()` + 重新設定 GLES 的來源 Surface（`VirtualDisplay.setSurface()`），不銷毀重建整個 VD。舊制虛擬顯示（用 `meta.name`/`dir.name` 建的）升級後對不上新 key 變孤兒——不處理，使用者自行在 Displays 頁手動刪。

## A. 新增/改動的檔案

| 檔案 | 內容 |
|---|---|
| `IMoonClickerService.aidl` | 新增 `boolean resizeVirtualDisplay(int displayId, int width, int height, int densityDpi)` |
| `MoonClickerService.kt` | 實作 `resizeVirtualDisplay`：`nativeCreateDistributor(width,height)` 建新 distributor → `vd.resize(width,height,densityDpi)` → `vd.setSurface(newSurface)` → 銷毀舊 distributor → 更新 `vdStore`/`distributorStore` |
| `MoonClickerService.kt` `startRotationTracking` | 見 Q2，可能要改成不 closure 住 nativePtr |
| `RecordingMoonClickerService.kt`（androidTest 測試替身） | 補上 `resizeVirtualDisplay` 的紀錄型實作 |
| `Script.kt` | `DisplayConfig.name` 改成由 `uniqueId` 組出（如直接用 `uniqueId`，已限制在 `[a-z0-9._-]+`） |
| `ScriptSession.kt` `resolveDisplay` | `NewVirtual` 分支：先用 `getDisplayInfo(id)?.name == target.config.name` 找現有顯示；找到但尺寸/densityDpi 不符就呼叫 `resizeVirtualDisplay`；找不到才 `createVirtualDisplay` |
| `ScriptSession.kt` `matchesSize` | 移除（不再靠尺寸比對找顯示器） |
| `ScriptSessionTest.kt` | 現有 5 個 `matchesSize` 測試整批換成 name-matching／resize 分支的測試 |

## B. 已定案

1. 尺寸不符時**resize 既有 VD**，不銷毀重建（保留 displayId，避免仰賴它的 consumer 斷線）。
2. 舊制虛擬顯示孤兒不處理，不寫遷移或清理邏輯。
3. **舊 distributor 的 sink 不轉移**：resize 換新 distributor 後，舊 distributor 上已掛的 consumer surface（全螢幕預覽、vision-test）直接斷線，由呼叫端自己重連。理由：轉移要處理「handle 是每個 distributor 各自從 0 起算」的問題（舊 handle 在新 distributor 裡對不上，需要額外的對照表，且目前 AIDL 沒有回呼機制能把新 handle 通知回呼叫端），而 resize 實際發生時（腳本啟動、name 對得上但尺寸不同）多半還沒有 consumer attach，是低機率換高結構成本，不值得。
4. **rotation tracking 重啟監聽**：`resizeVirtualDisplay` 換了新 nativePtr 後，先 `stopRotationTracking(displayId)` 停掉舊監聽（其 closure 還指著已 `nativeDestroyDistributor` 的舊 ptr），再用新 ptr 呼叫 `startRotationTracking(displayId, newPtr)`。
5. **`resizeVirtualDisplay` 失敗直接失敗**：`vd.resize`/`vd.setSurface` 丟例外時回傳 `false`，不退回銷毀重建；`ScriptSession.start` 走既有的「Target display not found」失敗路徑。
