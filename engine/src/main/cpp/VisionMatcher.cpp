#include "VisionMatcher.h"

#include <opencv2/imgproc.hpp>
#include <opencv2/imgcodecs.hpp>
#include <android/log.h>

#include <algorithm>
#include <cmath>
#include <sys/stat.h>

#define VM_LOG_TAG "VisionMatcher"
#define VMLOGE(...) __android_log_print(ANDROID_LOG_ERROR, VM_LOG_TAG, __VA_ARGS__)

VisionMatcher::VisionMatcher(int frameWidth, int frameHeight, std::string scriptDir)
        : frameWidth(frameWidth), frameHeight(frameHeight), scriptDir(std::move(scriptDir)) {}

void VisionMatcher::setRotation(int rotation) {
    displayRotation.store(rotation & 3);
}

void VisionMatcher::logicalSize(int &width, int &height) const {
    if ((displayRotation.load() & 1) != 0) {
        width = frameHeight;
        height = frameWidth;
    } else {
        width = frameWidth;
        height = frameHeight;
    }
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
    // 快取鍵要包含前處理參數——同一張圖用不同 gray/scale/rotation 是不同的模板。
    const int quarterTurns = rotation();
    std::string key = request.imagePath + "|" + (request.gray ? "g" : "c") + "|" +
                      std::to_string(request.scale) + "|r" + std::to_string(quarterTurns);

    std::lock_guard<std::mutex> lock(cacheMutex);
    auto cached = cache.find(key);
    if (cached != cache.end()) return cached->second;

    cv::Mat image = cv::imread(request.imagePath, cv::IMREAD_UNCHANGED);
    if (image.empty()) {
        VMLOGE("Failed to load template image: %s", request.imagePath.c_str());
        cache[key] = cv::Mat();  // 記住失敗，不要每幀都重試讀檔
        return {};
    }

    // 模板是**邏輯空間**的產物——腳本作者截的是他在畫面上看到的樣子。影格卻在 surface
    // 空間，顯示器轉 90/270 時內容是被轉「進」緩衝區的，而 matchTemplate 不是旋轉不變的。
    // 所以比對前把模板轉到影格的方向。方向取自 frameToLogical 的逆：r=1 時它把影格右上角
    // 映到邏輯左上角，也就是影格內容是邏輯內容順時針轉 90 度。
    //
    // 轉模板而不是轉影格：兩者數學上等價（命中位置一一對應），但模板小且這裡有跨呼叫的
    // 快取，整場執行只轉一次；影格每次比對都是新的一張，快取不了。見 ADR-0013。
    switch (quarterTurns) {
        case 1:
            cv::rotate(image, image, cv::ROTATE_90_CLOCKWISE);
            break;
        case 2:
            cv::rotate(image, image, cv::ROTATE_180);
            break;
        case 3:
            cv::rotate(image, image, cv::ROTATE_90_COUNTERCLOCKWISE);
            break;
        default:
            break;
    }

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
            // ROI 由腳本以**邏輯**座標指定，先轉回影格空間再套用縮放。
            double ax, ay, bx, by;
            logicalToFrame(request.roi.x, request.roi.y, ax, ay);
            logicalToFrame(request.roi.x + request.roi.width,
                           request.roi.y + request.roi.height, bx, by);

            int x = std::max(0, static_cast<int>(std::min(ax, bx) * request.scale));
            int y = std::max(0, static_cast<int>(std::min(ay, by) * request.scale));
            int w = std::min(static_cast<int>(std::abs(bx - ax) * request.scale), base.cols - x);
            int h = std::min(static_cast<int>(std::abs(by - ay) * request.scale), base.rows - y);

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

        // 回到未縮放的影格空間，再一路換算成邏輯空間。
        double frameX = (maxLoc.x + offsetX) / request.scale;
        double frameY = (maxLoc.y + offsetY) / request.scale;
        double frameW = templateImage.cols / request.scale;
        double frameH = templateImage.rows / request.scale;

        double ax, ay, bx, by;
        frameToLogical(frameX, frameY, ax, ay);
        frameToLogical(frameX + frameW, frameY + frameH, bx, by);

        VisionHit &hit = hits[i];
        hit.found = true;
        hit.x = std::min(ax, bx);
        hit.y = std::min(ay, by);
        hit.w = std::abs(bx - ax);
        hit.h = std::abs(by - ay);
        hit.cx = hit.x + hit.w / 2.0;
        hit.cy = hit.y + hit.h / 2.0;
        hit.confidence = maxVal;
    }

    return hits;
}

// 影格在 surface 空間、內容被旋轉「進」其中；邏輯空間才是 injectMotionEvent 的座標系。
// 旋轉方向與畫面側套用的 -(rotation * 90) 反向旋轉一致（見 :app 的 Viewport）。
void VisionMatcher::frameToLogical(double fx, double fy, double &lx, double &ly) const {
    switch (displayRotation.load() & 3) {
        case 1:
            lx = fy;
            ly = frameWidth - fx;
            break;
        case 2:
            lx = frameWidth - fx;
            ly = frameHeight - fy;
            break;
        case 3:
            lx = frameHeight - fy;
            ly = fx;
            break;
        default:
            lx = fx;
            ly = fy;
            break;
    }
}

void VisionMatcher::logicalToFrame(double lx, double ly, double &fx, double &fy) const {
    switch (displayRotation.load() & 3) {
        case 1:
            fx = frameWidth - ly;
            fy = lx;
            break;
        case 2:
            fx = frameWidth - lx;
            fy = frameHeight - ly;
            break;
        case 3:
            fx = ly;
            fy = frameHeight - lx;
            break;
        default:
            fx = lx;
            fy = ly;
            break;
    }
}
