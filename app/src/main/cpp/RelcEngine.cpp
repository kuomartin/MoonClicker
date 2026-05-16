#include "RelcEngine.h"
#include <opencv2/imgproc.hpp>
#include <opencv2/imgcodecs.hpp>
#include <android/native_window_jni.h>
#include <android/log.h>

RelcEngine::RelcEngine(JNIEnv *env, jobject service, jobject detector) : javaVM(nullptr),
                                                                         displayId(-1),
                                                                         sinkHandle(-1),
                                                                         isRunning(false) {
    env->GetJavaVM(&javaVM);
    serviceObj = env->NewGlobalRef(service);

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
}

RelcEngine::~RelcEngine() {
    stop();
    JNIEnv *env;
    if (javaVM->GetEnv((void **) &env, JNI_VERSION_1_6) == JNI_OK) {
        env->DeleteGlobalRef(serviceObj);
    }
}

bool RelcEngine::start(int width, int height, const std::string &script) {
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
    lua_pop(L, 1);

    // match.wait
    lua_getglobal(L, "match");
    lua_pushlightuserdata(L, this);
    lua_pushcclosure(L, lua_match_wait, 1);
    lua_setfield(L, -2, "wait");
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

    imageReader = std::make_unique<NativeImageReader>(width, height);
    if (!imageReader->init()) return false;

    imageReader->setCallback([this](const cv::Mat &frame) {
        if (!isRunning) return;
        processFrame(frame);
    });

    isRunning = true;
    luaThread = std::thread(&RelcEngine::luaThreadLoop, this, script);

    return true;
}

void RelcEngine::luaThreadLoop(const std::string &script) {
    if (!luaEngine->loadScript(script)) {
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

    // Check if on_start is defined and call it
    lua_getglobal(gL, "on_start");
    if (lua_isfunction(gL, -1)) {
        if (lua_pcall(gL, 0, 0, 0) != LUA_OK) {
            LOGE("on_start error: %s", lua_tostring(gL, -1));
            lua_pop(gL, 1);
        }
    } else {
        lua_pop(gL, 1);
    }

    // Initial start
    int nres = 0;
    int status = lua_resume(coL, gL, 0, &nres);
    if (status != LUA_OK && status != LUA_YIELD) {
        LOGE("Script execution error: %s", lua_tostring(coL, -1));
        isRunning = false;
        return;
    }

    // Fixed 15 FPS Logic Ticker
    while (isRunning) {
        auto start = std::chrono::steady_clock::now();

        // 1. Process on_tick if defined
        lua_getglobal(gL, "on_tick");
        bool hasOnTick = lua_isfunction(gL, -1);
        if (hasOnTick) {
            lua_pushnumber(gL, 0.066); // approx dt
            if (lua_pcall(gL, 1, 0, 0) != LUA_OK) {
                LOGE("on_tick error: %s", lua_tostring(gL, -1));
                lua_pop(gL, 1);
            }
        } else {
            lua_pop(gL, 1);
        }

        // 2. Process on_match if defined
        lua_getglobal(gL, "on_match");
        if (lua_isfunction(gL, -1)) {
            std::vector<MatchResultItem> matchesCopy;
            {
                std::lock_guard<std::mutex> lock(resultMutex);
                matchesCopy = latestResult.matches;
            }

            if (!matchesCopy.empty()) {
                lua_pushstring(gL, matchesCopy[0].name.c_str());

                lua_newtable(gL);
                for (const auto &item: matchesCopy) {
                    if (item.found) {
                        lua_newtable(gL);
                        lua_pushboolean(gL, true);
                        lua_setfield(gL, -2, "found");
                        lua_pushnumber(gL, item.x);
                        lua_setfield(gL, -2, "x");
                        lua_pushnumber(gL, item.y);
                        lua_setfield(gL, -2, "y");
                        lua_pushnumber(gL, item.confidence);
                        lua_setfield(gL, -2, "confidence");
                        lua_setfield(gL, -2, item.name.c_str());
                    }
                }

                if (lua_pcall(gL, 2, 0, 0) != LUA_OK) {
                    LOGE("on_match error: %s", lua_tostring(gL, -1));
                    lua_pop(gL, 1);
                }
            } else {
                lua_pop(gL, 1);
            }
        } else {
            lua_pop(gL, 1);
        }

        // 3. Resume main coroutine if it yielded
        if (lua_status(coL) == LUA_YIELD) {
            int nres_loop = 0;
            int resume_status = lua_resume(coL, gL, 0, &nres_loop);

            if (resume_status != LUA_OK && resume_status != LUA_YIELD) {
                LOGE("Script execution error: %s", lua_tostring(coL, -1));
                isRunning = false;
                break;
            }
        }

        // 4. Check if we should exit
        if (lua_status(coL) == LUA_OK && !hasOnTick) {
            LOGD("Script finished execution");
            isRunning = false;
            break;
        }

        auto end = std::chrono::steady_clock::now();
        auto elapsed = std::chrono::duration_cast<std::chrono::milliseconds>(end - start);
        if (elapsed.count() < 66) {
            std::this_thread::sleep_for(std::chrono::milliseconds(66 - elapsed.count()));
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

void RelcEngine::updateTemplatesFromLua() {
    lua_State *L = luaEngine->getLuaState();
    lua_getglobal(L, "match");
    lua_getfield(L, -1, "templates");

    std::vector<SearchTemplate> newTemplates;

    if (lua_istable(L, -1)) {
        auto n = static_cast<int>(lua_rawlen(L, -1));
        for (int i = 1; i <= n; i++) {
            lua_rawgeti(L, -1, i);
            if (lua_istable(L, -1)) {
                SearchTemplate t;
                lua_getfield(L, -1, "name");
                t.name = luaL_optstring(L, -1, "");
                lua_pop(L, 1);
                lua_getfield(L, -1, "threshold");
                t.threshold = luaL_optnumber(L, -1, 0.8);
                lua_pop(L, 1);

                lua_getfield(L, -1, "target");
                std::string path = luaL_optstring(L, -1, "");
                lua_pop(L, 1);

                if (!path.empty()) {
                    if (templateCache.find(path) == templateCache.end()) {
                        cv::Mat img = cv::imread(path, cv::IMREAD_UNCHANGED);
                        if (!img.empty()) {
                            if (img.channels() == 3) {
                                cv::cvtColor(img, img, cv::COLOR_BGR2RGBA);
                            }
                            templateCache[path] = img;
                        }
                    }

                    if (templateCache.find(path) != templateCache.end()) {
                        t.image = templateCache[path];
                        newTemplates.push_back(t);
                    }
                }
            }
            lua_pop(L, 1);
        }
    }
    lua_pop(L, 2);

    std::lock_guard<std::mutex> lock(resultMutex);
    templates = std::move(newTemplates);
}

void RelcEngine::processFrame(const cv::Mat &frame) {
    if (!isRunning) return;

    std::lock_guard<std::mutex> lock(resultMutex);
    latestResult.matches.clear();

    for (const auto &t: templates) {
        if (t.image.empty() || frame.cols < t.image.cols || frame.rows < t.image.rows) continue;

        cv::Mat result;
        cv::matchTemplate(frame, t.image, result, cv::TM_CCOEFF_NORMED);

        double minVal, maxVal;
        cv::Point minLoc, maxLoc;
        cv::minMaxLoc(result, &minVal, &maxVal, &minLoc, &maxLoc);

        if (maxVal >= t.threshold) {
            MatchResultItem item;
            item.name = t.name;
            item.found = true;
            item.x = maxLoc.x + t.image.cols / 2.0;
            item.y = maxLoc.y + t.image.rows / 2.0;
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

    self->updateTemplatesFromLua();

    std::unique_lock<std::mutex> lock(self->resultMutex);

    if (self->latestResult.matches.empty()) {
        // No matches found, yield the coroutine
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
                points.push_back(static_cast<int>(luaL_checkinteger(L, -1)));
                lua_pop(L, 1);
            }
        } else {
            points.push_back(static_cast<int>(luaL_checkinteger(L, -1)));
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
