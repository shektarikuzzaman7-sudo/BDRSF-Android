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
    
    // --- ক্যামেরায় তোলা ডকুমেন্টের সঠিক ওরিয়েন্টেশন (Rotation) ঠিক রাখার জন্য ---
    implementation("androidx.exifinterface:exifinterface:1.3.7")

    // --- Google ML Kit Barcode/QR Scanner (গ্যালারি ও লাইভ কিউআর রিডের জন্য) ---
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    
    // --- CameraX লাইব্রেরি (লাইভ ভিউফাইন্ডার ফ্রেম, অটো-ক্যাপচার শার্পনেস অ্যানালাইসিস ও হাই-কোয়ালিটি ফটো তোলার জন্য) ---
    val cameraxVersion = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // নোট: নতুন MainActivity-তে Gemini বা সার্ভার OCR API বাদ দেওয়া হয়েছে। 
    // যদি প্রজেক্টের অন্য কোনো ফিচার বা ব্যাকএন্ড রিকোয়েস্টে OkHttp দরকার না হয়, তবে এটি পুরোপুরি বাদ দিতে পারেন।
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
