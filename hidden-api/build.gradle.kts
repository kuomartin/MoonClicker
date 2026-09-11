plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.xaxaxax.hidden_api"
    compileSdk {
        version = release(libs.versions.targetSdk.get().toInt())
    }

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.core.ktx)
    testImplementation(libs.junit)
    // No androidTest here on purpose. An instrumented test in this module would package the
    // `android.*` stubs into the test APK, where ART's bootclasspath still wins — the test would
    // load the platform class and quietly prove nothing. The contract tests that verify these
    // stubs live in :engine's androidTest instead. See issue #18.
    annotationProcessor(libs.rikka.refine.annotation.processor)
    compileOnly(libs.rikka.refine.annotation)
}