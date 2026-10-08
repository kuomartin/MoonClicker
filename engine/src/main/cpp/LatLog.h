#pragma once
// spike/latency：量測紀錄同時寫 logcat 與 /data/user/0/<package>/files/lat.log。
// logcat 在高頻輸出時會掉行（Pixel 7a 上 80 行只留下 38 行），檔案才是可信的來源。
#include <android/log.h>
#include <cstdarg>
#include <cstdio>
#include <mutex>
#include <string>

inline void latLog(const char *fmt, ...) {
    static std::mutex mutex;
    static FILE *file = [] {
        char cmd[256] = {0};
        FILE *f = fopen("/proc/self/cmdline", "r");
        if (f) { fread(cmd, 1, sizeof(cmd) - 1, f); fclose(f); }
        std::string pkg(cmd);
        auto colon = pkg.find(':');
        if (colon != std::string::npos) pkg = pkg.substr(0, colon);
        return fopen(("/data/user/0/" + pkg + "/files/lat.log").c_str(), "a");
    }();
    char buf[1024];
    va_list args;
    va_start(args, fmt);
    vsnprintf(buf, sizeof(buf), fmt, args);
    va_end(args);
    __android_log_write(ANDROID_LOG_INFO, "LAT", buf);
    std::lock_guard<std::mutex> lock(mutex);
    if (file) {
        timespec ts{};
        clock_gettime(CLOCK_REALTIME, &ts);
        fprintf(file, "%ld.%03ld %s\n", (long) ts.tv_sec, ts.tv_nsec / 1000000, buf);
        fflush(file);
    }
}
