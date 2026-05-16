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

struct SearchTemplate {
    std::string name;
    cv::Mat image;
    double threshold;
};

struct MatchResultItem {
    std::string name;
    bool found;
    double x;
    double y;
    double confidence;
};

struct FrameResult {
    std::vector<MatchResultItem> matches;
};

class RelcEngine {
public:
    RelcEngine(JNIEnv *env, jobject service);

    ~RelcEngine();

    bool start(int width, int height, const std::string &script);

    void stop();

    ANativeWindow *getWindow();

    void setPreviewSurface(JNIEnv *env, jobject surface);

    // Helpers for Lua callbacks
    bool multiTouchSwipe(int pointerId, const std::vector<int> &points, long duration, bool keep);

    bool createVirtualDisplay(int width, int height, int densityDpi, int flags);

    bool destroyVirtualDisplay();

    bool launchInDisplay(const std::string &packageName, int targetDisplayId);

    std::vector<int> getVirtualDisplays();

    bool isEngineRunning() const { return isRunning; }

private:
    void processFrame(const cv::Mat &frame);

    void updateTemplatesFromLua();

    void luaThreadLoop(const std::string &script);

    static void lua_stop_hook(lua_State *L, lua_Debug *ar);

    // JNI Upcalls for Lua API
    static int lua_swipe(lua_State *L);

    static int lua_log(lua_State *L);

    static int lua_display_create(lua_State *L);

    static int lua_display_launch(lua_State *L);

    static int lua_display_get_all(lua_State *L);

    static int lua_match_wait(lua_State *L);

    std::unique_ptr<LuaEngine> luaEngine;
    std::unique_ptr<NativeImageReader> imageReader;

    JavaVM *javaVM;
    jobject serviceObj;

    jmethodID swipeMethodId;
    jmethodID createVirtualDisplayMethodId;
    jmethodID destroyVirtualDisplayMethodId;
    jmethodID launchInDisplayMethodId;
    jmethodID getVirtualDisplaysMethodId;
    int displayId;

    std::vector<SearchTemplate> templates;
    std::unordered_map<std::string, cv::Mat> templateCache;

    std::thread luaThread;
    std::mutex resultMutex;
    FrameResult latestResult;
    std::atomic<bool> isRunning;

    ANativeWindow *previewWindow;
    std::mutex previewMutex;
};

#endif // RELC_ENGINE_H
