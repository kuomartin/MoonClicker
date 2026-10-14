#ifndef MOONCLICKER_JSON_STRING_H
#define MOONCLICKER_JSON_STRING_H

#include <cstdio>
#include <string>

/** 把 [s] 寫成含引號的 JSON 字串字面值。控制字元以 `\uXXXX` 跳脫，非 ASCII 的 UTF-8 原樣保留。 */
inline std::string jsonString(const std::string &s) {
    std::string out = "\"";
    out.reserve(s.size() + 2);
    for (unsigned char c: s) {
        if (c == '"' || c == '\\') {
            out += '\\';
            out += static_cast<char>(c);
        } else if (c < 0x20) {
            char escaped[8];
            snprintf(escaped, sizeof(escaped), "\\u%04x", c);
            out += escaped;
        } else {
            out += static_cast<char>(c);
        }
    }
    return out + '"';
}

#endif //MOONCLICKER_JSON_STRING_H
