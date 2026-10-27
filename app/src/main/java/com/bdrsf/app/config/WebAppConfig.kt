// File: app/src/main/java/com/bdrsf/app/config/WebAppConfig.kt
package com.bdrsf.app.config

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView

object WebAppConfig {

    // মূল কার্যকর ওয়েবসাইটের লাইভ ডোমেন URL
    const val BASE_URL: String = "https://bdrsf.com"
    const val APP_USER_AGENT_TAG: String = "BDRSF-Android-NativeClient/1.0"
    const val JS_BRIDGE_NAME: String = "AndroidBridge"

    @SuppressLint("SetJavaScriptEnabled")
    fun applyDefaultSettings(context: Context, webView: WebView) {
        val settings: WebSettings = webView.settings

        // JavaScript ও DOM Storage সক্রিয়করণ
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true

        // ভিউপোর্ট ও উইন্ডো স্কেলিং
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.setSupportZoom(false)
        settings.builtInZoomControls = false
        settings.displayZoomControls = false

        // ক্যাশ মোড (অনলাইন ফার্স্ট)
        settings.cacheMode = WebSettings.LOAD_DEFAULT

        // ফাইল ও কনটেন্ট অ্যাক্সেস
        settings.allowFileAccess = false
        settings.allowContentAccess = true

        // নিরাপদ HTTPS কানেকশন
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW

        // কাস্টম নেটিভ ইউজার এজেন্ট ট্যাগ ইন্টিগ্রেশন
        val defaultUserAgent: String = settings.userAgentString ?: ""
        if (!defaultUserAgent.contains(APP_USER_AGENT_TAG)) {
            settings.userAgentString = "$defaultUserAgent $APP_USER_AGENT_TAG"
        }

        // কুকি পলিসি কনফিগারেশন
        setupCookies(webView)
    }

    private fun setupCookies(webView: WebView) {
        val cookieManager: CookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)
    }

    fun syncCookies() {
        val cookieManager: CookieManager = CookieManager.getInstance()
        cookieManager.flush()
    }
}
