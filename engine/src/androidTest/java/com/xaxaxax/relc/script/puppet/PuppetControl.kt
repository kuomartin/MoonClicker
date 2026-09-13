package com.xaxaxax.relc.script.puppet

/**
 * 測試對 puppet 的**輸入**：現在該畫什麼。
 *
 * 跟 [PuppetRecorder] 分開是刻意的——那邊記的是 puppet **觀測到**什麼（觸控、版面尺寸），
 * 這邊是測試**要求**它做什麼。混在一起的話，「這個欄位是誰寫的」會變成讀 code 才知道。
 */
internal object PuppetControl {

    @Volatile
    var markerVisible: Boolean = true

    @Volatile
    var glyphVisible: Boolean = true

    fun reset() {
        markerVisible = true
        glyphVisible = true
    }
}
