#include <jni.h>
#include <android/native_window_jni.h>
#include <android/bitmap.h>
#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>
#include "RelcEngine.h"

static RelcEngine *gEngine = nullptr;

extern "C" {

/**
 * 計算兩張圖的顏色差異 (平均值差異)
 * 返回 0-100，越小表示越接近
 */
double getColorDiff(const cv::Mat &candidate, const cv::Mat &target) {
    cv::Scalar meanCandidate = cv::mean(candidate);
    cv::Scalar meanTarget = cv::mean(target);

    double diff = 0;
    // 比較 B, G, R 三個通道 (RGBA 的前三個)
    for (int i = 0; i < 3; i++) {
        diff += std::abs(meanCandidate.val[i] - meanTarget.val[i]);
    }
    // 標準化到 0-100
    return (diff * 100.0) / (255.0 * 3.0);
}

JNIEXPORT jobject JNICALL
Java_com_xaxaxax_relc_display_cv_NativeDetector_startEngine(
        JNIEnv *env,
        jobject thiz,
        jobject service, // Now IRelcV2Service
        jint width,
        jint height,
        jstring script) {

//    if (gEngine) {
//        delete gEngine;
//    }

    gEngine = new RelcEngine(env, service);

    const char *nativeScript = env->GetStringUTFChars(script, nullptr);
    bool success = gEngine->start(width, height, nativeScript);
    env->ReleaseStringUTFChars(script, nativeScript);

    if (!success) {
        delete gEngine;
        gEngine = nullptr;
        return nullptr;
    }

    ANativeWindow *window = gEngine->getWindow();
    if (!window) return nullptr;

    return ANativeWindow_toSurface(env, window);
}

JNIEXPORT void JNICALL
Java_com_xaxaxax_relc_display_cv_NativeDetector_stopEngine(
        JNIEnv *env,
        jobject thiz) {
    if (gEngine) {
        delete gEngine;
        gEngine = nullptr;
    }
}

JNIEXPORT void JNICALL
Java_com_xaxaxax_relc_display_cv_NativeDetector_setPreviewSurface(
        JNIEnv *env,
        jobject thiz,
        jobject surface) {
    if (gEngine) {
        gEngine->setPreviewSurface(env, surface);
    }
}

JNIEXPORT jboolean JNICALL
Java_com_xaxaxax_relc_display_cv_NativeDetector_isEngineRunning(
        JNIEnv *env,
        jobject thiz) {
    if (gEngine) {
        return (jboolean) gEngine->isEngineRunning();
    }
    return JNI_FALSE;
}

JNIEXPORT jobject JNICALL
Java_com_xaxaxax_relc_display_cv_NativeDetector_matchTemplateNative(
        JNIEnv *env,
        jobject thiz,
        jobject screen_bitmap,
        jobject target_bitmap,
        jint x, jint y, jint width, jint height,
        jint method) {

    AndroidBitmapInfo screen_info, target_info;
    void *screen_pixels, *target_pixels;

    if (AndroidBitmap_getInfo(env, screen_bitmap, &screen_info) < 0 ||
        AndroidBitmap_getInfo(env, target_bitmap, &target_info) < 0) {
        return nullptr;
    }

    if (AndroidBitmap_lockPixels(env, screen_bitmap, &screen_pixels) < 0 ||
        AndroidBitmap_lockPixels(env, target_bitmap, &target_pixels) < 0) {
        return nullptr;
    }

    // 建立全螢幕 Mat
    cv::Mat screenFull(screen_info.height, screen_info.width, CV_8UC4, screen_pixels);
    cv::Mat target(target_info.height, target_info.width, CV_8UC4, target_pixels);

    // 處理 ROI (裁剪區域)
    cv::Rect roi(x, y, width, height);
    // 邊界檢查，防止越界
    roi &= cv::Rect(0, 0, screenFull.cols, screenFull.rows);

    if (roi.width < target.cols || roi.height < target.rows) {
        AndroidBitmap_unlockPixels(env, screen_bitmap);
        AndroidBitmap_unlockPixels(env, target_bitmap);
        return nullptr;
    }

    cv::Mat screenRoi = screenFull(roi);

    // 進行模板匹配 (OpenCV 的 matchTemplate 本身就支援多通道彩色匹配)
    int result_cols = screenRoi.cols - target.cols + 1;
    int result_rows = screenRoi.rows - target.rows + 1;
    cv::Mat result(result_rows, result_cols, CV_32FC1);

    cv::matchTemplate(screenRoi, target, result, method);

    double minVal, maxVal;
    cv::Point minLoc, maxLoc;
    cv::minMaxLoc(result, &minVal, &maxVal, &minLoc, &maxLoc);

    // 取得最佳匹配區域
    cv::Rect matchRect(maxLoc.x, maxLoc.y, target.cols, target.rows);
    cv::Mat candidate = screenRoi(matchRect);

    // 計算顏色差異 (輔助驗證)
    double colorDiff = getColorDiff(candidate, target);

    AndroidBitmap_unlockPixels(env, screen_bitmap);
    AndroidBitmap_unlockPixels(env, target_bitmap);

    jclass result_class = env->FindClass("com/xaxaxax/relc/display/cv/DetectionResult");
    jmethodID constructor = env->GetMethodID(result_class, "<init>", "(ZIIDD)V");

    // 將座標轉換回全螢幕座標
    return env->NewObject(result_class, constructor,
                          true,
                          (jint) (roi.x + maxLoc.x + target.cols / 2),
                          (jint) (roi.y + maxLoc.y + target.rows / 2),
                          (jdouble) maxVal,
                          (jdouble) colorDiff);
}

}
