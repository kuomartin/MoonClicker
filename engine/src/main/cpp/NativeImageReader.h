#ifndef RELC_NATIVE_IMAGE_READER_H
#define RELC_NATIVE_IMAGE_READER_H

#include <media/NdkImageReader.h>
#include <android/native_window.h>
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
    std::mutex callbackMutex;
};

#endif // RELC_NATIVE_IMAGE_READER_H
