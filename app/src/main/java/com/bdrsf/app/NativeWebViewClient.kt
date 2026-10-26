package com.bdrsf.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

class NativeWebViewClient(
    private val onFirstPageFinished: (() -> Unit)? = null
) : WebViewClient() {

    private var isFirstLoad = true

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
        try {
            CookieManager.getInstance().flush()
        } catch (_: Exception) {}

        if (isFirstLoad) {
            isFirstLoad = false
            onFirstPageFinished?.invoke()
        }
    }

    private fun handleUrlLoading(view: WebView?, url: String): Boolean {
        if (url.isBlank()) return false

        try {
            val uri = Uri.parse(url)
            val scheme = uri.scheme?.lowercase() ?: ""

            // ১. স্পেশাল intent:// স্কিম প্রসেসিং (পেমেন্ট গেটওয়ে, বিকাশ, নগদ ইত্যাদির জন্য)
            if (scheme == "intent") {
                try {
                    val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                    if (intent != null) {
                        view?.context?.startActivity(intent)
                        return true
                    }
                } catch (_: Exception) {
                    // অ্যাপ ইনস্টল না থাকলে ফলব্যাক ইউআরএল চেষ্টা করা
                    try {
                        val fallbackUrl = Intent.parseUri(url, Intent.URI_INTENT_SCHEME).getStringExtra("browser_fallback_url")
                        if (!fallbackUrl.isNullOrBlank()) {
                            view?.loadUrl(fallbackUrl)
                            return true
                        }
                    } catch (_: Exception) {}
                }
                return true
            }

            // ২. নেটিভ অ্যাপ স্কিম (tel, mailto, whatsapp, sms)
            if (scheme == "tel" || scheme == "mailto" || scheme == "whatsapp" || scheme == "sms") {
                try {
                    val intent = Intent(Intent.ACTION_VIEW, uri)
                    view?.context?.startActivity(intent)
                } catch (_: ActivityNotFoundException) {
                }
                return true
            }

            // ৩. অ্যাপের মূল ডোমেইনের ভেতরে থাকলে ওয়েবভিউতেই চলবে
            if (WebAppConfig.shouldStayInsideWebView(url)) {
                return false
            }

            // ৪. বাইরের অন্য যেকোনো ওয়েবসাইট ডিফল্ট ব্রাউজারে খুলবে
            val externalIntent = Intent(Intent.ACTION_VIEW, uri)
            view?.context?.startActivity(externalIntent)
            return true

        } catch (_: Exception) {
            return false
        }
    }
}
