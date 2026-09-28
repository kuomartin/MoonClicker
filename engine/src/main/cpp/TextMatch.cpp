#include "TextMatch.h"

#include <algorithm>
#include <cstdint>
#include <vector>

namespace {

/** 正規化後的一個字元，以及它在原字串中的 codepoint 索引。 */
struct Char {
    char32_t code;
    int index;
};

bool isSpace(char32_t c) {
    return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == 0x3000;
}

/** UTF-8 解碼、去除空白、全形英數與符號（U+FF01～U+FF5E）轉半形。不合法的位元組當成單一字元保留。 */
std::vector<Char> normalize(const std::string &s) {
    std::vector<Char> out;
    int index = 0;
    for (size_t i = 0; i < s.size(); index++) {
        auto byte = static_cast<unsigned char>(s[i]);
        char32_t code;
        size_t length;
        if (byte < 0x80) {
            code = byte;
            length = 1;
        } else if ((byte >> 5) == 0x6) {
            code = byte & 0x1F;
            length = 2;
        } else if ((byte >> 4) == 0xE) {
            code = byte & 0x0F;
            length = 3;
        } else if ((byte >> 3) == 0x1E) {
            code = byte & 0x07;
            length = 4;
        } else {
            code = byte;
            length = 1;
        }
        if (i + length > s.size()) length = 1;
        for (size_t k = 1; k < length; k++) code = (code << 6) | (static_cast<unsigned char>(s[i + k]) & 0x3F);
        i += length;

        if (isSpace(code)) continue;
        if (code >= 0xFF01 && code <= 0xFF5E) code -= 0xFEE0;
        out.push_back({code, index});
    }
    return out;
}

}  // namespace

TextMatch matchText(const std::string &target, const std::string &line, bool exact) {
    std::vector<Char> a = normalize(target);
    std::vector<Char> b = normalize(line);
    const int m = static_cast<int>(a.size());
    const int n = static_cast<int>(b.size());
    if (m == 0) return {};

    // cost[j]：目前這一列、對到 b 的前 j 個字元的最小編輯距離；from[j]：那條路徑在 b 中的起點。
    // 子字串比對時第 0 列全為 0（可從 b 的任何位置開始，不計成本）；整行比對時第 0 列是 j。
    std::vector<int> cost(n + 1), from(n + 1), nextCost(n + 1), nextFrom(n + 1);
    for (int j = 0; j <= n; j++) {
        cost[j] = exact ? j : 0;
        from[j] = exact ? 0 : j;
    }
    for (int i = 1; i <= m; i++) {
        nextCost[0] = i;
        nextFrom[0] = 0;
        for (int j = 1; j <= n; j++) {
            int substitute = cost[j - 1] + (a[i - 1].code == b[j - 1].code ? 0 : 1);
            int remove = cost[j] + 1;          // 目標多一個字
            int insert = nextCost[j - 1] + 1;  // 行裡多一個字
            if (substitute <= remove && substitute <= insert) {
                nextCost[j] = substitute;
                nextFrom[j] = from[j - 1];
            } else if (remove <= insert) {
                nextCost[j] = remove;
                nextFrom[j] = from[j];
            } else {
                nextCost[j] = insert;
                nextFrom[j] = nextFrom[j - 1];
            }
        }
        std::swap(cost, nextCost);
        std::swap(from, nextFrom);
    }

    int end = n;
    if (!exact) {
        // 距離最小者；同距離取較短的範圍，避免把雜訊一起框進來。
        for (int j = 0; j <= n; j++) {
            if (cost[j] < cost[end] || (cost[j] == cost[end] && j - from[j] < end - from[end])) end = j;
        }
    }
    const int start = from[end];
    const int denominator = exact ? std::max(m, n) : m;

    TextMatch result;
    result.similarity = std::max(0.0, 1.0 - static_cast<double>(cost[end]) / denominator);
    if (end > start) {
        result.start = b[start].index;
        result.end = b[end - 1].index + 1;
    }
    return result;
}
