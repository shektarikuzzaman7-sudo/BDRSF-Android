package com.bdrsf.app

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PorterDuff
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

        // ২. Custom Green Splash Overlay (FrameLayout ব্যবহার করা হয়েছে যেন ঠিক লোগোর চারপাশে ঘূর্ণায়মান সার্কেল বসে)
        splashLayout = FrameLayout(this)
        val splashParams = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.MATCH_PARENT,
            RelativeLayout.LayoutParams.MATCH_PARENT
        )
        splashLayout.layoutParams = splashParams
        // ডিপ রিচ গ্রীন ব্যাকগ্রাউন্ড (#0A2F1D)
        splashLayout.setBackgroundColor(Color.parseColor("#0A2F1D"))

        // লোগো সাইজ ৭০% ছোট (১৩০dp x ১৩০dp)
        val logoSizePx = (130 * resources.displayMetrics.density).toInt()
        // স্পিনারের সাইজ লোগোর চেয়ে সামান্য বড় যেন ঠিক গা ঘেঁষে ঘুরতে পারে (১৫৫dp x ১৫৫dp)
        val loaderSizePx = (155 * resources.displayMetrics.density).toInt()

        // ৩. লোগোর গা ঘেঁষে ঘুরার জন্য কাস্টম গোল্ডেন প্রোগ্রেস সার্কেল
        val circularProgressBar = ProgressBar(this, null, android.R.attr.progressBarStyleLarge)
        val progressParams = FrameLayout.LayoutParams(loaderSizePx, loaderSizePx)
        progressParams.gravity = Gravity.CENTER
        circularProgressBar.layoutParams = progressParams
        
        // লোগোর সাথে মিলিয়ে গোল্ডেন কালার ফিল্টার (#FFD700)
        circularProgressBar.indeterminateDrawable.setColorFilter(
            Color.parseColor("#FFD700"),
            PorterDuff.Mode.SRC_IN
        )
        splashLayout.addView(circularProgressBar)

        // ৪. ব্র্যান্ড লোগো ImageView (সম্পূর্ণ স্থির থাকবে, কোনো নড়াচড়া করবে না)
        val logoImageView = ImageView(this)
        val logoParams = FrameLayout.LayoutParams(logoSizePx, logoSizePx)
        logoParams.gravity = Gravity.CENTER
        logoImageView.layoutParams = logoParams
        
        // res/drawable/ic_logo থেকে ব্যাকগ্রাউন্ড রিমুভ করা লোগো লোড করা
        val logoResId = resources.getIdentifier("ic_logo", "drawable", packageName)
        if (logoResId != 0) {
            logoImageView.setImageResource(logoResId)
        }
        splashLayout.addView(logoImageView)

        // Splash Layout টি WebView-এর ওপর যুক্ত করা
        mainLayout.addView(splashLayout)

        setContentView(mainLayout)

        // নেভিগেশন বার হাইড করা ও অটো-হাইড চালু করা
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

                // ওয়েবসাইট লোড সম্পন্ন হলে স্মুথভাবে লোডিং স্ক্রিন ফেড-আউট হয়ে যাবে
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
                // নিচের নেভিগেশন বার হাইড করা এবং সোয়াইপ করলে সাময়িক ভেসে ওঠার অপশন
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
        // Ignored - ব্যাক বাটন কাজ করবে না
    }
}
