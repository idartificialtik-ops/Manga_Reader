plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.idart.mangareader.exthost"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    // Exposed: the bridge calls Observable-returning methods across modules.
    api(libs.rxjava1)

    implementation(libs.okhttp)
    implementation(libs.okhttp.brotli)
    implementation(libs.okhttp.zstd)
    implementation(libs.okhttp.logging)
    implementation(libs.jsoup)
    implementation(libs.conscrypt)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.serialization.json.okio)
    implementation(libs.injekt.core)
    implementation(libs.androidx.preference)
    // WebView-based Cloudflare challenge solving suspends the interceptor.
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
}
