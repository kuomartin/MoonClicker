#ifndef RELC_NATIVE_IMAGE_READER_H
#define RELC_NATIVE_IMAGE_READER_H

#include <media/NdkImageReader.h>
#include <android/native_window.h>
#include <atomic>
#include <functional>
#include <mutex>
#include <opencv2/core.hpp>

class NativeImageReader {
public:
    NativeImageReader(int width, int height);
    ~NativeImageReader();

    bool init();
    ANativeWindow* getWindow();
    void setCallback(std::function<void(const cv::Mat&)> callback);
    void release();

private:
    static void onImageAvailable(void* context, AImageReader* reader);

    int width;
    int height;
    AImageReader* reader;
    ANativeWindow* window;
    std::function<void(const cv::Mat&)> frameCallback;
    /**
     * 保護整個 [onImageAvailable]，不只是呼叫 frameCallback 的那一小段。
     *
     * [release] 拿它當「在途的回呼已經做完」的屏障——只護住 callback 那一段的話，
     * 屏障通過時 `AImage_delete` 可能還沒跑完，而那正是會跟 `AImageReader_delete` 互鎖的
     * 那一步。
     */
    std::mutex callbackMutex;
    /** 已經進了 [onImageAvailable] 但還沒拿到鎖的那一次，看到它就直接收工。 */
    std::atomic<bool> closing{false};
};

#endif // RELC_NATIVE_IMAGE_READER_H
