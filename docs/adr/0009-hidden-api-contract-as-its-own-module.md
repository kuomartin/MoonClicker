# Hidden-API 契約測試獨立成 `:hidden-api-contract` 模組

`:hidden-api` 的 stub 是對平台的假設——某個 `@hide` 成員存在、簽章長某樣——而驗證它們必須在真機上跨 API level 用反射問平台。這組測試住在一個**只有 androidTest、沒有 main source set**的薄模組 `:hidden-api-contract`，Gradle Managed Devices 的 API 矩陣也放這裡。

## Status

Accepted，2026-09-11。相關：[issue #18](https://github.com/kuomartin/ReLC/issues/18)。
