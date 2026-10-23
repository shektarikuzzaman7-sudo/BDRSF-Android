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
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")

    // --- Google ML Kit QR Code Scanner (লাইভ এবং গ্যালারি কিউআর স্ক্যানের জন্য) ---
    implementation("com.google.mlkit:barcode-scanning:17.3.0")
    
    // --- Google ML Kit Text Recognition (OCR) - আপলোড করা NID ও পাসপোর্ট ছবি থেকে তথ্য পড়ার জন্য ---
    implementation("com.google.mlkit:text-recognition:16.0.0")
    
    // --- CameraX (শুধুমাত্র লাইভ কিউআর স্ক্যানারের ক্যামেরা প্রিভিউ ও অ্যানালাইসিসের জন্য) ---
    val cameraxVersion = "1.3.1"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")
}
