#include "OrtLoader.h"

#include <onnxruntime_cxx_api.h>

#include <dlfcn.h>
#include <mutex>

namespace {

std::mutex gMutex;
std::string gLoadedPath;

}  // namespace

std::string loadOrt(const std::string &libraryPath) {
    std::lock_guard<std::mutex> lock(gMutex);
    if (!gLoadedPath.empty()) {
        if (gLoadedPath == libraryPath) return {};
        return "ONNX Runtime is already loaded from " + gLoadedPath + "; restart the app to use " + libraryPath;
    }

    void *handle = dlopen(libraryPath.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (handle == nullptr) {
        const char *error = dlerror();
        return std::string("dlopen failed: ") + (error ? error : "unknown error");
    }

    using GetApiBase = const OrtApiBase *(*)();
    auto getApiBase = reinterpret_cast<GetApiBase>(dlsym(handle, "OrtGetApiBase"));
    if (getApiBase == nullptr) {
        dlclose(handle);
        return "OrtGetApiBase not found in " + libraryPath;
    }

    const OrtApiBase *base = getApiBase();
    const OrtApi *api = base->GetApi(ORT_API_VERSION);
    if (api == nullptr) {
        std::string version = base->GetVersionString();
        dlclose(handle);
        return "ONNX Runtime " + version + " does not provide API version " + std::to_string(ORT_API_VERSION);
    }

    Ort::InitApi(api);
    gLoadedPath = libraryPath;
    return {};
}
