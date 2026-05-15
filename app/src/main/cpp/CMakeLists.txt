cmake_minimum_required(VERSION 3.22.1)

project("relc_native")

# 這裡改用 find_package 或由 Gradle 傳入的 OpenCV_DIR
# 暫時先註釋掉舊的硬編碼路徑，避免 Ninja 報錯
# set(OPENCV_DIR ${CMAKE_CURRENT_SOURCE_DIR}/opencv)

# 如果你已經下載了 OpenCV Android SDK 並放在某處，可以在這裡設定路徑
# set(OpenCV_DIR "C:/path/to/OpenCV-android-sdk/sdk/native/jni")

find_package(OpenCV REQUIRED)

include_directories(${OpenCV_INCLUDE_DIRS})

# Main native library
add_library(relc_native SHARED
        relc_native.cpp
)

find_library(log-lib log)

target_link_libraries(relc_native
        ${OpenCV_LIBS}
        jnigraphics
        ${log-lib}
)
