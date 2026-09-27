plugins {
    id("com.android.application")
}

android {
    namespace = "com.quotadesk.mobile"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.quotadesk.mobile"
        minSdk = 24
        targetSdk = 35
        versionCode = 53
        versionName = "0.0.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("androidx.activity:activity:1.10.1")
    implementation("androidx.camera:camera-camera2:1.5.3")
    implementation("androidx.camera:camera-lifecycle:1.5.3")
    implementation("androidx.camera:camera-view:1.5.3")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
}

