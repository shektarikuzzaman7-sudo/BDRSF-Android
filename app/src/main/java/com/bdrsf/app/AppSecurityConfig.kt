package com.bdrsf.app

import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import java.util.Locale

object AppSecurityConfig {

    const val APP_BASE_URL = "https://bdrsf.com"
    const val APP_HOME_PATH = "/"

    val TRUSTED_HOSTS = setOf(
        "bdrsf.com",
        "www.bdrsf.com"
    )

    val EXTERNAL_SCHEMES = setOf(
        "tel",
        "mailto",
        "whatsapp",
        "intent"
    )

    const val DEFAULT_TIMEOUT_MS = 45_000L

    fun isTrustedHost(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val normalized = host.trim().lowercase(Locale.US)
        return normalized == "bdrsf.com" || normalized.endsWith(".bdrsf.com")
    }

    fun isTrustedUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return try {
            val uri = Uri.parse(url)
            val scheme = uri.scheme?.lowercase(Locale.US) ?: return false

            // বাহ্যিক অ্যাপ স্কিম (tel, mailto, whatsapp ইত্যাদি হোস্ট ছাড়াও অনুমোদিত)
            if (EXTERNAL_SCHEMES.contains(scheme)) {
                return true
            }

            val host = uri.host?.lowercase(Locale.US)
            if (host != null && isTrustedHost(host)) {
                return scheme == "https" || scheme == "about" || scheme == "data"
            }

            false
        } catch (_: Exception) {
            false
        }
    }

    fun configureWebSettings(webSettings: WebSettings) {
        webSettings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            cacheMode = WebSettings.LOAD_DEFAULT
            loadWithOverviewMode = true
            useWideViewPort = true
            allowFileAccess = true
            allowContentAccess = true
            allowUniversalAccessFromFileURLs = false
            allowFileAccessFromFileURLs = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            safeBrowsingEnabled = true
            savePassword = false
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
        }
    }

    fun configureCookiePolicy(webView: WebView, cookieManager: CookieManager) {
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)
    }

    fun buildSafeUrl(rawUrl: String?): String {
        if (rawUrl.isNullOrBlank()) return APP_BASE_URL
        return try {
            val uri = Uri.parse(rawUrl)
            val scheme = uri.scheme?.lowercase(Locale.US)
            val host = uri.host?.lowercase(Locale.US)
            if (scheme == "https" && isTrustedHost(host)) {
                rawUrl
            } else {
                APP_BASE_URL
            }
        } catch (_: Exception) {
            APP_BASE_URL
        }
    }
}
