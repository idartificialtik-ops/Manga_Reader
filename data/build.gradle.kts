plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.sqldelight)
}

android {
    namespace = "dev.idart.mangareader.data"
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

sqldelight {
    databases {
        create("MangaReaderDatabase") {
            packageName.set("dev.idart.mangareader.data.db")
        }
    }
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":source-api"))
    implementation(project(":core:common"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.sqldelight.android.driver)
    implementation(libs.sqldelight.coroutines)
    implementation(libs.sqldelight.primitive.adapters)

    implementation(libs.okhttp)

    testImplementation(libs.junit)
    testImplementation(libs.sqldelight.sqlite.driver)
}
