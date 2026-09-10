#include <jni.h>
#include <android/native_window_jni.h>
#include <android/bitmap.h>
#include <opencv2/core.hpp>
#include <opencv2/imgproc.hpp>
#include "RelcEngine.h"
#include "GlesDistributor.h"

static RelcEngine *gEngine = nullptr;

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_xaxaxax_relc_RelcV2Service_nativeCreateDistributor(JNIEnv *env, jobject thiz, jint width,
                                                            jint height) {
    auto *distributor = new GlesDistributor(width, height);
    if (distributor->init(env)) {
        return reinterpret_cast<jlong>(distributor);
    } else {
        delete distributor;
        return 0;
    }
}

JNIEXPORT jobject JNICALL
Java_com_xaxaxax_relc_RelcV2Service_nativeGetDistributorSurface(JNIEnv *env, jobject thiz,
                                                                jlong ptr) {
    auto *distributor = reinterpret_cast<GlesDistributor *>(ptr);
    return distributor->getSurface(env);
}

JNIEXPORT jint JNICALL
Java_com_xaxaxax_relc_RelcV2Service_nativeAddSurface(JNIEnv *env, jobject thiz, jlong ptr, jobject surface) {
    auto *distributor = reinterpret_cast<GlesDistributor *>(ptr);
    return distributor->addSurface(env, surface);
}

JNIEXPORT void JNICALL
Java_com_xaxaxax_relc_RelcV2Service_nativeRemoveSurface(JNIEnv *env, jobject thiz, jlong ptr, jint handle) {
    auto *distributor = reinterpret_cast<GlesDistributor *>(ptr);
    distributor->removeSurface(handle);
}


JNIEXPORT void JNICALL
Java_com_xaxaxax_relc_RelcV2Service_nativeDestroyDistributor(JNIEnv *env, jobject thiz, jlong ptr) {
    auto *distributor = reinterpret_cast<GlesDistributor *>(ptr);
    distributor->release(env);
    delete distributor;
}

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
Java_com_xaxaxax_relc_lua_LuaNative_startEngine(
        JNIEnv *env,
        jobject thiz,
        jobject service, // Now IRelcV2Service
        jint displayId,
        jint width,
        jint height,
        jstring scriptPath) {

//    if (gEngine) {
//        delete gEngine;
//    }

    gEngine = new RelcEngine(env, service, thiz);

    const char *nativeScriptPath = env->GetStringUTFChars(scriptPath, nullptr);
    bool success = gEngine->start(displayId, width, height, nativeScriptPath);
    env->ReleaseStringUTFChars(scriptPath, nativeScriptPath);

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
Java_com_xaxaxax_relc_lua_LuaNative_stopEngine(
        JNIEnv *env,
        jobject thiz) {
    if (gEngine) {
        delete gEngine;
        gEngine = nullptr;
    }
}

JNIEXPORT jboolean JNICALL
Java_com_xaxaxax_relc_lua_LuaNative_isEngineRunning(
        JNIEnv *env,
        jobject thiz) {
    if (gEngine) {
        return (jboolean) gEngine->isEngineRunning();
    }
    return JNI_FALSE;
}

}
