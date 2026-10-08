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

    /**
     * VD 自己的 rotation（Surface.ROTATION_*，0..3）。WindowManager 在
     * VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT 下把內容轉進這個固定尺寸的 surface 裡
     * （ADR-0017），這裡收到的紋理因此已經帶著這個旋轉；drawFrame() 用它反著選 texcoord
     * 把內容轉正，consumer 就不用知道 v 存在。方向已在 Pixel 7a 上真機驗證。
     */
    void setRotation(int quarterTurns);

private:
    void renderLoop();
    void setupEGL();
    void terminateEGL();
    /** 把 [timestamp] 這張影格畫到還沒收過它的 sink；回傳實際畫了幾個。 */
    int drawFrame(long long timestamp);

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
        /** 這個 sink 最後收到的影格（SurfaceTexture 時間戳）；-1 表示還沒收過。 */
        long long deliveredTimestamp;
    };

    std::vector<Sink> sinks;
    std::mutex sinksMutex;

    std::thread renderThread;
    std::atomic<bool> isRunning;
    std::mutex frameMutex;
    std::condition_variable frameCond;

    JavaVM* javaVM;
    std::atomic<int> nextHandle;
    std::atomic<int> rotation;
    /** rotation 改了：同一張影格要以新方向重畫給所有 sink。 */
    std::atomic<bool> redrawAll;
};

#endif // GLES_DISTRIBUTOR_H
