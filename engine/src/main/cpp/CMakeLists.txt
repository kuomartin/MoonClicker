cmake_minimum_required(VERSION 3.22.1)

project("relc_native")

# OpenCV setup (Statically link only core, imgproc, imgcodecs to minimize APK size)
find_package(OpenCV REQUIRED core imgproc imgcodecs)

include_directories(${OpenCV_INCLUDE_DIRS})
include_directories(lua/lua-5.5.0)
include_directories(lua/lua-cjson-2.1.0.9)

# Lua Source Files (excluding lua.c, onelua.c, and ltests.c)
set(LUA_DIR lua/lua-5.5.0)
set(LUA_SRC
        ${LUA_DIR}/lapi.c
        ${LUA_DIR}/lauxlib.c
        ${LUA_DIR}/lbaselib.c
        ${LUA_DIR}/lcode.c
        ${LUA_DIR}/lcorolib.c
        ${LUA_DIR}/lctype.c
        ${LUA_DIR}/ldblib.c
        ${LUA_DIR}/ldebug.c
        ${LUA_DIR}/ldo.c
        ${LUA_DIR}/ldump.c
        ${LUA_DIR}/lfunc.c
        ${LUA_DIR}/lgc.c
        ${LUA_DIR}/linit.c
        ${LUA_DIR}/liolib.c
        ${LUA_DIR}/llex.c
        ${LUA_DIR}/lmathlib.c
        ${LUA_DIR}/lmem.c
        ${LUA_DIR}/loadlib.c
        ${LUA_DIR}/lobject.c
        ${LUA_DIR}/lopcodes.c
        ${LUA_DIR}/loslib.c
        ${LUA_DIR}/lparser.c
        ${LUA_DIR}/lstate.c
        ${LUA_DIR}/lstring.c
        ${LUA_DIR}/lstrlib.c
        ${LUA_DIR}/ltable.c
        ${LUA_DIR}/ltablib.c
        ${LUA_DIR}/ltm.c
        ${LUA_DIR}/lundump.c
        ${LUA_DIR}/lutf8lib.c
        ${LUA_DIR}/lvm.c
        ${LUA_DIR}/lzio.c
)

set(LUA_CJSON_DIR lua/lua-cjson-2.1.0.9)
set(LUA_CJSON_SRC
        ${LUA_CJSON_DIR}/lua_cjson.c
        ${LUA_CJSON_DIR}/strbuf.c
        ${LUA_CJSON_DIR}/fpconv.c
)


# Main native library
add_library(relc_native SHARED
        relc_native.cpp
        LuaEngine.cpp
        NativeImageReader.cpp
        ScriptRuntime.cpp
        VisionMatcher.cpp
        LuaBindings.cpp
        GlesDistributor.cpp
        ${LUA_SRC}
        ${LUA_CJSON_SRC}
)

# Suppress warnings for Lua source files
set_source_files_properties(${LUA_SRC} PROPERTIES COMPILE_FLAGS "-Wno-deprecated-declarations")
set_source_files_properties(${LUA_CJSON_SRC} PROPERTIES COMPILE_FLAGS "-Wno-deprecated-declarations")

find_library(android-lib android)
find_library(log-lib log)
find_library(media-lib mediandk)

target_link_libraries(relc_native
        ${OpenCV_LIBS}
        jnigraphics
        EGL
        GLESv2
        ${android-lib}
        ${log-lib}
        ${media-lib}
)
