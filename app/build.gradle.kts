plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.bdrsf.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bdrsf.app"
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // ML Kit Barcode Scanning (পেছনের বারকোড পড়ার জন্য)
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    // ML Kit Text Recognition (সামনের ইংরেজি নাম, NID ও লেখা স্পষ্ট কি না তা যাচাই করতে)
    implementation("com.google.mlkit:text-recognition:16.0.1")

    // নেটওয়ার্ক কল (সার্ভারে ডেটা পাঠানোর জন্য)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // CameraX (লাইভ ক্যামেরা প্রিভিউ, ফ্রেম গাইড ও অটো স্ক্যানারের জন্য)
    val cameraxVersion = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")
}
