#include "RelcEngine.h"
#include <opencv2/imgproc.hpp>
#include <opencv2/imgcodecs.hpp>
#include <android/native_window_jni.h>
#include <android/log.h>
#include <sstream>

// Must match com.xaxaxax.relc.engine.state.EngineEventType
namespace EngineEventType {
    constexpr int RUNNING = 0;
    constexpr int FINISHED = 1;
    constexpr int ERROR = 2;
    constexpr int STOPPED = 3;
    constexpr int MATCH_RESULT = 4;
}

namespace {
    std::string jsonEscape(const std::string &s) {
        std::string out;
        out.reserve(s.size());
        for (char c: s) {
            if (c == '"' || c == '\\') out += '\\';
            out += c;
        }
        return out;
    }

    std::string matchResultsToJson(const std::vector<MatchResultItem> &matches) {
        std::ostringstream out;
        out << '[';
        for (size_t i = 0; i < matches.size(); ++i) {
            const auto &m = matches[i];
            if (i > 0) out << ',';
            out << "{\"name\":\"" << jsonEscape(m.name) << "\",\"found\":"
                << (m.found ? "true" : "false")
                << ",\"x\":" << m.x << ",\"y\":" << m.y
                << ",\"width\":" << m.width << ",\"height\":" << m.height
                << ",\"confidence\":" << m.confidence << '}';
        }
        out << ']';
        return out.str();
    }
}

RelcEngine::RelcEngine(JNIEnv *env, jobject service, jobject detector) : javaVM(nullptr),
                                                                         displayId(-1),
                                                                         sinkHandle(-1),
                                                                         isRunning(false),
                                                                         imageScale(1.0),
                                                                         tickIntervalMs(66),
                                                                         tickNum(0) {
    env->GetJavaVM(&javaVM);
    serviceObj = env->NewGlobalRef(service);
    luaNativeObj = env->NewGlobalRef(detector); // detector is thiz from startEngine

    jclass serviceClass = env->GetObjectClass(serviceObj);
    // V2 Method: multiTouchSwipe(int pointerId, int displayId, int[] points, long duration, boolean keep)
    swipeMethodId = env->GetMethodID(serviceClass, "multiTouchSwipe", "(II[IJZ)V");
    createVirtualDisplayMethodId = env->GetMethodID(serviceClass, "createVirtualDisplay",
                                                    "(Ljava/lang/String;IIII)I");
    addVirtualDisplaySurfaceMethodId = env->GetMethodID(serviceClass, "addVirtualDisplaySurface",
                                                        "(ILandroid/view/Surface;)I");
    removeVirtualDisplaySurfaceMethodId = env->GetMethodID(serviceClass,
                                                           "removeVirtualDisplaySurface", "(II)Z");
    destroyVirtualDisplayMethodId = env->GetMethodID(serviceClass, "destroyVirtualDisplay", "(I)Z");
    launchInDisplayMethodId = env->GetMethodID(serviceClass, "launchInDisplay",
                                               "(Ljava/lang/String;I)Z");
    getVirtualDisplaysMethodId = env->GetMethodID(serviceClass, "getVirtualDisplays", "()[I");

    jclass luaNativeClass = env->GetObjectClass(luaNativeObj);
    setSharedDataMethodId = env->GetMethodID(luaNativeClass, "setSharedData",
                                             "(Ljava/lang/String;Ljava/lang/Object;)V");
    onEngineEventMethodId = env->GetMethodID(luaNativeClass, "onEngineEvent",
                                             "(ILjava/lang/String;)V");
    showNotificationMethodId = env->GetMethodID(luaNativeClass, "showNotification",
                                                "(Ljava/lang/String;Ljava/lang/String;)V");
    startIntentMethodId = env->GetMethodID(luaNativeClass, "startIntent", "(Ljava/lang/String;)V");
    systemActionMethodId = env->GetMethodID(luaNativeClass, "systemAction",
                                            "(Ljava/lang/String;)V");

    jclass localDoubleClass = env->FindClass("java/lang/Double");
    doubleClass = (jclass) env->NewGlobalRef(localDoubleClass);
    doubleConstructor = env->GetMethodID(doubleClass, "<init>", "(D)V");

    jclass localBooleanClass = env->FindClass("java/lang/Boolean");
    booleanClass = (jclass) env->NewGlobalRef(localBooleanClass);
    booleanConstructor = env->GetMethodID(booleanClass, "<init>", "(Z)V");
}

RelcEngine::~RelcEngine() {
    stop();
    JNIEnv *env;
    if (javaVM->GetEnv((void **) &env, JNI_VERSION_1_6) == JNI_OK) {
        env->DeleteGlobalRef(serviceObj);
        env->DeleteGlobalRef(luaNativeObj);
        env->DeleteGlobalRef(doubleClass);
        env->DeleteGlobalRef(booleanClass);
    }
}

bool RelcEngine::start(int displayId, int width, int height, const std::string &scriptPath) {
    this->displayId = displayId;
    this->scriptPath = scriptPath;
    size_t lastSlash = scriptPath.find_last_of("/\\");
    if (lastSlash != std::string::npos) {
        this->scriptDir = scriptPath.substr(0, lastSlash + 1);
    } else {
        this->scriptDir = "./";
    }

    luaEngine = std::make_unique<LuaEngine>();
    if (!luaEngine->init()) return false;

    lua_State *L = luaEngine->getLuaState();

    // Store 'this' in registry for hook to access
    lua_pushlightuserdata(L, this);
    lua_setfield(L, LUA_REGISTRYINDEX, "RelcEngineInstance");

    // Global log function
    lua_pushlightuserdata(L, this);
    lua_pushcclosure(L, lua_log, 1);
    lua_setglobal(L, "log");

    // input.swipe
    lua_getglobal(L, "input");
    lua_pushlightuserdata(L, this);
    lua_pushcclosure(L, lua_swipe, 1);
    lua_setfield(L, -2, "swipe");

    lua_pushlightuserdata(L, this);
    lua_pushcclosure(L, lua_input_click, 1);
    lua_setfield(L, -2, "click");
    lua_pop(L, 1);

    // system module
    lua_newtable(L);
    lua_pushlightuserdata(L, this);
    lua_pushcclosure(L, lua_system_action, 1);
    lua_setfield(L, -2, "action");

    lua_pushlightuserdata(L, this);
    lua_pushcclosure(L, lua_system_startIntent, 1);
    lua_setfield(L, -2, "startIntent");

    lua_pushlightuserdata(L, this);
    lua_pushcclosure(L, lua_system_notification, 1);
    lua_setfield(L, -2, "notification");
    lua_setglobal(L, "system");

    // wait function
    lua_pushlightuserdata(L, this);
    lua_pushcclosure(L, lua_wait, 1);
    lua_setglobal(L, "wait");

    // screen module
    lua_newtable(L);
    lua_pushlightuserdata(L, this);
    lua_pushcclosure(L, lua_screen_findImage, 1);
    lua_setfield(L, -2, "findImage");
    lua_setglobal(L, "screen");

    // match.wait
    lua_getglobal(L, "match");
    lua_pushlightuserdata(L, this);
    lua_pushcclosure(L, lua_match_wait, 1);
    lua_setfield(L, -2, "wait");

    lua_pushlightuserdata(L, this);
    lua_pushcclosure(L, lua_match_enable, 1);
    lua_setfield(L, -2, "enable");

    lua_pushlightuserdata(L, this);
    lua_pushcclosure(L, lua_match_disable, 1);
    lua_setfield(L, -2, "disable");

    lua_pushlightuserdata(L, this);
    lua_pushcclosure(L, lua_match_set_enabled, 1);
    lua_setfield(L, -2, "set_enabled");

    lua_pop(L, 1);

    // display.create
    lua_newtable(L);
    lua_pushlightuserdata(L, this);
    lua_pushcclosure(L, lua_display_create, 1);
    lua_setfield(L, -2, "create");

    // display.launch
    lua_pushlightuserdata(L, this);
    lua_pushcclosure(L, lua_display_launch, 1);
    lua_setfield(L, -2, "launch");

    // display.get_all
    lua_pushlightuserdata(L, this);
    lua_pushcclosure(L, lua_display_get_all, 1);
    lua_setfield(L, -2, "get_all");

    lua_setglobal(L, "display");

    // app module
    lua_newtable(L);
    lua_pushlightuserdata(L, this);
    lua_pushcclosure(L, lua_bridge_set, 1);
    lua_setfield(L, -2, "set_data");
    lua_setglobal(L, "app");

    frameWidth = width;
    frameHeight = height;
    imageReader = std::make_unique<NativeImageReader>(width, height);
    if (!imageReader->init()) return false;

    imageReader->setCallback([this](const cv::Mat &frame) {
        if (!isRunning) return;
        processFrame(frame);
    });

    if (displayId != -1) {
        JNIEnv *env;
        bool attached = false;
        int res = javaVM->GetEnv((void **) &env, JNI_VERSION_1_6);
        if (res == JNI_EDETACHED) {
            if (javaVM->AttachCurrentThread(&env, nullptr) == JNI_OK) attached = true;
        }

        if (res == JNI_OK || attached) {
            ANativeWindow *window = imageReader->getWindow();
            jobject surface = ANativeWindow_toSurface(env, window);
            sinkHandle = addVirtualDisplaySurface(displayId, surface);
            env->DeleteLocalRef(surface);
            if (attached) javaVM->DetachCurrentThread();
        }
    }

    isRunning = true;
    luaThread = std::thread(&RelcEngine::luaThreadLoop, this, scriptPath);

    return true;
}

void RelcEngine::pushEngineEvent(int type, const std::string &payload) {
    JNIEnv *env;
    bool attached = false;
    if (javaVM->GetEnv((void **) &env, JNI_VERSION_1_6) == JNI_EDETACHED) {
        if (javaVM->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
        attached = true;
    }

    jstring jPayload = env->NewStringUTF(payload.c_str());
    env->CallVoidMethod(luaNativeObj, onEngineEventMethodId, type, jPayload);
    env->DeleteLocalRef(jPayload);

    if (attached) javaVM->DetachCurrentThread();
}

void RelcEngine::luaThreadLoop(const std::string &scriptPath) {
    JNIEnv *env;
    if (javaVM->AttachCurrentThread(&env, nullptr) != JNI_OK) {
        LOGE("Failed to attach lua thread to JVM");
        isRunning = false;
        return;
    }

    if (!luaEngine->loadFile(scriptPath)) {
        isRunning = false;
        pushEngineEvent(EngineEventType::ERROR, "Failed to load script: " + scriptPath);
        javaVM->DetachCurrentThread();
        return;
    }

    lua_State *coL = luaEngine->getCoroutineState();
    if (!coL) {
        isRunning = false;
        pushEngineEvent(EngineEventType::ERROR, "Failed to obtain Lua coroutine state");
        javaVM->DetachCurrentThread();
        return;
    }

    // Set a hook that runs every 1000 instructions to check if we should stop
    lua_sethook(coL, lua_stop_hook, LUA_MASKCOUNT, 1000);

    lua_State *gL = luaEngine->getLuaState();

    // 1. Initial start to execute top-level (config, etc.)
    int nres = 0;
    int status = lua_resume(coL, gL, 0, &nres);
    if (status != LUA_OK && status != LUA_YIELD) {
        std::string message = lua_tostring(coL, -1) ? lua_tostring(coL, -1) : "unknown error";
        LOGE("Script execution error (init): %s", message.c_str());
        isRunning = false;
        pushEngineEvent(EngineEventType::ERROR, message);
        javaVM->DetachCurrentThread();
        return;
    }

    // 2. Parse config
    parseConfigFromLua();

    // 3. Check if on_start is defined and call it
    lua_getglobal(gL, "on_start");
    if (lua_isfunction(gL, -1)) {
        if (lua_pcall(gL, 0, 0, 0) != LUA_OK) {
            LOGE("on_start error: %s", lua_tostring(gL, -1));
            lua_pop(gL, 1);
        }
    } else {
        lua_pop(gL, 1);
    }

    pushEngineEvent(EngineEventType::RUNNING, "");

    // Main Ticker Loop
    bool exitedByErrorOrFinish = false;
    auto lastTickTime = std::chrono::steady_clock::now();
    while (isRunning) {
        auto start = std::chrono::steady_clock::now();
        auto interval = std::chrono::duration_cast<std::chrono::milliseconds>(
                start - lastTickTime).count();
        if (interval > 100) {
            LOGD("Tick interval spike: %lld ms", (long long) interval);
        }
        lastTickTime = start;

        tickNum++;

        // 4. Process on_tick(matches, tick_num)
        lua_getglobal(gL, "on_tick");
        if (lua_isfunction(gL, -1)) {
            // matches table
            lua_newtable(gL);
            std::vector<MatchResultItem> matchesCopy;
            {
                std::lock_guard<std::mutex> lock(resultMutex);
                matchesCopy = latestResult.matches;
            }

            if (!matchesCopy.empty()) {
                pushEngineEvent(EngineEventType::MATCH_RESULT, matchResultsToJson(matchesCopy));
            }

            for (const auto &m: matchesCopy) {
                lua_newtable(gL);
                lua_pushboolean(gL, true);
                lua_setfield(gL, -2, "found");
                lua_pushnumber(gL, m.x);
                lua_setfield(gL, -2, "x");
                lua_pushnumber(gL, m.y);
                lua_setfield(gL, -2, "y");
                lua_pushnumber(gL, m.width);
                lua_setfield(gL, -2, "width");
                lua_pushnumber(gL, m.height);
                lua_setfield(gL, -2, "height");
                lua_pushnumber(gL, m.confidence);
                lua_setfield(gL, -2, "confidence");

                lua_setfield(gL, -2, m.name.c_str());
            }

            // tick_num
            lua_pushinteger(gL, tickNum);

            if (lua_pcall(gL, 2, 0, 0) != LUA_OK) {
                LOGE("on_tick error: %s", lua_tostring(gL, -1));
                lua_pop(gL, 1);
            }
        } else {
            lua_pop(gL, 1);
        }

        // 5. Resume main coroutine if it yielded
        if (lua_status(coL) == LUA_YIELD) {
            int nres_loop = 0;
            int resume_status = lua_resume(coL, gL, 0, &nres_loop);

            if (resume_status != LUA_OK && resume_status != LUA_YIELD) {
                std::string message = lua_tostring(coL, -1) ? lua_tostring(coL, -1) : "unknown error";
                LOGE("Script execution error: %s", message.c_str());
                isRunning = false;
                pushEngineEvent(EngineEventType::ERROR, message);
                exitedByErrorOrFinish = true;
                break;
            }
        }

        // 6. Check if we should exit
        if (lua_status(coL) == LUA_OK) {
            lua_getglobal(gL, "on_tick");
            bool hasOnTick = lua_isfunction(gL, -1);
            lua_pop(gL, 1);
            if (!hasOnTick) {
                LOGD("Script finished execution");
                isRunning = false;
                pushEngineEvent(EngineEventType::FINISHED, "");
                exitedByErrorOrFinish = true;
                break;
            }
        }

        auto end = std::chrono::steady_clock::now();
        auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(end - start);
        if (elapsed.count() < tickIntervalMs) {
            std::this_thread::sleep_for(
                    std::chrono::milliseconds(tickIntervalMs - elapsed.count()));
        }
    }
    if (!exitedByErrorOrFinish) {
        // Loop condition (isRunning) went false without hitting the error/finish
        // breaks above, meaning stop() was called externally.
        pushEngineEvent(EngineEventType::STOPPED, "");
    }
    javaVM->DetachCurrentThread();
}

void RelcEngine::lua_stop_hook(lua_State *L, lua_Debug *ar) {
    lua_getfield(L, LUA_REGISTRYINDEX, "RelcEngineInstance");
    auto *self = static_cast<RelcEngine *>(lua_touserdata(L, -1));
    lua_pop(L, 1);

    if (self && !self->isRunning) {
        luaL_error(L, "Script terminated by user");
    }
}

void RelcEngine::stop() {
    isRunning = false;
    if (luaThread.joinable()) {
        luaThread.join();
    }

    destroyVirtualDisplay();

    if (imageReader) imageReader->release();
    if (luaEngine) luaEngine->stop();
}

bool RelcEngine::destroyVirtualDisplay() {
    if (displayId == -1) return false;

    JNIEnv *env;
    bool attached = false;
    int res = javaVM->GetEnv((void **) &env, JNI_VERSION_1_6);
    if (res == JNI_EDETACHED) {
        if (javaVM->AttachCurrentThread(&env, nullptr) != JNI_OK) return false;
        attached = true;
    }

    if (sinkHandle != -1) {
        removeVirtualDisplaySurface(displayId, sinkHandle);
        sinkHandle = -1;
    }

    jboolean ok = env->CallBooleanMethod(serviceObj, destroyVirtualDisplayMethodId, displayId);
    displayId = -1;

    if (attached) javaVM->DetachCurrentThread();
    return static_cast<bool>(ok);
}

void RelcEngine::setDisplayRotation(int rotation) {
    displayRotation.store(rotation & 3);
}

// 影格在 surface 空間、內容被旋轉「進」其中；邏輯空間才是 injectMotionEvent 的座標系。
// 旋轉方向與畫面側套用的 -(rotation * 90) 反向旋轉一致（見 :app 的 Viewport）。
void RelcEngine::frameToLogical(double fx, double fy, double &lx, double &ly) const {
    switch (displayRotation.load()) {
        case 1:
            lx = fy;
            ly = frameWidth - fx;
            break;
        case 2:
            lx = frameWidth - fx;
            ly = frameHeight - fy;
            break;
        case 3:
            lx = frameHeight - fy;
            ly = fx;
            break;
        default:
            lx = fx;
            ly = fy;
            break;
    }
}

void RelcEngine::logicalToFrame(double lx, double ly, double &fx, double &fy) const {
    switch (displayRotation.load()) {
        case 1:
            fx = frameWidth - ly;
            fy = lx;
            break;
        case 2:
            fx = frameWidth - lx;
            fy = frameHeight - ly;
            break;
        case 3:
            fx = ly;
            fy = frameHeight - lx;
            break;
        default:
            fx = lx;
            fy = ly;
            break;
    }
}

ANativeWindow *RelcEngine::getWindow() {
    return imageReader ? imageReader->getWindow() : nullptr;
}

void RelcEngine::parseConfigFromLua() {
    lua_State *L = luaEngine->getLuaState();

    // Helper to parse templates from a table at top of stack
    auto parseTemplates = [&](int tableIdx) {
        if (!lua_istable(L, tableIdx)) return;

        std::vector<SearchTemplate> newTemplates;
        auto n = static_cast<int>(lua_rawlen(L, tableIdx));
        for (int i = 1; i <= n; i++) {
            lua_rawgeti(L, tableIdx, i);
            if (lua_istable(L, -1)) {
                SearchTemplate t;

                // 1. name
                lua_getfield(L, -1, "name");
                const char *nameStr = lua_tostring(L, -1);
                if (nameStr) t.name = nameStr;
                lua_pop(L, 1);

                if (t.name.empty()) {
                    lua_pop(L, 1);
                    continue;
                }

                // 2. img / path
                std::string imgFileName;
                lua_getfield(L, -1, "img");
                if (lua_isstring(L, -1)) {
                    imgFileName = lua_tostring(L, -1);
                } else {
                    lua_pop(L, 1);
                    lua_getfield(L, -1, "path");
                    if (lua_isstring(L, -1)) {
                        imgFileName = lua_tostring(L, -1);
                    }
                }
                lua_pop(L, 1);

                if (imgFileName.empty()) {
                    imgFileName = t.name + ".png";
                }

                // 3. mask (roi)
                lua_getfield(L, -1, "mask");
                if (lua_istable(L, -1)) {
                    lua_rawgeti(L, -1, 1);
                    t.roi.x = static_cast<int>(lua_tointeger(L, -1));
                    lua_pop(L, 1);
                    lua_rawgeti(L, -1, 2);
                    t.roi.y = static_cast<int>(lua_tointeger(L, -1));
                    lua_pop(L, 1);
                    lua_rawgeti(L, -1, 3);
                    t.roi.width = static_cast<int>(lua_tointeger(L, -1));
                    lua_pop(L, 1);
                    lua_rawgeti(L, -1, 4);
                    t.roi.height = static_cast<int>(lua_tointeger(L, -1));
                    lua_pop(L, 1);
                } else {
                    t.roi = cv::Rect(0, 0, 0, 0); // Full screen
                }
                lua_pop(L, 1);

                // 4. threshold
                lua_getfield(L, -1, "threshold");
                if (lua_isnumber(L, -1)) t.threshold = lua_tonumber(L, -1);
                else t.threshold = 0.8;
                lua_pop(L, 1);

                // 5. grayscale
                lua_getfield(L, -1, "grayscale");
                if (lua_isboolean(L, -1)) t.grayscale = lua_toboolean(L, -1);
                else t.grayscale = false;
                lua_pop(L, 1);

                // 6. enabled
                lua_getfield(L, -1, "enabled");
                if (lua_isboolean(L, -1)) t.enabled = lua_toboolean(L, -1);
                else t.enabled = true;
                lua_pop(L, 1);

                // Load image
                std::string path = scriptDir + imgFileName;
                if (templateCache.find(path) == templateCache.end()) {
                    cv::Mat img = cv::imread(path, cv::IMREAD_UNCHANGED);
                    if (!img.empty()) {
                        if (img.channels() == 3) {
                            cv::cvtColor(img, img, cv::COLOR_BGR2RGBA);
                        } else if (img.channels() == 1) {
                            cv::cvtColor(img, img, cv::COLOR_GRAY2RGBA);
                        }
                        templateCache[path] = img;
                    } else {
                        LOGE("Failed to load template image: %s", path.c_str());
                    }
                }

                if (templateCache.find(path) != templateCache.end()) {
                    t.image = templateCache[path].clone();

                    // Pre-convert to grayscale if needed
                    if (t.grayscale && t.image.channels() == 4) {
                        cv::Mat gray;
                        cv::cvtColor(t.image, gray, cv::COLOR_RGBA2GRAY);
                        t.image = gray;
                    }

                    // Pre-resize template to match the scaled frame
                    if (imageScale < 1.0) {
                        cv::Mat resized;
                        cv::resize(t.image, resized, cv::Size(), imageScale, imageScale,
                                   cv::INTER_AREA);
                        t.image = resized;
                    }

                    newTemplates.push_back(t);
                }
            }
            lua_pop(L, 1);
        }

        std::lock_guard<std::mutex> lock(resultMutex);
        templates = std::move(newTemplates);
    };

    // Try config.templates
    lua_getglobal(L, "config");
    if (lua_istable(L, -1)) {
        // Parse FPS
        lua_getfield(L, -1, "fps");
        if (lua_isnumber(L, -1)) {
            int fps = static_cast<int>(lua_tointeger(L, -1));
            if (fps > 0) tickIntervalMs = 1000 / fps;
        }
        lua_pop(L, 1);

        // Parse Scale
        lua_getfield(L, -1, "scale");
        if (lua_isnumber(L, -1)) {
            imageScale = lua_tonumber(L, -1);
            if (imageScale <= 0) imageScale = 1.0;
        }
        lua_pop(L, 1);

        lua_getfield(L, -1, "templates");
        if (lua_istable(L, -1)) {
            parseTemplates(-1);
            lua_pop(L, 2); // pop templates and config
            return;
        }
        lua_pop(L, 1); // pop templates (nil)
    }
    lua_pop(L, 1); // pop config

    // Try match.templates
    lua_getglobal(L, "match");
    if (lua_istable(L, -1)) {
        lua_getfield(L, -1, "templates");
        if (lua_istable(L, -1)) {
            parseTemplates(-1);
            lua_pop(L, 2); // pop templates and match
            return;
        }
        lua_pop(L, 1);
    }
    lua_pop(L, 1);
}

void RelcEngine::processFrame(const cv::Mat &frame) {
    auto startTime = std::chrono::steady_clock::now();
    if (!isRunning) return;

    // Frame skipping: If still processing previous frame, skip this one
    static std::atomic<bool> isProcessing(false);
    if (isProcessing.exchange(true)) return;

    // 1. Get a copy of templates to work with safely
    std::vector<SearchTemplate> templatesToProcess;
    {
        std::lock_guard<std::mutex> lock(resultMutex);
        templatesToProcess = templates;
    }

    if (templatesToProcess.empty()) {
        isProcessing = false;
        return;
    }

    FrameResult currentFrameResult;

    cv::Mat processedFrame = frame;
    if (imageScale < 1.0) {
        cv::resize(frame, processedFrame, cv::Size(), imageScale, imageScale, cv::INTER_LINEAR);
    }

    // Optimization: Pre-convert to grayscale once if any template needs it
    cv::Mat processedFrameGray;
    bool grayConverted = false;

    for (const auto &t: templatesToProcess) {
        if (!t.enabled || t.image.empty()) continue;

        cv::Mat target;
        int offsetX = 0;
        int offsetY = 0;

        // Determine the target matrix (color or grayscale)
        cv::Mat baseFrame;
        if (t.grayscale) {
            if (!grayConverted) {
                if (processedFrame.channels() == 4) {
                    cv::cvtColor(processedFrame, processedFrameGray, cv::COLOR_RGBA2GRAY);
                } else if (processedFrame.channels() == 3) {
                    cv::cvtColor(processedFrame, processedFrameGray, cv::COLOR_RGB2GRAY);
                } else {
                    processedFrameGray = processedFrame;
                }
                grayConverted = true;
            }
            baseFrame = processedFrameGray;
        } else {
            baseFrame = processedFrame;
        }

        // Apply ROI if specified (adjust ROI to scale)
        if (t.roi.width > 0 && t.roi.height > 0) {
            // ROI 由腳本以**邏輯**座標指定，先轉回影格空間再套用縮放（#19）。
            double rx0, ry0, rx1, ry1;
            logicalToFrame(t.roi.x, t.roi.y, rx0, ry0);
            logicalToFrame(t.roi.x + t.roi.width, t.roi.y + t.roi.height, rx1, ry1);
            double frameRoiX = std::min(rx0, rx1);
            double frameRoiY = std::min(ry0, ry1);
            double frameRoiW = std::abs(rx1 - rx0);
            double frameRoiH = std::abs(ry1 - ry0);

            int x = std::max(0, (int) (frameRoiX * imageScale));
            int y = std::max(0, (int) (frameRoiY * imageScale));
            int w = std::min((int) (frameRoiW * imageScale), baseFrame.cols - x);
            int h = std::min((int) (frameRoiH * imageScale), baseFrame.rows - y);

            // Ensure ROI is large enough for the template
            if (w < t.image.cols || h < t.image.rows) continue;

            target = baseFrame(cv::Rect(x, y, w, h));
            offsetX = x;
            offsetY = y;
        } else {
            target = baseFrame;
        }

        if (target.cols < t.image.cols || target.rows < t.image.rows) continue;

        cv::Mat result;
        cv::matchTemplate(target, t.image, result, cv::TM_CCOEFF_NORMED);

        double minVal, maxVal;
        cv::Point minLoc, maxLoc;
        cv::minMaxLoc(result, &minVal, &maxVal, &minLoc, &maxLoc);

        if (maxVal >= t.threshold) {
            MatchResultItem item;
            item.name = t.name;
            item.found = true;
            // Scale results back to original size
            // 影格座標 → 邏輯座標，讓 match 的結果可以直接餵給 tap（#19）。
            double frameX = (maxLoc.x + t.image.cols / 2.0 + offsetX) / imageScale;
            double frameY = (maxLoc.y + t.image.rows / 2.0 + offsetY) / imageScale;
            frameToLogical(frameX, frameY, item.x, item.y);

            double matchWidth = t.image.cols / imageScale;
            double matchHeight = t.image.rows / imageScale;
            bool turned = (displayRotation.load() & 1) != 0;
            item.width = turned ? matchHeight : matchWidth;
            item.height = turned ? matchWidth : matchHeight;
            item.confidence = maxVal;
            currentFrameResult.matches.push_back(item);
        }
    }

    // 2. Update the shared result with a brief lock
    {
        std::lock_guard<std::mutex> lock(resultMutex);
        latestResult = std::move(currentFrameResult);
    }

    auto endTime = std::chrono::steady_clock::now();
    auto duration = std::chrono::duration_cast<std::chrono::milliseconds>(
            endTime - startTime).count();
    if (duration > 33) { // Log if processing takes longer than ~30FPS frame time
        LOGD("processFrame duration: %lld ms", (long long) duration);
    }

    isProcessing = false;
}

bool RelcEngine::createVirtualDisplay(int width, int height, int densityDpi, int flags) {
    JNIEnv *env;
    bool attached = false;
    int res = javaVM->GetEnv((void **) &env, JNI_VERSION_1_6);
    if (res == JNI_EDETACHED) {
        if (javaVM->AttachCurrentThread(&env, nullptr) != JNI_OK) return false;
        attached = true;
    }

    // Call service to create display without surface
    jstring name = env->NewStringUTF("RelcLuaDisplay");
    displayId = env->CallIntMethod(serviceObj, createVirtualDisplayMethodId, name, width, height,
                                   densityDpi, flags);
    env->DeleteLocalRef(name);

    if (displayId != -1 && imageReader) {
        // Now add our own ImageReader surface as a sink
        ANativeWindow *window = imageReader->getWindow();
        jobject surface = ANativeWindow_toSurface(env, window);
        sinkHandle = addVirtualDisplaySurface(displayId, surface);
        env->DeleteLocalRef(surface);
    }

    if (attached) javaVM->DetachCurrentThread();
    return displayId != -1;
}

int RelcEngine::addVirtualDisplaySurface(int targetDisplayId, jobject surface) {
    JNIEnv *env;
    bool attached = false;
    int res = javaVM->GetEnv((void **) &env, JNI_VERSION_1_6);
    if (res == JNI_EDETACHED) {
        if (javaVM->AttachCurrentThread(&env, nullptr) != JNI_OK) return -1;
        attached = true;
    }

    int handle = env->CallIntMethod(serviceObj, addVirtualDisplaySurfaceMethodId, targetDisplayId,
                                    surface);

    if (attached) javaVM->DetachCurrentThread();
    return handle;
}

bool RelcEngine::removeVirtualDisplaySurface(int targetDisplayId, int handle) {
    JNIEnv *env;
    bool attached = false;
    int res = javaVM->GetEnv((void **) &env, JNI_VERSION_1_6);
    if (res == JNI_EDETACHED) {
        if (javaVM->AttachCurrentThread(&env, nullptr) != JNI_OK) return false;
        attached = true;
    }

    jboolean ok = env->CallBooleanMethod(serviceObj, removeVirtualDisplaySurfaceMethodId,
                                         targetDisplayId, handle);

    if (attached) javaVM->DetachCurrentThread();
    return static_cast<bool>(ok);
}

int RelcEngine::lua_display_create(lua_State *L) {
    auto *self = static_cast<RelcEngine *>(lua_touserdata(L, lua_upvalueindex(1)));
    auto width = static_cast<int>(luaL_checkinteger(L, 1));
    auto height = static_cast<int>(luaL_checkinteger(L, 2));
    auto densityDpi = static_cast<int>(luaL_optinteger(L, 3, 440));
    auto flags = static_cast<int>(luaL_optinteger(L, 4, 16));

    bool success = self->createVirtualDisplay(width, height, densityDpi, flags);
    if (success) {
        lua_pushinteger(L, self->displayId);
    } else {
        lua_pushnil(L);
    }
    return 1;
}

bool RelcEngine::launchInDisplay(const std::string &packageName, int targetDisplayId) {
    JNIEnv *env;
    bool attached = false;
    int res = javaVM->GetEnv((void **) &env, JNI_VERSION_1_6);
    if (res == JNI_EDETACHED) {
        if (javaVM->AttachCurrentThread(&env, nullptr) != JNI_OK) return false;
        attached = true;
    }

    jstring pkg = env->NewStringUTF(packageName.c_str());
    jboolean success = env->CallBooleanMethod(serviceObj, launchInDisplayMethodId, pkg,
                                              targetDisplayId);
    env->DeleteLocalRef(pkg);

    if (attached) javaVM->DetachCurrentThread();
    return static_cast<bool>(success);
}

int RelcEngine::lua_display_launch(lua_State *L) {
    auto *self = static_cast<RelcEngine *>(lua_touserdata(L, lua_upvalueindex(1)));
    const char *packageName = luaL_checkstring(L, 1);
    auto dId = static_cast<int>(luaL_optinteger(L, 2, self->displayId));

    bool ok = self->launchInDisplay(packageName, dId);
    lua_pushboolean(L, ok);
    return 1;
}

std::vector<int> RelcEngine::getVirtualDisplays() {
    std::vector<int> ids;
    JNIEnv *env;
    bool attached = false;
    int res = javaVM->GetEnv((void **) &env, JNI_VERSION_1_6);
    if (res == JNI_EDETACHED) {
        if (javaVM->AttachCurrentThread(&env, nullptr) != JNI_OK) return ids;
        attached = true;
    }

    auto jIds = (jintArray) env->CallObjectMethod(serviceObj, getVirtualDisplaysMethodId);
    if (jIds) {
        jsize len = env->GetArrayLength(jIds);
        jint *elements = env->GetIntArrayElements(jIds, nullptr);
        for (int i = 0; i < len; i++) {
            ids.push_back(elements[i]);
        }
        env->ReleaseIntArrayElements(jIds, elements, JNI_ABORT);
        env->DeleteLocalRef(jIds);
    }

    if (attached) javaVM->DetachCurrentThread();
    return ids;
}

int RelcEngine::lua_display_get_all(lua_State *L) {
    auto *self = static_cast<RelcEngine *>(lua_touserdata(L, lua_upvalueindex(1)));
    std::vector<int> ids = self->getVirtualDisplays();

    lua_newtable(L);
    for (size_t i = 0; i < ids.size(); i++) {
        lua_pushinteger(L, ids[i]);
        lua_rawseti(L, -2, i + 1);
    }
    return 1;
}

int RelcEngine::lua_match_wait(lua_State *L) {
    auto *self = static_cast<RelcEngine *>(lua_touserdata(L, lua_upvalueindex(1)));

    // Since we now use on_tick, lua_match_wait might be less useful, 
    // but we can still support it for yielding scripts.
    std::unique_lock<std::mutex> lock(self->resultMutex);

    if (self->latestResult.matches.empty()) {
        return lua_yield(L, 0);
    }

    if (!self->isRunning) {
        lua_pushnil(L);
        return 1;
    }

    lua_newtable(L);
    for (const auto &item: self->latestResult.matches) {
        if (item.found) {
            lua_newtable(L);
            lua_pushboolean(L, true);
            lua_setfield(L, -2, "found");
            lua_pushnumber(L, item.x);
            lua_setfield(L, -2, "x");
            lua_pushnumber(L, item.y);
            lua_setfield(L, -2, "y");
            lua_pushnumber(L, item.confidence);
            lua_setfield(L, -2, "confidence");
            lua_setfield(L, -2, item.name.c_str());
        }
    }

    return 1;
}

int RelcEngine::lua_match_set_enabled(lua_State *L) {
    auto *self = static_cast<RelcEngine *>(lua_touserdata(L, lua_upvalueindex(1)));
    const char *name = luaL_checkstring(L, 1);
    bool enabled = lua_toboolean(L, 2);

    std::lock_guard<std::mutex> lock(self->resultMutex);
    for (auto &t: self->templates) {
        if (t.name == name) {
            t.enabled = enabled;
            break;
        }
    }
    return 0;
}

int RelcEngine::lua_match_enable(lua_State *L) {
    lua_pushboolean(L, true);
    lua_insert(L, 2);
    return lua_match_set_enabled(L);
}

int RelcEngine::lua_match_disable(lua_State *L) {
    lua_pushboolean(L, false);
    lua_insert(L, 2);
    return lua_match_set_enabled(L);
}

bool RelcEngine::multiTouchSwipe(int pointerId, const std::vector<int> &points, long duration,
                                 bool keep) {
    JNIEnv *env;
    bool attached = false;
    int res = javaVM->GetEnv((void **) &env, JNI_VERSION_1_6);
    if (res == JNI_EDETACHED) {
        if (javaVM->AttachCurrentThread(&env, nullptr) != JNI_OK) return false;
        attached = true;
    }

    auto size = static_cast<jsize>(points.size());
    jintArray jPoints = env->NewIntArray(size);
    env->SetIntArrayRegion(jPoints, 0, size, points.data());

    env->CallVoidMethod(serviceObj, swipeMethodId, pointerId, displayId, jPoints, (jlong) duration,
                        (jboolean) keep);

    env->DeleteLocalRef(jPoints);

    if (attached) javaVM->DetachCurrentThread();
    return true;
}

int RelcEngine::lua_swipe(lua_State *L) {
    auto *self = static_cast<RelcEngine *>(lua_touserdata(L, lua_upvalueindex(1)));

    auto pointerId = static_cast<int>(luaL_checkinteger(L, 1));
    luaL_checktype(L, 2, LUA_TTABLE);
    auto duration = static_cast<long>(luaL_optinteger(L, 3, 0));
    bool keep = static_cast<bool>(lua_toboolean(L, 4));

    std::vector<int> points;
    auto n = static_cast<int>(lua_rawlen(L, 2));
    for (int i = 1; i <= n; i++) {
        lua_rawgeti(L, 2, i);
        if (lua_istable(L, -1)) {
            auto innerN = static_cast<int>(lua_rawlen(L, -1));
            for (int j = 1; j <= innerN; j++) {
                lua_rawgeti(L, -1, j);
                points.push_back(static_cast<int>(luaL_checknumber(L, -1)));
                lua_pop(L, 1);
            }
        } else {
            points.push_back(static_cast<int>(luaL_checknumber(L, -1)));
        }
        lua_pop(L, 1);
    }

    self->multiTouchSwipe(pointerId, points, duration, keep);
    return 0;
}

int RelcEngine::lua_log(lua_State *L) {
    const char *msg = luaL_checkstring(L, 1);
    __android_log_print(ANDROID_LOG_DEBUG, "LuaScript", "[NativeLog] %s", msg);
    return 0;
}

int RelcEngine::lua_bridge_set(lua_State *L) {
    auto *self = static_cast<RelcEngine *>(lua_touserdata(L, lua_upvalueindex(1)));
    const char *key = luaL_checkstring(L, 1);

    JNIEnv *env;
    bool attached = false;
    if (self->javaVM->GetEnv((void **) &env, JNI_VERSION_1_6) == JNI_EDETACHED) {
        if (self->javaVM->AttachCurrentThread(&env, nullptr) != JNI_OK) return 0;
        attached = true;
    }

    jstring jKey = env->NewStringUTF(key);
    jobject jValue = nullptr;

    int type = lua_type(L, 2);
    if (type == LUA_TNUMBER) {
        jValue = env->NewObject(self->doubleClass, self->doubleConstructor, lua_tonumber(L, 2));
    } else if (type == LUA_TSTRING) {
        jValue = env->NewStringUTF(lua_tostring(L, 2));
    } else if (type == LUA_TBOOLEAN) {
        jValue = env->NewObject(self->booleanClass, self->booleanConstructor,
                                (jboolean) lua_toboolean(L, 2));
    } else if (type == LUA_TTABLE) {
        // For tables, we use cjson to convert to string, then Kotlin can parse it or we can just pass the string.
        // But to make it truly efficient, we should probably pass a string and let Kotlin use it as a raw string 
        // or parse it if it needs to. 
        // For now, let's just pass it as a JSON string to keep it simple but functional.
        lua_getglobal(L, "cjson");
        lua_getfield(L, -1, "encode");
        lua_pushvalue(L, 2);
        if (lua_pcall(L, 1, 1, 0) == LUA_OK) {
            jValue = env->NewStringUTF(lua_tostring(L, -1));
            lua_pop(L, 2); // pop result and cjson table
        } else {
            LOGE("Failed to encode table to JSON: %s", lua_tostring(L, -1));
            lua_pop(L, 2);
        }
    }

    auto jniStartTime = std::chrono::steady_clock::now();
    env->CallVoidMethod(self->luaNativeObj, self->setSharedDataMethodId, jKey, jValue);
    auto jniEndTime = std::chrono::steady_clock::now();
    auto jniDuration = std::chrono::duration_cast<std::chrono::microseconds>(
            jniEndTime - jniStartTime).count();
    if (jniDuration > 5000) { // Log if JNI call takes > 5ms
        LOGD("setSharedData JNI duration: %lld us for key: %s", (long long) jniDuration, key);
    }

    env->DeleteLocalRef(jKey);
    if (jValue) env->DeleteLocalRef(jValue);

    if (attached) self->javaVM->DetachCurrentThread();
    return 0;
}

int RelcEngine::lua_input_click(lua_State *L) {
    auto *self = static_cast<RelcEngine *>(lua_touserdata(L, lua_upvalueindex(1)));
    auto x = static_cast<int>(luaL_checknumber(L, 1));
    auto y = static_cast<int>(luaL_checknumber(L, 2));

    std::vector<int> points = {x, y, x, y};
    self->multiTouchSwipe(1, points, 50, false);
    return 0;
}

int RelcEngine::lua_system_action(lua_State *L) {
    auto *self = static_cast<RelcEngine *>(lua_touserdata(L, lua_upvalueindex(1)));
    const char *action = luaL_checkstring(L, 1);

    JNIEnv *env;
    bool attached = false;
    if (self->javaVM->GetEnv((void **) &env, JNI_VERSION_1_6) == JNI_EDETACHED) {
        if (self->javaVM->AttachCurrentThread(&env, nullptr) != JNI_OK) return 0;
        attached = true;
    }

    jstring jAction = env->NewStringUTF(action);
    env->CallVoidMethod(self->luaNativeObj, self->systemActionMethodId, jAction);
    env->DeleteLocalRef(jAction);

    if (attached) self->javaVM->DetachCurrentThread();
    return 0;
}

int RelcEngine::lua_system_startIntent(lua_State *L) {
    auto *self = static_cast<RelcEngine *>(lua_touserdata(L, lua_upvalueindex(1)));
    const char *uri = luaL_checkstring(L, 1);

    JNIEnv *env;
    bool attached = false;
    if (self->javaVM->GetEnv((void **) &env, JNI_VERSION_1_6) == JNI_EDETACHED) {
        if (self->javaVM->AttachCurrentThread(&env, nullptr) != JNI_OK) return 0;
        attached = true;
    }

    jstring jUri = env->NewStringUTF(uri);
    env->CallVoidMethod(self->luaNativeObj, self->startIntentMethodId, jUri);
    env->DeleteLocalRef(jUri);

    if (attached) self->javaVM->DetachCurrentThread();
    return 0;
}

int RelcEngine::lua_system_notification(lua_State *L) {
    auto *self = static_cast<RelcEngine *>(lua_touserdata(L, lua_upvalueindex(1)));
    const char *title = luaL_checkstring(L, 1);
    const char *text = luaL_checkstring(L, 2);

    JNIEnv *env;
    bool attached = false;
    if (self->javaVM->GetEnv((void **) &env, JNI_VERSION_1_6) == JNI_EDETACHED) {
        if (self->javaVM->AttachCurrentThread(&env, nullptr) != JNI_OK) return 0;
        attached = true;
    }

    jstring jTitle = env->NewStringUTF(title);
    jstring jText = env->NewStringUTF(text);
    env->CallVoidMethod(self->luaNativeObj, self->showNotificationMethodId, jTitle, jText);
    env->DeleteLocalRef(jTitle);
    env->DeleteLocalRef(jText);

    if (attached) self->javaVM->DetachCurrentThread();
    return 0;
}

int RelcEngine::lua_wait(lua_State *L) {
    auto ms = static_cast<long>(luaL_checkinteger(L, 1));
    std::this_thread::sleep_for(std::chrono::milliseconds(ms));
    return 0;
}

int RelcEngine::lua_screen_findImage(lua_State *L) {
    auto *self = static_cast<RelcEngine *>(lua_touserdata(L, lua_upvalueindex(1)));
    const char *name = luaL_checkstring(L, 1);

    std::lock_guard<std::mutex> lock(self->resultMutex);
    for (const auto &item: self->latestResult.matches) {
        if (item.name == name && item.found) {
            lua_newtable(L);
            lua_pushboolean(L, true);
            lua_setfield(L, -2, "found");
            lua_pushnumber(L, item.x);
            lua_setfield(L, -2, "x");
            lua_pushnumber(L, item.y);
            lua_setfield(L, -2, "y");
            lua_pushnumber(L, item.confidence);
            lua_setfield(L, -2, "confidence");
            return 1;
        }
    }

    lua_newtable(L);
    lua_pushboolean(L, false);
    lua_setfield(L, -2, "found");
    return 1;
}
