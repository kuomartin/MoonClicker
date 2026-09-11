#ifndef RELC_ENGINE_H
#define RELC_ENGINE_H

#include "LuaEngine.h"
#include "NativeImageReader.h"
#include <memory>
#include <vector>
#include <string>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <jni.h>
#include <opencv2/core.hpp>
#include <thread>
#include <mutex>
#include <condition_variable>
#include <atomic>
#include <unordered_map>
#include <queue>

struct SearchTemplate {
    std::string name;
    cv::Mat image;
    double threshold;
    bool enabled;
    bool grayscale;
    cv::Rect roi;
};

struct MatchResultItem {
    std::string name;
    bool found;
    double x;
    double y;
    double width;
    double height;
    double confidence;
};

struct FrameResult {
    std::vector<MatchResultItem> matches;
};

class RelcEngine {
public:
    RelcEngine(JNIEnv *env, jobject service, jobject detector);

    ~RelcEngine();

    bool start(int displayId, int width, int height, const std::string &scriptPath);

    void stop();

    ANativeWindow *getWindow();

    // Engine state event upcall (see LuaNative.onEngineEvent / EngineEventType)
    void pushEngineEvent(int type, const std::string &payload);

    /**
     * 記下虛擬顯示當前的 rotation（Surface.ROTATION_*，0..3），由 app 進程的 DisplayListener 推入。
     *
     * **這不會旋轉任何顯示**——與 IRelcV2Service.setDisplayRotation 名字相近但語意相反，
     * 後者才是真的去旋轉。這裡只是記錄供座標轉換使用。
     *
     * 影格在 **surface 空間**（虛擬顯示建立時的尺寸，內容被旋轉「進」其中），而
     * injectMotionEvent 使用的是**邏輯空間**；兩者差這個旋轉。見 issue #19。
     */
    void onDisplayRotationChanged(int rotation);

    // Helpers for Lua callbacks
    bool multiTouchSwipe(int pointerId, const std::vector<int> &points, long duration, bool keep);

    bool createVirtualDisplay(int width, int height, int densityDpi, int flags);

    int addVirtualDisplaySurface(int targetDisplayId, jobject surface);

    bool removeVirtualDisplaySurface(int targetDisplayId, int handle);

    bool destroyVirtualDisplay();

    bool launchInDisplay(const std::string &packageName, int targetDisplayId);

    std::vector<int> getVirtualDisplays();

    bool isEngineRunning() const { return isRunning; }

private:
    void processFrame(const cv::Mat &frame);

    void parseConfigFromLua();

    void luaThreadLoop(const std::string &scriptPath);

    static void lua_stop_hook(lua_State *L, lua_Debug *ar);

    // JNI Upcalls for Lua API
    static int lua_swipe(lua_State *L);

    static int lua_log(lua_State *L);

    static int lua_display_create(lua_State *L);

    static int lua_display_launch(lua_State *L);

    static int lua_display_get_all(lua_State *L);

    static int lua_match_wait(lua_State *L);

    static int lua_match_set_enabled(lua_State *L);

    static int lua_match_enable(lua_State *L);

    static int lua_match_disable(lua_State *L);

    // Lua UI API

    // Bridge API
    static int lua_bridge_set(lua_State *L);

    // Klick'r equivalent APIs
    static int lua_input_click(lua_State *L);
    static int lua_system_action(lua_State *L); // handles home, back, recents
    static int lua_system_startIntent(lua_State *L);
    static int lua_system_notification(lua_State *L);
    static int lua_wait(lua_State *L);
    static int lua_screen_findImage(lua_State *L);

    std::unique_ptr<LuaEngine> luaEngine;
    std::unique_ptr<NativeImageReader> imageReader;

    std::string scriptPath;
    std::string scriptDir;

    // 影格（surface 空間）的尺寸，即 AImageReader 的建立尺寸。
    int frameWidth = 0;
    int frameHeight = 0;
    std::atomic<int> displayRotation{0};

    /** surface(影格) 空間 → 邏輯空間。 */
    void frameToLogical(double fx, double fy, double &lx, double &ly) const;

    /** 邏輯空間 → surface(影格) 空間。ROI 由腳本以邏輯座標指定，需反向轉回影格。 */
    void logicalToFrame(double lx, double ly, double &fx, double &fy) const;

    double imageScale;
    int tickIntervalMs;
    long tickNum;

    JavaVM *javaVM;
    jobject serviceObj;

    jmethodID swipeMethodId;
    jmethodID createVirtualDisplayMethodId;
    jmethodID addVirtualDisplaySurfaceMethodId;
    jmethodID removeVirtualDisplaySurfaceMethodId;
    jmethodID destroyVirtualDisplayMethodId;
    jmethodID launchInDisplayMethodId;
    jmethodID getVirtualDisplaysMethodId;
    
    // UI Upcalls
    jobject luaNativeObj;
    jmethodID setSharedDataMethodId;
    jmethodID onEngineEventMethodId;
    
    jmethodID showNotificationMethodId;
    jmethodID startIntentMethodId;
    jmethodID systemActionMethodId;

    jclass doubleClass;
    jmethodID doubleConstructor;
    jclass booleanClass;
    jmethodID booleanConstructor;

    int displayId;
    int sinkHandle;

    std::vector<SearchTemplate> templates;
    std::unordered_map<std::string, cv::Mat> templateCache;

    std::thread luaThread;
    std::mutex resultMutex;
    FrameResult latestResult;
    std::atomic<bool> isRunning;
};

#endif // RELC_ENGINE_H
