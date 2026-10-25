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
    
    // ছবির রোটেশন ও ওরিয়েন্টেশন ঠিক রাখার জন্য
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // ১. বারকোড স্ক্যানার (পেছনের অংশের জন্য)
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    
    // ২. টেক্সট রিকগনিশন (সামনের অংশের ইংরেজি টেক্সট পড়ার জন্য)
    implementation("com.google.mlkit:text-recognition:16.0.1")
    
    // এপিআই কলের জন্য OkHttp
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    
    // ক্যামেরা লাইব্রেরি
    val cameraxVersion = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")
}
