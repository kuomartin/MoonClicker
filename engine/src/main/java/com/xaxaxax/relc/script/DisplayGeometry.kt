package com.xaxaxax.relc.script

/**
 * Surface 空間與邏輯空間之間的換算（見 CONTEXT.md「Surface 空間 / 邏輯空間」）。
 *
 * 這裡只有一個函式，但它值得單獨存在並且有測試：issue #19 那一類錯位，根源幾乎都是
 * 某處把「邏輯尺寸」當成「surface 尺寸」用。而它在未旋轉時兩者恆等，所以 rotation 0
 * 的測試永遠看不出問題——只有畫面真的轉了才會現形。
 */
object DisplayGeometry {

    /**
     * 從**邏輯**尺寸回推 surface（建立時）尺寸。
     *
     * `IRelcV2Service.getDisplaySize` 回的是 `Display.getRealSize()`，也就是套用旋轉後的
     * 邏輯尺寸；但 `AImageReader` 必須以虛擬顯示建立時的尺寸開，因為影格是被旋轉「進」
     * 那個固定尺寸裡的。旋轉 90/270 時兩者長寬互換，把它換回來就是 surface 尺寸。
     *
     * @param rotation `Surface.ROTATION_*`（0..3）。
     */
    fun surfaceSize(logicalWidth: Int, logicalHeight: Int, rotation: Int): Pair<Int, Int> =
        if (rotation and 1 != 0) logicalHeight to logicalWidth else logicalWidth to logicalHeight
}
