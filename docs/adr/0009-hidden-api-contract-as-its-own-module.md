# ADR-0009: Hidden-API 契約測試獨立成 `:hidden-api-contract` 模組

- 狀態：Accepted
- 日期：2026-09-11
- 相關：[issue #18](https://github.com/kuomartin/ReLC/issues/18)、[ADR-0006](0006-module-split-and-engine-facade.md)

## 背景

`:hidden-api` 的 stub 是對平台的**假設**——宣告某個 `@hide` 成員存在、簽章長某樣；編譯期用 stub，執行期解析到真的 framework 類別。這些假設過去完全沒有自動驗證，只靠人工比對 AOSP。#16 就曾經誤判「API 27–29 沒有 `freezeDisplayRotation`」（Android 10 其實就有），靠人工查證才發現。

要驗證這件事，得在真機／模擬器上用反射問平台，而且要跨多個 API level 問。問題是這組測試該住在哪個模組。

## 決策

新增一個薄模組 `:hidden-api-contract`，**只有 androidTest，沒有 main source set**。Gradle Managed Devices 的 API 矩陣也放這裡。

### 為什麼不放 `:hidden-api`

模組的 androidTest 一定會把該模組自己的 classes 打包進 test APK。stub 佔的是 `android.*` 命名空間，而 ART 解析這些名稱時 **bootclasspath 永遠優先**——載入的仍是平台真類別。測試碰不到 stub，等於拿平台驗平台，而且會**綠燈**。看起來有在驗證、實際什麼都沒驗，比沒有測試更危險。

### 為什麼不放 `:engine`（issue #18 原本的提議）

契約測試是純反射，對 `:engine` 的程式碼**零引用**。但 `:engine` 的 androidTest 會連帶跑 CMake／OpenCV 的 native build，四個 ABI 全編——test APK 實測 **78.8 MB**，而且矩陣裡每一台裝置都要重跑一次。抽出來之後是 **1.7 MB**。

真正被測的是**平台**，規格是 **`:hidden-api` 的 stub**；兩個既有模組都不是自然的家。命名為 `-contract` 而非 `-test`，是因為測的不是 `:hidden-api` 的行為（那裡面沒有行為，只有 stub），而是平台對 stub 所編碼假設的符合度。

## 後果

- `androidTestCompileOnly(project(":hidden-api"))` 是關鍵機制：讓 javac 把 `DisplayManagerHidden` 的 `VIRTUAL_DISPLAY_FLAG_*` 常數 inline 進測試（production 呼叫端拿到的正是這些被烘進去的值），同時 stub 不進 APK，執行期只載入平台。
- 常數比方法更危險，所以那張表刻意用 Java 寫：JLS 13.1 規定 constant variable 的引用必須在編譯期 inline，這是語言規格的保證，不是編譯器的選擇。方法簽章錯會擲 `NoSuchMethodError`；常數錯**完全沒有聲音**，只是虛擬顯示被用錯誤的旗標建立。
- 測試以 app UID 執行（不是 shell），所以只驗「成員存不存在／簽章符不符」，不驗「呼叫會成功」。要驗真實效果需走 `UiAutomation.executeShellCommand()`。
- 這批慢，與 `Viewport` 那批純 JVM 測試分開跑。
- 新增 stub 成員時，必須同步在 `HiddenApiContracts.kt` 加一筆，否則新假設一樣沒人驗。
