#include "ScriptRuntime.h"
#include "LuaBindings.h"

#include <android/native_window_jni.h>

ScriptRuntime::ScriptRuntime(JNIEnv *env, jobject host, jobject service) {
    env->GetJavaVM(&javaVM);
    hostObj = env->NewGlobalRef(host);
    serviceObj = env->NewGlobalRef(service);

    jclass hostClass = env->GetObjectClass(hostObj);
    hostMethods.swipe = env->GetMethodID(hostClass, "swipe", "(I[IJZ)Z");
    hostMethods.pointerUp = env->GetMethodID(hostClass, "pointerUp", "(I)Z");
    hostMethods.key = env->GetMethodID(hostClass, "key", "(I)Z");
    hostMethods.launch = env->GetMethodID(hostClass, "launch", "(Ljava/lang/String;)Z");
    hostMethods.notify = env->GetMethodID(hostClass, "notify",
                                          "(Ljava/lang/String;Ljava/lang/String;)V");
    hostMethods.openUri = env->GetMethodID(hostClass, "openUri", "(Ljava/lang/String;)V");
    hostMethods.setData = env->GetMethodID(hostClass, "setData",
                                           "(Ljava/lang/String;Ljava/lang/Object;)V");
    hostMethods.onEvent = env->GetMethodID(hostClass, "onEngineEvent", "(ILjava/lang/String;)V");
    hostMethods.log = env->GetMethodID(hostClass, "log", "(Ljava/lang/String;)V");

    jclass serviceClass = env->GetObjectClass(serviceObj);
    addSurfaceMethodId = env->GetMethodID(serviceClass, "addVirtualDisplaySurface",
                                          "(ILandroid/view/Surface;)I");
    removeSurfaceMethodId = env->GetMethodID(serviceClass, "removeVirtualDisplaySurface", "(II)Z");
    acquireMirrorMethodId = env->GetMethodID(serviceClass, "acquireDisplayMirror", "(I)Z");
    releaseMirrorMethodId = env->GetMethodID(serviceClass, "releaseDisplayMirror", "(I)Z");
    isMirrorActiveMethodId = env->GetMethodID(serviceClass, "isDisplayMirrorActive", "(I)Z");

    jclass surfaceClass = env->FindClass("android/view/Surface");
    surfaceReleaseMethodId = env->GetMethodID(surfaceClass, "release", "()V");

    jclass localDouble = env->FindClass("java/lang/Double");
    doubleClass = (jclass) env->NewGlobalRef(localDouble);
    doubleConstructor = env->GetMethodID(doubleClass, "<init>", "(D)V");

    jclass localBoolean = env->FindClass("java/lang/Boolean");
    booleanClass = (jclass) env->NewGlobalRef(localBoolean);
    booleanConstructor = env->GetMethodID(booleanClass, "<init>", "(Z)V");
}

ScriptRuntime::~ScriptRuntime() {
    stop();

    JNIEnv *env;
    bool attached = false;
    if (javaVM->GetEnv((void **) &env, JNI_VERSION_1_6) == JNI_EDETACHED) {
        if (javaVM->AttachCurrentThread(&env, nullptr) == JNI_OK) attached = true;
        else return;
    }
    env->DeleteGlobalRef(hostObj);
    env->DeleteGlobalRef(serviceObj);
    env->DeleteGlobalRef(doubleClass);
    env->DeleteGlobalRef(booleanClass);
    if (attached) javaVM->DetachCurrentThread();
}

bool ScriptRuntime::start(int displayId, bool isPhysical, bool withVision, int surfaceWidth, int surfaceHeight,
                          int initialRotation, const std::string &scriptDir) {
    if (running.load()) {
        LOGE("start() called while a script is already running");
        return false;
    }

    this->displayId = displayId;
    this->isPhysical = isPhysical;
    this->scriptDir = scriptDir;
    this->surfaceWidth = surfaceWidth;
    this->surfaceHeight = surfaceHeight;
    this->heldMirrorRef = false;

    visionMatcher = std::make_unique<VisionMatcher>(surfaceWidth, surfaceHeight, scriptDir);
    // 一定要在腳本執行緒起跑前設好，否則第一行 screen.width 讀到的是未旋轉的值。
    visionMatcher->setRotation(initialRotation);

    if (withVision) {
        if (!attachImageReader()) {
            LOGE("attachImageReader failed in start() for display %d", displayId);
            return false;
        }
        visionEnabled = true;
    } else {
        visionEnabled = false;
    }

    luaEngine = std::make_unique<LuaEngine>();
    if (!luaEngine->init()) {
        LOGE("Failed to init Lua state");
        detachImageReader();
        return false;
    }

    running.store(true);
    luaThread = std::thread(&ScriptRuntime::threadMain, this);
    return true;
}

void ScriptRuntime::stop() {
    if (running.exchange(false)) {
        if (visionMatcher) visionMatcher->shutdown();
        sleepCv.notify_all();
    }
    if (luaThread.joinable()) luaThread.join();

    detachImageReader();
    if (heldMirrorRef) {
        JNIEnv *env = nullptr;
        bool attached = false;
        if (javaVM->GetEnv((void **) &env, JNI_VERSION_1_6) == JNI_EDETACHED) {
            if (javaVM->AttachCurrentThread(&env, nullptr) == JNI_OK) attached = true;
        }
        if (env && releaseMirrorMethodId) {
            env->CallBooleanMethod(serviceObj, releaseMirrorMethodId, displayId);
            if (attached) javaVM->DetachCurrentThread();
        }
        heldMirrorRef = false;
    }
    if (luaEngine) luaEngine->close();
}

bool ScriptRuntime::attachImageReader() {
    if (sinkHandle >= 0 && sinkSurface != nullptr) {
        return true;
    }
    imageReader = std::make_unique<NativeImageReader>(surfaceWidth, surfaceHeight);
    if (!imageReader->init()) {
        LOGE("Failed to init NativeImageReader");
        imageReader.reset();
        return false;
    }
    imageReader->setCallback([this](const cv::Mat &frame) {
        if (running.load()) visionMatcher->onFrame(frame);
    });

    JNIEnv *env = nullptr;
    bool attached = false;
    if (javaVM->GetEnv((void **) &env, JNI_VERSION_1_6) == JNI_EDETACHED) {
        if (javaVM->AttachCurrentThread(&env, nullptr) != JNI_OK) return false;
        attached = true;
    }
    jobject surface = ANativeWindow_toSurface(env, imageReader->getWindow());
    sinkHandle = env->CallIntMethod(serviceObj, addSurfaceMethodId, displayId, surface);
    sinkSurface = env->NewGlobalRef(surface);
    env->DeleteLocalRef(surface);
    if (attached) javaVM->DetachCurrentThread();

    if (sinkHandle < 0) {
        LOGE("addVirtualDisplaySurface failed for display %d", displayId);
        detachImageReader();
        return false;
    }
    return true;
}

void ScriptRuntime::detachImageReader() {
    if (sinkHandle >= 0 || sinkSurface != nullptr) {
        JNIEnv *env = nullptr;
        bool attached = false;
        if (javaVM->GetEnv((void **) &env, JNI_VERSION_1_6) == JNI_EDETACHED) {
            if (javaVM->AttachCurrentThread(&env, nullptr) == JNI_OK) attached = true;
        }
        if (env) {
            if (sinkHandle >= 0) {
                env->CallBooleanMethod(serviceObj, removeSurfaceMethodId, displayId, sinkHandle);
            }
            if (sinkSurface) {
                // 先讓服務端不再送影格，再釋放自己這一份 Surface。
                env->CallVoidMethod(sinkSurface, surfaceReleaseMethodId);
                env->DeleteGlobalRef(sinkSurface);
                sinkSurface = nullptr;
            }
            if (attached) javaVM->DetachCurrentThread();
        }
        sinkHandle = -1;
    }
    // 顯示器本身刻意不銷毀——它的生命週期屬於 :app 的 Displays 頁。
    if (imageReader) {
        // 先斷開回呼再刪 reader：不然刪除期間還可能有一張影格正在被處理，
        // logcat 會噴 ConsumerBase is abandoned。
        imageReader->setCallback(nullptr);
        imageReader->release();
        imageReader.reset();
    }
}

bool ScriptRuntime::isMirrorActive() {
    if (!isPhysical) return true;
    if (heldMirrorRef) return true;
    JNIEnv *env = nullptr;
    bool attached = false;
    if (javaVM->GetEnv((void **) &env, JNI_VERSION_1_6) == JNI_EDETACHED) {
        if (javaVM->AttachCurrentThread(&env, nullptr) == JNI_OK) attached = true;
    }
    if (!env || !isMirrorActiveMethodId) return false;
    jboolean active = env->CallBooleanMethod(serviceObj, isMirrorActiveMethodId, displayId);
    if (attached) javaVM->DetachCurrentThread();
    return active;
}

bool ScriptRuntime::startMirror() {
    if (!isPhysical) return true;
    if (heldMirrorRef) return true;

    JNIEnv *env = nullptr;
    bool attached = false;
    if (javaVM->GetEnv((void **) &env, JNI_VERSION_1_6) == JNI_EDETACHED) {
        if (javaVM->AttachCurrentThread(&env, nullptr) == JNI_OK) attached = true;
    }
    if (!env || !acquireMirrorMethodId) return false;

    jboolean ok = env->CallBooleanMethod(serviceObj, acquireMirrorMethodId, displayId);
    if (attached) javaVM->DetachCurrentThread();
    if (!ok) {
        LOGE("acquireDisplayMirror failed for display %d", displayId);
        return false;
    }
    heldMirrorRef = true;

    if (!attachImageReader()) {
        LOGE("attachImageReader failed after acquireDisplayMirror");
        stopMirror();
        return false;
    }
    visionEnabled = true;
    return true;
}

bool ScriptRuntime::stopMirror() {
    if (!isPhysical) return true;
    if (!heldMirrorRef) return false;

    detachImageReader();
    visionEnabled = false;

    JNIEnv *env = nullptr;
    bool attached = false;
    if (javaVM->GetEnv((void **) &env, JNI_VERSION_1_6) == JNI_EDETACHED) {
        if (javaVM->AttachCurrentThread(&env, nullptr) == JNI_OK) attached = true;
    }
    if (env && releaseMirrorMethodId) {
        env->CallBooleanMethod(serviceObj, releaseMirrorMethodId, displayId);
        if (attached) javaVM->DetachCurrentThread();
    }
    heldMirrorRef = false;
    return true;
}

void ScriptRuntime::setRotation(int rotation) {
    if (visionMatcher) visionMatcher->setRotation(rotation);
}

bool ScriptRuntime::interruptibleSleep(long ms) {
    if (!running.load() && !cleaningUp.load()) return false;
    if (cleaningUp.load()) return true;  // 收尾中不真的睡，但也不算被打斷
    if (ms <= 0) return true;

    std::unique_lock<std::mutex> lock(sleepMutex);
    sleepCv.wait_for(lock, std::chrono::milliseconds(ms), [this] { return !running.load(); });
    return running.load();
}

void ScriptRuntime::pushEvent(int type, const std::string &payload) {
    // 刻意不用快取的 luaEnv：JNIEnv 是綁執行緒的，而 start() 失敗時的事件是從呼叫端
    // 的執行緒推出去的。每次重新取得比較慢，但不會把別條執行緒的 env 拿來用。
    JNIEnv *env = nullptr;
    bool attached = false;
    if (javaVM->GetEnv((void **) &env, JNI_VERSION_1_6) == JNI_EDETACHED) {
        if (javaVM->AttachCurrentThread(&env, nullptr) != JNI_OK) return;
        attached = true;
    }

    jstring jPayload = env->NewStringUTF(payload.c_str());
    env->CallVoidMethod(hostObj, hostMethods.onEvent, type, jPayload);
    env->DeleteLocalRef(jPayload);

    if (attached) javaVM->DetachCurrentThread();
}

jobject ScriptRuntime::boxLuaValue(lua_State *L, int index) {
    JNIEnv *e = env();
    if (e == nullptr) return nullptr;

    switch (lua_type(L, index)) {
        case LUA_TNUMBER:
            return e->NewObject(doubleClass, doubleConstructor, lua_tonumber(L, index));
        case LUA_TSTRING:
            return e->NewStringUTF(lua_tostring(L, index));
        case LUA_TBOOLEAN:
            return e->NewObject(booleanClass, booleanConstructor,
                                (jboolean) lua_toboolean(L, index));
        case LUA_TTABLE: {
            // table 以 JSON 字串過橋——Kotlin 端拿到的是字串，要用再自己解析。
            //
            // cjson 是 luaL_requiref(..., glb = 0) 載入的，而且原始碼裡註冊全域的那段被
            // ENABLE_CJSON_GLOBAL 關掉了——所以 _G.cjson 是 nil，只有 package.loaded 有它。
            // 從全域讀會在 lua_getfield 當場拋錯，把整份腳本一起帶走。
            lua_getfield(L, LUA_REGISTRYINDEX, LUA_LOADED_TABLE);
            lua_getfield(L, -1, "cjson");
            lua_getfield(L, -1, "encode");
            lua_pushvalue(L, index);
            jobject boxed = nullptr;
            if (lua_pcall(L, 1, 1, 0) == LUA_OK) {
                boxed = e->NewStringUTF(lua_tostring(L, -1));
            } else {
                LOGE("Failed to encode table for data.set: %s", lua_tostring(L, -1));
            }
            lua_pop(L, 3);  // 結果（或錯誤）、cjson 表、package.loaded
            return boxed;
        }
        default:
            return nullptr;
    }
}

void ScriptRuntime::stopHook(lua_State *L, lua_Debug *ar) {
    lua_getfield(L, LUA_REGISTRYINDEX, relc::kRuntimeRegistryKey);
    auto *self = static_cast<ScriptRuntime *>(lua_touserdata(L, -1));
    lua_pop(L, 1);

    if (self && !self->running.load()) {
        // 錯誤訊息不會被使用者看到：threadMain 看到 running == false 就回報 STOPPED。
        luaL_error(L, "script stopped");
    }
}

/** pcall 的訊息處理器，把 traceback 接在錯誤訊息後面。 */
static int traceback(lua_State *L) {
    const char *message = lua_tostring(L, 1);
    luaL_traceback(L, L, message ? message : "(non-string error)", 1);
    return 1;
}

void ScriptRuntime::threadMain() {
    if (javaVM->AttachCurrentThread(&luaEnv, nullptr) != JNI_OK) {
        LOGE("Failed to attach script thread to the JVM");
        running.store(false);
        return;
    }

    runScript();

    luaEnv = nullptr;
    javaVM->DetachCurrentThread();
}

void ScriptRuntime::runScript() {
    lua_State *L = luaEngine->state();

    lua_pushlightuserdata(L, this);
    lua_setfield(L, LUA_REGISTRYINDEX, relc::kRuntimeRegistryKey);

    relc::registerApi(L, this);

    // require 以腳本資料夾為根，讓多檔腳本可以 require("helpers")。
    lua_getglobal(L, "package");
    lua_pushstring(L, (scriptDir + "?.lua;" + scriptDir + "?/init.lua").c_str());
    lua_setfield(L, -2, "path");
    lua_pop(L, 1);

    std::string mainPath = scriptDir + "main.lua";
    if (luaL_loadfile(L, mainPath.c_str()) != LUA_OK) {
        std::string message = lua_tostring(L, -1) ? lua_tostring(L, -1) : "unknown load error";
        lua_pop(L, 1);
        LOGE("Failed to load %s: %s", mainPath.c_str(), message.c_str());
        running.store(false);
        pushEvent(EngineEventType::ERROR, message);
        return;
    }

    lua_sethook(L, stopHook, LUA_MASKCOUNT, 1000);
    pushEvent(EngineEventType::RUNNING, "");

    lua_pushcfunction(L, traceback);
    lua_insert(L, -2);  // 訊息處理器要在被呼叫的 chunk 下面
    int status = lua_pcall(L, 0, 0, -2);
    lua_remove(L, status == LUA_OK ? -1 : -2);  // 移掉訊息處理器，錯誤物件留著

    lua_sethook(L, nullptr, 0, 0);

    // 不論哪條路徑結束，都給腳本一次收尾的機會（放開按住的觸控之類）。
    cleaningUp.store(true);
    lua_getglobal(L, "on_stop");
    if (lua_isfunction(L, -1)) {
        if (lua_pcall(L, 0, 0, 0) != LUA_OK) {
            LOGE("on_stop error: %s", lua_tostring(L, -1));
            lua_pop(L, 1);
        }
    } else {
        lua_pop(L, 1);
    }
    cleaningUp.store(false);

    if (status == LUA_OK) {
        running.store(false);
        pushEvent(EngineEventType::FINISHED, "");
        return;
    }

    std::string message = lua_tostring(L, -1) ? lua_tostring(L, -1) : "unknown error";
    lua_pop(L, 1);

    // running 只會因為 stop() 變成 false——所以這是使用者主動停止，不是腳本出錯。
    if (!running.load()) {
        pushEvent(EngineEventType::STOPPED, "");
        return;
    }

    LOGE("Script error: %s", message.c_str());
    running.store(false);
    pushEvent(EngineEventType::ERROR, message);
}
