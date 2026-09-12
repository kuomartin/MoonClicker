#ifndef RELC_SCRIPT_RUNTIME_H
#define RELC_SCRIPT_RUNTIME_H

#include "LuaEngine.h"
#include "NativeImageReader.h"
#include "VisionMatcher.h"

#include <jni.h>
#include <android/native_window.h>

#include <atomic>
#include <condition_variable>
#include <memory>
#include <mutex>
#include <string>
#include <thread>

/** 必須與 com.xaxaxax.relc.engine.state.EngineEventType 一致。 */
namespace EngineEventType {
    constexpr int RUNNING = 0;
    constexpr int FINISHED = 1;
    constexpr int ERROR = 2;
    constexpr int STOPPED = 3;
    constexpr int VISION_RESULT = 4;
}

/** ScriptHost（Kotlin）上的 upcall 方法。非視覺的 Lua API 全部經由它實作。 */
struct HostMethods {
    jmethodID swipe = nullptr;      // (I[IJZ)Z   pointerId, points, durationMs, keep
    jmethodID pointerUp = nullptr;  // (I)Z
    jmethodID key = nullptr;        // (I)Z
    jmethodID launch = nullptr;     // (Ljava/lang/String;)Z
    jmethodID notify = nullptr;     // (Ljava/lang/String;Ljava/lang/String;)V
    jmethodID openUri = nullptr;    // (Ljava/lang/String;)V
    jmethodID setData = nullptr;    // (Ljava/lang/String;Ljava/lang/Object;)V
    jmethodID onEvent = nullptr;    // (ILjava/lang/String;)V
};

/**
 * 跑一份腳本：`main.lua` 在一條專屬執行緒上從上到下執行，跑完就結束。
 *
 * 沒有 tick 迴圈、沒有 coroutine。會阻塞的 Lua API（`sleep`、`vision.wait`、`input.*`）
 * 就真的在那條執行緒上阻塞，而且全部可以被 [stop] 打斷——這就是「線性腳本」的全部實作。
 *
 * 它**不擁有目標顯示器**：顯示器由 :app 建立與銷毀，這裡只把自己的 ImageReader surface
 * 掛上去，結束時再拿掉。
 */
class ScriptRuntime {
public:
    ScriptRuntime(JNIEnv *env, jobject host, jobject service);

    ~ScriptRuntime();

    /**
     * @param displayId     目標顯示器。
     * @param withVision    是否掛 ImageReader 取影格。實體螢幕拿不到影格，必須傳 false。
     * @param displayWidth  顯示器建立時的尺寸（surface 空間）。
     * @param scriptDir     腳本資料夾，必須以 '/' 結尾，內含 main.lua。
     */
    bool start(int displayId, bool withVision, int displayWidth, int displayHeight,
               const std::string &scriptDir);

    /** 要求停止並等執行緒結束。可重入。 */
    void stop();

    bool isRunning() const { return running.load(); }

    void setRotation(int rotation);

    // --- 以下給 LuaBindings 使用，全部只在 Lua 執行緒上呼叫 -----------------

    JNIEnv *env() const { return luaEnv; }

    jobject hostObject() const { return hostObj; }

    const HostMethods &host() const { return hostMethods; }

    VisionMatcher &vision() { return *visionMatcher; }

    bool hasVision() const { return visionEnabled; }

    /** 可被 [stop] 打斷的 sleep。回傳 false 表示被叫停，binding 應盡快收手。 */
    bool interruptibleSleep(long ms);

    void pushEvent(int type, const std::string &payload);

    /** 把 Lua 堆疊上 [index] 的值裝箱成 Java 物件（number/string/boolean/table→JSON）。 */
    jobject boxLuaValue(lua_State *L, int index);

private:
    void threadMain();

    void runScript();

    /** 每 1000 個指令檢查一次是否被叫停；被叫停就把腳本中斷掉。 */
    static void stopHook(lua_State *L, lua_Debug *ar);

    void detachImageReader();

    std::unique_ptr<LuaEngine> luaEngine;
    std::unique_ptr<NativeImageReader> imageReader;
    std::unique_ptr<VisionMatcher> visionMatcher;

    JavaVM *javaVM = nullptr;
    JNIEnv *luaEnv = nullptr;     // 只在 Lua 執行緒上有效
    jobject hostObj = nullptr;
    jobject serviceObj = nullptr;
    HostMethods hostMethods;

    jclass doubleClass = nullptr;
    jmethodID doubleConstructor = nullptr;
    jclass booleanClass = nullptr;
    jmethodID booleanConstructor = nullptr;

    jmethodID addSurfaceMethodId = nullptr;     // IRelcV2Service.addVirtualDisplaySurface
    jmethodID removeSurfaceMethodId = nullptr;  // IRelcV2Service.removeVirtualDisplaySurface
    jmethodID surfaceReleaseMethodId = nullptr; // android.view.Surface.release

    /**
     * ANativeWindow_toSurface 產生的 Java Surface。必須留著到收尾時明確 release()——
     * 只 DeleteLocalRef 的話底層資源要等 finalizer 才回收，logcat 會出現
     * 「A resource failed to call Surface.release.」，而且每跑一次漏一個。
     */
    jobject sinkSurface = nullptr;

    std::string scriptDir;
    int displayId = -1;
    int sinkHandle = -1;
    bool visionEnabled = false;

    std::thread luaThread;
    std::atomic<bool> running{false};
    /** on_stop 收尾期間為 true——此時 sleep 立刻返回，避免收尾把停止流程拖住。 */
    std::atomic<bool> cleaningUp{false};

    std::mutex sleepMutex;
    std::condition_variable sleepCv;
};

#endif // RELC_SCRIPT_RUNTIME_H
