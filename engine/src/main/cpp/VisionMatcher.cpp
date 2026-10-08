#include "VisionMatcher.h"
#include "Ocr.h"
#include "TextMatch.h"

#include <opencv2/imgproc.hpp>
#include <opencv2/imgcodecs.hpp>
#include <android/log.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <array>
#include <cmath>
#include <sys/stat.h>

#define VM_LOG_TAG "VisionMatcher"
#define VMLOGE(...) __android_log_print(ANDROID_LOG_ERROR, VM_LOG_TAG, __VA_ARGS__)
// spike/latency：量測用，tag 固定為 LAT，見 docs/research/latency.md。
#include "LatLog.h"
#define LAT(...) latLog(__VA_ARGS__)

namespace {
double latNowMs() {
    return std::chrono::duration<double, std::milli>(
            std::chrono::steady_clock::now().time_since_epoch()).count();
}
std::atomic<double> latLastFrameMs{0};
}

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
        double t0 = latNowMs();
        frame.copyTo(latestFrame);
        double t1 = latNowMs();
        latLastFrameMs = t1;
        frames++;
        if (frames % 120 == 0) LAT("copy frame=%dx%d copyMs=%.2f", frame.cols, frame.rows, t1 - t0);
        if (frames == 1) {
            LAT("firstframe frame=%dx%d", frame.cols, frame.rows);
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

cv::Mat VisionMatcher::snapshot() {
    std::lock_guard<std::mutex> lock(frameMutex);
    cv::Mat frame;
    latestFrame.copyTo(frame);
    return frame;
}

cv::Rect VisionMatcher::clampRoi(const cv::Mat &frame, bool hasRoi, const cv::Rect &roi) const {
    cv::Rect bounds(0, 0, frame.cols, frame.rows);
    return hasRoi ? (roi & bounds) : bounds;
}

namespace {

/** 辨識是對裁切出的 ROI 做的，把框與字元位置移回影格座標。 */
void offsetLine(OcrLine &line, cv::Point offset) {
    line.box += offset;
    for (auto &span: line.spans) {
        span.first += static_cast<float>(offset.x);
        span.second += static_cast<float>(offset.x);
    }
}

/** 影格是 RGBA（NativeImageReader），PP-OCR 以 BGR 訓練。 */
cv::Mat toBgr(const cv::Mat &rgba) {
    cv::Mat bgr;
    cv::cvtColor(rgba, bgr, cv::COLOR_RGBA2BGR);
    return bgr;
}

}  // namespace

std::vector<OcrLine> VisionMatcher::readText(Ocr &ocr, bool hasRoi, const cv::Rect &roi, bool detect) {
    cv::Mat frame = snapshot();
    if (frame.empty()) return {};
    cv::Rect area = clampRoi(frame, hasRoi, roi);
    if (area.empty()) return {};

    double t0 = latNowMs();
    cv::Mat bgr = toBgr(frame(area));
    double t1 = latNowMs();
    cv::Rect whole(0, 0, bgr.cols, bgr.rows);
    std::vector<OcrLine> lines;
    OcrTiming timing;
    if (detect) {
        lines = ocr.detectAndRecognize(bgr, whole, &timing);
    } else {
        OcrLine line = ocr.recognize(bgr, whole, &timing);
        if (!line.text.empty()) lines.push_back(std::move(line));
    }
    LAT("ocr mode=%s area=%dx%d bgr=%.2f det=%.1f rec=%.1f total=%.1f lines=%zu",
        detect ? "read_lines" : "read", area.width, area.height, t1 - t0, timing.detMs, timing.recMs,
        latNowMs() - t0, lines.size());
    for (auto &line: lines) offsetLine(line, area.tl());
    return lines;
}

std::vector<VisionHit> VisionMatcher::match(const std::vector<VisionRequest> &requests, Ocr *ocr) {
    std::vector<VisionHit> hits(requests.size());

    double tSnap0 = latNowMs();
    double frameAge = tSnap0 - latLastFrameMs.load();
    cv::Mat frame = snapshot();
    double snapMs = latNowMs() - tSnap0;
    if (frame.empty()) {
        LAT("noframe requests=%zu", requests.size());
        return hits;
    }

    // 同一個 ROI 的文字辨識在這批 request 之間共用：wait_any 等多段文字時不重複推論。
    std::map<std::array<int, 4>, std::vector<OcrLine>> textCache;
    auto linesFor = [&](const VisionRequest &request) -> const std::vector<OcrLine> & {
        cv::Rect area = clampRoi(frame, request.hasRoi, request.roi);
        std::array<int, 4> key{area.x, area.y, area.width, area.height};
        auto it = textCache.find(key);
        if (it != textCache.end()) return it->second;

        std::vector<OcrLine> lines;
        if (!area.empty()) {
            double t0 = latNowMs();
            cv::Mat bgr = toBgr(frame(area));
            OcrTiming timing;
            lines = ocr->detectAndRecognize(bgr, cv::Rect(0, 0, bgr.cols, bgr.rows), &timing);
            LAT("ocr mode=find area=%dx%d det=%.1f rec=%.1f total=%.1f lines=%zu snap=%.2f age=%.1f",
                area.width, area.height, timing.detMs, timing.recMs, latNowMs() - t0, lines.size(),
                snapMs, frameAge);
            for (auto &line: lines) offsetLine(line, area.tl());
        }
        return textCache.emplace(key, std::move(lines)).first->second;
    };

    auto matchTextRequest = [&](const VisionRequest &request, VisionHit &hit) {
        const OcrLine *bestLine = nullptr;
        TextMatch best;
        for (const auto &line: linesFor(request)) {
            TextMatch candidate = matchText(request.text, line.text, request.exact);
            // 嚴格大於：同分時留閱讀順序在前的那一行。
            if (bestLine == nullptr || candidate.similarity > best.similarity) {
                best = candidate;
                bestLine = &line;
            }
        }
        if (bestLine == nullptr || best.similarity < request.threshold) return;

        cv::Rect box = bestLine->spanBox(best.start, best.end);
        hit.found = true;
        hit.x = box.x;
        hit.y = box.y;
        hit.w = box.width;
        hit.h = box.height;
        hit.cx = box.x + box.width / 2.0;
        hit.cy = box.y + box.height / 2.0;
        hit.confidence = best.similarity;
        hit.text = bestLine->text;
    };

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
        if (request.isText()) {
            matchTextRequest(request, hits[i]);
            continue;
        }
        cv::Mat templateImage = templateFor(request);
        if (templateImage.empty()) continue;

        double tPre0 = latNowMs();
        const cv::Mat &base = baseFrameFor(request.scale, request.gray);
        double preMs = latNowMs() - tPre0;
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

        double tMt0 = latNowMs();
        cv::Mat result;
        cv::matchTemplate(target, templateImage, result, cv::TM_CCOEFF_NORMED);

        double minVal, maxVal;
        cv::Point minLoc, maxLoc;
        cv::minMaxLoc(result, &minVal, &maxVal, &minLoc, &maxLoc);
        LAT("tm frame=%dx%d tpl=%dx%d target=%dx%d gray=%d snap=%.2f pre=%.2f mt=%.2f age=%.1f",
            frame.cols, frame.rows, templateImage.cols, templateImage.rows, target.cols, target.rows,
            request.gray ? 1 : 0, snapMs, preMs, latNowMs() - tMt0, frameAge);
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
