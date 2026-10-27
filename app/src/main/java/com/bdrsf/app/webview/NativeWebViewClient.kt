// File: app/src/main/java/com/bdrsf/app/webview/NativeWebViewClient.kt
package com.bdrsf.app.webview

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.util.Log
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import com.bdrsf.app.config.AppSecurityConfig
import java.net.URISyntaxException

/**
 * BDRSF নেটিভ ওয়েবভিউ ক্লায়েন্ট।
 * সুরক্ষিত URL রাউটিং, পেমেন্ট গেটওয়ে, থার্ড-পার্টি অ্যাপ স্কিম এবং ব্রাউজার নেভিগেশন নিয়ন্ত্রণ করে।
 */
class NativeWebViewClient(
    private val activity: Activity,
    private val onPageStartedListener: (() -> Unit)? = null,
    private val onPageFinishedListener: (() -> Unit)? = null,
    private val onErrorReceivedListener: ((errorCode: Int, description: String) -> Unit)? = null
) : WebViewClient() {

    companion object {
        private const val TAG = "NativeWebViewClient"
    }

    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
        val url = request?.url?.toString() ?: return false
        return handleCustomUrlRouting(view, url)
    }

    @Suppress("DEPRECATION")
    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return handleCustomUrlRouting(view, url)
    }

    /**
     * সার্বজনীন URL রাউটিং এবং স্কিম হ্যান্ডলার
     */
    private fun handleCustomUrlRouting(view: WebView?, url: String): Boolean {
        val uri = try {
            Uri.parse(url)
        } catch (e: Exception) {
            Log.e(TAG, "Malformed URL: $url", e)
            return false
        }

        val scheme = uri.scheme?.lowercase() ?: ""

        // ১. অনুমোদিত ইন্টারনাল ডোমেন হলে ওয়েবভিউতেই পেজ লোড হবে
        if (AppSecurityConfig.isWhitelistedUrl(url)) {
            return false
        }

        // ২. অনুমোদিত পেমেন্ট গেটওয়ে ডোমেন (bKash, Nagad, SSLCommerz Web) হলে ওয়েবভিউতে চলবে
        if (AppSecurityConfig.isAllowedPaymentGateway(url)) {
            return false
        }

        // ৩. Android Intent Scheme হ্যান্ডলিং (যেমন: বিকাশ, নগদ বা ব্যাংকিং অ্যাপ সরাসরি ওপেন হওয়া)
        if (scheme == "intent") {
            return try {
                val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
                if (intent != null) {
                    val packageManager = activity.packageManager
                    val resolveInfo = packageManager.resolveActivity(intent, 0)

                    if (resolveInfo != null) {
                        activity.startActivity(intent)
                    } else {
                        // ডিভাইসে সংশ্লিষ্ট অ্যাপ না থাকলে ব্রাউজার ফলব্যাক URL চেষ্টা করবে
                        val fallbackUrl = intent.getStringExtra("browser_fallback_url")
                        if (!fallbackUrl.isNullOrBlank()) {
                            view?.loadUrl(fallbackUrl)
                        } else {
                            // প্লে স্টোর থেকে অ্যাপ ইনস্টল করার পেজ ওপেন করবে
                            val appPackage = intent.`package`
                            if (!appPackage.isNullOrBlank()) {
                                openGooglePlayStore(appPackage)
                            }
                        }
                    }
                    true
                } else {
                    false
                }
            } catch (e: URISyntaxException) {
                Log.e(TAG, "Intent URI syntax error: $url", e)
                false
            } catch (e: Exception) {
                Log.e(TAG, "Failed to resolve intent scheme", e)
                false
            }
        }

        // ৪. অনুমোদিত এক্সটার্নাল কমিউনিকেশন স্কিম (WhatsApp, Tel, Mailto, SMS)
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
                Log.e(TAG, "Error opening external scheme: $url", e)
                true
            }
        }

        // ৫. বাইরের কোনো সাধারণ ওয়েব লিঙ্ক হলে বাহ্যিক ব্রাউজারে ওপেন হবে
        return try {
            val browserIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(browserIntent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Could not open browser for URL: $url", e)
            false
        }
    }

    private fun openGooglePlayStore(packageName: String) {
        try {
            val playIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(playIntent)
        } catch (_: ActivityNotFoundException) {
            val webIntent = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://play.google.com/store/apps/details?id=$packageName")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(webIntent)
        }
    }

    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
        super.onPageStarted(view, url, favicon)
        onPageStartedListener?.invoke()
    }

    override fun onPageFinished(view: WebView?, url: String?) {
        super.onPageFinished(view, url)
        onPageFinishedListener?.invoke()
    }

    override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
        super.onReceivedError(view, request, error)
        if (request?.isForMainFrame == true) {
            val errorCode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                error?.errorCode ?: -1
            } else {
                -1
            }
            val errorDescription = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                error?.description?.toString() ?: "Unknown Network Error"
            } else {
                "Network connection error"
            }
            Log.e(TAG, "MainFrame Error: [$errorCode] $errorDescription")
            onErrorReceivedListener?.invoke(errorCode, errorDescription)
        }
    }

    @Suppress("DEPRECATION")
    override fun onReceivedError(view: WebView?, errorCode: Int, description: String?, failingUrl: String?) {
        super.onReceivedError(view, errorCode, description, failingUrl)
        Log.e(TAG, "Legacy Web Error: [$errorCode] $description on $failingUrl")
        onErrorReceivedListener?.invoke(errorCode, description ?: "Connection failure")
    }

    override fun onReceivedHttpError(view: WebView?, request: WebResourceRequest?, errorResponse: WebResourceResponse?) {
        super.onReceivedHttpError(view, request, errorResponse)
        if (request?.isForMainFrame == true) {
            val statusCode = errorResponse?.statusCode ?: 0
            Log.e(TAG, "HTTP Error: $statusCode on ${request.url}")
        }
    }

    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
        // ম্যান-ইন-দ্য-মিডল (MITM) আক্রমণ রোধে ইনভ্যালিড এসএসএল হ্যান্ডলার সর্বদা ক্যানসেল করা হবে
        Log.e(TAG, "SSL Certificate Error: ${error?.primaryError}. Aborting connection.")
        handler?.cancel()
    }
}
