#include "ScriptRuntime.h"
#include "LuaBindings.h"

#include <android/native_window_jni.h>

#include <cstdarg>

ScriptRuntime::ScriptRuntime(JNIEnv *env, jobject host) {
    env->GetJavaVM(&javaVM);
    hostObj = env->NewGlobalRef(host);

    jclass hostClass = env->GetObjectClass(hostObj);
    hostMethods.swipe = env->GetMethodID(hostClass, "swipe", "(I[IJZ)V");
    hostMethods.pointerUp = env->GetMethodID(hostClass, "pointerUp", "(I)V");
    hostMethods.key = env->GetMethodID(hostClass, "key", "(I)Z");
    hostMethods.text = env->GetMethodID(hostClass, "text", "(Ljava/lang/String;)Z");
    hostMethods.launch = env->GetMethodID(hostClass, "launch", "(Ljava/lang/String;)Z");
    hostMethods.listApps = env->GetMethodID(hostClass, "listApps",
                                            "()[Lcom/xaxaxax/moonclicker/MoonClickerAppTask;");
    hostMethods.mute = env->GetMethodID(hostClass, "mute", "(Ljava/lang/String;Z)Z");
    hostMethods.notify = env->GetMethodID(hostClass, "notify",
                                          "(Ljava/lang/String;Ljava/lang/String;)V");
    hostMethods.openUri = env->GetMethodID(hostClass, "openUri", "(Ljava/lang/String;)V");
    hostMethods.setData = env->GetMethodID(hostClass, "setData",
                                           "(Ljava/lang/String;Ljava/lang/Object;)V");
    hostMethods.onEvent = env->GetMethodID(hostClass, "onEngineEvent", "(ILjava/lang/String;)V");
    hostMethods.log = env->GetMethodID(hostClass, "log", "(Ljava/lang/String;)V");
    hostMethods.attachSink = env->GetMethodID(hostClass, "attachSink",
                                              "(Landroid/view/Surface;)Lcom/xaxaxax/moonclicker/DisplaySink;");
    hostMethods.acquireMirror = env->GetMethodID(hostClass, "acquireMirror", "()Z");
    hostMethods.releaseMirror = env->GetMethodID(hostClass, "releaseMirror", "()Z");
    hostMethods.isMirrorActive = env->GetMethodID(hostClass, "isMirrorActive", "()Z");
    hostMethods.viewTree = env->GetMethodID(hostClass, "viewTree", "()[B");
    hostMethods.replResult = env->GetMethodID(hostClass, "replResult", "(JI[B)V");

    // 在呼叫端（有 app class loader 的 Java 執行緒）解析；Lua 執行緒是 attach 進來的，FindClass 找不到 app 的類別。
    jclass appTaskClass = env->FindClass("com/xaxaxax/moonclicker/MoonClickerAppTask");
    appTask.packageName = env->GetFieldID(appTaskClass, "packageName", "Ljava/lang/String;");
    appTask.displayId = env->GetFieldID(appTaskClass, "displayId", "I");
    env->DeleteLocalRef(appTaskClass);

    jclass sinkClass = env->FindClass("com/xaxaxax/moonclicker/DisplaySink");
    sinkCloseMethodId = env->GetMethodID(sinkClass, "close", "()V");
    env->DeleteLocalRef(sinkClass);

    jclass surfaceClass = env->FindClass("android/view/Surface");
    surfaceReleaseMethodId = env->GetMethodID(surfaceClass, "release", "()V");

    jclass throwableClass = env->FindClass("java/lang/Throwable");
    throwableGetMessage = env->GetMethodID(throwableClass, "getMessage", "()Ljava/lang/String;");
    env->DeleteLocalRef(throwableClass);
    jclass objectClass = env->FindClass("java/lang/Object");
    objectGetClass = env->GetMethodID(objectClass, "getClass", "()Ljava/lang/Class;");
    env->DeleteLocalRef(objectClass);
    jclass classClass = env->FindClass("java/lang/Class");
    classGetName = env->GetMethodID(classClass, "getName", "()Ljava/lang/String;");
    env->DeleteLocalRef(classClass);

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
    env->DeleteGlobalRef(doubleClass);
    env->DeleteGlobalRef(booleanClass);
    if (attached) javaVM->DetachCurrentThread();
}

bool ScriptRuntime::start(int displayId, bool isPhysical, bool withVision, int surfaceWidth, int surfaceHeight,
                          int initialRotation, const std::string &scriptDir, const std::string &ocrPackDir,
                          int ocrThreads) {
    if (!prepare(displayId, isPhysical, withVision, surfaceWidth, surfaceHeight, initialRotation, scriptDir,
                 ocrPackDir, ocrThreads)) {
        return false;
    }
    replMode = false;
    running.store(true);
    luaThread = std::thread(&ScriptRuntime::threadMain, this);
    return true;
}

bool ScriptRuntime::startRepl(int displayId, bool isPhysical, bool withVision, int surfaceWidth,
                              int surfaceHeight, int initialRotation, const std::string &scriptDir,
                              const std::string &ocrPackDir, int ocrThreads) {
    if (!prepare(displayId, isPhysical, withVision, surfaceWidth, surfaceHeight, initialRotation, scriptDir,
                 ocrPackDir, ocrThreads)) {
        return false;
    }
    replMode = true;
    running.store(true);
    luaThread = std::thread(&ScriptRuntime::threadMain, this);
    return true;
}

bool ScriptRuntime::prepare(int displayId, bool isPhysical, bool withVision, int surfaceWidth, int surfaceHeight,
                            int initialRotation, const std::string &scriptDir, const std::string &ocrPackDir,
                            int ocrThreads) {
    if (running.load()) {
        LOGE("start() called while a script is already running");
        return false;
    }

    this->displayId = displayId;
    this->isPhysical = isPhysical;
    this->scriptDir = scriptDir;
    this->surfaceWidth = surfaceWidth;
    this->surfaceHeight = surfaceHeight;
    this->ocrPackDir = ocrPackDir;
    this->ocrThreads = ocrThreads;

    visionMatcher = std::make_unique<VisionMatcher>(surfaceWidth, surfaceHeight, initialRotation, scriptDir);

    if (withVision) {
        if (!attachImageReader()) {
            LOGE("attachImageReader failed in start() for display %d", displayId);
            return false;
        }
        visionEnabled = true;
        firstFramePending = true;
        firstFrameAfter = 0;
    } else {
        visionEnabled = false;
    }

    luaEngine = std::make_unique<LuaEngine>();
    if (!luaEngine->init()) {
        LOGE("Failed to init Lua state");
        detachImageReader();
        return false;
    }
    return true;
}

void ScriptRuntime::evalChunk(int64_t id, std::string code) {
    {
        std::lock_guard<std::mutex> lock(replMutex);
        replQueue.push_back({id, std::move(code)});
    }
    replCv.notify_all();
}

void ScriptRuntime::interrupt() {
    {
        std::lock_guard<std::mutex> lock(replMutex);
        if (!replExecuting) return;
        interrupted.store(true);
    }
    // 叫醒 sleep；vision.wait 每 kWaitPollMs 回來檢查一次。
    sleepCv.notify_all();
}

void ScriptRuntime::stop() {
    if (running.exchange(false)) {
        if (visionMatcher) visionMatcher->shutdown();
        sleepCv.notify_all();
        // 先拿鎖再通知：REPL 執行緒在鎖內檢查完 running 之後才睡，不會錯過這次通知。
        { std::lock_guard<std::mutex> lock(replMutex); }
        replCv.notify_all();
    }
    if (luaThread.joinable()) luaThread.join();
    ocrEngine.reset();

    // 鏡像引用由 ScriptHost.close() 歸還；它在這之後才被呼叫，所以順序仍是先拿掉 reader 再歸還。
    detachImageReader();
    if (luaEngine) luaEngine->close();
}

Ocr *ScriptRuntime::ocr(std::string &error) {
    if (ocrEngine) return ocrEngine.get();
    if (ocrPackDir.empty()) {
        error = "OCR is not installed: download it in Settings > Text recognition (OCR)";
        return nullptr;
    }
    try {
        ocrEngine = std::make_unique<Ocr>(ocrPackDir, ocrThreads);
    } catch (const std::exception &e) {
        error = std::string("failed to load OCR: ") + e.what();
        return nullptr;
    }
    return ocrEngine.get();
}

bool ScriptRuntime::attachImageReader() {
    if (sink != nullptr && sinkSurface != nullptr) {
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
    jobject localSink = env->CallObjectMethod(hostObj, hostMethods.attachSink, surface);
    if (localSink != nullptr) {
        sink = env->NewGlobalRef(localSink);
        env->DeleteLocalRef(localSink);
    }
    sinkSurface = env->NewGlobalRef(surface);
    env->DeleteLocalRef(surface);
    if (attached) javaVM->DetachCurrentThread();

    if (sink == nullptr) {
        LOGE("attachDisplaySink failed for display %d", displayId);
        detachImageReader();
        return false;
    }
    return true;
}

void ScriptRuntime::detachImageReader() {
    if (sink != nullptr || sinkSurface != nullptr) {
        JNIEnv *env = nullptr;
        bool attached = false;
        if (javaVM->GetEnv((void **) &env, JNI_VERSION_1_6) == JNI_EDETACHED) {
            if (javaVM->AttachCurrentThread(&env, nullptr) == JNI_OK) attached = true;
        }
        if (env) {
            if (sink) {
                env->CallVoidMethod(sink, sinkCloseMethodId);
                env->DeleteGlobalRef(sink);
                sink = nullptr;
            }
            if (sinkSurface) {
                // 先讓服務端不再送影格，再釋放自己這一份 Surface。
                env->CallVoidMethod(sinkSurface, surfaceReleaseMethodId);
                env->DeleteGlobalRef(sinkSurface);
                sinkSurface = nullptr;
            }
            if (attached) javaVM->DetachCurrentThread();
        }
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

bool ScriptRuntime::isMirrorActive(lua_State *L) {
    if (!isPhysical) return true;
    return hostBoolean(L, "screen.is_mirror_active", hostMethods.isMirrorActive);
}

bool ScriptRuntime::startMirror(lua_State *L) {
    if (!isPhysical) return true;
    // 實體螢幕上 reader 只會由這裡掛上，掛著就代表已經持有鏡像。
    if (visionEnabled) return true;

    if (!hostBoolean(L, "screen.start_mirror", hostMethods.acquireMirror)) {
        luaL_error(L, "screen.start_mirror: could not acquire the mirror of display %d", displayId);
    }

    uint64_t framesBefore = visionMatcher->frameCounter();
    if (!attachImageReader()) {
        hostBoolean(L, "screen.start_mirror", hostMethods.releaseMirror);
        luaL_error(L, "screen.start_mirror: could not attach the frame reader to display %d", displayId);
    }
    visionEnabled = true;
    firstFramePending = true;
    firstFrameAfter = framesBefore;
    return true;
}

void ScriptRuntime::awaitFirstFrameIfPending() {
    if (!firstFramePending || !visionEnabled) return;
    firstFramePending = false;
    if (!visionMatcher->waitForFrameAfter(firstFrameAfter, kFirstFrameTimeoutMs) && running.load()) {
        LOGE("no frame within %ld ms on display %d; vision calls will see nothing until one arrives",
             kFirstFrameTimeoutMs, displayId);
    }
}

bool ScriptRuntime::stopMirror(lua_State *L) {
    if (!isPhysical) return true;
    detachImageReader();
    visionEnabled = false;
    return hostBoolean(L, "screen.stop_mirror", hostMethods.releaseMirror);
}

jboolean ScriptRuntime::hostBoolean(lua_State *L, const char *function, jmethodID method, ...) {
    va_list args;
    va_start(args, method);
    jboolean result = luaEnv->CallBooleanMethodV(hostObj, method, args);
    va_end(args);
    raiseIfThrown(L, function);
    return result;
}

jobject ScriptRuntime::hostObject(lua_State *L, const char *function, jmethodID method, ...) {
    va_list args;
    va_start(args, method);
    jobject result = luaEnv->CallObjectMethodV(hostObj, method, args);
    va_end(args);
    raiseIfThrown(L, function);
    return result;
}

void ScriptRuntime::hostVoid(lua_State *L, const char *function, jmethodID method, ...) {
    va_list args;
    va_start(args, method);
    luaEnv->CallVoidMethodV(hostObj, method, args);
    va_end(args);
    raiseIfThrown(L, function);
}

void ScriptRuntime::raiseIfThrown(lua_State *L, const char *function) {
    jthrowable thrown = luaEnv->ExceptionOccurred();
    if (thrown == nullptr) return;
    luaEnv->ExceptionClear();
    LocalRef<jthrowable> thrownRef(luaEnv, thrown);

    // DeadObjectException 之類通常沒有 message，退而用類別名稱，至少看得出是哪一種失敗。
    LocalRef<jstring> message(luaEnv, (jstring) luaEnv->CallObjectMethod(thrown, throwableGetMessage));
    std::string text;
    if (message.get() != nullptr) {
        const char *chars = luaEnv->GetStringUTFChars(message.get(), nullptr);
        text = chars;
        luaEnv->ReleaseStringUTFChars(message.get(), chars);
    } else {
        LocalRef<jobject> type(luaEnv, luaEnv->CallObjectMethod(thrown, objectGetClass));
        LocalRef<jstring> name(luaEnv, (jstring) luaEnv->CallObjectMethod(type.get(), classGetName));
        const char *chars = luaEnv->GetStringUTFChars(name.get(), nullptr);
        text = chars;
        luaEnv->ReleaseStringUTFChars(name.get(), chars);
    }
    luaL_error(L, "%s: %s", function, text.c_str());
}

bool ScriptRuntime::interruptibleSleep(long ms) {
    if (!isRunning() && !cleaningUp.load()) return false;
    if (cleaningUp.load()) return true;  // 收尾中不真的睡，但也不算被打斷
    if (ms <= 0) return true;

    std::unique_lock<std::mutex> lock(sleepMutex);
    sleepCv.wait_for(lock, std::chrono::milliseconds(ms), [this] { return !isRunning(); });
    return isRunning();
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
    // 不是 Lua API，可能在呼叫端的執行緒上，沒有 Lua 堆疊可以拋錯；但不能留下 pending exception。
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        LOGE("onEngineEvent(%d) threw", type);
    }

    if (attached) javaVM->DetachCurrentThread();
}

jobject ScriptRuntime::boxLuaValue(lua_State *L, int index) {
    JNIEnv *e = env();

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
    lua_getfield(L, LUA_REGISTRYINDEX, moonclicker::kRuntimeRegistryKey);
    auto *self = static_cast<ScriptRuntime *>(lua_touserdata(L, -1));
    lua_pop(L, 1);

    if (self && !self->isRunning()) {
        // 錯誤訊息不會被使用者看到：runScript 看到 running == false 就回報 STOPPED，REPL 則回報 INTERRUPTED。
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

    if (replMode) {
        runRepl();
    } else {
        runScript();
    }

    luaEnv = nullptr;
    javaVM->DetachCurrentThread();
}

void ScriptRuntime::setUpState(lua_State *L) {
    lua_pushlightuserdata(L, this);
    lua_setfield(L, LUA_REGISTRYINDEX, moonclicker::kRuntimeRegistryKey);

    moonclicker::registerApi(L, this);

    // require 以腳本資料夾為根，讓多檔腳本可以 require("helpers")。
    lua_getglobal(L, "package");
    lua_pushstring(L, (scriptDir + "?.lua;" + scriptDir + "?/init.lua").c_str());
    lua_setfield(L, -2, "path");
    lua_pop(L, 1);
}

void ScriptRuntime::runScript() {
    lua_State *L = luaEngine->state();
    setUpState(L);

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

void ScriptRuntime::runRepl() {
    lua_State *L = luaEngine->state();
    setUpState(L);
    lua_sethook(L, stopHook, LUA_MASKCOUNT, 1000);
    pushEvent(EngineEventType::RUNNING, "");

    while (true) {
        ReplChunk chunk;
        {
            std::unique_lock<std::mutex> lock(replMutex);
            replCv.wait(lock, [this] { return !running.load() || !replQueue.empty(); });
            if (!running.load()) break;
            chunk = std::move(replQueue.front());
            replQueue.pop_front();
            replExecuting = true;
            interrupted.store(false);
        }
        evalReplChunk(L, chunk.id, chunk.code);
        {
            std::lock_guard<std::mutex> lock(replMutex);
            replExecuting = false;
            interrupted.store(false);
        }
    }

    lua_sethook(L, nullptr, 0, 0);
    pushEvent(EngineEventType::STOPPED, "");
}

/** 以 luaL_tolstring 把堆疊上 [first] 起的值以 tab 串起來，與官方 `lua` REPL 印回傳值的方式相同。 */
static std::string joinValues(lua_State *L, int first) {
    std::string out;
    int top = lua_gettop(L);
    for (int i = first; i <= top; i++) {
        if (i > first) out += '\t';
        size_t length = 0;
        const char *text = luaL_tolstring(L, i, &length);
        out.append(text, length);
        lua_pop(L, 1);
    }
    return out;
}

void ScriptRuntime::evalReplChunk(lua_State *L, int64_t id, const std::string &code) {
    lua_settop(L, 0);

    // 與官方 `lua` 相同：先當運算式（補上 return）試，不成再當敘述。
    std::string asExpression = "return " + code + ";";
    int status = luaL_loadbuffer(L, asExpression.data(), asExpression.size(), "=repl");
    if (status != LUA_OK) {
        lua_pop(L, 1);
        status = luaL_loadbuffer(L, code.data(), code.size(), "=repl");
    }
    if (status != LUA_OK) {
        size_t length = 0;
        const char *text = lua_tolstring(L, -1, &length);
        std::string message = text ? std::string(text, length) : "syntax error";
        lua_settop(L, 0);
        // 語法錯誤停在檔尾，就是還沒寫完。
        static const std::string eof = "<eof>";
        bool incomplete = message.size() >= eof.size() &&
                          message.compare(message.size() - eof.size(), eof.size(), eof) == 0;
        reportReplResult(id, incomplete ? ReplStatus::INCOMPLETE : ReplStatus::ERROR, message);
        return;
    }

    lua_pushcfunction(L, traceback);
    lua_insert(L, 1);
    status = lua_pcall(L, 0, LUA_MULTRET, 1);

    if (!running.load()) {
        lua_settop(L, 0);
        return;  // 整個 REPL 被關掉，沒有人在等結果
    }
    if (interrupted.load()) {
        lua_settop(L, 0);
        reportReplResult(id, ReplStatus::INTERRUPTED, "interrupted");
        return;
    }

    std::string output;
    if (status == LUA_OK) {
        output = joinValues(L, 2);
    } else {
        size_t length = 0;
        const char *text = lua_tolstring(L, -1, &length);
        output = text ? std::string(text, length) : "(non-string error)";
    }
    lua_settop(L, 0);
    reportReplResult(id, status == LUA_OK ? ReplStatus::OK : ReplStatus::ERROR, output);
}

void ScriptRuntime::reportReplResult(int64_t id, int status, const std::string &output) {
    // 以 UTF-8 bytes 過橋：NewStringUTF 吃的是 modified UTF-8，4 位元組的字元（emoji）會壞掉。
    LocalRef<jbyteArray> bytes(luaEnv, luaEnv->NewByteArray(static_cast<jsize>(output.size())));
    luaEnv->SetByteArrayRegion(bytes.get(), 0, static_cast<jsize>(output.size()),
                               reinterpret_cast<const jbyte *>(output.data()));
    luaEnv->CallVoidMethod(hostObj, hostMethods.replResult, static_cast<jlong>(id), status, bytes.get());
    if (luaEnv->ExceptionCheck()) {
        luaEnv->ExceptionDescribe();
        luaEnv->ExceptionClear();
        LOGE("replResult(%lld) threw", static_cast<long long>(id));
    }
}
