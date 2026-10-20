package com.bdrsf.app

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.RelativeLayout

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var splashLayout: FrameLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Title bar সরিয়ে Full screen করা
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        
        // Soft Keyboard Adjust Resize Enabled
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

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

        // ২. Custom Green Splash Overlay
        splashLayout = FrameLayout(this)
        val splashParams = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.MATCH_PARENT,
            RelativeLayout.LayoutParams.MATCH_PARENT
        )
        splashLayout.layoutParams = splashParams
        splashLayout.setBackgroundColor(Color.parseColor("#0A2F1D"))

        val logoSizePx = (130 * resources.displayMetrics.density).toInt()
        val loaderSizePx = (155 * resources.displayMetrics.density).toInt()

        // ৩. লোগোর গা ঘেঁষে ঘুরার জন্য কাস্টম গোল্ডেন প্রোগ্রেস সার্কেল
        val circularProgressBar = ProgressBar(this, null, android.R.attr.progressBarStyleLarge)
        val progressParams = FrameLayout.LayoutParams(loaderSizePx, loaderSizePx)
        progressParams.gravity = Gravity.CENTER
        circularProgressBar.layoutParams = progressParams
        
        circularProgressBar.indeterminateDrawable.setColorFilter(
            Color.parseColor("#FFD700"),
            PorterDuff.Mode.SRC_IN
        )
        splashLayout.addView(circularProgressBar)

        // ৪. ব্র্যান্ড লোগো ImageView
        val logoImageView = ImageView(this)
        val logoParams = FrameLayout.LayoutParams(logoSizePx, logoSizePx)
        logoParams.gravity = Gravity.CENTER
        logoImageView.layoutParams = logoParams
        
        val logoResId = resources.getIdentifier("ic_logo", "drawable", packageName)
        if (logoResId != 0) {
            logoImageView.setImageResource(logoResId)
        }
        splashLayout.addView(logoImageView)

        mainLayout.addView(splashLayout)
        setContentView(mainLayout)

        // নেভিগেশন বার হাইড করা
        hideSystemNavigationBar()

        // Soft Keyboard State Listener to restore Fullscreen Immersive layout
        setupKeyboardListener(mainLayout)

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

        // অ্যাপ চালু হলে সেশন ক্লিয়ার করে স্মার্ট আইডি বা অ্যাকাউন্ট নাম্বার ঠিক রাখা
        clearAppSessionKeepSmartId()

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
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
                CookieManager.getInstance().flush()

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

    private fun setupKeyboardListener(rootView: View) {
        rootView.viewTreeObserver.addOnGlobalLayoutListener {
            val r = Rect()
            rootView.getWindowVisibleDisplayFrame(r)
            val screenHeight = rootView.rootView.height
            val keypadHeight = screenHeight - r.bottom

            if (keypadHeight < screenHeight * 0.15) {
                // Keyboard is closed -> Restore Immersive full screen
                hideSystemNavigationBar()
            }
        }
    }

    private fun clearAppSessionKeepSmartId() {
        val cookieManager = CookieManager.getInstance()
        val cookies = cookieManager.getCookie("https://bdrsf.com/")
        
        if (!cookies.isNullOrEmpty()) {
            val cookieArray = cookies.split(";")
            var smartIdValue = ""
            
            for (cookie in cookieArray) {
                val trimmedCookie = cookie.trim()
                if (trimmedCookie.startsWith("verified_login_id=")) {
                    smartIdValue = trimmedCookie
                    break
                }
            }

            cookieManager.removeSessionCookies(null)

            if (smartIdValue.isNotEmpty()) {
                cookieManager.setCookie("https://bdrsf.com/", smartIdValue)
            }
            cookieManager.flush()
        }
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

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Ignored
    }
}
