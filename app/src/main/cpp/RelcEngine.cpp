#include "RelcEngine.h"
#include <opencv2/imgproc.hpp>
#include <opencv2/imgcodecs.hpp>
#include <android/native_window_jni.h>
#include <android/log.h>

RelcEngine::RelcEngine(JNIEnv *env, jobject service, jobject detector) : javaVM(nullptr),
                                                                         displayId(-1),
                                                                         sinkHandle(-1),
                                                                         isRunning(false),
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
    uiAddMethodId = env->GetMethodID(luaNativeClass, "uiAdd", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)V");
    uiUpdateMethodId = env->GetMethodID(luaNativeClass, "uiUpdate", "(Ljava/lang/String;Ljava/lang/String;)V");
    uiRemoveMethodId = env->GetMethodID(luaNativeClass, "uiRemove", "(Ljava/lang/String;)V");
    showNotificationMethodId = env->GetMethodID(luaNativeClass, "showNotification", "(Ljava/lang/String;Ljava/lang/String;)V");
    startIntentMethodId = env->GetMethodID(luaNativeClass, "startIntent", "(Ljava/lang/String;)V");
    systemActionMethodId = env->GetMethodID(luaNativeClass, "systemAction", "(Ljava/lang/String;)V");
}

RelcEngine::~RelcEngine() {
    stop();
    JNIEnv *env;
    if (javaVM->GetEnv((void **) &env, JNI_VERSION_1_6) == JNI_OK) {
        env->DeleteGlobalRef(serviceObj);
        env->DeleteGlobalRef(luaNativeObj);
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

    // ui module
    lua_newtable(L);
    lua_pushlightuserdata(L, this);
    lua_pushcclosure(L, lua_ui_add, 1);
    lua_setfield(L, -2, "add");

    lua_pushlightuserdata(L, this);
    lua_pushcclosure(L, lua_ui_update, 1);
    lua_setfield(L, -2, "update");

    lua_pushlightuserdata(L, this);
    lua_pushcclosure(L, lua_ui_remove, 1);
    lua_setfield(L, -2, "remove");
    
    lua_setglobal(L, "ui");

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

void RelcEngine::pushUIEvent(const std::string& elementId, const std::string& eventType) {
    std::lock_guard<std::mutex> lock(uiEventMutex);
    uiEventQueue.push({elementId, eventType});
}

void RelcEngine::luaThreadLoop(const std::string &scriptPath) {
    if (!luaEngine->loadFile(scriptPath)) {
        isRunning = false;
        return;
    }

    lua_State *coL = luaEngine->getCoroutineState();
    if (!coL) {
        isRunning = false;
        return;
    }

    // Set a hook that runs every 1000 instructions to check if we should stop
    lua_sethook(coL, lua_stop_hook, LUA_MASKCOUNT, 1000);

    lua_State *gL = luaEngine->getLuaState();

    // 1. Initial start to execute top-level (config, etc.)
    int nres = 0;
    int status = lua_resume(coL, gL, 0, &nres);
    if (status != LUA_OK && status != LUA_YIELD) {
        LOGE("Script execution error (init): %s", lua_tostring(coL, -1));
        isRunning = false;
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

    // Main Ticker Loop
    while (isRunning) {
        auto start = std::chrono::steady_clock::now();
        tickNum++;

        // 3.5 Process on_event(events, tick_num)
        lua_getglobal(gL, "on_event");
        if (lua_isfunction(gL, -1)) {
            lua_newtable(gL);
            int eventIndex = 1;
            
            {
                std::lock_guard<std::mutex> lock(uiEventMutex);
                while (!uiEventQueue.empty()) {
                    auto ev = uiEventQueue.front();
                    uiEventQueue.pop();

                    lua_newtable(gL);
                    lua_pushstring(gL, ev.elementId.c_str());
                    lua_setfield(gL, -2, "id");
                    lua_pushstring(gL, ev.eventType.c_str());
                    lua_setfield(gL, -2, "type");
                    
                    lua_rawseti(gL, -2, eventIndex++);
                }
            }
            
            lua_pushinteger(gL, tickNum);

            if (lua_pcall(gL, 2, 0, 0) != LUA_OK) {
                LOGE("on_event error: %s", lua_tostring(gL, -1));
                lua_pop(gL, 1);
            }
        } else {
            lua_pop(gL, 1);
            std::lock_guard<std::mutex> lock(uiEventMutex);
            while (!uiEventQueue.empty()) uiEventQueue.pop();
        }

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

            {
                std::lock_guard<std::mutex> lock(resultMutex);
                for (const auto& t : templates) {
                    lua_newtable(gL);
                    bool found = false;
                    for (const auto& m : matchesCopy) {
                        if (m.name == t.name) {
                            lua_pushboolean(gL, true);
                            lua_setfield(gL, -2, "found");
                            lua_pushnumber(gL, m.x);
                            lua_setfield(gL, -2, "x");
                            lua_pushnumber(gL, m.y);
                            lua_setfield(gL, -2, "y");
                            lua_pushnumber(gL, m.confidence);
                            lua_setfield(gL, -2, "confidence");
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        lua_pushboolean(gL, false);
                        lua_setfield(gL, -2, "found");
                    }
                    lua_setfield(gL, -2, t.name.c_str());
                }
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
                LOGE("Script execution error: %s", lua_tostring(coL, -1));
                isRunning = false;
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
                break;
            }
        }

        auto end = std::chrono::steady_clock::now();
        auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(end - start);
        if (elapsed.count() < tickIntervalMs) {
            std::this_thread::sleep_for(std::chrono::milliseconds(tickIntervalMs - elapsed.count()));
        }
    }
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

ANativeWindow *RelcEngine::getWindow() {
    return imageReader ? imageReader->getWindow() : nullptr;
}

void RelcEngine::parseConfigFromLua() {
    lua_State *L = luaEngine->getLuaState();
    lua_getglobal(L, "config");

    if (!lua_istable(L, -1)) {
        lua_pop(L, 1);
        LOGD("No 'config' table found, using defaults");
        return;
    }

    // Parse FPS
    lua_getfield(L, -1, "fps");
    if (lua_isnumber(L, -1)) {
        int fps = static_cast<int>(lua_tointeger(L, -1));
        if (fps > 0) tickIntervalMs = 1000 / fps;
    }
    lua_pop(L, 1);

    // Parse Templates
    lua_getfield(L, -1, "templates");
    if (lua_istable(L, -1)) {
        std::vector<SearchTemplate> newTemplates;
        auto n = static_cast<int>(lua_rawlen(L, -1));
        for (int i = 1; i <= n; i++) {
            lua_rawgeti(L, -1, i);
            if (lua_istable(L, -1)) {
                SearchTemplate t;

                // Format: { name = '...', img = '...', mask = {x, y, w, h}, threshold = 0.8, grayscale = false, enabled = true }
                
                // 1. name
                lua_getfield(L, -1, "name");
                const char* nameStr = lua_tostring(L, -1);
                if (nameStr) t.name = nameStr;
                lua_pop(L, 1);

                if (t.name.empty()) {
                    lua_pop(L, 1);
                    continue;
                }

                // 2. img (filename)
                std::string imgFileName = t.name + ".png"; // default
                lua_getfield(L, -1, "img");
                if (lua_isstring(L, -1)) {
                    imgFileName = lua_tostring(L, -1);
                }
                lua_pop(L, 1);

                // 3. mask (roi)
                lua_getfield(L, -1, "mask");
                if (lua_istable(L, -1)) {
                    lua_rawgeti(L, -1, 1); t.roi.x = static_cast<int>(lua_tointeger(L, -1)); lua_pop(L, 1);
                    lua_rawgeti(L, -1, 2); t.roi.y = static_cast<int>(lua_tointeger(L, -1)); lua_pop(L, 1);
                    lua_rawgeti(L, -1, 3); t.roi.width = static_cast<int>(lua_tointeger(L, -1)); lua_pop(L, 1);
                    lua_rawgeti(L, -1, 4); t.roi.height = static_cast<int>(lua_tointeger(L, -1)); lua_pop(L, 1);
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
                        // Ensure 4 channels (RGBA) for consistent processing before grayscale conversion
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
                    t.image = templateCache[path].clone(); // Clone because we might convert it to grayscale

                    // If grayscale is requested, convert template now
                    if (t.grayscale && t.image.channels() == 4) {
                        cv::Mat gray;
                        cv::cvtColor(t.image, gray, cv::COLOR_RGBA2GRAY);
                        t.image = gray;
                    }

                    newTemplates.push_back(t);
                }
            }
            lua_pop(L, 1);
        }

        std::lock_guard<std::mutex> lock(resultMutex);
        templates = std::move(newTemplates);
    }
    lua_pop(L, 2); // pop templates and config
}

void RelcEngine::processFrame(const cv::Mat &frame) {
    if (!isRunning) return;

    std::lock_guard<std::mutex> lock(resultMutex);
    latestResult.matches.clear();

    for (const auto &t: templates) {
        if (!t.enabled) continue;
        if (t.image.empty()) continue;

        cv::Mat searchArea = frame;
        int offsetX = 0;
        int offsetY = 0;

        // Apply ROI if specified
        if (t.roi.width > 0 && t.roi.height > 0) {
            int x = std::max(0, t.roi.x);
            int y = std::max(0, t.roi.y);
            int w = std::min(t.roi.width, frame.cols - x);
            int h = std::min(t.roi.height, frame.rows - y);

            if (w < t.image.cols || h < t.image.rows) continue;

            searchArea = frame(cv::Rect(x, y, w, h));
            offsetX = x;
            offsetY = y;
        }

        if (searchArea.cols < t.image.cols || searchArea.rows < t.image.rows) continue;

        cv::Mat target;
        if (t.grayscale && searchArea.channels() == 4) {
            cv::Mat grayArea;
            cv::cvtColor(searchArea, grayArea, cv::COLOR_RGBA2GRAY);
            target = grayArea;
        } else {
            target = searchArea;
        }

        cv::Mat result;
        cv::matchTemplate(target, t.image, result, cv::TM_CCOEFF_NORMED);

        double minVal, maxVal;
        cv::Point minLoc, maxLoc;
        cv::minMaxLoc(result, &minVal, &maxVal, &minLoc, &maxLoc);

        if (maxVal >= t.threshold) {
            MatchResultItem item;
            item.name = t.name;
            item.found = true;
            item.x = maxLoc.x + t.image.cols / 2.0 + offsetX;
            item.y = maxLoc.y + t.image.rows / 2.0 + offsetY;
            item.confidence = maxVal;
            latestResult.matches.push_back(item);
        }
    }
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
    for (auto &t : self->templates) {
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

int RelcEngine::lua_ui_add(lua_State *L) {
    auto *self = static_cast<RelcEngine *>(lua_touserdata(L, lua_upvalueindex(1)));
    
    // Signature: ui.add(parentId, id, jsonExp)
    const char *parentId = nullptr;
    if (!lua_isnil(L, 1)) {
        parentId = luaL_checkstring(L, 1);
    }
    const char *id = luaL_checkstring(L, 2);
    const char *jsonExp = luaL_checkstring(L, 3);

    JNIEnv *env;
    bool attached = false;
    if (self->javaVM->GetEnv((void **) &env, JNI_VERSION_1_6) == JNI_EDETACHED) {
        if (self->javaVM->AttachCurrentThread(&env, nullptr) != JNI_OK) return 0;
        attached = true;
    }

    jstring jParentId = parentId ? env->NewStringUTF(parentId) : nullptr;
    jstring jId = env->NewStringUTF(id);
    jstring jJsonExp = env->NewStringUTF(jsonExp);

    env->CallVoidMethod(self->luaNativeObj, self->uiAddMethodId, jParentId, jId, jJsonExp);

    if (jParentId) env->DeleteLocalRef(jParentId);
    env->DeleteLocalRef(jId);
    env->DeleteLocalRef(jJsonExp);

    if (attached) self->javaVM->DetachCurrentThread();
    return 0;
}

int RelcEngine::lua_ui_update(lua_State *L) {
    auto *self = static_cast<RelcEngine *>(lua_touserdata(L, lua_upvalueindex(1)));
    const char *id = luaL_checkstring(L, 1);
    const char *jsonExp = luaL_checkstring(L, 2);

    JNIEnv *env;
    bool attached = false;
    if (self->javaVM->GetEnv((void **) &env, JNI_VERSION_1_6) == JNI_EDETACHED) {
        if (self->javaVM->AttachCurrentThread(&env, nullptr) != JNI_OK) return 0;
        attached = true;
    }

    jstring jId = env->NewStringUTF(id);
    jstring jJsonExp = env->NewStringUTF(jsonExp);

    env->CallVoidMethod(self->luaNativeObj, self->uiUpdateMethodId, jId, jJsonExp);

    env->DeleteLocalRef(jId);
    env->DeleteLocalRef(jJsonExp);

    if (attached) self->javaVM->DetachCurrentThread();
    return 0;
}

int RelcEngine::lua_ui_remove(lua_State *L) {
    auto *self = static_cast<RelcEngine *>(lua_touserdata(L, lua_upvalueindex(1)));
    const char *id = luaL_checkstring(L, 1);

    JNIEnv *env;
    bool attached = false;
    if (self->javaVM->GetEnv((void **) &env, JNI_VERSION_1_6) == JNI_EDETACHED) {
        if (self->javaVM->AttachCurrentThread(&env, nullptr) != JNI_OK) return 0;
        attached = true;
    }

    jstring jId = env->NewStringUTF(id);
    env->CallVoidMethod(self->luaNativeObj, self->uiRemoveMethodId, jId);
    env->DeleteLocalRef(jId);

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
    for (const auto &item : self->latestResult.matches) {
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
