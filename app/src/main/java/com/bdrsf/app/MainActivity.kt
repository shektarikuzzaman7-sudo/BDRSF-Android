package com.bdrsf.app

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.RelativeLayout
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var splashLayout: FrameLayout

    // File Chooser variables for Camera & Gallery
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraImageUri: Uri? = null
    private val FILE_CHOOSER_REQUEST_CODE = 1001
    private val PERMISSION_REQUEST_CODE = 1002

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // চিরতরে স্ক্রিন রোটেট বন্ধ করে পোর্ট্রেট মোডে লক করা
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        // Title bar সরিয়ে Full screen এবং ট্রান্সপারেন্ট স্ট্যাটাস বার নিশ্চিত করা
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        
        window.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

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

        // অ্যাপ চালুর সাথে সাথে প্রয়োজনীয় রানটাইম পারমিশনগুলো রিকোয়েস্ট করা
        checkAndRequestPermissions()

        // নেভিগেশন বার হাইড করা
        hideSystemNavigationBar()

        // Clean & Smooth Navigation / System Bar State Handler
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

        // ক্যামেরা ও গ্যালারি ফাইল আপলোডের জন্য WebChromeClient সেটআপ
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                if (filePathCallback != null) {
                    filePathCallback?.onReceiveValue(null)
                }
                filePathCallback = callback

                // Camera intent তৈরি
                var takePictureIntent: Intent? = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                if (takePictureIntent?.resolveActivity(packageManager) != null) {
                    var photoFile: File? = null
                    try {
                        photoFile = createImageFile()
                    } catch (ex: IOException) {
                        // Handle error
                    }
                    if (photoFile != null) {
                        cameraImageUri = FileProvider.getUriForFile(
                            this@MainActivity,
                            "$packageName.fileprovider",
                            photoFile
                        )
                        takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, cameraImageUri)
                    } else {
                        takePictureIntent = null
                    }
                }

                // Gallery/File selector intent তৈরি
                val contentSelectionIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*" // সব ধরণের ফাইল, ছবি বা গ্যালারি থেকে সিলেকশনের সুযোগ রাখতে
                }

                val intentArray: Array<Intent> = takePictureIntent?.let { arrayOf(it) } ?: arrayOf()

                val chooserIntent = Intent(Intent.ACTION_CHOOSER).apply {
                    putExtra(Intent.EXTRA_INTENT, contentSelectionIntent)
                    putExtra(Intent.EXTRA_TITLE, "ফাইল বা ছবি সিলেক্ট করুন")
                    putExtra(Intent.EXTRA_INITIAL_INTENTS, intentArray)
                }

                try {
                    startActivityForResult(chooserIntent, FILE_CHOOSER_REQUEST_CODE)
                } catch (e: ActivityNotFoundException) {
                    filePathCallback = null
                    Toast.makeText(this@MainActivity, "ফাইল সিলেক্টর ওপেন করা যাচ্ছে না", Toast.LENGTH_SHORT).show()
                    return false
                }
                return true
            }
        }

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

    // ক্যামেরা দিয়ে ছবি তোলার জন্য টেম্পোরারি ফাইল তৈরি করার মেথড
    @Throws(IOException::class)
    private fun createImageFile(): File {
        val timeStamp: String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val storageDir: File? = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
        return File.createTempFile(
            "JPEG_${timeStamp}_",
            ".jpg",
            storageDir
        )
    }

    // ফাইল সিলেক্ট বা ক্যামেরা থেকে ছবি তোলার পর রিসিভ করার হ্যান্ডেলার
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            if (filePathCallback == null) return
            var results: Array<Uri>? = null

            if (resultCode == Activity.RESULT_OK) {
                if (data == null || data.data == null) {
                    // যদি ইউজার ক্যামেরা থেকে ছবি তোলে, তবে cameraImageUri ব্যবহার হবে
                    if (cameraImageUri != null) {
                        results = arrayOf(cameraImageUri!!)
                    }
                } else {
                    val dataString = data.dataString
                    if (dataString != null) {
                        results = arrayOf(Uri.parse(dataString))
                    }
                }
            }
            filePathCallback?.onReceiveValue(results)
            filePathCallback = null
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    // রানটাইম পারমিশন চেক ও চাওয়ার মেথড
    private fun checkAndRequestPermissions() {
        val listPermissionsNeeded = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            listPermissionsNeeded.add(Manifest.permission.CAMERA)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                listPermissionsNeeded.add(Manifest.permission.READ_MEDIA_IMAGES)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                listPermissionsNeeded.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        if (listPermissionsNeeded.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, listPermissionsNeeded.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    private fun setupKeyboardListener(rootView: View) {
        rootView.viewTreeObserver.addOnGlobalLayoutListener {
            val r = Rect()
            rootView.getWindowVisibleDisplayFrame(r)
            val screenHeight = rootView.rootView.height
            val keypadHeight = screenHeight - r.bottom

            // কিবোর্ড বন্ধ হলে সিস্টেম নেভিগেশন বার নিখুঁতভাবে রি-হাইড করা নিশ্চিত করা হয়
            if (keypadHeight < screenHeight * 0.15) {
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
                or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            )
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Ignored
    }
}
