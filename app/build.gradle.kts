plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.gms.google-services")
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

    androidResources {
        noCompress += listOf("tflite", "lite")
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
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    // Google Play Services Tasks (Tasks.await সাপোর্টের জন্য)
    implementation("com.google.android.gms:play-services-tasks:18.2.0")

    // Launcher icon unread badge / dot support
    implementation("me.leolin:ShortcutBadger:1.1.22@aar")

    // Firebase Cloud Messaging (পুশ নোটিফিকেশনের জন্য)
    implementation(platform("com.google.firebase:firebase-bom:33.7.0"))
    implementation("com.google.firebase:firebase-messaging")

    // Google ML Kit: বারকোড স্ক্যানার (PDF417, MicroPDF417, Digital NID QR, Passport QR)
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    // Google ML Kit: টেক্সট রিকগনিশন (NID ও পাসপোর্টের MRZ ও ইংরেজি তথ্য রিড করতে)
    implementation("com.google.mlkit:text-recognition:16.0.1")

    // Google ML Kit: Accurate ফেস ডিটেকশন (কার্ড থেকে পোর্ট্রেট কপি কাটা ও রিয়েল হিউম্যান ফেস ল্যান্ডমার্ক যাচাই)
    implementation("com.google.mlkit:face-detection:16.1.7")

    // ZXing Core: QR ও ব্যাকআপ বারকোড ডিকোডার ইঞ্জিন
    implementation("com.google.zxing:core:3.5.3")

    // নেটওয়ার্ক রিকোয়েস্ট
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // CameraX (NID, Passport ও HD Face লাইভ ক্যামেরা ক্যাপচার)
    val cameraxVersion = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")
}
