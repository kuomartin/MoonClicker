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
                create("api27") { device = "Pixel 2"; sdkVersion = 27; systemImageSource = "aosp" }
                create("api28") { device = "Pixel 2"; sdkVersion = 28; systemImageSource = "aosp" }
                create("api29") { device = "Pixel 3"; sdkVersion = 29; systemImageSource = "aosp" }
                create("api30") { device = "Pixel 3"; sdkVersion = 30; systemImageSource = "aosp-atd" }
                create("api31") { device = "Pixel 6"; sdkVersion = 31; systemImageSource = "aosp-atd" }
                create("api33") { device = "Pixel 6"; sdkVersion = 33; systemImageSource = "aosp-atd" }
                create("api34") { device = "Pixel 6"; sdkVersion = 34; systemImageSource = "aosp-atd" }
                create("api35") { device = "Pixel 6"; sdkVersion = 35; systemImageSource = "aosp-atd" }
                create("api36") { device = "Pixel 6"; sdkVersion = 36; systemImageSource = "aosp-atd" }
            }
            groups {
                create("apiMatrix") { targetDevices.addAll(localDevices) }
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
    // Non-SDK interface restrictions would otherwise hide the very members under test.
    androidTestImplementation(libs.hiddenapibypass)
}
