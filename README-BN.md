# BDRSF Android — Starter Source

এটি https://bdrsf.com/ সাইটকে Android WebView-তে দেখানোর প্রাথমিক Kotlin প্রজেক্ট।

## সীমাবদ্ধতা
- এটি সোর্স কোড ZIP; APK নয়। এখানে Gradle build করে APK পরীক্ষা করা হয়নি।
- Download handling আপাতত অনুমোদিত HTTPS URL Android-এ খোলার চেষ্টা করে; login-cookie-সহ protected download-এর জন্য DownloadManager integration লাগতে পারে।
- ফাইল picker আছে; camera capture, QR scanner, push notification, biometrics/PIN ও signed release build এখনও সম্পূর্ণ করা হয়নি।
- সাইটের login, KYC, deposit/withdrawal ও transaction validation সার্ভারেই থাকবে।
- production ব্যবহারের আগে build, security review এবং বাস্তব ফোনে login, upload, download, session expiry ও Back behavior পরীক্ষা করুন।

## ফাইল
- app/src/main/AndroidManifest.xml
- app/src/main/java/com/bdrsf/app/MainActivity.kt
- Gradle configuration
