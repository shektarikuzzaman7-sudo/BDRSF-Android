
package com.bdrsf.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.view.ViewGroup
import android.graphics.Color

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private val homeUrl = "https://bdrsf.com/"
    private val allowedHost = "bdrsf.com"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = Color.rgb(10, 37, 64)

        webView = WebView(this)
        webView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.allowFileAccess = false
        webView.settings.allowContentAccess = true

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance()
            .setAcceptThirdPartyCookies(webView, false)

        webView.webViewClient = object : WebViewClient() {

            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean {
                val uri = request.url
                val host = uri.host ?: return true

                if (uri.scheme == "https" &&
                    (host == allowedHost ||
                     host.endsWith(".$allowedHost"))
                ) {
                    return false
                }

                if (uri.scheme == "https" ||
                    uri.scheme == "http" ||
                    uri.scheme == "mailto" ||
                    uri.scheme == "tel"
                ) {
                    if (uri.scheme == "mailto" ||
                        uri.scheme == "tel"
                    ) {
                        try {
                            startActivity(
                                Intent(Intent.ACTION_VIEW, uri)
                            )
                        } catch (_: Exception) {
                        }
                    } else {
                        try {
                            startActivity(
                                Intent(Intent.ACTION_VIEW, uri)
                            )
                        } catch (_: Exception) {
                        }
                    }
                }

                return true
            }
        }

        webView.webChromeClient = WebChromeClient()

        setContentView(webView)
        webView.loadUrl(homeUrl)
    }

    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        val currentUrl = webView.url ?: homeUrl
        val currentHost = Uri.parse(currentUrl).host

        if (currentHost == allowedHost ||
            (currentHost?.endsWith(".$allowedHost") == true)
        ) {
            if (currentUrl.trimEnd('/') != homeUrl.trimEnd('/')) {
                webView.loadUrl(homeUrl)
            } else {
                AlertDialog.Builder(this)
                    .setTitle("BDRSF থেকে বের হবেন?")
                    .setMessage("আপনি কি অ্যাপটি বন্ধ করতে চান?")
                    .setNegativeButton("না", null)
                    .setPositiveButton("হ্যাঁ") { _, _ ->
                        finish()
                    }
                    .show()
            }
        } else {
            webView.loadUrl(homeUrl)
        }
    }
}
