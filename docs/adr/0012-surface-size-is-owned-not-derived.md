# Surface 尺寸由服務擁有，不由呼叫端回推

`getDisplaySize` 回的是**邏輯**尺寸（旋轉 90/270 時長寬互換），但 `AImageReader` 必須以虛擬
顯示**建立時**的 surface 尺寸開。呼叫端不從（邏輯尺寸, rotation）回推——那是兩次獨立讀取，
中間畫面轉了就會算出一組看起來合理、實際不自洽的答案，而影格緩衝區的長寬比一錯就錯到腳本
結束。`vdStore` 存 `ManagedDisplay(display, surfaceWidth, surfaceHeight)`，
`getDisplaySurfaceSize` 直接回建立時的常數；服務沒建過的顯示器回 `[0, 0]` 讓呼叫端當場失敗，
而不是帶著可能錯的尺寸跑完整場。

## Status

Accepted。與 [ADR-0011](0011-scripts-do-not-own-displays.md) 一致——腳本不擁有顯示器，也就
不該是知道它幾何的人。
