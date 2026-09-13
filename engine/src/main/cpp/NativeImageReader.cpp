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

    // 整段都在鎖裡，包含 AImage_delete。release() 靠這把鎖判斷「在途的回呼做完了」，
    // 而 AImage_delete 正是會跟 AImageReader_delete 互鎖的那一步——把它留在鎖外面，
    // 屏障就形同虛設。
    std::lock_guard<std::mutex> lock(self->callbackMutex);
    if (self->closing.load()) return;

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

    if (self->frameCallback) {
        self->frameCallback(mat);
    }

    AImage_delete(image);
}

/**
 * 拆掉 reader。順序是有講究的，弄反會死鎖。
 *
 * `AImageReader_delete` 會先拿 reader 的內部鎖，再等回呼執行緒退出（`ALooper::stop()` →
 * `Thread::requestExitAndWait()`）。而執行中的回呼在 `AImage_delete` 裡正需要同一把內部
 * 鎖——刪除者等執行緒、執行緒等鎖，兩邊互等。60fps 之下「拆除當下剛好有一張影格在處理」
 * 是機率事件，所以它是間歇性的：在 SM-A217F 上大約每五、六次執行會中一次。
 *
 * 所以：先拔 listener（不再有新的回呼），再等在途的那一次做完，最後才刪。
 * 只清掉 frameCallback 是不夠的——native listener 還註冊著，回呼照樣進來、照樣呼叫
 * `AImage_delete`。
 */
void NativeImageReader::release() {
    if (!reader) {
        window = nullptr;
        return;
    }

    closing.store(true);
    AImageReader_setImageListener(reader, nullptr);

    // 屏障：拿到就代表沒有回呼還在跑。拿到後**立刻放掉**再刪——抓著它去刪的話，
    // 被擋在鎖外面的那個回呼執行緒永遠退不出來，又是同一個互等。
    { std::lock_guard<std::mutex> lock(callbackMutex); }

    AImageReader_delete(reader);
    reader = nullptr;
    window = nullptr;
}
