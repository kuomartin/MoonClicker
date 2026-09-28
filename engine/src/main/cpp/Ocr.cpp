#include "Ocr.h"
#include "OrtLoader.h"

#include <opencv2/imgproc.hpp>

#include <algorithm>
#include <chrono>
#include <cmath>
#include <fstream>
#include <stdexcept>

namespace {

// 偵測：長邊縮到 960、ImageNet 正規化，DB 後處理的三個參數。與選型研究量測時相同。
constexpr int kDetLimit = 960;
constexpr float kDetThresh = 0.2f;
constexpr float kBoxThresh = 0.45f;
constexpr float kUnclip = 1.4f;
// 辨識：高 48、寬依比例。
constexpr int kRecHeight = 48;
// ROI 直接辨識前的補邊。
constexpr int kRoiPad = 8;

double msSince(std::chrono::steady_clock::time_point start) {
    return std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start).count();
}

/** ORT 的 Env 整個行程一份；必須在 loadOrt 之後才建立。 */
Ort::Env &ortEnv() {
    static Ort::Env env(ORT_LOGGING_LEVEL_WARNING, "ocr");
    return env;
}

/** NCHW blob（N=1）。[image] 必須已是 CV_32FC3。 */
cv::Mat toBlob(const cv::Mat &image) {
    int sizes[] = {1, 3, image.rows, image.cols};
    cv::Mat blob(4, sizes, CV_32F);
    std::vector<cv::Mat> planes;
    for (int c = 0; c < 3; c++) planes.emplace_back(image.rows, image.cols, CV_32F, blob.ptr<float>(0, c));
    cv::split(image, planes);
    return blob;
}

/** 點集的外接矩形。OpenCV 5 把 `cv::boundingRect` 移到 `geometry` 模組，為了這一個函式不值得連結它。 */
cv::Rect bounds(const std::vector<cv::Point> &points) {
    int left = INT32_MAX, top = INT32_MAX, right = INT32_MIN, bottom = INT32_MIN;
    for (const auto &p: points) {
        left = std::min(left, p.x);
        top = std::min(top, p.y);
        right = std::max(right, p.x);
        bottom = std::max(bottom, p.y);
    }
    return {left, top, right - left + 1, bottom - top + 1};
}

/**
 * DB 後處理，輸出軸對齊框（偵測輸入的座標，未縮放回原圖）。
 * 遊戲畫面的文字幾乎都是水平的，軸對齊框省掉 `minAreaRect`／透視變換，也就不需要 `geometry` 模組。
 */
std::vector<cv::Rect2f> dbPostprocess(const cv::Mat &prob) {
    cv::Mat bitmap = prob > kDetThresh;
    std::vector<std::vector<cv::Point>> contours;
    cv::findContours(bitmap, contours, cv::RETR_LIST, cv::CHAIN_APPROX_SIMPLE);

    std::vector<cv::Rect2f> boxes;
    for (const auto &contour: contours) {
        cv::Rect rect = bounds(contour) & cv::Rect(0, 0, prob.cols, prob.rows);
        if (std::min(rect.width, rect.height) < 3) continue;

        cv::Mat mask = cv::Mat::zeros(rect.size(), CV_8U);
        cv::fillPoly(mask, std::vector<std::vector<cv::Point>>{contour}, 1, cv::LINE_8, 0, -rect.tl());
        if (cv::mean(prob(rect), mask)[0] < kBoxThresh) continue;

        // 矩形做 round-join 外擴再取外接矩形，等同四邊各加 d。
        auto w = static_cast<float>(rect.width), h = static_cast<float>(rect.height);
        float d = w * h * kUnclip / (2 * (w + h));
        cv::Rect2f grown(rect.x - d, rect.y - d, w + 2 * d, h + 2 * d);
        if (std::min(grown.width, grown.height) < 5) continue;
        boxes.push_back(grown);
    }
    return boxes;
}

/**
 * CTC 的輸出很尖：每個字只在字形中央亮一兩個時間步，字與字之間都是 blank，所以解碼得到的
 * 範圍只是字的中央。中心是可靠的，邊界改取相鄰兩字中心的中點；行首與行尾的字往外延伸半個
 * 平均字距（只有一個字時用行高，字大致是方的）。
 */
void widenSpans(std::vector<std::pair<float, float>> &spans, float width, float height) {
    if (spans.empty()) return;
    std::vector<float> centers;
    centers.reserve(spans.size());
    for (const auto &span: spans) centers.push_back((span.first + span.second) / 2);
    const size_t n = centers.size();
    const float pitch = n > 1 ? (centers.back() - centers.front()) / static_cast<float>(n - 1) : height;
    for (size_t i = 0; i < n; i++) {
        float left = i > 0 ? (centers[i - 1] + centers[i]) / 2 : centers[i] - pitch / 2;
        float right = i + 1 < n ? (centers[i] + centers[i + 1]) / 2 : centers[i] + pitch / 2;
        spans[i] = {std::clamp(left, 0.f, width), std::clamp(right, 0.f, width)};
    }
}

}  // namespace

Ocr::Model Ocr::load(const std::string &path, int threads) {
    Ort::SessionOptions options;
    options.SetIntraOpNumThreads(threads);
    options.SetInterOpNumThreads(1);

    Model model;
    model.session = Ort::Session(ortEnv(), path.c_str(), options);
    Ort::AllocatorWithDefaultOptions allocator;
    model.input = model.session.GetInputNameAllocated(0, allocator).get();
    model.output = model.session.GetOutputNameAllocated(0, allocator).get();
    return model;
}

Ocr::Ocr(const std::string &packDir, int threads) {
    std::string error = loadOrt(packDir + "/libonnxruntime.so");
    if (!error.empty()) throw std::runtime_error(error);

    try {
        det = load(packDir + "/det.onnx", threads);
        rec = load(packDir + "/rec.onnx", threads);
    } catch (const Ort::Exception &e) {
        throw std::runtime_error(std::string("failed to load OCR model: ") + e.what());
    }

    std::ifstream in(packDir + "/dict.txt");
    for (std::string line; std::getline(in, line);) dict.push_back(line);
    if (dict.empty()) throw std::runtime_error("empty OCR dictionary: " + packDir + "/dict.txt");
}

cv::Mat Ocr::run(Model &model, const cv::Mat &blob) {
    std::vector<int64_t> shape(blob.size.p, blob.size.p + blob.dims);
    auto memory = Ort::MemoryInfo::CreateCpu(OrtArenaAllocator, OrtMemTypeDefault);
    Ort::Value input = Ort::Value::CreateTensor<float>(
            memory, const_cast<float *>(blob.ptr<float>()), blob.total(), shape.data(), shape.size());

    const char *in = model.input.c_str();
    const char *out = model.output.c_str();
    auto outputs = model.session.Run(Ort::RunOptions{nullptr}, &in, &input, 1, &out, 1);

    auto outShape = outputs[0].GetTensorTypeAndShapeInfo().GetShape();
    std::vector<int> sizes(outShape.begin(), outShape.end());
    cv::Mat result(static_cast<int>(sizes.size()), sizes.data(), CV_32F);
    std::copy_n(outputs[0].GetTensorData<float>(), result.total(), result.ptr<float>());
    return result;
}

OcrLine Ocr::recognizeCrop(const cv::Mat &crop) {
    int width = std::clamp(static_cast<int>(std::ceil(static_cast<double>(kRecHeight) * crop.cols /
                                                      std::max(crop.rows, 1))), 16, 3200);
    cv::Mat resized, image;
    cv::resize(crop, resized, cv::Size(width, kRecHeight));
    resized.convertTo(image, CV_32FC3, 1.0 / 127.5, -1.0);
    cv::Mat out = run(rec, toBlob(image));  // [1, T, C]，模型內含 softmax

    // CTC greedy 解碼：類別 0 是 blank，類別 i 對應 dict 第 i-1 行；超出字典的最後一類是空白。
    // 同一類別連續的時間步合併成一個字元，那段時間步就是它在行內的水平位置。
    const int steps = out.size[1], classes = out.size[2];
    const float stepWidth = static_cast<float>(crop.cols) / steps;
    OcrLine line;
    int previous = -1;
    double sum = 0;
    int count = 0;
    size_t firstSpan = 0;  // 目前這個字元在 spans 中的起點，後續時間步延長它的 x1
    for (int t = 0; t < steps; t++) {
        const float *row = out.ptr<float>(0, t);
        int best = static_cast<int>(std::max_element(row, row + classes) - row);
        if (best != 0 && best != previous) {
            const std::string &piece = best - 1 < static_cast<int>(dict.size()) ? dict[best - 1] : " ";
            line.text += piece;
            firstSpan = line.spans.size();
            // 字典的一項可能不只一個 codepoint，每個 codepoint 都記同一段範圍。
            for (unsigned char c: piece) {
                if ((c & 0xC0) != 0x80) line.spans.emplace_back(t * stepWidth, (t + 1) * stepWidth);
            }
            sum += row[best];
            count++;
        } else if (best != 0 && best == previous) {
            for (size_t i = firstSpan; i < line.spans.size(); i++) line.spans[i].second = (t + 1) * stepWidth;
        }
        previous = best;
    }
    line.confidence = count ? static_cast<float>(sum / count) : 0.f;
    widenSpans(line.spans, static_cast<float>(crop.cols), static_cast<float>(crop.rows));
    return line;
}

cv::Rect OcrLine::spanBox(int start, int end) const {
    if (spans.empty() || start < 0 || end > static_cast<int>(spans.size()) || start >= end) return box;
    int x0 = static_cast<int>(std::floor(spans[start].first));
    int x1 = static_cast<int>(std::ceil(spans[end - 1].second));
    return cv::Rect(x0, box.y, std::max(1, x1 - x0), box.height) & box;
}

OcrLine Ocr::recognize(const cv::Mat &bgr, const cv::Rect &roi, OcrTiming *timing) {
    auto start = std::chrono::steady_clock::now();
    cv::Rect area = roi & cv::Rect(0, 0, bgr.cols, bgr.rows);
    if (area.empty()) return {};

    // 補邊複製 ROI 自己的邊緣。沒有 BORDER_ISOLATED 的話，子矩陣會取 ROI 外的真實像素補邊，
    // 卡片邊框之類的線條會被讀成多出來的字。
    cv::Mat padded;
    cv::copyMakeBorder(bgr(area), padded, kRoiPad, kRoiPad, kRoiPad, kRoiPad,
                       cv::BORDER_REPLICATE | cv::BORDER_ISOLATED);
    OcrLine line = recognizeCrop(padded);
    line.box = area;
    for (auto &span: line.spans) {
        span.first = std::clamp(span.first - kRoiPad, 0.f, static_cast<float>(area.width)) + area.x;
        span.second = std::clamp(span.second - kRoiPad, 0.f, static_cast<float>(area.width)) + area.x;
    }
    if (timing) timing->recMs += msSince(start);
    return line;
}

std::vector<OcrLine> Ocr::detectAndRecognize(const cv::Mat &bgr, const cv::Rect &roi, OcrTiming *timing) {
    auto start = std::chrono::steady_clock::now();
    cv::Rect area = roi & cv::Rect(0, 0, bgr.cols, bgr.rows);
    if (area.empty()) return {};
    cv::Mat source = bgr(area);

    // 長邊縮到 kDetLimit 以內，兩邊都取 32 的倍數（DBNet 的下採樣倍率）。
    double r = std::max(source.rows, source.cols) > kDetLimit
               ? static_cast<double>(kDetLimit) / std::max(source.rows, source.cols) : 1.0;
    int h = std::max(32, static_cast<int>(std::lround(source.rows * r / 32)) * 32);
    int w = std::max(32, static_cast<int>(std::lround(source.cols * r / 32)) * 32);
    cv::Mat resized, image;
    cv::resize(source, resized, cv::Size(w, h));
    resized.convertTo(image, CV_32FC3, 1.0 / 255);
    cv::subtract(image, cv::Scalar(0.485, 0.456, 0.406), image);
    cv::divide(image, cv::Scalar(0.229, 0.224, 0.225), image);
    cv::Mat out = run(det, toBlob(image));
    cv::Mat prob(out.size[2], out.size[3], CV_32F, out.ptr<float>(0, 0));
    std::vector<cv::Rect2f> boxes = dbPostprocess(prob);
    if (timing) timing->detMs += msSince(start);

    start = std::chrono::steady_clock::now();
    const double scaleX = static_cast<double>(source.cols) / w;
    const double scaleY = static_cast<double>(source.rows) / h;
    std::vector<OcrLine> lines;
    for (const auto &box: boxes) {
        cv::Rect crop(static_cast<int>(std::floor(box.x * scaleX)), static_cast<int>(std::floor(box.y * scaleY)),
                      static_cast<int>(std::ceil(box.width * scaleX)), static_cast<int>(std::ceil(box.height * scaleY)));
        crop &= cv::Rect(0, 0, source.cols, source.rows);
        if (crop.width < 1 || crop.height < 1) continue;

        cv::Mat patch = source(crop);
        bool vertical = patch.rows >= 1.5 * patch.cols;
        if (vertical) cv::rotate(patch, patch, cv::ROTATE_90_COUNTERCLOCKWISE);
        OcrLine line = recognizeCrop(patch);
        if (line.text.empty()) continue;
        line.box = crop + area.tl();
        if (vertical) {
            line.spans.clear();  // 旋轉過，水平位置沒有意義，spanBox 會退回整行
        } else {
            for (auto &span: line.spans) {
                span.first += static_cast<float>(line.box.x);
                span.second += static_cast<float>(line.box.x);
            }
        }
        lines.push_back(std::move(line));
    }
    std::sort(lines.begin(), lines.end(), [](const OcrLine &a, const OcrLine &b) {
        return a.box.y != b.box.y ? a.box.y < b.box.y : a.box.x < b.box.x;
    });
    if (timing) timing->recMs += msSince(start);
    return lines;
}

double Ocr::timeDummyInference() {
    // 2400×1080 的畫面縮到長邊 960 後是 960×448；辨識取常見的一行寬度。
    static const cv::Mat detInput(448, 960, CV_32FC3, cv::Scalar::all(0));
    static const cv::Mat recInput(kRecHeight, 320, CV_32FC3, cv::Scalar::all(0));
    auto start = std::chrono::steady_clock::now();
    run(det, toBlob(detInput));
    run(rec, toBlob(recInput));
    return msSince(start);
}
