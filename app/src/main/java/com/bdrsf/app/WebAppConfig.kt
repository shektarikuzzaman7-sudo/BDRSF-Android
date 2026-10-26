package com.bdrsf.app

import android.content.Context
import android.webkit.WebSettings
import android.webkit.WebView

object WebAppConfig {

    const val HOME_URL = "https://bdrsf.com/"
    const val HOME_HOST = "bdrsf.com"
    const val JS_BRIDGE_NAME = "AndroidBridge"

    fun configureWebView(webView: WebView, context: Context) {
        val settings = webView.settings
        AppSecurityConfig.configureWebSettings(settings)

        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.setSupportZoom(false)
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        settings.safeBrowsingEnabled = true
        settings.savePassword = false
        settings.setSupportMultipleWindows(false)
        settings.javaScriptCanOpenWindowsAutomatically = false
    }

    fun loadHomePage(webView: WebView) {
        webView.loadUrl(HOME_URL)
    }

    fun isHomeUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return url.trim().startsWith(HOME_URL) || url.trim().startsWith("https://bdrsf.com")
    }

    fun shouldStayInsideWebView(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return AppSecurityConfig.isTrustedUrl(url)
    }
}
