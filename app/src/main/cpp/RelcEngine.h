#ifndef RELC_ENGINE_H
#define RELC_ENGINE_H

#include "LuaEngine.h"
#include "NativeImageReader.h"
#include <memory>
#include <vector>
#include <string>
#include <jni.h>
#include <opencv2/core.hpp>

struct SearchTemplate {
    std::string name;
    cv::Mat image;
    double threshold;
};

class RelcEngine {
public:
    RelcEngine(JNIEnv* env, jobject service);
    ~RelcEngine();

    bool start(int width, int height, const std::string& script);
    void stop();

    ANativeWindow* getWindow();

    // Helper for Lua callbacks
    bool multiTouchSwipe(int pointerId, const std::vector<int>& points, long duration, bool keep);

private:
    void processFrame(const cv::Mat& frame);
    void updateTemplatesFromLua();
    
    // JNI Upcalls for Lua API
    static int lua_swipe(lua_State* L);
    static int lua_log(lua_State* L);

    std::unique_ptr<LuaEngine> luaEngine;
    std::unique_ptr<NativeImageReader> imageReader;
    
    JavaVM* javaVM;
    jobject serviceObj;
    
    jmethodID swipeMethodId;
    int displayId;

    std::vector<SearchTemplate> templates;
};

#endif // RELC_ENGINE_H
