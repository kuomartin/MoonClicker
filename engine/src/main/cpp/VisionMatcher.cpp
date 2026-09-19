#include "VisionMatcher.h"

#include <opencv2/imgproc.hpp>
#include <opencv2/imgcodecs.hpp>
#include <android/log.h>

#include <algorithm>
#include <cmath>
#include <sys/stat.h>

#define VM_LOG_TAG "VisionMatcher"
#define VMLOGE(...) __android_log_print(ANDROID_LOG_ERROR, VM_LOG_TAG, __VA_ARGS__)

VisionMatcher::VisionMatcher(int frameWidth, int frameHeight, int rotation, std::string scriptDir)
        : frameWidth(frameWidth), frameHeight(frameHeight), displayRotation(rotation & 3),
          scriptDir(std::move(scriptDir)) {}

void VisionMatcher::logicalSize(int &width, int &height) const {
    width = frameWidth;
    height = frameHeight;
}

void VisionMatcher::onFrame(const cv::Mat &frame) {
    {
        std::lock_guard<std::mutex> lock(frameMutex);
        if (stopped) return;
        frame.copyTo(latestFrame);
        frames++;
        if (frames == 1) {
            // 只印第一張。「vision 永遠比對不到」最常見的原因是影格根本沒進來，
            // 而那和「進來了但比不中」在 log 上長得一模一樣——這行把兩者分開。
            __android_log_print(ANDROID_LOG_DEBUG, VM_LOG_TAG,
                                "First frame received: %dx%d", frame.cols, frame.rows);
        }
    }
    frameCv.notify_all();
}

uint64_t VisionMatcher::frameCounter() const {
    std::lock_guard<std::mutex> lock(frameMutex);
    return frames;
}

bool VisionMatcher::waitForFrameAfter(uint64_t after, long timeoutMs) {
    std::unique_lock<std::mutex> lock(frameMutex);
    if (stopped) return false;
    if (frames > after) return true;
    if (timeoutMs <= 0) return false;
    bool arrived = frameCv.wait_for(lock, std::chrono::milliseconds(timeoutMs),
                                    [&] { return stopped || frames > after; });
    return arrived && !stopped;
}

void VisionMatcher::shutdown() {
    {
        std::lock_guard<std::mutex> lock(frameMutex);
        stopped = true;
    }
    frameCv.notify_all();
}

std::string VisionMatcher::resolvePath(const std::string &relative) const {
    if (!relative.empty() && relative[0] == '/') return relative;
    return scriptDir + relative;
}

bool VisionMatcher::templateExists(const VisionRequest &request) {
    return !templateFor(request).empty();
}

cv::Mat VisionMatcher::templateFor(const VisionRequest &request) {
    // 快取鍵要包含前處理參數——同一張圖用不同 gray/scale 是不同的模板。
    std::string key = request.imagePath + "|" + (request.gray ? "g" : "c") + "|" +
                      std::to_string(request.scale);

    std::lock_guard<std::mutex> lock(cacheMutex);
    auto cached = cache.find(key);
    if (cached != cache.end()) return cached->second;

    cv::Mat image = cv::imread(request.imagePath, cv::IMREAD_UNCHANGED);
    if (image.empty()) {
        VMLOGE("Failed to load template image: %s", request.imagePath.c_str());
        cache[key] = cv::Mat();  // 記住失敗，不要每幀都重試讀檔
        return {};
    }

    // 模板是邏輯空間的產物，影格現在也是（distributor 已經把 v 轉正，見 ADR-0017），
    // 兩者同一個方向，不需要再轉模板去對齊。見 ADR-0013。

    if (image.channels() == 3) {
        cv::cvtColor(image, image, cv::COLOR_BGR2RGBA);
    } else if (image.channels() == 1) {
        cv::cvtColor(image, image, cv::COLOR_GRAY2RGBA);
    }

    if (request.gray) {
        cv::Mat grayImage;
        cv::cvtColor(image, grayImage, cv::COLOR_RGBA2GRAY);
        image = grayImage;
    }

    if (request.scale < 1.0) {
        cv::Mat resized;
        cv::resize(image, resized, cv::Size(), request.scale, request.scale, cv::INTER_AREA);
        image = resized;
    }

    cache[key] = image;
    return image;
}

std::vector<VisionHit> VisionMatcher::match(const std::vector<VisionRequest> &requests) {
    std::vector<VisionHit> hits(requests.size());

    cv::Mat frame;
    {
        std::lock_guard<std::mutex> lock(frameMutex);
        if (latestFrame.empty()) return hits;
        latestFrame.copyTo(frame);
    }

    // 同一個 scale 的縮放結果在這批 request 之間共用，灰階轉換同理。
    std::unordered_map<double, cv::Mat> scaledColor;
    std::unordered_map<double, cv::Mat> scaledGray;

    auto baseFrameFor = [&](double scale, bool gray) -> const cv::Mat & {
        auto &bucket = gray ? scaledGray : scaledColor;
        auto it = bucket.find(scale);
        if (it != bucket.end()) return it->second;

        cv::Mat scaled;
        if (scale < 1.0) {
            cv::resize(frame, scaled, cv::Size(), scale, scale, cv::INTER_LINEAR);
        } else {
            scaled = frame;
        }
        if (gray) {
            cv::Mat grayFrame;
            if (scaled.channels() == 4) {
                cv::cvtColor(scaled, grayFrame, cv::COLOR_RGBA2GRAY);
            } else if (scaled.channels() == 3) {
                cv::cvtColor(scaled, grayFrame, cv::COLOR_RGB2GRAY);
            } else {
                grayFrame = scaled;
            }
            scaled = grayFrame;
        }
        return bucket.emplace(scale, scaled).first->second;
    };

    for (size_t i = 0; i < requests.size(); i++) {
        const VisionRequest &request = requests[i];
        cv::Mat templateImage = templateFor(request);
        if (templateImage.empty()) continue;

        const cv::Mat &base = baseFrameFor(request.scale, request.gray);
        cv::Mat target;
        int offsetX = 0;
        int offsetY = 0;

        if (request.hasRoi && request.roi.width > 0 && request.roi.height > 0) {
            // ROI 是邏輯座標，影格現在也是邏輯空間，直接套用縮放即可，不需要座標轉換。
            int x = std::max(0, static_cast<int>(request.roi.x * request.scale));
            int y = std::max(0, static_cast<int>(request.roi.y * request.scale));
            int w = std::min(static_cast<int>(request.roi.width * request.scale), base.cols - x);
            int h = std::min(static_cast<int>(request.roi.height * request.scale), base.rows - y);

            if (w < templateImage.cols || h < templateImage.rows) continue;

            target = base(cv::Rect(x, y, w, h));
            offsetX = x;
            offsetY = y;
        } else {
            target = base;
        }

        if (target.cols < templateImage.cols || target.rows < templateImage.rows) continue;

        cv::Mat result;
        cv::matchTemplate(target, templateImage, result, cv::TM_CCOEFF_NORMED);

        double minVal, maxVal;
        cv::Point minLoc, maxLoc;
        cv::minMaxLoc(result, &minVal, &maxVal, &minLoc, &maxLoc);
        if (maxVal < request.threshold) continue;

        // 回到未縮放的影格空間——影格本身已經是邏輯空間，不需要再轉一次。
        VisionHit &hit = hits[i];
        hit.found = true;
        hit.x = (maxLoc.x + offsetX) / request.scale;
        hit.y = (maxLoc.y + offsetY) / request.scale;
        hit.w = templateImage.cols / request.scale;
        hit.h = templateImage.rows / request.scale;
        hit.cx = hit.x + hit.w / 2.0;
        hit.cy = hit.y + hit.h / 2.0;
        hit.confidence = maxVal;
    }

    return hits;
}
