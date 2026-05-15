#include "RelcEngine.h"
#include <opencv2/imgproc.hpp>
#include <opencv2/imgcodecs.hpp>
#include <android/native_window_jni.h>
#include <android/log.h>

RelcEngine::RelcEngine(JNIEnv* env, jobject service) : displayId(-1), isRunning(false) {
    env->GetJavaVM(&javaVM);
    serviceObj = env->NewGlobalRef(service);

    jclass serviceClass = env->GetObjectClass(serviceObj);
    // V2 Method: multiTouchSwipe(int pointerId, int displayId, int[] points, long duration, boolean keep)
    swipeMethodId = env->GetMethodID(serviceClass, "multiTouchSwipe", "(II[IJZ)V");
    createVirtualDisplayMethodId = env->GetMethodID(serviceClass, "createVirtualDisplay", "(Ljava/lang/String;IIILandroid/view/Surface;I)I");
    launchInDisplayMethodId = env->GetMethodID(serviceClass, "launchInDisplay", "(Ljava/lang/String;I)Z");
    getVirtualDisplaysMethodId = env->GetMethodID(serviceClass, "getVirtualDisplays", "()[I");
}

RelcEngine::~RelcEngine() {
    stop();
    JNIEnv* env;
    if (javaVM->GetEnv((void**)&env, JNI_VERSION_1_6) == JNI_OK) {
        env->DeleteGlobalRef(serviceObj);
    }
}

bool RelcEngine::start(int width, int height, const std::string& script) {
    luaEngine = std::make_unique<LuaEngine>();
    if (!luaEngine->init()) return false;

    lua_State* L = luaEngine->getLuaState();
    
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

    imageReader->setCallback([this](const cv::Mat& frame) {
        processFrame(frame);
    });

    isRunning = true;
    luaThread = std::thread(&RelcEngine::luaThreadLoop, this, script);

    return true;
}

void RelcEngine::luaThreadLoop(std::string script) {
    if (!luaEngine->loadScript(script)) return;
    
    // Run the script
    luaEngine->resume();
}

void RelcEngine::stop() {
    isRunning = false;
    resultCV.notify_all();
    if (luaThread.joinable()) {
        luaThread.join();
    }

    if (imageReader) imageReader->release();
    if (luaEngine) luaEngine->stop();
}

ANativeWindow* RelcEngine::getWindow() {
    return imageReader ? imageReader->getWindow() : nullptr;
}

void RelcEngine::updateTemplatesFromLua() {
    lua_State* L = luaEngine->getLuaState();
    lua_getglobal(L, "match");
    lua_getfield(L, -1, "templates");

    std::vector<SearchTemplate> newTemplates;

    if (lua_istable(L, -1)) {
        int n = lua_rawlen(L, -1);
        for (int i = 1; i <= n; i++) {
            lua_rawgeti(L, -1, i);
            if (lua_istable(L, -1)) {
                SearchTemplate t;
                lua_getfield(L, -1, "name"); t.name = luaL_optstring(L, -1, ""); lua_pop(L, 1);
                lua_getfield(L, -1, "threshold"); t.threshold = luaL_optnumber(L, -1, 0.8); lua_pop(L, 1);
                
                lua_getfield(L, -1, "target");
                std::string path = luaL_optstring(L, -1, "");
                lua_pop(L, 1);

                if (!path.empty()) {
                    t.image = cv::imread(path, cv::IMREAD_UNCHANGED);
                    if (!t.image.empty()) {
                        if (t.image.channels() == 3) {
                            cv::cvtColor(t.image, t.image, cv::COLOR_BGR2RGBA);
                        }
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

void RelcEngine::processFrame(const cv::Mat& frame) {
    if (!isRunning) return;

    std::lock_guard<std::mutex> lock(resultMutex);
    latestResult.matches.clear();
    
    for (const auto& t : templates) {
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
    
    latestResult.hasResult = true;
    resultCV.notify_one();
}

bool RelcEngine::createVirtualDisplay(int width, int height, int densityDpi, int flags) {
    JNIEnv* env;
    bool attached = false;
    int res = javaVM->GetEnv((void**)&env, JNI_VERSION_1_6);
    if (res == JNI_EDETACHED) {
        if (javaVM->AttachCurrentThread(&env, nullptr) != JNI_OK) return false;
        attached = true;
    }

    jstring name = env->NewStringUTF("RelcLuaDisplay");
    ANativeWindow* window = getWindow();
    jobject surface = window ? ANativeWindow_toSurface(env, window) : nullptr;

    displayId = env->CallIntMethod(serviceObj, createVirtualDisplayMethodId, name, width, height, densityDpi, surface, flags);

    if (surface) env->DeleteLocalRef(surface);
    env->DeleteLocalRef(name);

    if (attached) javaVM->DetachCurrentThread();
    return displayId != -1;
}

int RelcEngine::lua_display_create(lua_State* L) {
    RelcEngine* self = (RelcEngine*)lua_touserdata(L, lua_upvalueindex(1));
    int width = luaL_checkinteger(L, 1);
    int height = luaL_checkinteger(L, 2);
    int densityDpi = luaL_optinteger(L, 3, 440);
    int flags = luaL_optinteger(L, 4, 16);
    
    bool success = self->createVirtualDisplay(width, height, densityDpi, flags);
    lua_pushboolean(L, success);
    return 1;
}

bool RelcEngine::launchInDisplay(const std::string& packageName, int displayId) {
    JNIEnv* env;
    bool attached = false;
    int res = javaVM->GetEnv((void**)&env, JNI_VERSION_1_6);
    if (res == JNI_EDETACHED) {
        if (javaVM->AttachCurrentThread(&env, nullptr) != JNI_OK) return false;
        attached = true;
    }

    jstring pkg = env->NewStringUTF(packageName.c_str());
    jboolean success = env->CallBooleanMethod(serviceObj, launchInDisplayMethodId, pkg, displayId);
    env->DeleteLocalRef(pkg);

    if (attached) javaVM->DetachCurrentThread();
    return (bool)success;
}

int RelcEngine::lua_display_launch(lua_State* L) {
    RelcEngine* self = (RelcEngine*)lua_touserdata(L, lua_upvalueindex(1));
    const char* packageName = luaL_checkstring(L, 1);
    int dId = (int)luaL_optinteger(L, 2, self->displayId);

    bool ok = self->launchInDisplay(packageName, dId);
    lua_pushboolean(L, ok);
    return 1;
}

std::vector<int> RelcEngine::getVirtualDisplays() {
    std::vector<int> ids;
    JNIEnv* env;
    bool attached = false;
    int res = javaVM->GetEnv((void**)&env, JNI_VERSION_1_6);
    if (res == JNI_EDETACHED) {
        if (javaVM->AttachCurrentThread(&env, nullptr) != JNI_OK) return ids;
        attached = true;
    }

    jintArray jIds = (jintArray)env->CallObjectMethod(serviceObj, getVirtualDisplaysMethodId);
    if (jIds) {
        jsize len = env->GetArrayLength(jIds);
        jint* elements = env->GetIntArrayElements(jIds, nullptr);
        for (int i = 0; i < len; i++) {
            ids.push_back(elements[i]);
        }
        env->ReleaseIntArrayElements(jIds, elements, JNI_ABORT);
        env->DeleteLocalRef(jIds);
    }

    if (attached) javaVM->DetachCurrentThread();
    return ids;
}

int RelcEngine::lua_display_get_all(lua_State* L) {
    RelcEngine* self = (RelcEngine*)lua_touserdata(L, lua_upvalueindex(1));
    std::vector<int> ids = self->getVirtualDisplays();

    lua_newtable(L);
    for (size_t i = 0; i < ids.size(); i++) {
        lua_pushinteger(L, ids[i]);
        lua_rawseti(L, -2, i + 1);
    }
    return 1;
}

int RelcEngine::lua_match_wait(lua_State* L) {
    RelcEngine* self = (RelcEngine*)lua_touserdata(L, lua_upvalueindex(1));
    
    self->updateTemplatesFromLua();

    std::unique_lock<std::mutex> lock(self->resultMutex);
    self->latestResult.hasResult = false;
    
    self->resultCV.wait(lock, [self]() {
        return self->latestResult.hasResult || !self->isRunning;
    });

    if (!self->isRunning) {
        lua_pushnil(L);
        return 1;
    }

    lua_newtable(L);
    for (const auto& item : self->latestResult.matches) {
        if (item.found) {
            lua_newtable(L);
            lua_pushboolean(L, true); lua_setfield(L, -2, "found");
            lua_pushnumber(L, item.x); lua_setfield(L, -2, "x");
            lua_pushnumber(L, item.y); lua_setfield(L, -2, "y");
            lua_pushnumber(L, item.confidence); lua_setfield(L, -2, "confidence");
            lua_setfield(L, -2, item.name.c_str());
        }
    }
    
    return 1;
}

bool RelcEngine::multiTouchSwipe(int pointerId, const std::vector<int>& points, long duration, bool keep) {
    JNIEnv* env;
    bool attached = false;
    int res = javaVM->GetEnv((void**)&env, JNI_VERSION_1_6);
    if (res == JNI_EDETACHED) {
        if (javaVM->AttachCurrentThread(&env, nullptr) != JNI_OK) return false;
        attached = true;
    }

    jintArray jPoints = env->NewIntArray(points.size());
    env->SetIntArrayRegion(jPoints, 0, points.size(), points.data());

    env->CallVoidMethod(serviceObj, swipeMethodId, pointerId, displayId, jPoints, (jlong)duration, (jboolean)keep);
    
    env->DeleteLocalRef(jPoints);

    if (attached) javaVM->DetachCurrentThread();
    return true;
}

int RelcEngine::lua_swipe(lua_State* L) {
    RelcEngine* self = (RelcEngine*)lua_touserdata(L, lua_upvalueindex(1));
    
    int pointerId = (int)luaL_checkinteger(L, 1);
    luaL_checktype(L, 2, LUA_TTABLE);
    long duration = (long)luaL_optinteger(L, 3, 0);
    bool keep = lua_toboolean(L, 4);

    std::vector<int> points;
    int n = lua_rawlen(L, 2);
    for (int i = 1; i <= n; i++) {
        lua_rawgeti(L, 2, i);
        if (lua_istable(L, -1)) {
            int innerN = lua_rawlen(L, -1);
            for (int j = 1; j <= innerN; j++) {
                lua_rawgeti(L, -1, j);
                points.push_back((int)luaL_checkinteger(L, -1));
                lua_pop(L, 1);
            }
        } else {
            points.push_back((int)luaL_checkinteger(L, -1));
        }
        lua_pop(L, 1);
    }

    self->multiTouchSwipe(pointerId, points, duration, keep);
    return 0;
}

int RelcEngine::lua_log(lua_State* L) {
    const char* msg = luaL_checkstring(L, 1);
    __android_log_print(ANDROID_LOG_DEBUG, "LuaScript", "[NativeLog] %s", msg);
    return 0;
}
