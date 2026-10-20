package com.bdrsf.app

import android.app.Activity
import android.os.Bundle
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient

class MainActivity : Activity() {

    private lateinit var webView: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Remove title bar and make it full screen edge-to-edge
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )

        // Initialize WebView
        webView = WebView(this)
        setContentView(webView)

        // Hardware Acceleration for Speed
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        val webSettings: WebSettings = webView.settings
        webSettings.javaScriptEnabled = true
        webSettings.domStorageEnabled = true
        webSettings.databaseEnabled = true
        
        // Performance & Caching Settings
        webSettings.cacheMode = WebSettings.LOAD_DEFAULT
        webSettings.loadWithOverviewMode = true
        webSettings.useWideViewPort = true
        webSettings.allowFileAccess = true
        webSettings.allowContentAccess = true

        // Faster Rendering
        webSettings.setRenderPriority(WebSettings.RenderPriority.HIGH)

        // Session & Cookie Support
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (url != null && url.contains("bdrsf.com")) {
                    view?.loadUrl(url)
                    return true
                }
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // Force sync cookies so user stays logged in
                CookieManager.getInstance().flush()
            }
        }

        // Load your website
        webView.loadUrl("https://bdrsf.com/")
    }

    override fun onBackPressed() {
        if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
