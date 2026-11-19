plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.cosmos.files"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.cosmos.files"
        minSdk = 26
        targetSdk = 34
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = System.getenv("APP_VERSION") ?: "1.0"
    }
    signingConfigs {
        create("cosmos") {
            storeFile = rootProject.file("cosmos.jks")
            storePassword = "cosmos123"; keyAlias = "cosmos"; keyPassword = "cosmos123"
        }
    }
    buildTypes { getByName("debug") { signingConfig = signingConfigs.getByName("cosmos") } }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.core:core-ktx:1.13.1")
}
