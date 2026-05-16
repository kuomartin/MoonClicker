#ifndef GLES_DISTRIBUTOR_H
#define GLES_DISTRIBUTOR_H

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <jni.h>
#include <vector>
#include <mutex>
#include <thread>
#include <atomic>
#include <condition_variable>

class GlesDistributor {
public:
    GlesDistributor(int width, int height);
    ~GlesDistributor();

    bool init(JNIEnv* env);
    void release(JNIEnv* env);

    jobject getSurface(JNIEnv* env);
    int addSurface(JNIEnv* env, jobject surface);
    void removeSurface(int handle);

private:
    void renderLoop();
    void setupEGL();
    void terminateEGL();
    void drawFrame();

    int width;
    int height;

    EGLDisplay eglDisplay;
    EGLContext eglContext;
    EGLSurface eglPbufferSurface;
    EGLConfig eglConfig;

    GLuint textureId;
    GLuint program;
    GLuint vPositionHandle;
    GLuint vTextureHandle;

    jobject jSurfaceTexture;
    jobject jSurface;
    
    struct Sink {
        int handle;
        jobject jSurface; // Global ref to the Java Surface
        ANativeWindow* window;
        EGLSurface eglSurface;
    };

    std::vector<Sink> sinks;
    std::mutex sinksMutex;

    std::thread renderThread;
    std::atomic<bool> isRunning;
    std::mutex frameMutex;
    std::condition_variable frameCond;
    std::atomic<bool> frameAvailable;

    JavaVM* javaVM;
    std::atomic<int> nextHandle;

    static void onFrameAvailable(JNIEnv* env, jobject thiz, jobject surfaceTexture);
};

#endif // GLES_DISTRIBUTOR_H
