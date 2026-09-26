import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Credenciais de assinatura ficam em keystore.properties (nao versionado).
// Sem esse arquivo o build de release sai sem assinatura, o que impede
// publicar na Play Store.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties =
    Properties().apply {
        if (keystorePropertiesFile.exists()) {
            keystorePropertiesFile.inputStream().use { load(it) }
        }
    }

android {
    namespace = "dev.idart.mangareader"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.idart.mangareader"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.1.1"
    }

    signingConfigs {
        if (keystoreProperties.isNotEmpty()) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (keystoreProperties.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":data"))
    implementation(project(":source-api"))
    implementation(project(":extension-host"))
    implementation(project(":sources"))
    implementation(project(":core:common"))
    implementation(project(":ocr-onnx"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)
    // PullToRefreshBox requires material3 1.3.1+ (newer than the BOM's).
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.coil3.compose)
    implementation(libs.coil3.network)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.datastore.preferences)
    // Renders the extension-provided preference screens in Compose.
    implementation(libs.androidx.preference)

    implementation(libs.sqldelight.android.driver)

    implementation(libs.injekt.core)

    implementation(libs.androidx.security.crypto)

    implementation(libs.androidx.play.services.mlkit.text.latin)
    implementation(libs.androidx.play.services.mlkit.text.japanese)
    implementation(libs.androidx.play.services.mlkit.text.chinese)
    implementation(libs.androidx.play.services.mlkit.text.korean)
    implementation(libs.androidx.play.services.mlkit.translate)
    implementation(libs.androidx.play.services.mlkit.language.id)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
