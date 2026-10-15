#include <jni.h>
#include <android/native_window_jni.h>
#include <opencv2/imgcodecs.hpp>

#include <sstream>
#include <string>

#include "GlesDistributor.h"
#include "JsonString.h"
#include "Ocr.h"
#include "ScriptRuntime.h"
#include "TextMatch.h"

static std::string toString(JNIEnv *env, jstring value) {
    const char *chars = env->GetStringUTFChars(value, nullptr);
    std::string copy(chars);
    env->ReleaseStringUTFChars(value, chars);
    return copy;
}

static void throwIllegalState(JNIEnv *env, const std::string &message) {
    env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), message.c_str());
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

/** 每次執行一個 ScriptRuntime，handle 由 Kotlin 的 ScriptEngine 實例持有。 */
JNIEXPORT jlong JNICALL
Java_com_xaxaxax_moonclicker_lua_LuaNative_nativeStart(
        JNIEnv *env,
        jobject thiz,
        jobject host,
        jint displayId,
        jboolean isPhysical,
        jboolean withVision,
        jint surfaceWidth,
        jint surfaceHeight,
        jint initialRotation,
        jstring scriptDir,
        jstring ocrPackDir,
        jint ocrThreads) {
    const char *dir = env->GetStringUTFChars(scriptDir, nullptr);
    std::string dirCopy(dir);
    env->ReleaseStringUTFChars(scriptDir, dir);
    if (dirCopy.empty() || dirCopy.back() != '/') dirCopy += '/';

    auto *runtime = new ScriptRuntime(env, host);
    if (!runtime->start(displayId, isPhysical, withVision, surfaceWidth, surfaceHeight, initialRotation,
                        dirCopy, toString(env, ocrPackDir), ocrThreads)) {
        delete runtime;
        return 0;
    }
    return reinterpret_cast<jlong>(runtime);
}

/** REPL 模式的 ScriptRuntime，handle 由 Kotlin 的 ReplSession 實例持有。參數同 nativeStart。 */
JNIEXPORT jlong JNICALL
Java_com_xaxaxax_moonclicker_lua_LuaNative_nativeStartRepl(
        JNIEnv *env,
        jobject thiz,
        jobject host,
        jint displayId,
        jboolean isPhysical,
        jboolean withVision,
        jint surfaceWidth,
        jint surfaceHeight,
        jint initialRotation,
        jstring scriptDir,
        jstring ocrPackDir,
        jint ocrThreads) {
    std::string dir = toString(env, scriptDir);
    if (dir.empty() || dir.back() != '/') dir += '/';

    auto *runtime = new ScriptRuntime(env, host);
    if (!runtime->startRepl(displayId, isPhysical, withVision, surfaceWidth, surfaceHeight, initialRotation,
                            dir, toString(env, ocrPackDir), ocrThreads)) {
        delete runtime;
        return 0;
    }
    return reinterpret_cast<jlong>(runtime);
}

/** [code] 是 UTF-8 bytes，理由同 nativeReadImage 的回傳值。 */
JNIEXPORT void JNICALL
Java_com_xaxaxax_moonclicker_lua_LuaNative_nativeReplEval(JNIEnv *env, jobject thiz, jlong handle, jlong id,
                                                          jbyteArray code) {
    jsize length = env->GetArrayLength(code);
    std::string text(static_cast<size_t>(length), '\0');
    env->GetByteArrayRegion(code, 0, length, reinterpret_cast<jbyte *>(text.data()));
    reinterpret_cast<ScriptRuntime *>(handle)->evalChunk(id, std::move(text));
}

JNIEXPORT void JNICALL
Java_com_xaxaxax_moonclicker_lua_LuaNative_nativeReplInterrupt(JNIEnv *env, jobject thiz, jlong handle) {
    reinterpret_cast<ScriptRuntime *>(handle)->interrupt();
}

JNIEXPORT void JNICALL
Java_com_xaxaxax_moonclicker_lua_LuaNative_nativeStop(JNIEnv *env, jobject thiz, jlong handle) {
    delete reinterpret_cast<ScriptRuntime *>(handle);  // 解構子會 stop() 並等執行緒結束
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

JNIEXPORT jdoubleArray JNICALL
Java_com_xaxaxax_moonclicker_ocr_OcrNative_nativeMatchText(JNIEnv *env, jobject thiz, jstring target,
                                                           jstring line, jboolean exact) {
    TextMatch match = matchText(toString(env, target), toString(env, line), exact);
    jdouble values[] = {match.similarity, static_cast<jdouble>(match.start), static_cast<jdouble>(match.end)};
    jdoubleArray result = env->NewDoubleArray(3);
    env->SetDoubleArrayRegion(result, 0, 3, values);
    return result;
}

}
