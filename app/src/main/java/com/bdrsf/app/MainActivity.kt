package com.bdrsf.app

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.view.animation.Animation
import android.view.animation.ScaleAnimation
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RelativeLayout
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var splashLayout: LinearLayout
    private lateinit var progressBar: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Title bar সরিয়ে Full screen করা
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )

        // Main Layout Setup (RelativeLayout)
        val mainLayout = RelativeLayout(this)
        mainLayout.layoutParams = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.MATCH_PARENT,
            RelativeLayout.LayoutParams.MATCH_PARENT
        )

        // ১. WebView Setup
        webView = WebView(this)
        val webViewParams = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.MATCH_PARENT,
            RelativeLayout.LayoutParams.MATCH_PARENT
        )
        webView.layoutParams = webViewParams
        mainLayout.addView(webView)

        // ২. Custom Green Loading / Splash Screen Layout
        splashLayout = LinearLayout(this)
        val splashParams = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.MATCH_PARENT,
            RelativeLayout.LayoutParams.MATCH_PARENT
        )
        splashLayout.layoutParams = splashParams
        splashLayout.orientation = LinearLayout.VERTICAL
        splashLayout.gravity = Gravity.CENTER
        // Logo-র সাথে মিলিয়ে ডিপ এমারেল্ড গ্রীন ব্যাকগ্রাউন্ড (#0B3B17)
        splashLayout.setBackgroundColor(Color.parseColor("#0B3B17"))

        // ব্র্যান্ড লোগো ImageView
        val logoImageView = ImageView(this)
        val logoParams = LinearLayout.LayoutParams(400, 400) // লোগোর সাইজ
        logoParams.setMargins(0, 0, 0, 40)
        logoImageView.layoutParams = logoParams
        
        // res/drawable/ic_logo ফোল্ডার থেকে লোগো লোড করা
        val logoResId = resources.getIdentifier("ic_logo", "drawable", packageName)
        if (logoResId != 0) {
            logoImageView.setImageResource(logoResId)
        }
        splashLayout.addView(logoImageView)

        // লোগোর জন্য হালকা Pulsing Animation (বোরিং না লাগার জন্য)
        val pulseAnimation = ScaleAnimation(
            0.95f, 1.05f, 0.95f, 1.05f,
            Animation.RELATIVE_TO_SELF, 0.5f,
            Animation.RELATIVE_TO_SELF, 0.5f
        ).apply {
            duration = 1000
            repeatCount = Animation.INFINITE
            repeatMode = Animation.REVERSE
        }
        logoImageView.startAnimation(pulseAnimation)

        // সুন্দর প্রোগ্রেস বার (Loading Spinner)
        progressBar = ProgressBar(this)
        val progressParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
        progressParams.setMargins(0, 20, 0, 20)
        progressBar.layoutParams = progressParams
        splashLayout.addView(progressBar)

        // লোডিং টেক্সট (Optional)
        val loadingTextView = TextView(this)
        loadingTextView.text = "Loading BDRSF..."
        loadingTextView.setTextColor(Color.parseColor("#FFD700")) // Gold color
        loadingTextView.textSize = 16f
        loadingTextView.setTypeface(null, Typeface.BOLD)
        splashLayout.addView(loadingTextView)

        // Splash Layout টি WebView-এর ওপর যুক্ত করা
        mainLayout.addView(splashLayout)

        setContentView(mainLayout)

        // নেভিগেশন বার হাইড করা
        hideSystemNavigationBar()

        // WebView Settings & Acceleration
        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        val webSettings: WebSettings = webView.settings
        webSettings.javaScriptEnabled = true
        webSettings.domStorageEnabled = true
        webSettings.databaseEnabled = true
        webSettings.cacheMode = WebSettings.LOAD_DEFAULT
        webSettings.loadWithOverviewMode = true
        webSettings.useWideViewPort = true
        webSettings.allowFileAccess = true
        webSettings.allowContentAccess = true
        webSettings.setRenderPriority(WebSettings.RenderPriority.HIGH)

        // Cookie & Session Handling
        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        // WebView Client Event Handling
        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                // পেজ লোড হওয়া শুরু হলে স্প্ল্যাশ স্ক্রিন দৃশ্যমান থাকবে
                splashLayout.visibility = View.VISIBLE
            }

            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (url != null && url.contains("bdrsf.com")) {
                    view?.loadUrl(url)
                    return true
                }
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                // কুকি সিঙ্ক করা
                CookieManager.getInstance().flush()

                // ওয়েবসাইট লোড সম্পন্ন হলে স্মুথভাবে স্প্ল্যাশ স্ক্রিন গায়েব হয়ে যাবে
                splashLayout.animate()
                    .alpha(0f)
                    .setDuration(400)
                    .withEndAction {
                        splashLayout.visibility = View.GONE
                    }
            }
        }

        // ওয়েবসাইট লোড করা
        webView.loadUrl("https://bdrsf.com/")
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemNavigationBar()
        }
    }

    private fun hideSystemNavigationBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
        }
    }

    // ব্যাক বাটন সম্পূর্ণ ডিসেবল রাখা
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Ignored
    }
}
