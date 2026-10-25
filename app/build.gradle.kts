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
    
    // --- HD Camera Image Rotation ও EXIF ডাটা ঠিক রাখার জন্য ---
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // --- Google ML Kit QR Code Scanner (লাইভ এবং গ্যালারি কিউআর স্ক্যানের জন্য) ---
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    
    // --- API Call এবং সার্ভার থেকে API Keys আনার জন্য OkHttp ---
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    
    // --- CameraX (শুধুমাত্র লাইভ কিউআর স্ক্যানারের ক্যামেরা প্রিভিউ ও অ্যানালাইসিসের জন্য) ---
    val cameraxVersion = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // =========================================================================
    //  নতুন যুক্ত করা ডিপেন্ডেন্সি (Direct Gemini SDK & Coroutines)
    // =========================================================================

    // --- Google Gemini AI SDK (সরাসরি অ্যাপ থেকে ফাস্ট OCR করার জন্য) ---
    implementation("com.google.ai.client.generativeai:generativeai:0.7.0")

    // --- Kotlin Coroutines (UI ফ্রিজ না করে ব্যাকগ্রাউন্ডে AI প্রসেস করার জন্য) ---
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
}
