package com.bdrsf.app.navigation

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import com.bdrsf.app.config.AppSecurityConfig
import java.net.URISyntaxException

/**
 * ওয়েবভিউ রিডাইরেকশন, পেমেন্ট গেটওয়ে স্কিম (bKash/Nagad/Rocket), 
 * বাহ্যিক ইন্টেন্ট (WhatsApp, Tel, Mailto) এবং ডোমেন ফিল্টারিং হ্যান্ডলার।
 */
class NavigationFeature(
    private val activity: Activity,
    private val onPageFinishedCallback: () -> Unit
) : WebViewClient() {

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val url = request?.url?.toString() ?: return false
        return handleUrlNavigation(view, url)
    }

    @Suppress("DEPRECATION")
    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
        if (url == null) return false
        return handleUrlNavigation(view, url)
    }

    private fun handleUrlNavigation(view: WebView?, url: String): Boolean {
        val uri = try {
            Uri.parse(url)
        } catch (_: Exception) {
            return false
        }

        val scheme = uri.scheme?.lowercase() ?: ""

        // ১. অনুমোদিত ইন্টারনাল ডোমেন হলে ওয়েবভিউতেই পেজ লোড হবে
        if (AppSecurityConfig.isWhitelistedUrl(url)) {
            return false
        }

        // ২. অনুমোদিত পেমেন্ট গেটওয়ে ডোমেন (SSLCommerz, bKash Web, Nagad Web) হলে ওয়েবভিউতে চলবে
        if (AppSecurityConfig.isAllowedPaymentGateway(url)) {
            return false
        }

        // ৩. Android Intent Scheme হ্যান্ডলিং (যেমন: বিকাশ বা নগদ অ্যাপ সরাসরি ওপেন হওয়া)
        if (scheme == "intent") {
            return try {
                val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                if (intent != null) {
                    val packageManager = activity.packageManager
                    val info = packageManager.resolveActivity(intent, 0)
                    if (info != null) {
                        activity.startActivity(intent)
                    } else {
                        // যদি ফোনে অ্যাপ না থাকে তবে ফলব্যাক ব্রাউজার URL খুলবে
                        val fallbackUrl = intent.getStringExtra("browser_fallback_url")
                        if (!fallbackUrl.isNullOrBlank()) {
                            view?.loadUrl(fallbackUrl)
                        } else {
                            val pkg = intent.`package`
                            if (!pkg.isNullOrBlank()) {
                                openPlayStore(pkg)
                            }
                        }
                    }
                    true
                } else false
            } catch (e: URISyntaxException) {
                false
            } catch (e: Exception) {
                false
            }
        }

        // ৪. কমিউনিকেশন স্কিম হ্যান্ডলিং (WhatsApp, ফোন কল, ইমেইল, এসএমএস)
        if (AppSecurityConfig.isAllowedExternalScheme(url)) {
            return try {
                val externalIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                activity.startActivity(externalIntent)
                true
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(activity, "অ্যাপটি আপনার ডিভাইসে ইনস্টল করা নেই", Toast.LENGTH_SHORT).show()
                true
            } catch (e: Exception) {
                true
            }
        }

        // ৫. বাইরের কোনো থার্ড-পার্টি সাধারণ ওয়েব লিঙ্ক হলে বাহ্যিক সিস্টেমে (যেমন Chrome) ওপেন হবে
        return try {
            val browserIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(browserIntent)
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun openPlayStore(packageName: String) {
        try {
            val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(marketIntent)
        } catch (_: ActivityNotFoundException) {
            val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$packageName")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(webIntent)
        }
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        onPageFinishedCallback()
    }

    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
        super.onReceivedError(view, request, error)
        if (request?.isForMainFrame == true) {
            // মূল ফ্রেম লোড হতে ব্যর্থ হলে ইউজারকে নোটিফাই করা
        }
    }
}
