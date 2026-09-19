#ifndef RELC_VISION_MATCHER_H
#define RELC_VISION_MATCHER_H

#include <opencv2/core.hpp>
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
 * 比對不是每幀無條件執行：影格回呼只存下影格並喚醒等待者，OpenCV 只在腳本真的呼叫
 * `vision.find` / `vision.wait` 時才跑。
 *
 * 影格已經是邏輯空間（distributor 在源頭把 v 消掉，見 ADR-0017），這裡不再做任何座標轉換。
 */
class VisionMatcher {
public:
    /**
     * @param rotation 啟動當下的 VD rotation（Surface.ROTATION_*，純粹是給 Lua `screen.rotation`
     *   讀的中繼資料）。frameWidth/frameHeight 是 distributor 轉正後的影格尺寸，兩者在整場執行
     *   期間都固定不變——AImageReader 不會在 VD 中途旋轉時重開，見 ADR-0017 的 Consequences。
     */
    VisionMatcher(int frameWidth, int frameHeight, int rotation, std::string scriptDir);

    int rotation() const { return displayRotation; }

    /** 目標顯示器的邏輯尺寸——就是建立時固定的 frameWidth/frameHeight，不再隨旋轉互換。 */
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

    const int frameWidth;
    const int frameHeight;
    const int displayRotation;
    const std::string scriptDir;

    mutable std::mutex frameMutex;
    std::condition_variable frameCv;
    cv::Mat latestFrame;
    uint64_t frames = 0;
    bool stopped = false;

    std::mutex cacheMutex;
    std::unordered_map<std::string, cv::Mat> cache;
};

#endif // RELC_VISION_MATCHER_H
