import com.android.build.api.variant.BuildConfigField

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.rikka.refine)
    alias(libs.plugins.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.parcelize)
    alias(libs.plugins.wire)
}

android {
    namespace = "com.xaxaxax.relc"
    compileSdk {
        version = release(libs.versions.targetSdk.get().toInt())
    }

    defaultConfig {
        applicationId = "com.xaxaxax.relc"
        minSdk = libs.versions.minSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        targetSdk = libs.versions.targetSdk.get().toInt()
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        buildConfig = true
        compose = true
        aidl = true
    }
}

androidComponents {
    onVariants { variant ->
        val currentTimestamp = System.currentTimeMillis().toString()
        variant.buildConfigFields?.put(
            "BUILD_TIME",
            BuildConfigField(
                "Long",
                "${currentTimestamp}L",
                "Timestamp of when the APK was built"
            )
        )
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-XXLanguage:+ContextParameters")
    }
}

// Workbench 的串流事件 schema 是唯一來源，Kotlin（這裡）與 vscode-extension（@bufbuild/protobuf）
// 都從同一份 .proto 產生型別，見 proto/workbench_stream_event.proto。
wire {
    sourcePath {
        srcDir("../proto")
    }
    kotlin {}
}
dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.foundation.layout)
    implementation(libs.androidx.compose.material3.adaptive.navigation.suite)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.material3)
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // viewModel
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    // Timber Logger
    implementation(libs.timber)
    // Shizuku
    implementation(libs.rikka.shizuku.api)
    implementation(libs.rikka.shizuku.shared)
    implementation(libs.rikka.shizuku.provider)
    compileOnly(project(":hidden-api"))
    implementation(project(":engine"))
    implementation(libs.rikka.refine.runtime)
    ksp(libs.rikka.refine.annotation.processor)
    implementation(libs.hiddenapibypass)
    // material.icons
    implementation(libs.androidx.material.icons.core)
    implementation(libs.androidx.material.icons.extended)

    // nav compose
    implementation(libs.androidx.navigation.compose)

    // LuaJ
    implementation(libs.luaj)

    implementation(libs.kotlinx.serialization.json)

    // Hilt
    implementation(libs.hilt.android.core)
    implementation(libs.androidx.hilt.navigation.compose)
    ksp(libs.hilt.compiler)
    implementation(kotlin("reflect"))

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // coil
    implementation(libs.coil.compose) // 請根據當前最新版本調整
    implementation(libs.coil.svg)

    // Ktor (Script Workbench embedded server)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.websockets)
    implementation(libs.ktor.server.cors)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktor.client.websockets)

    // QR code (workbench pairing)
    implementation(libs.zxing.core)

    // Workbench 串流事件 schema（proto/workbench_stream_event.proto 產生的型別）
    implementation(libs.wire.runtime)
}
