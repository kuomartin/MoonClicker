#include <jni.h>
#include <android/native_window_jni.h>
#include "RelcEngine.h"

static RelcEngine* gEngine = nullptr;

extern "C" {

JNIEXPORT jobject JNICALL
Java_com_xaxaxax_relc_display_cv_NativeDetector_startEngine(
        JNIEnv *env,
        jobject thiz,
        jobject service, // Now IRelcV2Service
        jint width,
        jint height,
        jstring script) {

    if (gEngine) {
        delete gEngine;
    }

    gEngine = new RelcEngine(env, service);
    
    const char* nativeScript = env->GetStringUTFChars(script, nullptr);
    bool success = gEngine->start(width, height, nativeScript);
    env->ReleaseStringUTFChars(script, nativeScript);

    if (!success) {
        delete gEngine;
        gEngine = nullptr;
        return nullptr;
    }

    ANativeWindow* window = gEngine->getWindow();
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

// ... existing matchTemplateNative if needed for compatibility ...

}
