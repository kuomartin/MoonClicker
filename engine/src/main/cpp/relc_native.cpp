#include <jni.h>
#include <android/native_window_jni.h>
#include <mutex>
#include <string>

#include "GlesDistributor.h"
#include "ScriptRuntime.h"

/**
 * 一次只跑一個腳本。這不是偷懶——UI 也是以「執行中的那一個」來表達的
 * （見 :app 的 ScriptSession）。要並行就得連同 Kotlin 端的狀態模型一起改。
 */
static ScriptRuntime *gRuntime = nullptr;
static std::mutex gRuntimeMutex;

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
Java_com_xaxaxax_relc_RelcV2Service_nativeAddSurface(JNIEnv *env, jobject thiz, jlong ptr,
                                                     jobject surface) {
    auto *distributor = reinterpret_cast<GlesDistributor *>(ptr);
    return distributor->addSurface(env, surface);
}

JNIEXPORT void JNICALL
Java_com_xaxaxax_relc_RelcV2Service_nativeRemoveSurface(JNIEnv *env, jobject thiz, jlong ptr,
                                                        jint handle) {
    auto *distributor = reinterpret_cast<GlesDistributor *>(ptr);
    distributor->removeSurface(handle);
}

JNIEXPORT void JNICALL
Java_com_xaxaxax_relc_RelcV2Service_nativeSetDistributorRotation(JNIEnv *env, jobject thiz,
                                                                  jlong ptr, jint rotation) {
    auto *distributor = reinterpret_cast<GlesDistributor *>(ptr);
    distributor->setRotation(rotation);
}

JNIEXPORT void JNICALL
Java_com_xaxaxax_relc_RelcV2Service_nativeDestroyDistributor(JNIEnv *env, jobject thiz, jlong ptr) {
    auto *distributor = reinterpret_cast<GlesDistributor *>(ptr);
    distributor->release(env);
    delete distributor;
}

JNIEXPORT jboolean JNICALL
Java_com_xaxaxax_relc_lua_LuaNative_nativeStart(
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
Java_com_xaxaxax_relc_lua_LuaNative_nativeStop(JNIEnv *env, jobject thiz) {
    std::lock_guard<std::mutex> lock(gRuntimeMutex);
    delete gRuntime;  // 解構子會 stop() 並等執行緒結束
    gRuntime = nullptr;
}

JNIEXPORT jboolean JNICALL
Java_com_xaxaxax_relc_lua_LuaNative_nativeIsRunning(JNIEnv *env, jobject thiz) {
    std::lock_guard<std::mutex> lock(gRuntimeMutex);
    return gRuntime && gRuntime->isRunning() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_xaxaxax_relc_lua_LuaNative_nativeSetDisplayRotation(JNIEnv *env, jobject thiz,
                                                             jint rotation) {
    std::lock_guard<std::mutex> lock(gRuntimeMutex);
    if (gRuntime) gRuntime->setRotation(rotation);
}

}
