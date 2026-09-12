#ifndef RELC_VISION_MATCHER_H
#define RELC_VISION_MATCHER_H

#include <opencv2/core.hpp>
#include <atomic>
#include <condition_variable>
#include <mutex>
#include <string>
#include <unordered_map>
#include <vector>

/**
 * 一個要找的模板。`roi` 用**邏輯空間**座標（腳本看到的那個），
 * 轉回影格空間由 VisionMatcher 內部處理。
 */
struct VisionRequest {
    std::string imagePath;      // 絕對路徑
    double threshold = 0.8;
    double scale = 1.0;         // 比對前把影格與模板一起縮小，純粹是效能旋鈕
    bool gray = false;
    bool hasRoi = false;
    cv::Rect roi;               // 邏輯空間
};

/** 命中的位置。**全部都是邏輯空間座標**——可以直接餵給 input.tap。 */
struct VisionHit {
    bool found = false;
    double x = 0, y = 0;        // 左上角
    double w = 0, h = 0;
    double cx = 0, cy = 0;      // 中心點
    double confidence = 0;
};

/**
 * 持有虛擬顯示最新的一張影格，並在被要求時對它跑 `cv::matchTemplate`。
 *
 * 與上一代的差別：比對**不再每幀無條件執行**。影格回呼只負責存下影格並喚醒等待者，
 * OpenCV 只在腳本真的呼叫 `vision.find` / `vision.wait` 時才跑。
 *
 * 這裡同時是 surface 空間 ↔ 邏輯空間轉換的唯一所在（見 CONTEXT.md「Surface 空間 / 邏輯空間」）。
 * 影格是 surface 空間，對外的一切都是邏輯空間。
 */
class VisionMatcher {
public:
    VisionMatcher(int frameWidth, int frameHeight, const std::string &scriptDir);

    /** 由 app 進程的 DisplayListener 推入（Surface.ROTATION_*, 0..3）。不會旋轉任何東西，只是記錄。 */
    void setRotation(int rotation);

    int rotation() const { return displayRotation.load() & 3; }

    /** 目標顯示器的**邏輯**尺寸——旋轉 90/270 時長寬互換。 */
    void logicalSize(int &width, int &height) const;

    /** 影格送達（由 AImageReader 的執行緒呼叫）。只複製並喚醒等待者，不做比對。 */
    void onFrame(const cv::Mat &frame);

    /** 目前的影格序號；0 表示還沒收到任何影格。 */
    uint64_t frameCounter() const;

    /**
     * 等到序號大於 [after] 的影格出現。
     * @return true 表示有新影格；false 表示逾時或已 shutdown。
     */
    bool waitForFrameAfter(uint64_t after, long timeoutMs);

    /** 對最新影格比對 [requests]，回傳與其等長的結果。沒有影格時全部 found = false。 */
    std::vector<VisionHit> match(const std::vector<VisionRequest> &requests);

    /** 喚醒所有等待者並讓後續等待立刻返回（停止腳本時呼叫）。 */
    void shutdown();

    /** 解析腳本相對路徑成絕對路徑。 */
    std::string resolvePath(const std::string &relative) const;

    /** 模板檔案是否讀得到——讓 Lua 端可以報出有意義的錯誤而不是靜默找不到。 */
    bool templateExists(const VisionRequest &request);

private:
    /** 取得（必要時載入並快取）已套用 gray/scale 前處理的模板。失敗回傳空 Mat。 */
    cv::Mat templateFor(const VisionRequest &request);

    /** surface(影格) 空間 → 邏輯空間。 */
    void frameToLogical(double fx, double fy, double &lx, double &ly) const;

    /** 邏輯空間 → surface(影格) 空間。ROI 由腳本以邏輯座標指定，需反向轉回影格。 */
    void logicalToFrame(double lx, double ly, double &fx, double &fy) const;

    const int frameWidth;
    const int frameHeight;
    const std::string scriptDir;

    std::atomic<int> displayRotation{0};

    mutable std::mutex frameMutex;
    std::condition_variable frameCv;
    cv::Mat latestFrame;
    uint64_t frames = 0;
    bool stopped = false;

    std::mutex cacheMutex;
    std::unordered_map<std::string, cv::Mat> cache;
};

#endif // RELC_VISION_MATCHER_H
