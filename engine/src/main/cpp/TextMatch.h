#ifndef MOONCLICKER_TEXT_MATCH_H
#define MOONCLICKER_TEXT_MATCH_H

#include <string>

/** 目標文字在一行辨識結果中比對的結果。 */
struct TextMatch {
    /** 1 − 編輯距離／分母，0～1；子字串比對的分母是目標長度，整行比對是兩者較長者。 */
    double similarity = 0;
    /** 對上的範圍 [start, end)，以行的 codepoint 索引表示（未正規化前的索引，含空白）。 */
    int start = 0;
    int end = 0;
};

/**
 * 以 Levenshtein 距離比對 [target] 與一行 OCR 結果 [line]（皆為 UTF-8）。
 *
 * 兩邊先去除空白、全形英數轉半形，以 codepoint 計算。預設是近似子字串比對：在行內找與目標
 * 編輯距離最小的一段，OCR 常見的錯字與黏在字尾的雜訊（`SKIP`→`SKIPH`）都容得下。[exact] 為
 * true 時改與整行比對。數字不另做處理：`123` 會命中 `1234`，要避免就用 [exact]。
 *
 * 目標正規化後為空時 similarity 為 0。
 */
TextMatch matchText(const std::string &target, const std::string &line, bool exact);

#endif // MOONCLICKER_TEXT_MATCH_H
