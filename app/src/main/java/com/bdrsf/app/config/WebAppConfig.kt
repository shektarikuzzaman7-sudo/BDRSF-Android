// File: app/src/main/java/com/bdrsf/app/config/WebAppConfig.kt
package com.bdrsf.app.config

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView

/**
 * BDRSF সার্বজনীন ওয়েবভিউ কনফিগারেশন ও সেশন/ক্যাশ ম্যানেজার।
 */
object WebAppConfig {

    const val BASE_URL: String = "https://bdrsf.com"
    const val APP_USER_AGENT_TAG: String = "BDRSF-Android-NativeClient/1.0"
    const val JS_BRIDGE_NAME: String = "AndroidBridge"

    @SuppressLint("SetJavaScriptEnabled")
    fun applyDefaultSettings(context: Context, webView: WebView) {
        val settings: WebSettings = webView.settings

        // JavaScript ও স্টোরেজ ইঞ্জিন (লগইন সেশন স্থায়ী রাখতে DOM & Database আবশ্যক)
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true

        // ভিউপোর্ট ও ডিসপ্লে অ্যাডজাস্টমেন্ট
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.setSupportZoom(false)
        settings.builtInZoomControls = false
        settings.displayZoomControls = false

        // ক্যাশ পলিসি (ডিফল্ট অনলাইন-ফার্স্ট ক্যাশিং)
        settings.cacheMode = WebSettings.LOAD_DEFAULT

        // ফাইল ও কনটেন্ট সিকিউরিটি
        settings.allowFileAccess = false
        settings.allowContentAccess = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW

        // কাস্টম অ্যাপ ইউজার-এজেন্ট যুক্ত করা
        val defaultUserAgent: String = settings.userAgentString ?: ""
        if (!defaultUserAgent.contains(APP_USER_AGENT_TAG)) {
            settings.userAgentString = "$defaultUserAgent $APP_USER_AGENT_TAG"
        }

        // স্থায়ী কুকি পলিসি কনফিগারেশন
        setupPersistentCookies(webView)
    }

    /**
     * অ্যাপ কেটে দেওয়ার পরেও যাতে লগইন সেশন না হারায় সেজন্য পারসিস্টেন্ট কুকি চালু করা
     */
    private fun setupPersistentCookies(webView: WebView) {
        val cookieManager: CookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)
        cookieManager.flush()
    }

    /**
     * র‍্যামে থাকা সেশন কুকি তাৎক্ষণিকভাবে ডিভাইসের ফিজিক্যাল স্টোরেজে সংরক্ষণ করে
     */
    fun syncCookies() {
        try {
            val cookieManager: CookieManager = CookieManager.getInstance()
            cookieManager.flush()
        } catch (_: Exception) {}
    }

    /**
     * লগইন সেশন বা কুকি নষ্ট না করে শুধুমাত্র ওয়েবসাইটের ক্যাশ (CSS, JS, HTML) 
     * মেমোরি থেকে সম্পূর্ণ ডিলিট করে নো-ক্যাশ মোডে নতুন আপডেট লোড করে।
     */
    fun forceRefreshWithCacheBypass(webView: WebView) {
        try {
            // কুকি সুরক্ষিত রেখে শুধুমাত্র রিসোর্স ক্যাশ ক্লিয়ার করা
            webView.clearCache(true)

            // সাময়িকভাবে ক্যাশ বাইপাস মোড চালু করা
            webView.settings.cacheMode = WebSettings.LOAD_NO_CACHE
            webView.reload()

            // রিফ্রেশ সফল হলে পুনরায় ডিফল্ট মোডে ফিরে যাওয়া
            Handler(Looper.getMainLooper()).postDelayed({
                webView.settings.cacheMode = WebSettings.LOAD_DEFAULT
            }, 3000)
        } catch (_: Exception) {
            webView.reload()
        }
    }

    /**
     * বর্তমান ইউআরএল হোমপেজ বা ড্যাশবোর্ড কি না তা যাচাই করে
     */
    fun isHomeOrDashboard(url: String?): Boolean {
        if (url.isNullOrBlank()) return true
        val cleanUrl = url.trimEnd('/')
        return cleanUrl == BASE_URL ||
               cleanUrl == "$BASE_URL/index.php" ||
               cleanUrl.contains("page=dashboard") ||
               cleanUrl == "$BASE_URL/"
    }
}
