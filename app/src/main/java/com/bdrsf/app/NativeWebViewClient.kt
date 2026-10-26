package com.bdrsf.app

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

class NativeWebViewClient : WebViewClient() {

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val url = request?.url?.toString() ?: return false
        return handleUrlLoading(view, url)
    }

    @Deprecated("Deprecated in Java")
    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
        return handleUrlLoading(view, url ?: "")
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
    }

    private fun handleUrlLoading(view: WebView?, url: String): Boolean {
        if (url.isBlank()) return false

        val uri = Uri.parse(url)
        val scheme = uri.scheme?.lowercase() ?: ""
        val host = uri.host?.lowercase() ?: ""

        if (scheme == "tel" || scheme == "mailto" || scheme == "whatsapp" || scheme == "intent") {
            try {
                val intent = Intent(Intent.ACTION_VIEW, uri)
                view?.context?.startActivity(intent)
            } catch (_: Exception) {
            }
            return true
        }

        // Allow only trusted URLs; everything else opens externally
        val allowed = AppSecurityConfig.isTrustedUrl(url)
        if (allowed) return false

        try {
            val intent = Intent(Intent.ACTION_VIEW, uri)
            view?.context?.startActivity(intent)
        } catch (_: Exception) {
        }

        return true
    }
}
