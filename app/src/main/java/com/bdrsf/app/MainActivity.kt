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
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
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
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var splashLayout: FrameLayout

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraImageUri: Uri? = null
    private val FILE_CHOOSER_REQUEST_CODE = 1001
    private val PERMISSION_REQUEST_CODE = 1002
    private val ML_KIT_QR_REQUEST_CODE = 1003

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        
        window.setFlags(
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)

        val mainLayout = RelativeLayout(this)
        mainLayout.layoutParams = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.MATCH_PARENT,
            RelativeLayout.LayoutParams.MATCH_PARENT
        )

        webView = WebView(this)
        val webViewParams = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.MATCH_PARENT,
            RelativeLayout.LayoutParams.MATCH_PARENT
        )
        webView.layoutParams = webViewParams
        mainLayout.addView(webView)

        splashLayout = FrameLayout(this)
        val splashParams = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.MATCH_PARENT,
            RelativeLayout.LayoutParams.MATCH_PARENT
        )
        splashLayout.layoutParams = splashParams
        splashLayout.setBackgroundColor(Color.parseColor("#0A2F1D"))

        val logoSizePx = (130 * resources.displayMetrics.density).toInt()
        val loaderSizePx = (155 * resources.displayMetrics.density).toInt()

        val circularProgressBar = ProgressBar(this, null, android.R.attr.progressBarStyleLarge)
        val progressParams = FrameLayout.LayoutParams(loaderSizePx, loaderSizePx)
        progressParams.gravity = Gravity.CENTER
        circularProgressBar.layoutParams = progressParams
        
        circularProgressBar.indeterminateDrawable.setColorFilter(
            Color.parseColor("#FFD700"),
            PorterDuff.Mode.SRC_IN
        )
        splashLayout.addView(circularProgressBar)

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

        requestAllRequiredPermissions()
        hideSystemNavigationBar()
        setupKeyboardListener(mainLayout)

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
        webSettings.mediaPlaybackRequiresUserGesture = false
        webSettings.setRenderPriority(WebSettings.RenderPriority.HIGH)

        // ওয়েব থেকে নেটিভ এমএল কিট স্ক্যানার ট্রিগার করার জন্য ব্রিজ
        webView.addJavascriptInterface(WebAppInterface(this), "AndroidBridge")

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        clearAppSessionKeepSmartId()

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest?) {
                if (request != null) {
                    runOnUiThread {
                        request.grant(request.resources)
                    }
                }
            }

            override fun onShowFileChooser(
                webView: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                if (!hasCameraPermission()) {
                    requestAllRequiredPermissions()
                    Toast.makeText(this@MainActivity, "ক্যামেরা পারমিশন প্রয়োজন। অনুগ্রহ করে পারমিশন দিন।", Toast.LENGTH_LONG).show()
                    callback?.onReceiveValue(null)
                    return false
                }

                if (filePathCallback != null) {
                    filePathCallback?.onReceiveValue(null)
                }
                filePathCallback = callback

                try {
                    val contentSelectionIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*" 
                    }

                    val captureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                    val photoFile: File? = try {
                        createImageFile()
                    } catch (ex: IOException) {
                        null
                    }

                    if (photoFile != null) {
                        cameraImageUri = FileProvider.getUriForFile(
                            this@MainActivity,
                            "${packageName}.fileprovider",
                            photoFile
                        )
                        captureIntent.putExtra(MediaStore.EXTRA_OUTPUT, cameraImageUri)
                    } else {
                        cameraImageUri = null
                    }

                    val chooserIntent = Intent(Intent.ACTION_CHOOSER).apply {
                        putExtra(Intent.EXTRA_INTENT, contentSelectionIntent)
                        putExtra(Intent.EXTRA_TITLE, "ফাইল বা ছবি সিলেক্ট করুন")
                        putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(captureIntent))
                    }

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

        webView.loadUrl("https://bdrsf.com/")
    }

    class WebAppInterface(private val activity: MainActivity) {
        @JavascriptInterface
        fun startNativeMLKitScanner() {
            if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.CAMERA), activity.PERMISSION_REQUEST_CODE)
                return
            }
            // নেটিভ ক্যামেরা ইনটেন্ট চালু করা হচ্ছে যা ছবি তুলে সরাসরি ML Kit দিয়ে স্ক্যান করবে
            val takePictureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            try {
                val photoFile: File = activity.createImageFile()
                activity.cameraImageUri = FileProvider.getUriForFile(
                    activity,
                    "${activity.packageName}.fileprovider",
                    photoFile
                )
                takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, activity.cameraImageUri)
                activity.startActivityForResult(takePictureIntent, activity.ML_KIT_QR_REQUEST_CODE)
            } catch (e: Exception) {
                Toast.makeText(activity, "ক্যামেরা ওপেন করতে সমস্যা হয়েছে", Toast.LENGTH_SHORT).show()
            }
        }
    }

    @Throws(IOException::class)
    private fun createImageFile(): File {
        val timeStamp: String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val storageDir: File = cacheDir 
        return File.createTempFile("KYC_${timeStamp}_", ".jpg", storageDir)
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestAllRequiredPermissions() {
        val permissionsList = mutableListOf<String>()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            permissionsList.add(Manifest.permission.CAMERA)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                permissionsList.add(Manifest.permission.READ_MEDIA_IMAGES)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionsList.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsList.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (permissionsList.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, permissionsList.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == ML_KIT_QR_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK && cameraImageUri != null) {
                try {
                    val image = InputImage.fromFilePath(this, cameraImageUri!!)
                    val scanner = BarcodeScanning.getClient()
                    scanner.process(image)
                        .addOnSuccessListener { barcodes ->
                            var scannedValue = ""
                            for (barcode in barcodes) {
                                scannedValue = barcode.rawValue ?: ""
                                if (scannedValue.isNotEmpty()) break
                            }
                            if (scannedValue.isNotEmpty()) {
                                webView.post {
                                    webView.evaluateJavascript("javascript:processScannedData('$scannedValue');", null)
                                }
                            } else {
                                Toast.makeText(this, "কিউআর কোড পাওয়া যায়নি, আবার চেষ্টা করুন", Toast.LENGTH_LONG).show()
                            }
                        }
                        .addOnFailureListener {
                            Toast.makeText(this, "স্ক্যানিং ব্যর্থ হয়েছে", Toast.LENGTH_SHORT).show()
                        }
                } catch (e: Exception) {
                    Toast.makeText(this, "ইমেজ প্রসেস করতে সমস্যা হয়েছে", Toast.LENGTH_SHORT).show()
                }
            }
            cameraImageUri = null
        } else if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            if (filePathCallback == null) return
            var results: Array<Uri>? = null

            if (resultCode == Activity.RESULT_OK) {
                if (data == null || data.dataString == null) {
                    if (cameraImageUri != null) {
                        results = arrayOf(cameraImageUri!!)
                    }
                } else {
                    data.dataString?.let {
                        results = arrayOf(Uri.parse(it))
                    }
                }
            }
            filePathCallback?.onReceiveValue(results)
            filePathCallback = null
            cameraImageUri = null
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    private fun setupKeyboardListener(rootView: View) {
        rootView.viewTreeObserver.addOnGlobalLayoutListener {
            val r = Rect()
            rootView.getWindowVisibleDisplayFrame(r)
            val screenHeight = rootView.rootView.height
            val keypadHeight = screenHeight - r.bottom
            if (keypadHeight < screenHeight * 0.15) {
                hideSystemNavigationBar()
            }
        }
    }

    @Suppress("DEPRECATION")
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

    override fun onWindowFocusChanged(hasFocus: Boolean) {}

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
    override fun onBackPressed() {}
}
