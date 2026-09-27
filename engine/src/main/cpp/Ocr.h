#ifndef MOONCLICKER_OCR_H
#define MOONCLICKER_OCR_H

#include <onnxruntime_cxx_api.h>
#include <opencv2/core.hpp>

#include <string>
#include <vector>

/** 一行辨識結果。`box` 是輸入影像的座標（已加回 ROI 偏移）。 */
struct OcrLine {
    std::string text;
    float confidence = 0;  // CTC 解碼時每個輸出字元機率的平均
    cv::Rect box;
};

/** 一次呼叫各階段的耗時，給延遲量測用。 */
struct OcrTiming {
    double detMs = 0;  // 偵測：前處理＋推論＋後處理
    double recMs = 0;  // 辨識：裁切＋前處理＋推論＋解碼（所有行加總）
};

/**
 * PP-OCRv6 small 的偵測與辨識（ADR-0018）。推論走 ONNX Runtime 的 CPU EP，
 * 前後處理沿用選型研究（`docs/research/ocr-engine-selection.md`）量測時的設定。
 *
 * 輸入一律是 BGR（`CV_8UC3`）——PP-OCR 以 BGR 訓練。不是執行緒安全的：一個實例同一時間
 * 只給一條執行緒用。ORT 沒有 OpenMP 那種執行緒限制，建立與推論可以在不同執行緒。
 */
class Ocr {
public:
    /**
     * 載入 [packDir] 內的 `libonnxruntime.so`、`det.onnx`、`rec.onnx`、`dict.txt`。
     * @param threads ORT 的 intra-op 執行緒數；最佳值因機型而異，由校準決定。
     * @throws std::runtime_error 任何一個檔案載入失敗。
     */
    Ocr(const std::string &packDir, int threads);

    /**
     * 把 [roi] 內的影像當成單一文字行直接辨識，不經偵測——讀數值用。
     * 七段式數字與小字 `/` 在偵測＋辨識下會被切碎，直接辨識才讀得對。
     */
    OcrLine recognize(const cv::Mat &bgr, const cv::Rect &roi, OcrTiming *timing = nullptr);

    /** 在 [roi] 內偵測所有文字行後逐行辨識，依 y、x 排序。 */
    std::vector<OcrLine> detectAndRecognize(const cv::Mat &bgr, const cv::Rect &roi,
                                            OcrTiming *timing = nullptr);

    /**
     * 以固定形狀的假輸入跑一次偵測＋辨識，回傳毫秒數。推論耗時只取決於張量形狀，
     * 所以校準執行緒數不需要真的影像。
     */
    double timeDummyInference();

private:
    /** 單輸入單輸出的 ONNX 模型。 */
    struct Model {
        Ort::Session session{nullptr};
        std::string input;
        std::string output;
    };

    static Model load(const std::string &path, int threads);

    /** 推論一次：輸入 NCHW float blob（N=1），輸出複製成 N 維 float Mat。 */
    static cv::Mat run(Model &model, const cv::Mat &blob);

    OcrLine recognizeCrop(const cv::Mat &crop);

    Model det;
    Model rec;
    std::vector<std::string> dict;
};

#endif // MOONCLICKER_OCR_H
