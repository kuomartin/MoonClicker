package com.xaxaxax.moonclicker.ocr

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * `vision.*` 文字請求的比對規則（`TextMatch.h`）。純字串運算，不需要 OCR 套件與圖形堆疊；
 * 跑在裝置上只是因為它在 `moonclicker_native.so` 裡。例子取自選型研究記下的 OCR 錯誤。
 */
@RunWith(AndroidJUnit4::class)
class OcrTextMatchTest {

    private fun match(target: String, line: String, exact: Boolean = false) =
        OcrNative.nativeMatchText(target, line, exact).let { Triple(it[0], it[1].toInt(), it[2].toInt()) }

    @Test
    fun noise_glued_to_the_end_still_matches_as_a_substring() {
        assertEquals(Triple(1.0, 0, 4), match("SKIP", "SKIPH"))
    }

    @Test
    fun one_wrong_character_in_six_passes_the_default_threshold() {
        val (similarity, _, _) = match("合作紀念禮包", "合作紀念程包唱")
        assertEquals(5.0 / 6, similarity, 1e-9)
    }

    @Test
    fun the_span_points_at_the_matched_word_not_the_whole_line() {
        // 起訖是原字串的 codepoint 索引，含空白：「取消」在 "確定  取消" 的第 4、5 個字。
        assertEquals(Triple(1.0, 4, 6), match("取消", "確定  取消"))
        assertEquals(Triple(1.0, 2, 4), match("OK", "BOOK"))
    }

    @Test
    fun digits_get_no_special_treatment_unless_exact() {
        assertEquals(1.0, match("123", "1234").first, 0.0)
        assertEquals(0.75, match("123", "1234", exact = true).first, 1e-9)
    }

    @Test
    fun exact_compares_against_the_whole_line() {
        assertEquals(0.5, match("OK", "BOOK", exact = true).first, 1e-9)
        assertEquals(1.0, match("LEVEL 6", "LEVEL6", exact = true).first, 0.0)
    }

    @Test
    fun whitespace_and_full_width_forms_are_normalized() {
        assertEquals(1.0, match("ＯＫ", "OK", exact = true).first, 0.0)
        assertEquals(1.0, match("12 345", "12345", exact = true).first, 0.0)
    }

    @Test
    fun short_targets_have_little_room_for_errors() {
        assertEquals(0.5, match("OK", "0K").first, 1e-9)
    }

    @Test
    fun an_empty_line_never_matches() {
        assertEquals(0.0, match("abc", "").first, 0.0)
    }
}
