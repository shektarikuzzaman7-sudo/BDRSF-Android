package com.bdrsf.app

import android.net.Uri
import android.webkit.WebView
import java.util.Locale

object WebAppConfig {

    const val HOME_URL = "https://bdrsf.com/"
    const val HOME_HOST = "bdrsf.com"
    const val JS_BRIDGE_NAME = "AndroidBridge"

    fun configureWebView(webView: WebView) {
        AppSecurityConfig.configureWebSettings(webView.settings)
    }

    fun loadHomePage(webView: WebView) {
        webView.loadUrl(HOME_URL)
    }

    fun isHomeUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val clean = url.trim().trimEnd('/')
        val homeClean = HOME_URL.trimEnd('/')
        return clean.isEmpty() || clean == homeClean
    }

    fun shouldStayInsideWebView(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return try {
            val uri = Uri.parse(url)
            val scheme = uri.scheme?.lowercase(Locale.US)
            val host = uri.host?.lowercase(Locale.US)
            (scheme == "https" || scheme == "http") && AppSecurityConfig.isTrustedHost(host)
        } catch (_: Exception) {
            false
        }
    }
}
