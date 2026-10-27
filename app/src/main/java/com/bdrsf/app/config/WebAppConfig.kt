// File: app/src/main/java/com/bdrsf/app/config/WebAppConfig.kt
package com.bdrsf.app.config

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView

object WebAppConfig {

    // আপনার যে ডোমেনটি ব্রাউজারে সক্রিয়ভাবে চলছে সেটি এখানে নিশ্চিত করুন
    const val BASE_URL: String = "https://bdrsn.com"
    const val APP_USER_AGENT_TAG: String = "BDRSF-Android-NativeClient/1.0"
    const val JS_BRIDGE_NAME: String = "AndroidBridge"

    @SuppressLint("SetJavaScriptEnabled")
    fun applyDefaultSettings(context: Context, webView: WebView) {
        val settings: WebSettings = webView.settings

        // JavaScript & DOM Storage
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true

        // Viewport & Zoom Configuration
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.setSupportZoom(false)
        settings.builtInZoomControls = false
        settings.displayZoomControls = false

        // Cache Configuration
        settings.cacheMode = WebSettings.LOAD_DEFAULT

        // File & Content Access
        settings.allowFileAccess = false
        settings.allowContentAccess = true

        // Mixed Content Security
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW

        // Custom User Agent Integration
        val defaultUserAgent: String = settings.userAgentString ?: ""
        if (!defaultUserAgent.contains(APP_USER_AGENT_TAG)) {
            settings.userAgentString = "$defaultUserAgent $APP_USER_AGENT_TAG"
        }

        // Cookie Configuration
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
