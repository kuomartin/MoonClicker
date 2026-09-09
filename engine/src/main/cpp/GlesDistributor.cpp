#include "GlesDistributor.h"
#include <android/log.h>

#define LOG_TAG "GlesDistributor"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)

static const char *VERTEX_SHADER =
        "attribute vec4 vPosition;\n"
        "attribute vec2 vTexCoord;\n"
        "varying vec2 texCoord;\n"
        "void main() {\n"
        "  gl_Position = vPosition;\n"
        "  texCoord = vTexCoord;\n"
        "}\n";

static const char *FRAGMENT_SHADER =
        "#extension GL_OES_EGL_image_external : require\n"
        "precision mediump float;\n"
        "varying vec2 texCoord;\n"
        "uniform samplerExternalOES sTexture;\n"
        "void main() {\n"
        "  gl_FragColor = texture2D(sTexture, texCoord);\n"
        "}\n";

static const GLfloat VERTICES[] = {
        -1.0f, 1.0f, 0.0f,
        -1.0f, -1.0f, 0.0f,
        1.0f, 1.0f, 0.0f,
        1.0f, -1.0f, 0.0f
};

static const GLfloat TEX_COORDS[] = {
        0.0f, 0.0f,
        0.0f, 1.0f,
        1.0f, 0.0f,
        1.0f, 1.0f
};

GlesDistributor::GlesDistributor(int width, int height)
        : width(width), height(height), eglDisplay(EGL_NO_DISPLAY), eglContext(EGL_NO_CONTEXT),
          eglPbufferSurface(EGL_NO_SURFACE), eglConfig(nullptr), textureId(0), program(0),
          vPositionHandle(0), vTextureHandle(0),
          jSurfaceTexture(nullptr), jSurface(nullptr), isRunning(false), frameAvailable(false),
          javaVM(nullptr), nextHandle(1) {}

GlesDistributor::~GlesDistributor() = default;

bool GlesDistributor::init(JNIEnv *env) {
    env->GetJavaVM(&javaVM);

    setupEGL();
    if (eglContext == EGL_NO_CONTEXT) return false;

    auto loadShader = [](GLenum type, const char *source) -> GLuint {
        GLuint shader = glCreateShader(type);
        glShaderSource(shader, 1, &source, nullptr);
        glCompileShader(shader);
        GLint compiled;
        glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
        if (!compiled) {
            GLint infoLen = 0;
            glGetShaderiv(shader, GL_INFO_LOG_LENGTH, &infoLen);
            if (infoLen > 0) {
                char *infoLog = (char *) malloc(infoLen);
                glGetShaderInfoLog(shader, infoLen, nullptr, infoLog);
                LOGE("Error compiling shader:\n%s\n", infoLog);
                free(infoLog);
            }
            glDeleteShader(shader);
            return 0;
        }
        return shader;
    };

    GLuint vShader = loadShader(GL_VERTEX_SHADER, VERTEX_SHADER);
    GLuint fShader = loadShader(GL_FRAGMENT_SHADER, FRAGMENT_SHADER);
    if (!vShader || !fShader) return false;

    program = glCreateProgram();
    glAttachShader(program, vShader);
    glAttachShader(program, fShader);
    glLinkProgram(program);
    GLint linked;
    glGetProgramiv(program, GL_LINK_STATUS, &linked);
    if (!linked) {
        LOGE("Error linking program");
        return false;
    }
    glUseProgram(program);

    vPositionHandle = glGetAttribLocation(program, "vPosition");
    vTextureHandle = glGetAttribLocation(program, "vTexCoord");

    glGenTextures(1, &textureId);
    glBindTexture(GL_TEXTURE_EXTERNAL_OES, textureId);
    glTexParameterf(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameterf(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_EXTERNAL_OES, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

    jclass stClass = env->FindClass("android/graphics/SurfaceTexture");
    jmethodID stCtor = env->GetMethodID(stClass, "<init>", "(I)V");
    jobject st = env->NewObject(stClass, stCtor, (int) textureId);
    jSurfaceTexture = env->NewGlobalRef(st);

    jclass sClass = env->FindClass("android/view/Surface");
    jmethodID sCtor = env->GetMethodID(sClass, "<init>", "(Landroid/graphics/SurfaceTexture;)V");
    jobject s = env->NewObject(sClass, sCtor, jSurfaceTexture);
    jSurface = env->NewGlobalRef(s);

    eglMakeCurrent(eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);

    isRunning = true;
    renderThread = std::thread(&GlesDistributor::renderLoop, this);

    return true;
}

void GlesDistributor::release(JNIEnv *env) {
    isRunning = false;
    {
        std::lock_guard<std::mutex> lock(frameMutex);
        frameCond.notify_all();
    }
    if (renderThread.joinable()) renderThread.join();

    if (jSurface) {
        env->DeleteGlobalRef(jSurface);
        jSurface = nullptr;
    }
    if (jSurfaceTexture) {
        env->DeleteGlobalRef(jSurfaceTexture);
        jSurfaceTexture = nullptr;
    }

    terminateEGL();
}

jobject GlesDistributor::getSurface(JNIEnv *env) {
    return jSurface;
}

int GlesDistributor::addSurface(JNIEnv *env, jobject surface) {
    std::lock_guard<std::mutex> lock(sinksMutex);

    if (!eglDisplay || eglConfig == nullptr) {
        LOGE("Cannot add surface: EGL not initialized");
        return -1;
    }

    ANativeWindow *window = ANativeWindow_fromSurface(env, surface);
    if (!window) {
        LOGE("Failed to get ANativeWindow from Surface");
        return -1;
    }

    EGLint format;
    eglGetConfigAttrib(eglDisplay, eglConfig, EGL_NATIVE_VISUAL_ID, &format);
    ANativeWindow_setBuffersGeometry(window, 0, 0, format);

    EGLSurface eglSurface = eglCreateWindowSurface(eglDisplay, eglConfig, window, nullptr);
    if (eglSurface != EGL_NO_SURFACE) {
        int handle = nextHandle++;
        jobject globalSurface = env->NewGlobalRef(surface);
        sinks.push_back({handle, globalSurface, window, eglSurface});
        LOGD("Successfully added sink handle %d, window %p. Total sinks: %zu", handle, window,
             sinks.size());
        return handle;
    } else {
        LOGE("Failed to create EGL window surface: %x", eglGetError());
        ANativeWindow_release(window);
        return -1;
    }
}

void GlesDistributor::removeSurface(int handle) {
    std::lock_guard<std::mutex> lock(sinksMutex);
    for (auto it = sinks.begin(); it != sinks.end(); ++it) {
        if (it->handle == handle) {
            LOGD("Removing sink handle %d, surface %p", handle, it->window);
            eglDestroySurface(eglDisplay, it->eglSurface);
            ANativeWindow_release(it->window);

            JNIEnv *env;
            if (javaVM->GetEnv((void **) &env, JNI_VERSION_1_6) == JNI_OK) {
                env->DeleteGlobalRef(it->jSurface);
            }

            sinks.erase(it);
            LOGD("Removed sink handle %d. Total sinks: %zu", handle, sinks.size());
            return;
        }
    }
    LOGE("removeSurface: Handle %d NOT found in sinks!", handle);
}

void GlesDistributor::setupEGL() {
    eglDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    if (eglDisplay == EGL_NO_DISPLAY) {
        LOGE("eglGetDisplay failed: %x", eglGetError());
        return;
    }

    if (!eglInitialize(eglDisplay, nullptr, nullptr)) {
        LOGE("eglInitialize failed: %x", eglGetError());
        return;
    }

    EGLint configAttribs[] = {
            EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
            EGL_SURFACE_TYPE, EGL_WINDOW_BIT | EGL_PBUFFER_BIT,
            EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8,
            EGL_DEPTH_SIZE, 0, EGL_STENCIL_SIZE, 0,
            EGL_NONE
    };

    EGLint numConfigs;
    if (!eglChooseConfig(eglDisplay, configAttribs, &eglConfig, 1, &numConfigs) ||
        numConfigs == 0) {
        LOGE("eglChooseConfig failed or no configs found: %x", eglGetError());
        return;
    }

    EGLint contextAttribs[] = {EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE};
    eglContext = eglCreateContext(eglDisplay, eglConfig, EGL_NO_CONTEXT, contextAttribs);
    if (eglContext == EGL_NO_CONTEXT) {
        LOGE("eglCreateContext failed: %x", eglGetError());
        return;
    }

    EGLint pbufferAttribs[] = {EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE};
    eglPbufferSurface = eglCreatePbufferSurface(eglDisplay, eglConfig, pbufferAttribs);
    if (eglPbufferSurface == EGL_NO_SURFACE) {
        LOGE("eglCreatePbufferSurface failed: %x", eglGetError());
        return;
    }

    if (!eglMakeCurrent(eglDisplay, eglPbufferSurface, eglPbufferSurface, eglContext)) {
        LOGE("eglMakeCurrent failed in setupEGL: %x", eglGetError());
    } else {
        LOGD("EGL initialized successfully");
    }
}

void GlesDistributor::terminateEGL() {
    if (eglDisplay != EGL_NO_DISPLAY) {
        eglMakeCurrent(eglDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        if (eglContext != EGL_NO_CONTEXT) eglDestroyContext(eglDisplay, eglContext);
        if (eglPbufferSurface != EGL_NO_SURFACE) eglDestroySurface(eglDisplay, eglPbufferSurface);

        std::lock_guard<std::mutex> lock(sinksMutex);
        JNIEnv *env;
        bool attached = false;
        if (javaVM->GetEnv((void **) &env, JNI_VERSION_1_6) == JNI_EDETACHED) {
            javaVM->AttachCurrentThread(&env, nullptr);
            attached = true;
        }

        for (auto &sink: sinks) {
            eglDestroySurface(eglDisplay, sink.eglSurface);
            ANativeWindow_release(sink.window);
            if (env) env->DeleteGlobalRef(sink.jSurface);
        }
        sinks.clear();

        if (attached) javaVM->DetachCurrentThread();

        eglTerminate(eglDisplay);
    }
    eglDisplay = EGL_NO_DISPLAY;
    eglContext = EGL_NO_CONTEXT;
    eglPbufferSurface = EGL_NO_SURFACE;
}

void GlesDistributor::renderLoop() {
    JNIEnv *env;
    if (javaVM->AttachCurrentThread(&env, nullptr) != JNI_OK) {
        LOGE("Failed to attach render thread to Java VM");
        return;
    }

    jclass stClass = env->GetObjectClass(jSurfaceTexture);
    jmethodID updateTexImage = env->GetMethodID(stClass, "updateTexImage", "()V");
    jmethodID setDefaultBufferSize = env->GetMethodID(stClass, "setDefaultBufferSize", "(II)V");

    env->CallVoidMethod(jSurfaceTexture, setDefaultBufferSize, width, height);
    LOGD("Render loop started, width=%d, height=%d", width, height);

    int frameCount = 0;
    auto lastLogTime = std::chrono::steady_clock::now();

    while (isRunning) {
        {
            std::unique_lock<std::mutex> lock(frameMutex);
            frameCond.wait_for(lock, std::chrono::milliseconds(10));
        }

        if (!isRunning) break;

        eglMakeCurrent(eglDisplay, eglPbufferSurface, eglPbufferSurface, eglContext);
        env->CallVoidMethod(jSurfaceTexture, updateTexImage);

        drawFrame();

        frameCount++;
        if (frameCount >= 60) {
            auto now = std::chrono::steady_clock::now();
            auto duration = std::chrono::duration_cast<std::chrono::milliseconds>(
                    now - lastLogTime).count();
            LOGD("Render loop heartbeat: 60 frames in %lld ms", duration);
            frameCount = 0;
            lastLogTime = now;
        }
    }

    LOGD("Render loop exiting");
    javaVM->DetachCurrentThread();
}

void GlesDistributor::drawFrame() {
    std::lock_guard<std::mutex> lock(sinksMutex);
    if (sinks.empty()) return;

    for (auto it = sinks.begin(); it != sinks.end();) {
        if (!eglMakeCurrent(eglDisplay, it->eglSurface, it->eglSurface, eglContext)) {
            LOGE("eglMakeCurrent failed for sink handle %d: %x", it->handle, eglGetError());
            it++;
            continue;
        }

        int w, h;
        eglQuerySurface(eglDisplay, it->eglSurface, EGL_WIDTH, &w);
        eglQuerySurface(eglDisplay, it->eglSurface, EGL_HEIGHT, &h);
        glViewport(0, 0, w, h);

        glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT);

        glUseProgram(program);
        glVertexAttribPointer(vPositionHandle, 3, GL_FLOAT, GL_FALSE, 0, VERTICES);
        glEnableVertexAttribArray(vPositionHandle);
        glVertexAttribPointer(vTextureHandle, 2, GL_FLOAT, GL_FALSE, 0, TEX_COORDS);
        glEnableVertexAttribArray(vTextureHandle);

        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_EXTERNAL_OES, textureId);
        glUniform1i(glGetUniformLocation(program, "sTexture"), 0);

        glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

        if (!eglSwapBuffers(eglDisplay, it->eglSurface)) {
            EGLint err = eglGetError();
            LOGE("eglSwapBuffers failed for handle %d: %x", it->handle, err);
            if (err == EGL_BAD_SURFACE || err == EGL_BAD_NATIVE_WINDOW) {
                LOGW("Emergency removing invalid sink handle %d", it->handle);
                eglDestroySurface(eglDisplay, it->eglSurface);
                ANativeWindow_release(it->window);

                JNIEnv *env;
                if (javaVM->GetEnv((void **) &env, JNI_VERSION_1_6) == JNI_OK) {
                    env->DeleteGlobalRef(it->jSurface);
                }
                it = sinks.erase(it);
                continue;
            }
        }
        it++;
    }
}
