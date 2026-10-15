#ifndef MOONCLICKER_SCRIPT_RUNTIME_H
#define MOONCLICKER_SCRIPT_RUNTIME_H

#include "LuaEngine.h"
#include "Ocr.h"
#include "NativeImageReader.h"
#include "VisionMatcher.h"

#include <jni.h>
#include <android/native_window.h>

#include <atomic>
#include <condition_variable>
#include <deque>
#include <memory>
#include <mutex>
#include <string>
#include <thread>

/** 必須與 com.xaxaxax.moonclicker.engine.state.EngineEventType 一致。 */
namespace EngineEventType {
    constexpr int RUNNING = 0;
    constexpr int FINISHED = 1;
    constexpr int ERROR = 2;
    constexpr int STOPPED = 3;
    constexpr int VISION_RESULT = 4;
}

/** ScriptHost（Kotlin）上的 upcall 方法。非視覺的 Lua API 全部經由它實作。 */
struct HostMethods {
    jmethodID swipe = nullptr;      // (I[IJZ)V   pointerId, points, durationMs, keep
    jmethodID pointerUp = nullptr;  // (I)V
    jmethodID key = nullptr;        // (I)Z
    jmethodID text = nullptr;       // (Ljava/lang/String;)Z
    jmethodID launch = nullptr;     // (Ljava/lang/String;)Z
    jmethodID listApps = nullptr;   // ()[Lcom/xaxaxax/moonclicker/MoonClickerAppTask;
    jmethodID mute = nullptr;       // (Ljava/lang/String;Z)Z
    jmethodID notify = nullptr;     // (Ljava/lang/String;Ljava/lang/String;)V
    jmethodID openUri = nullptr;    // (Ljava/lang/String;)V
    jmethodID setData = nullptr;    // (Ljava/lang/String;Ljava/lang/Object;)V
    jmethodID onEvent = nullptr;    // (ILjava/lang/String;)V
    jmethodID log = nullptr;        // (Ljava/lang/String;)V
    jmethodID attachSink = nullptr; // (Landroid/view/Surface;)Lcom/xaxaxax/moonclicker/DisplaySink;
    jmethodID acquireMirror = nullptr;  // ()Z
    jmethodID releaseMirror = nullptr;  // ()Z
    jmethodID isMirrorActive = nullptr; // ()Z
    jmethodID replResult = nullptr;     // (JI[B)V   id, status, UTF-8 output
};

/** 必須與 com.xaxaxax.moonclicker.engine.ReplStatus 的 code 一致。 */
namespace ReplStatus {
    constexpr int OK = 0;
    constexpr int ERROR = 1;
    /** 語法上還沒寫完（像官方 `lua` 的 `>>` 續行）。 */
    constexpr int INCOMPLETE = 2;
    constexpr int INTERRUPTED = 3;
}

/**
 * JNI local ref 的擁有者，解構時 DeleteLocalRef。Lua 執行緒是 native attach 的，local ref 要等
 * 執行緒 detach 才回收；腳本用 pcall 包住會拋錯的呼叫跑迴圈時，手動刪的 ref 會在拋錯時漏掉。
 * Lua 以 C++ 編譯，Lua error 是 C++ 例外，這個解構子在拋錯時也會執行。
 */
template<typename T>
class LocalRef {
public:
    LocalRef(JNIEnv *env, T ref) : env(env), ref(ref) {}

    ~LocalRef() { if (ref != nullptr) env->DeleteLocalRef(ref); }

    LocalRef(const LocalRef &) = delete;

    LocalRef &operator=(const LocalRef &) = delete;

    T get() const { return ref; }

private:
    JNIEnv *env;
    T ref;
};

/** MoonClickerAppTask（AIDL parcelable）的欄位。 */
struct AppTaskFields {
    jfieldID packageName = nullptr;  // Ljava/lang/String;
    jfieldID displayId = nullptr;    // I
};

/**
 * 跑一份腳本：`main.lua` 在一條專屬執行緒上從上到下執行，跑完就結束。
 *
 * 沒有 tick 迴圈、沒有 coroutine。會阻塞的 Lua API（`sleep`、`vision.wait`、`input.*`）
 * 就真的在那條執行緒上阻塞，而且全部可以被 [stop] 打斷——這就是「線性腳本」的全部實作。
 *
 * 它**不擁有目標顯示器**：顯示器由 :app 建立與銷毀，這裡只把自己的 ImageReader surface
 * 掛上去，結束時再拿掉。
 *
 * 對 Kotlin 的呼叫一律經由 ScriptHost，不直接碰 IMoonClickerService；鏡像引用也由 ScriptHost
 * 持有，腳本結束時由它歸還。
 *
 * REPL 模式（[startRepl]）用同一套 API 與停止機制，差別只在執行緒不跑 `main.lua`，而是等
 * [evalChunk] 送來的程式碼。全域變數留在同一個 Lua state 裡；[interrupt] 只打斷當下這一段。
 */
class ScriptRuntime {
public:
    ScriptRuntime(JNIEnv *env, jobject host);

    ~ScriptRuntime();

    /**
     * @param displayId     目標顯示器。
     * @param withVision    是否掛 ImageReader 取影格。實體螢幕拿不到影格，必須傳 false。
     * @param surfaceWidth  顯示器**建立時**的尺寸（surface 空間），不是旋轉後的邏輯尺寸。
     * @param initialRotation 啟動當下的 rotation，在腳本執行緒起跑前就設好。
     * @param scriptDir     腳本資料夾，必須以 '/' 結尾，內含 main.lua。
     * @param ocrPackDir    OCR 套件目錄；空字串表示未安裝或不支援，腳本用到 OCR 時報錯。
     * @param ocrThreads    OCR 的執行緒數（校準或使用者設定的值）。
     */
    bool start(int displayId, bool isPhysical, bool withVision, int surfaceWidth, int surfaceHeight,
               int initialRotation, const std::string &scriptDir, const std::string &ocrPackDir,
               int ocrThreads);

    /**
     * 以 REPL 模式啟動：同一個 Lua state 一直留著，執行緒等 [evalChunk] 送來的程式碼，一段一段跑。
     * 參數同 [start]；[scriptDir] 是 `require` 與模板路徑的根。
     */
    bool startRepl(int displayId, bool isPhysical, bool withVision, int surfaceWidth, int surfaceHeight,
                   int initialRotation, const std::string &scriptDir, const std::string &ocrPackDir,
                   int ocrThreads);

    /** REPL：排入一段程式碼，跑完以 ScriptHost.replResult 回報 [id] 的結果。 */
    void evalChunk(int64_t id, std::string code);

    /** REPL：中斷正在跑的那一段；Lua state 保留，佇列中的下一段照跑。沒有在跑時不做事。 */
    void interrupt();

    /** 要求停止並等執行緒結束。可重入。 */
    void stop();

    /** 腳本（或 REPL 當下這一段）還該不該繼續跑；會阻塞的 binding 以它判斷是否被叫停。 */
    bool isRunning() const { return running.load() && !interrupted.load(); }

    // --- 以下給 LuaBindings 使用，全部只在 Lua 執行緒上呼叫 -----------------

    JNIEnv *env() const { return luaEnv; }

    jobject hostObject() const { return hostObj; }

    const HostMethods &host() const { return hostMethods; }

    /**
     * 呼叫 ScriptHost 的 [method]。ScriptHost 讓基礎設施失敗的例外穿出來；有例外時清掉它，
     * 以 `"<function>: <訊息>"` 拋 Lua error（不回來）。訊息為 null 時用例外的類別名稱。
     */
    jboolean hostBoolean(lua_State *L, const char *function, jmethodID method, ...);

    /** 同 [hostBoolean]；回傳的 local ref 由呼叫端擁有。 */
    jobject hostObject(lua_State *L, const char *function, jmethodID method, ...);

    /** 同 [hostBoolean]。 */
    void hostVoid(lua_State *L, const char *function, jmethodID method, ...);

    const AppTaskFields &appTaskFields() const { return appTask; }

    VisionMatcher &vision() { return *visionMatcher; }

    bool hasVision() const { return visionEnabled; }

    /**
     * 腳本第一次用到 OCR 時才載入（A21s 約 0.6 秒），腳本結束時釋放。
     * @return 失敗時為 null，原因寫進 [error]。
     */
    Ocr *ocr(std::string &error);

    bool isPhysicalDisplay() const { return isPhysical; }

    bool isMirrorActive(lua_State *L);

    /** 取不到鏡像或掛不上 reader 時拋 Lua error。 */
    bool startMirror(lua_State *L);

    bool stopMirror(lua_State *L);

    /**
     * 第一次呼叫 vision.* 時等影格管線送來第一張影格，最多 [kFirstFrameTimeoutMs]；之後的呼叫
     * 直接返回。影格管線剛接上時要 50–135 ms 才有第一張，那之前 vision.find 會拿到空影格而
     * 回傳 nil，腳本分不出「畫面上沒有」與「還沒有畫面」（#166）。延到第一次呼叫才等，
     * 不用 vision 的腳本就不必付這段時間。screen.start_mirror() 之後要等的是新的影格。
     */
    void awaitFirstFrameIfPending();

    /** 可被 [stop] 打斷的 sleep。回傳 false 表示被叫停，binding 應盡快收手。 */
    bool interruptibleSleep(long ms);

    void pushEvent(int type, const std::string &payload);

    /** 把 Lua 堆疊上 [index] 的值裝箱成 Java 物件（number/string/boolean/table→JSON）。 */
    jobject boxLuaValue(lua_State *L, int index);

private:
    /** start 與 startRepl 共用：記下參數、掛 reader、開 Lua state。 */
    bool prepare(int displayId, bool isPhysical, bool withVision, int surfaceWidth, int surfaceHeight,
                 int initialRotation, const std::string &scriptDir, const std::string &ocrPackDir,
                 int ocrThreads);

    void threadMain();

    /** 註冊 API、設定 require 路徑。 */
    void setUpState(lua_State *L);

    void runScript();

    void runRepl();

    void evalReplChunk(lua_State *L, int64_t id, const std::string &code);

    void reportReplResult(int64_t id, int status, const std::string &output);
    static constexpr long kFirstFrameTimeoutMs = 2000;
    /** 下一次 vision.* 呼叫前要等 [firstFrameAfter] 之後的影格。只在腳本執行緒上讀寫。 */
    bool firstFramePending = false;
    uint64_t firstFrameAfter = 0;

    /** 每 1000 個指令檢查一次是否被叫停；被叫停就把腳本中斷掉。 */
    static void stopHook(lua_State *L, lua_Debug *ar);

    bool attachImageReader();
    void detachImageReader();

    /** Lua 執行緒上有 pending exception 時：清掉，以 `"<function>: <訊息>"` 拋 Lua error。 */
    void raiseIfThrown(lua_State *L, const char *function);

    std::unique_ptr<LuaEngine> luaEngine;
    std::unique_ptr<NativeImageReader> imageReader;
    std::unique_ptr<VisionMatcher> visionMatcher;
    std::unique_ptr<Ocr> ocrEngine;
    std::string ocrPackDir;
    int ocrThreads = 4;

    JavaVM *javaVM = nullptr;
    JNIEnv *luaEnv = nullptr;     // 只在 Lua 執行緒上有效
    jobject hostObj = nullptr;
    HostMethods hostMethods;
    AppTaskFields appTask;

    jclass doubleClass = nullptr;
    jmethodID doubleConstructor = nullptr;
    jclass booleanClass = nullptr;
    jmethodID booleanConstructor = nullptr;

    jmethodID sinkCloseMethodId = nullptr;      // DisplaySink.close
    jmethodID surfaceReleaseMethodId = nullptr; // android.view.Surface.release
    jmethodID throwableGetMessage = nullptr;    // Throwable.getMessage
    jmethodID objectGetClass = nullptr;         // Object.getClass
    jmethodID classGetName = nullptr;           // Class.getName

    /**
     * ANativeWindow_toSurface 產生的 Java Surface。必須留著到收尾時明確 release()——
     * 只 DeleteLocalRef 的話底層資源要等 finalizer 才回收，logcat 會出現
     * 「A resource failed to call Surface.release.」，而且每跑一次漏一個。
     */
    jobject sinkSurface = nullptr;
    /** 掛著時是 ScriptHost.attachSink 回傳的 DisplaySink（global ref），收尾時 close()。 */
    jobject sink = nullptr;

    std::string scriptDir;
    int displayId = -1;
    bool isPhysical = false;
    int surfaceWidth = 0;
    int surfaceHeight = 0;
    bool visionEnabled = false;

    std::thread luaThread;
    std::atomic<bool> running{false};
    /** REPL 當下這一段被中斷。只在 [replExecuting] 時設起，每段開始與結束時清掉。 */
    std::atomic<bool> interrupted{false};

    bool replMode = false;
    struct ReplChunk {
        int64_t id;
        std::string code;
    };
    /** 以下三個由 [replMutex] 保護。 */
    std::deque<ReplChunk> replQueue;
    bool replExecuting = false;
    std::mutex replMutex;
    std::condition_variable replCv;
    /** on_stop 收尾期間為 true——此時 sleep 立刻返回，避免收尾把停止流程拖住。 */
    std::atomic<bool> cleaningUp{false};

    std::mutex sleepMutex;
    std::condition_variable sleepCv;
};

#endif // MOONCLICKER_SCRIPT_RUNTIME_H
