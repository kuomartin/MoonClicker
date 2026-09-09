#include "NativeImageReader.h"
#include <android/log.h>

#define LOG_TAG "NativeImageReader"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

NativeImageReader::NativeImageReader(int width, int height) 
    : width(width), height(height), reader(nullptr), window(nullptr) {}

NativeImageReader::~NativeImageReader() {
    release();
}

bool NativeImageReader::init() {
    // AIMAGE_FORMAT_RGBA_8888
    media_status_t status = AImageReader_new(width, height, 0x1, 2, &reader);
    if (status != AMEDIA_OK) {
        LOGE("AImageReader_new failed: %d", status);
        return false;
    }

    AImageReader_ImageListener listener{.context = this, .onImageAvailable = onImageAvailable};
    AImageReader_setImageListener(reader, &listener);

    status = AImageReader_getWindow(reader, &window);
    if (status != AMEDIA_OK) {
        LOGE("AImageReader_getWindow failed: %d", status);
        return false;
    }

    return true;
}

ANativeWindow* NativeImageReader::getWindow() {
    return window;
}

void NativeImageReader::setCallback(std::function<void(const cv::Mat&)> callback) {
    std::lock_guard<std::mutex> lock(callbackMutex);
    frameCallback = callback;
}

void NativeImageReader::onImageAvailable(void* context, AImageReader* reader) {
    auto* self = static_cast<NativeImageReader*>(context);
    AImage* image = nullptr;
    media_status_t status = AImageReader_acquireLatestImage(reader, &image);
    
    if (status != AMEDIA_OK || !image) return;

    int32_t imgWidth, imgHeight;
    AImage_getWidth(image, &imgWidth);
    AImage_getHeight(image, &imgHeight);

    uint8_t* data = nullptr;
    int32_t len = 0;
    AImage_getPlaneData(image, 0, &data, &len);

    int32_t rowStride;
    AImage_getPlaneRowStride(image, 0, &rowStride);

    // Map to cv::Mat (Zero-Copy)
    cv::Mat mat(imgHeight, imgWidth, CV_8UC4, data, rowStride);

    {
        std::lock_guard<std::mutex> lock(self->callbackMutex);
        if (self->frameCallback) {
            self->frameCallback(mat);
        }
    }

    AImage_delete(image);
}

void NativeImageReader::release() {
    if (reader) {
        AImageReader_delete(reader);
        reader = nullptr;
    }
    window = nullptr;
}
