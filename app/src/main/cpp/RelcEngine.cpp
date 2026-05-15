#include "RelcEngine.h"
#include <opencv2/imgproc.hpp>
#include <opencv2/imgcodecs.hpp>

RelcEngine::RelcEngine(JNIEnv* env, jobject service) : displayId(0) {
    env->GetJavaVM(&javaVM);
    serviceObj = env->NewGlobalRef(service);

    jclass serviceClass = env->GetObjectClass(serviceObj);
    // V2 Method: multiTouchSwipe(int pointerId, int displayId, int[] points, long duration, boolean keep)
    swipeMethodId = env->GetMethodID(serviceClass, "multiTouchSwipe", "(II[IJZ)V");
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

    if (!luaEngine->loadScript(script)) return false;

    imageReader = std::make_unique<NativeImageReader>(width, height);
    if (!imageReader->init()) return false;

    imageReader->setCallback([this](const cv::Mat& frame) {
        processFrame(frame);
    });

    return true;
}

void RelcEngine::stop() {
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

    if (lua_istable(L, -1)) {
        int n = lua_rawlen(L, -1);
        if (n != templates.size()) {
            templates.clear();
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
                            templates.push_back(t);
                        }
                    }
                }
                lua_pop(L, 1);
            }
        }
    }
    lua_pop(L, 2);
}

void RelcEngine::processFrame(const cv::Mat& frame) {
    updateTemplatesFromLua();

    lua_State* L = luaEngine->getLuaState();
    lua_getglobal(L, "match");
    lua_newtable(L);

    for (const auto& t : templates) {
        if (t.image.empty() || frame.cols < t.image.cols || frame.rows < t.image.rows) continue;

        cv::Mat result;
        cv::matchTemplate(frame, t.image, result, cv::TM_CCOEFF_NORMED);
        
        double minVal, maxVal;
        cv::Point minLoc, maxLoc;
        cv::minMaxLoc(result, &minVal, &maxVal, &minLoc, &maxLoc);

        if (maxVal >= t.threshold) {
            lua_newtable(L);
            lua_pushboolean(L, true); lua_setfield(L, -2, "found");
            lua_pushnumber(L, maxLoc.x + t.image.cols / 2); lua_setfield(L, -2, "x");
            lua_pushnumber(L, maxLoc.y + t.image.rows / 2); lua_setfield(L, -2, "y");
            lua_pushnumber(L, maxVal); lua_setfield(L, -2, "confidence");
            lua_setfield(L, -2, t.name.c_str());
        }
    }
    lua_setfield(L, -2, "result");
    lua_pop(L, 1);

    luaEngine->resume();
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
    
    // swipe(pointerId, pointsTable, duration, keep)
    int pointerId = (int)luaL_checkinteger(L, 1);
    luaL_checktype(L, 2, LUA_TTABLE);
    long duration = (long)luaL_optinteger(L, 3, 0);
    bool keep = lua_toboolean(L, 4);

    std::vector<int> points;
    int n = lua_rawlen(L, 2);
    for (int i = 1; i <= n; i++) {
        lua_rawgeti(L, 2, i);
        if (lua_istable(L, -1)) {
            // Nested table: {{x, y}, {x, y}}
            int innerN = lua_rawlen(L, -1);
            for (int j = 1; j <= innerN; j++) {
                lua_rawgeti(L, -1, j);
                points.push_back((int)luaL_checkinteger(L, -1));
                lua_pop(L, 1);
            }
        } else {
            // Flat table: {x1, y1, x2, y2}
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
