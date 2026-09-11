plugins {
    alias(libs.plugins.android.library)
}

/**
 * Contract tests for `:hidden-api`'s stubs — see issue #18.
 *
 * There is no main source set. The module exists only to host an androidTest APK, and it is
 * deliberately its own module rather than part of `:hidden-api` or `:engine`:
 *
 *  - Not `:hidden-api`: a module's androidTest packages that module's own classes, so the
 *    `android.*` stubs would land in the test APK — where ART's bootclasspath still wins and
 *    the platform class is loaded anyway. The tests would pass while verifying nothing.
 *  - Not `:engine`: the tests reference no `:engine` code at all, but `:engine`'s androidTest
 *    drags in the CMake/OpenCV native build across four ABIs — a ~79 MB test APK, rebuilt for
 *    every device in the matrix below.
 *
 * What *is* tested is the platform, against the assumptions `:hidden-api` encodes.
 */

val sdkPath = file("${System.getProperty("user.home")}/Android/Sdk")
if (sdkPath.exists()) {
    // 設定給目前專案讀取
    project.extra["android.sdk.path"] = sdkPath.absolutePath
}
android {
    namespace = "com.xaxaxax.relc.hiddenapi.contract"
    compileSdk {
        version = release(libs.versions.targetSdk.get().toInt())
    }

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // The contract only means anything when checked across API levels, so the matrix is the
    // runner. These are slow and CI-only — the fast pure-JVM tests stay on `test`.
    //
    //   ./gradlew :hidden-api-contract:apiMatrixGroupDebugAndroidTest   # every level
    //   ./gradlew :hidden-api-contract:api35DebugAndroidTest            # one level
    //
    // ATD (automated test device) images only exist from API 30, so the older entries use the
    // full AOSP image. The first run downloads each system image.
    testOptions {
        managedDevices {
            localDevices {
                create("api27") {
                    device = "Pixel 2"
                    apiLevel = 27
                    systemImageSource = "aosp"
                }
                create("api28") {
                    device = "Pixel 2"
                    apiLevel = 28
                    systemImageSource = "aosp"
                }
                create("api29") {
                    device = "Pixel 3"
                    apiLevel = 29
                    systemImageSource = "aosp"
                }
                create("api30") {
                    device = "Pixel 3"
                    apiLevel = 30
                    systemImageSource = "aosp-atd"
                }
                create("api31") {
                    device = "Pixel 6"
                    apiLevel = 31
                    systemImageSource = "aosp-atd"
                }
                create("api33") {
                    device = "Pixel 6"
                    apiLevel = 33
                    systemImageSource = "aosp-atd"
                }
                create("api34") {
                    device = "Pixel 6"
                    apiLevel = 34
                    systemImageSource = "aosp-atd"
                }
                create("api35") {
                    device = "Pixel 6"
                    apiLevel = 35
                    systemImageSource = "aosp-atd"
                }
                create("api36") {
                    device = "Pixel 6"
                    apiLevel = 36
                    systemImageSource = "aosp-atd"
                }
                all {
                    testedAbi = "x86_64"
                }
            }
            groups {
                create("apiMatrix") {
                    targetDevices.addAll(localDevices)
                }
            }
        }
    }
}

dependencies {
    // compileOnly is load-bearing: it lets javac inline DisplayManagerHidden's
    // VIRTUAL_DISPLAY_FLAG_* constants into the test (which is exactly what production callers
    // get) while keeping the stubs out of the APK, so the platform is the only thing loaded.
    androidTestCompileOnly(project(":hidden-api"))
    androidTestImplementation(libs.androidx.junit)
    // Supplies AndroidJUnitRunner itself — androidx.test.ext:junit does not pull it in.
    androidTestImplementation(libs.androidx.test.runner)
    // Non-SDK interface restrictions would otherwise hide the very members under test.
    androidTestImplementation(libs.hiddenapibypass)
}
