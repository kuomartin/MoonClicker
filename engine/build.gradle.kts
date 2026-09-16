plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.rikka.refine)
}

val userHome: String = System.getProperty("user.home") ?: ""
val openCvSdkDir: String = providers.environmentVariable("OPENCV_ANDROID_SDK_DIR")
    .getOrElse(file("$userHome/OpenCV-android-sdk").absolutePath)

android {
    namespace = "com.xaxaxax.relc.engine"
    compileSdk {
        version = release(libs.versions.targetSdk.get().toInt())
    }

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                cppFlags("-std=c++17")
                arguments(
                    "-DANDROID_STL=c++_shared",
                    "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON",
                    "-DOpenCV_DIR=$openCvSdkDir/sdk/native/jni",
                    "-DCMAKE_SHARED_LINKER_FLAGS=-Wl,-z,max-page-size=16384"
                )
            }
        }
    }

    sourceSets {
        getByName("main") {
            jniLibs.directories += "$openCvSdkDir/sdk/native/libs"
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

                // Tier 1 的 API 矩陣。全部用**有圖形堆疊**的映像檔，不然比對那一段會被跳過，
                // 而跨版本要驗的正好包含它。27–29 只有 `default` 有；30 起用 `aosp`。
                //
                // 每一級都在問不同的問題：
                //   27/28  沒有 MotionEvent.setDisplayId，注入不到虛擬顯示（產品的能力邊界），
                //          而且走的是 launchOrMoveViaActivityManager 那條 legacy 啟動路徑。
                //   29     setDisplayId 出現的第一級。
                //   30/31  TRUSTED 旗標存在但 shell 通常還拿不到權限。
                //   33     ADD_TRUSTED_DISPLAY 通常開始給的那一級。
                //   34/35  OWN_FOCUS / DEVICE_DISPLAY_GROUP。
                create("api27") { device = "Pixel 2"; apiLevel = 27; systemImageSource = "default"; testedAbi = "x86" }
                create("api28") { device = "Pixel 2"; apiLevel = 28; systemImageSource = "default"; testedAbi = "x86" }
                create("api29") { device = "Pixel 3"; apiLevel = 29; systemImageSource = "default"; testedAbi = "x86" }
                create("api30") { device = "Pixel 3"; apiLevel = 30; systemImageSource = "aosp"; testedAbi = "x86" }
                create("api31") { device = "Pixel 6"; apiLevel = 31; systemImageSource = "aosp"; testedAbi = "x86_64" }
                create("api33") { device = "Pixel 6"; apiLevel = 33; systemImageSource = "aosp"; testedAbi = "x86_64" }
                create("api34") { device = "Pixel 6"; apiLevel = 34; systemImageSource = "aosp"; testedAbi = "x86_64" }
                create("api35") { device = "Pixel 6"; apiLevel = 35; systemImageSource = "aosp"; testedAbi = "x86_64" }
            }
            groups {
                // ./gradlew :engine:tier1MatrixGroupDebugAndroidTest
                // 慢、而且第一次跑要下載每一份系統映像檔。平常用 api36 或實機。
                create("tier1Matrix") {
                    targetDevices.addAll(localDevices)
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
