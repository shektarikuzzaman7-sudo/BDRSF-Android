package com.bdrsf.app

import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebSettings
import java.util.Locale

object AppSecurityConfig {

    const val APP_BASE_URL = "https://bdrsf.com"
    const val APP_HOME_PATH = "/"

    val TRUSTED_HOSTS = setOf(
        "bdrsf.com",
        "www.bdrsf.com"
    )

    val TRUSTED_SCHEMES = setOf(
        "https",
        "about",
        "data",
        "file"
    )

    const val DEFAULT_TIMEOUT_MS = 30_000L

    fun isTrustedHost(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val normalized = host.trim().lowercase(Locale.US)
        return normalized == "bdrsf.com" || normalized.endsWith(".bdrsf.com")
    }

    fun isTrustedUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return try {
            val uri = Uri.parse(url)
            val scheme = uri.scheme?.lowercase(Locale.US)
            val host = uri.host?.lowercase(Locale.US)

            if (scheme == null || host == null) return false

            if (isTrustedHost(host)) {
                return scheme == "https" || scheme == "about" || scheme == "data"
            }

            when (scheme) {
                "tel", "mailto", "whatsapp", "intent" -> true
                else -> false
            }
        } catch (e: Exception) {
            false
        }
    }

    fun configureWebSettings(webSettings: WebSettings) {
        webSettings.javaScriptEnabled = true
        webSettings.domStorageEnabled = true
        webSettings.databaseEnabled = true
        webSettings.setSupportZoom(false)
        webSettings.builtInZoomControls = false
        webSettings.displayZoomControls = false
        webSettings.cacheMode = WebSettings.LOAD_DEFAULT
        webSettings.loadWithOverviewMode = true
        webSettings.useWideViewPort = true
        webSettings.allowFileAccess = true
        webSettings.allowContentAccess = true
        webSettings.allowUniversalAccessFromFileURLs = false
        webSettings.allowFileAccessFromFileURLs = false
        webSettings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        webSettings.safeBrowsingEnabled = true
        webSettings.savePassword = false
        webSettings.setSupportMultipleWindows(false)
        webSettings.javaScriptCanOpenWindowsAutomatically = false
    }

    fun configureCookiePolicy(context: Context, cookieManager: CookieManager) {
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(null, true)
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
        } catch (e: Exception) {
            APP_BASE_URL
        }
    }
}
