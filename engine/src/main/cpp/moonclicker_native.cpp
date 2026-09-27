#include <jni.h>
#include <android/native_window_jni.h>
#include <opencv2/imgcodecs.hpp>

#include <cstdio>
#include <mutex>
#include <sstream>
#include <string>

#include "GlesDistributor.h"
#include "Ocr.h"
#include "ScriptRuntime.h"

/**
 * 一次只跑一個腳本。這不是偷懶——UI 也是以「執行中的那一個」來表達的
 * （見 :app 的 ScriptSession）。要並行就得連同 Kotlin 端的狀態模型一起改。
 */
static ScriptRuntime *gRuntime = nullptr;
static std::mutex gRuntimeMutex;

static std::string toString(JNIEnv *env, jstring value) {
    const char *chars = env->GetStringUTFChars(value, nullptr);
    std::string copy(chars);
    env->ReleaseStringUTFChars(value, chars);
    return copy;
}

static void throwIllegalState(JNIEnv *env, const std::string &message) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message.c_str());
}

static std::string jsonString(const std::string &s) {
    std::string out = "\"";
    for (unsigned char c: s) {
        if (c == '"' || c == '\\') {
            out += '\\';
            out += static_cast<char>(c);
        } else if (c < 0x20) {
            char escaped[8];
            snprintf(escaped, sizeof(escaped), "\\u%04x", c);
            out += escaped;
        } else {
            out += static_cast<char>(c);
        }
    }
    return out + '"';
}

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_xaxaxax_moonclicker_MoonClickerService_nativeCreateDistributor(JNIEnv *env, jobject thiz, jint width,
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
Java_com_xaxaxax_moonclicker_MoonClickerService_nativeGetDistributorSurface(JNIEnv *env, jobject thiz,
                                                                jlong ptr) {
    auto *distributor = reinterpret_cast<GlesDistributor *>(ptr);
    return distributor->getSurface(env);
}

JNIEXPORT jint JNICALL
Java_com_xaxaxax_moonclicker_MoonClickerService_nativeAddSurface(JNIEnv *env, jobject thiz, jlong ptr,
                                                     jobject surface) {
    auto *distributor = reinterpret_cast<GlesDistributor *>(ptr);
    return distributor->addSurface(env, surface);
}

JNIEXPORT void JNICALL
Java_com_xaxaxax_moonclicker_MoonClickerService_nativeRemoveSurface(JNIEnv *env, jobject thiz, jlong ptr,
                                                        jint handle) {
    auto *distributor = reinterpret_cast<GlesDistributor *>(ptr);
    distributor->removeSurface(handle);
}

JNIEXPORT void JNICALL
Java_com_xaxaxax_moonclicker_MoonClickerService_nativeSetDistributorRotation(JNIEnv *env, jobject thiz,
                                                                  jlong ptr, jint rotation) {
    auto *distributor = reinterpret_cast<GlesDistributor *>(ptr);
    distributor->setRotation(rotation);
}

JNIEXPORT void JNICALL
Java_com_xaxaxax_moonclicker_MoonClickerService_nativeDestroyDistributor(JNIEnv *env, jobject thiz, jlong ptr) {
    auto *distributor = reinterpret_cast<GlesDistributor *>(ptr);
    distributor->release(env);
    delete distributor;
}

JNIEXPORT jboolean JNICALL
Java_com_xaxaxax_moonclicker_lua_LuaNative_nativeStart(
        JNIEnv *env,
        jobject thiz,
        jobject host,
        jobject service,
        jint displayId,
        jboolean isPhysical,
        jboolean withVision,
        jint surfaceWidth,
        jint surfaceHeight,
        jint initialRotation,
        jstring scriptDir) {
    std::lock_guard<std::mutex> lock(gRuntimeMutex);
    if (gRuntime != nullptr) {
        LOGE("nativeStart rejected: a script is already running");
        return JNI_FALSE;
    }

    const char *dir = env->GetStringUTFChars(scriptDir, nullptr);
    std::string dirCopy(dir);
    env->ReleaseStringUTFChars(scriptDir, dir);
    if (dirCopy.empty() || dirCopy.back() != '/') dirCopy += '/';

    auto *runtime = new ScriptRuntime(env, host, service);
    if (!runtime->start(displayId, isPhysical, withVision, surfaceWidth, surfaceHeight, initialRotation,
                        dirCopy)) {
        delete runtime;
        return JNI_FALSE;
    }

    gRuntime = runtime;
    return JNI_TRUE;
}

JNIEXPORT void JNICALL
Java_com_xaxaxax_moonclicker_lua_LuaNative_nativeStop(JNIEnv *env, jobject thiz) {
    std::lock_guard<std::mutex> lock(gRuntimeMutex);
    delete gRuntime;  // 解構子會 stop() 並等執行緒結束
    gRuntime = nullptr;
}

JNIEXPORT jboolean JNICALL
Java_com_xaxaxax_moonclicker_lua_LuaNative_nativeIsRunning(JNIEnv *env, jobject thiz) {
    std::lock_guard<std::mutex> lock(gRuntimeMutex);
    return gRuntime && gRuntime->isRunning() ? JNI_TRUE : JNI_FALSE;
}

// --- OCR -------------------------------------------------------------------

JNIEXPORT jlong JNICALL
Java_com_xaxaxax_moonclicker_ocr_OcrNative_nativeCreate(JNIEnv *env, jobject thiz, jstring packDir, jint threads) {
    try {
        return reinterpret_cast<jlong>(new Ocr(toString(env, packDir), threads));
    } catch (const std::exception &e) {
        throwIllegalState(env, e.what());
        return 0;
    }
}

JNIEXPORT void JNICALL
Java_com_xaxaxax_moonclicker_ocr_OcrNative_nativeDestroy(JNIEnv *env, jobject thiz, jlong handle) {
    delete reinterpret_cast<Ocr *>(handle);
}

JNIEXPORT jdouble JNICALL
Java_com_xaxaxax_moonclicker_ocr_OcrNative_nativeTimeDummyInference(JNIEnv *env, jobject thiz, jlong handle) {
    try {
        return reinterpret_cast<Ocr *>(handle)->timeDummyInference();
    } catch (const std::exception &e) {
        throwIllegalState(env, e.what());
        return 0;
    }
}

/** 回傳 UTF-8 的 JSON bytes：NewStringUTF 吃的是 modified UTF-8，字典裡的擴充區漢字（4 位元組）會壞掉。 */
JNIEXPORT jbyteArray JNICALL
Java_com_xaxaxax_moonclicker_ocr_OcrNative_nativeReadImage(JNIEnv *env, jobject thiz, jlong handle,
                                                           jstring imagePath, jintArray roi, jboolean detect) {
    std::string path = toString(env, imagePath);
    cv::Mat image = cv::imread(path, cv::IMREAD_COLOR);
    if (image.empty()) {
        throwIllegalState(env, "cannot read image: " + path);
        return nullptr;
    }

    cv::Rect area(0, 0, image.cols, image.rows);
    if (roi != nullptr && env->GetArrayLength(roi) == 4) {
        jint r[4];
        env->GetIntArrayRegion(roi, 0, 4, r);
        area = cv::Rect(r[0], r[1], r[2], r[3]);
    }

    try {
        auto *ocr = reinterpret_cast<Ocr *>(handle);
        OcrTiming timing;
        std::vector<OcrLine> lines;
        if (detect) {
            lines = ocr->detectAndRecognize(image, area, &timing);
        } else {
            lines.push_back(ocr->recognize(image, area, &timing));
        }

        std::ostringstream out;
        out << "{\"det_ms\":" << timing.detMs << ",\"rec_ms\":" << timing.recMs << ",\"lines\":[";
        for (size_t i = 0; i < lines.size(); i++) {
            const OcrLine &line = lines[i];
            if (i > 0) out << ',';
            out << "{\"text\":" << jsonString(line.text) << ",\"confidence\":" << line.confidence
                << ",\"x\":" << line.box.x << ",\"y\":" << line.box.y
                << ",\"w\":" << line.box.width << ",\"h\":" << line.box.height << '}';
        }
        out << "]}";
        std::string json = out.str();
        jbyteArray bytes = env->NewByteArray(static_cast<jsize>(json.size()));
        env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(json.size()),
                                reinterpret_cast<const jbyte *>(json.data()));
        return bytes;
    } catch (const std::exception &e) {
        throwIllegalState(env, e.what());
        return nullptr;
    }
}

}
