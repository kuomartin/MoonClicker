#include "VisionMatcher.h"
#include "Ocr.h"
#include "TextMatch.h"

#include <opencv2/imgproc.hpp>
#include <opencv2/imgcodecs.hpp>
#include <android/log.h>

#include <algorithm>
#include <array>
#include <cmath>
#include <sys/stat.h>

#define VM_LOG_TAG "VisionMatcher"
#define VMLOGE(...) __android_log_print(ANDROID_LOG_ERROR, VM_LOG_TAG, __VA_ARGS__)

VisionMatcher::VisionMatcher(int frameWidth, int frameHeight, int rotation, std::string scriptDir)
        : frameWidth(frameWidth), frameHeight(frameHeight), displayRotation(rotation & 3),
          scriptDir(std::move(scriptDir)) {}

// 解構在這裡定義：TextCache 的 OcrLine 在標頭只有前置宣告。
VisionMatcher::~VisionMatcher() = default;

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

cv::Mat VisionMatcher::snapshot() {
    if (framePinned) return pinnedFrame;
    std::lock_guard<std::mutex> lock(frameMutex);
    cv::Mat frame;
    latestFrame.copyTo(frame);
    return frame;
}

void VisionMatcher::pinFrame() {
    pinnedFrame = snapshot();
    framePinned = true;
}

void VisionMatcher::unpinFrame() {
    framePinned = false;
    pinnedFrame.release();
    pinnedText.clear();
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

const std::vector<OcrLine> &VisionMatcher::linesIn(Ocr &ocr, const cv::Mat &frame, const cv::Rect &area,
                                                   bool detect, TextCache &textCache) {
    std::array<int, 5> key{area.x, area.y, area.width, area.height, detect ? 1 : 0};
    auto it = textCache.find(key);
    if (it != textCache.end()) return it->second;

    std::vector<OcrLine> lines;
    if (!area.empty()) {
        cv::Mat bgr = toBgr(frame(area));
        cv::Rect whole(0, 0, bgr.cols, bgr.rows);
        if (detect) {
            lines = ocr.detectAndRecognize(bgr, whole);
        } else {
            OcrLine line = ocr.recognize(bgr, whole);
            if (!line.text.empty()) lines.push_back(std::move(line));
        }
        for (auto &line: lines) offsetLine(line, area.tl());
    }
    return textCache.emplace(key, std::move(lines)).first->second;
}

std::vector<OcrLine> VisionMatcher::readText(Ocr &ocr, bool hasRoi, const cv::Rect &roi, bool detect) {
    cv::Mat frame = snapshot();
    if (frame.empty()) return {};
    TextCache local;
    return linesIn(ocr, frame, clampRoi(frame, hasRoi, roi), detect, framePinned ? pinnedText : local);
}

std::vector<VisionHit> VisionMatcher::match(const std::vector<VisionRequest> &requests, Ocr *ocr) {
    std::vector<std::vector<VisionHit>> all = matchEach(requests, ocr, 1);
    std::vector<VisionHit> hits(requests.size());
    for (size_t i = 0; i < all.size(); i++) {
        if (!all[i].empty()) hits[i] = all[i][0];
    }
    return hits;
}

std::vector<VisionHit> VisionMatcher::findAll(const VisionRequest &request, Ocr *ocr) {
    return matchEach({request}, ocr, kMaxHits)[0];
}

std::vector<std::vector<VisionHit>> VisionMatcher::matchEach(const std::vector<VisionRequest> &requests,
                                                             Ocr *ocr, size_t limit) {
    std::vector<std::vector<VisionHit>> hits(requests.size());

    cv::Mat frame = snapshot();
    if (frame.empty()) return hits;

    // 同一個 ROI 的文字辨識在這批 request 之間共用：wait_any 等多段文字時不重複推論。
    // 固定影格時跨呼叫共用。
    TextCache local;
    TextCache &textCache = framePinned ? pinnedText : local;

    auto matchTextRequest = [&](const VisionRequest &request, std::vector<VisionHit> &out) {
        cv::Rect area = clampRoi(frame, request.hasRoi, request.roi);
        std::vector<std::pair<const OcrLine *, TextMatch>> found;
        for (const auto &line: linesIn(*ocr, frame, area, true, textCache)) {
            TextMatch candidate = matchText(request.text, line.text, request.exact);
            if (candidate.similarity >= request.threshold) found.emplace_back(&line, candidate);
        }
        // stable：同分時留閱讀順序在前的那一行。
        std::stable_sort(found.begin(), found.end(), [](const auto &a, const auto &b) {
            return a.second.similarity > b.second.similarity;
        });
        if (found.size() > limit) found.resize(limit);

        for (const auto &[line, textMatch]: found) {
            cv::Rect box = line->spanBox(textMatch.start, textMatch.end);
            VisionHit hit;
            hit.found = true;
            hit.x = box.x;
            hit.y = box.y;
            hit.w = box.width;
            hit.h = box.height;
            hit.cx = box.x + box.width / 2.0;
            hit.cy = box.y + box.height / 2.0;
            hit.confidence = textMatch.similarity;
            hit.text = line->text;
            out.push_back(hit);
        }
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

        // 每次取最高分，再把以它為中心、模板大小的範圍壓掉，下一輪就不會在同一個目標旁邊再中。
        const cv::Rect resultBounds(0, 0, result.cols, result.rows);
        while (hits[i].size() < limit) {
            double minVal, maxVal;
            cv::Point minLoc, maxLoc;
            cv::minMaxLoc(result, &minVal, &maxVal, &minLoc, &maxLoc);
            if (maxVal < request.threshold) break;

            // 回到未縮放的影格空間——影格本身已經是邏輯空間，不需要再轉一次。
            VisionHit hit;
            hit.found = true;
            hit.x = (maxLoc.x + offsetX) / request.scale;
            hit.y = (maxLoc.y + offsetY) / request.scale;
            hit.w = templateImage.cols / request.scale;
            hit.h = templateImage.rows / request.scale;
            hit.cx = hit.x + hit.w / 2.0;
            hit.cy = hit.y + hit.h / 2.0;
            hit.confidence = maxVal;
            hits[i].push_back(hit);

            cv::Rect suppress(maxLoc.x - templateImage.cols / 2, maxLoc.y - templateImage.rows / 2,
                              templateImage.cols, templateImage.rows);
            result(suppress & resultBounds).setTo(-1);
        }
    }

    return hits;
}
