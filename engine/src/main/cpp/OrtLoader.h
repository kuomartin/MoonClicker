#ifndef MOONCLICKER_ORT_LOADER_H
#define MOONCLICKER_ORT_LOADER_H

#include <string>

/**
 * 以 `dlopen` 載入 OCR 套件裡的 `libonnxruntime.so`，並初始化 ORT 的 C++ API（ADR-0018）。
 *
 * APK 不連結 ORT：整個 target 以 `ORT_API_MANUAL_INIT` 編譯，C++ API 在這裡呼叫
 * `Ort::InitApi` 之前不能使用。行程內只載入一次、永不 `dlclose`——ORT 內部有 static 狀態，
 * 卸載後再載入不安全。
 *
 * @param libraryPath `libonnxruntime.so` 的絕對路徑；必須在 app 的 `filesDir` 底下，
 *   其他位置會被 linker namespace 或 SELinux 擋掉。
 * @return 空字串表示成功（或已經從同一路徑載入過），否則是失敗原因。已從其他路徑載入過時也回傳錯誤：
 *   套件更新後要重啟行程才會生效。
 */
std::string loadOrt(const std::string &libraryPath);

#endif // MOONCLICKER_ORT_LOADER_H
