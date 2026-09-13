plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.rikka.refine)
}

android {
    namespace = "com.xaxaxax.relc.engine"
    compileSdk {
        version = release(libs.versions.targetSdk.get().toInt())
    }

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        externalNativeBuild {
            cmake {
                cppFlags("-std=c++17")
                arguments(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                    "-DOpenCV_DIR=~/OpenCV-android-sdk/sdk/native/jni",
                    "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384"
                )
            }
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.directories += "~/OpenCV-android-sdk/sdk/native/libs"
        }
    }

    packaging {
        jniLibs {
            useLegacyPackaging = false
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        aidl = true
    }

    // Lua API 的測試受測的是 relc_native.so，所以必須真的跑在裝置上。它們不需要 Shizuku
    // 也不需要虛擬顯示——`IRelcV2Service` 由 RecordingRelcService 頂替——所以一台乾淨的
    // 模擬器就夠了。
    //
    //   ./gradlew :engine:api36DebugAndroidTest      # 快的那台，跳過比對
    //   ./gradlew :engine:api36aospDebugAndroidTest  # 有圖形堆疊，全部都跑
    //   ./gradlew :engine:connectedDebugAndroidTest  # 用已連線的裝置
    //
    // 只留一個 API level：這裡驗的是 Lua 綁定，不是平台行為（那是 :hidden-api-contract
    // 的矩陣在做的事）。兩台的差別不是 API level，是有沒有圖形堆疊。
    testOptions {
        managedDevices {
            localDevices {
                // ATD（automated test device）映像檔把圖形堆疊拿掉了：虛擬顯示建得起來、
                // 影格也照送，但每一張都是全黑。所以它跑得完 Tier 0 與 Tier 1 的
                // step1–5，`vision.*` 的比對會被 Tier1SpikeTest 的 assumption 跳過。
                // 開機快，適合平常跑。
                create("api36") {
                    device = "Pixel 6"
                    apiLevel = 36
                    systemImageSource = "aosp-atd"
                    testedAbi = "x86_64"
                }
                // 同一個 API level 的完整 AOSP 映像檔，唯一的差別是它真的會合成畫面——
                // 所以比對那一段只有在這台（或實機）上才真的被執行到。映像檔更大、開機更慢。
                create("api36aosp") {
                    device = "Pixel 6"
                    apiLevel = 36
                    systemImageSource = "aosp"
                    testedAbi = "x86_64"
                }
            }
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.runtime)
    implementation(libs.timber)
    implementation(libs.kotlinx.coroutines.android)

    // Shizuku
    implementation(libs.rikka.shizuku.api)
    implementation(libs.rikka.shizuku.shared)
    implementation(libs.rikka.shizuku.provider)
    compileOnly(project(":hidden-api"))
    implementation(libs.rikka.refine.runtime)
    annotationProcessor(libs.rikka.refine.annotation.processor)
    implementation(libs.hiddenapibypass)

    testImplementation(libs.junit)

    // Lua API 的 instrumentation 測試（engine/src/androidTest）。它們必須跑在裝置上——
    // 受測的是 relc_native.so 裡的 C++ 綁定，不是 Kotlin。
    androidTestImplementation(libs.androidx.junit)
    // AndroidJUnitRunner 本身；androidx.test.ext:junit 不會帶進來。
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.kotlinx.coroutines.android)
}
