// File: app/src/main/java/com/bdrsf/app/MainActivity.kt
package com.bdrsf.app

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.PorterDuff
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.RelativeLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.bdrsf.app.bridge.AndroidBridge
import com.bdrsf.app.bridge.WebBridgeContract.Actions
import com.bdrsf.app.bridge.WebBridgeContract.BridgeResponse
import com.bdrsf.app.bridge.WebBridgeContract.Status
import com.bdrsf.app.config.WebAppConfig
import com.bdrsf.app.features.document.DocumentScannerFeature
import com.bdrsf.app.features.document.OcrProcessorFeature
import com.bdrsf.app.features.download.DownloadFeature
import com.bdrsf.app.features.qr.QrScannerFeature
import com.bdrsf.app.features.share.ShareFeature
import com.bdrsf.app.utils.PermissionHelper
import com.bdrsf.app.webview.NativeWebChromeClient
import com.bdrsf.app.webview.NativeWebViewClient
import org.json.JSONObject

/**
 * BDRSF-Android কেন্দ্রীয় কোঅর্ডিনেটর ও লাইফসাইকেল ম্যানেজার।
 * এটি সম্পূর্ণ ডিকাপলড আর্কিটেকচারে ফিচার ক্লাসগুলোর মধ্যে সমন্বয় রক্ষা করে।
 */
class MainActivity : AppCompatActivity(), AndroidBridge.BridgeActionHandler {

    companion object {
        private const val RC_GALLERY_QR = 6001
    }

    private lateinit var mainLayout: RelativeLayout
    private lateinit var splashLayout: FrameLayout
    private lateinit var webView: WebView

    // ফিচার ও হেল্পার ক্লাসের রেফারেন্স
    private lateinit var androidBridge: AndroidBridge
    private lateinit var webViewClient: NativeWebViewClient
    private lateinit var webChromeClient: NativeWebChromeClient
    private lateinit var downloadFeature: DownloadFeature
    private lateinit var shareFeature: ShareFeature
    private lateinit var qrScannerFeature: QrScannerFeature
    private lateinit var documentScannerFeature: DocumentScannerFeature
    private lateinit var ocrProcessorFeature: OcrProcessorFeature

    private var pendingGalleryQrCallback: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        setupSystemWindows()
        initViews()
        initFeatures()
        setupWebView()
        setupBackNavigation()

        PermissionHelper.requestAllInitialPermissions(this)

        // ওয়েবসাইট লোড শুরু
        webView.loadUrl(WebAppConfig.BASE_URL)
    }

    /**
     * স্ট্যাটাস বার ও নেভিগেশন বার ওয়েবসাইটের থিম কালার অনুযায়ী ফিক্সড রাখা
     */
    private fun setupSystemWindows() {
        try {
            val themeColor = Color.parseColor("#0A2F1D")
            WindowCompat.setDecorFitsSystemWindows(window, false)

            @Suppress("DEPRECATION")
            window.statusBarColor = themeColor

            @Suppress("DEPRECATION")
            window.navigationBarColor = themeColor

            // Android 10+ এ নেভিগেশন বারের ওপর সিস্টেমের ধূসর স্ক্রিম বন্ধ করা
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
                window.isStatusBarContrastEnforced = false
            }

            // ডার্ক গ্রিন ব্যাকগ্রাউন্ডে আইকনগুলো উজ্জ্বল/সাদা রাখা
            val insetsController = WindowInsetsControllerCompat(window, window.decorView)
            insetsController.isAppearanceLightStatusBars = false
            insetsController.isAppearanceLightNavigationBars = false

            @Suppress("DEPRECATION")
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        } catch (_: Exception) {}
    }

    private fun initViews() {
        mainLayout = RelativeLayout(this).apply {
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#0A2F1D"))
        }

        // স্ট্যাটাস বার, নেভিগেশন বার এবং কীবোর্ড (IME) ইনসেটস ডায়নামিক প্যাডিং
        // এর ফলে ওয়েবসাইটের কনটেন্ট ফোনের ব্যাক/হোম বাটনের ঠিক ওপর পর্যন্ত থাকবে
        ViewCompat.setOnApplyWindowInsetsListener(mainLayout) { view, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())

            val bottomInset = if (ime.bottom > 0) ime.bottom else navBars.bottom
            view.setPadding(0, statusBars.top, 0, bottomInset)

            WindowInsetsCompat.CONSUMED
        }

        webView = WebView(this).apply {
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.MATCH_PARENT
            )
        }
        mainLayout.addView(webView)

        // স্প্ল্যাশ স্ক্রিন ও ব্র্যান্ডিং লোডার
        splashLayout = FrameLayout(this).apply {
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#0A2F1D"))
        }

        val density = resources.displayMetrics.density
        val loaderSizePx = (155 * density).toInt()
        val circularProgressBar = ProgressBar(this, null, android.R.attr.progressBarStyleLarge).apply {
            layoutParams = FrameLayout.LayoutParams(loaderSizePx, loaderSizePx).apply {
                gravity = Gravity.CENTER
            }
            try {
                @Suppress("DEPRECATION")
                indeterminateDrawable.setColorFilter(Color.parseColor("#FFD700"), PorterDuff.Mode.SRC_IN)
            } catch (_: Exception) {}
        }
        splashLayout.addView(circularProgressBar)

        val logoSizePx = (130 * density).toInt()
        val logoImageView = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(logoSizePx, logoSizePx).apply {
                gravity = Gravity.CENTER
            }
            val logoResId = resources.getIdentifier("ic_logo", "drawable", packageName)
            if (logoResId != 0) setImageResource(logoResId)
        }
        splashLayout.addView(logoImageView)

        mainLayout.addView(splashLayout)
        setContentView(mainLayout)
    }

    private fun initFeatures() {
        androidBridge = AndroidBridge(this, webView, this)
        downloadFeature = DownloadFeature(this)
        shareFeature = ShareFeature(this)

        qrScannerFeature = QrScannerFeature(
            activity = this,
            rootContainer = mainLayout,
            bridge = androidBridge
        )

        ocrProcessorFeature = OcrProcessorFeature(
            activity = this,
            bridge = androidBridge,
            webView = webView
        )

        // ডকুমেন্ট ক্যামেরা থেকে ছবি ক্যাপচার হলে ওসিআর ইঞ্জিনে ডেলিগেট করা হবে
        documentScannerFeature = DocumentScannerFeature(
            activity = this,
            rootContainer = mainLayout,
            bridge = androidBridge,
            onDocumentCapturedListener = { capturedFile, scanMode, callback ->
                ocrProcessorFeature.processDocumentFile(capturedFile, scanMode, callback)
            }
        )

        webViewClient = NativeWebViewClient(
            activity = this,
            onPageFinishedListener = {
                runOnUiThread {
                    if (splashLayout.visibility == View.VISIBLE) {
                        splashLayout.animate().alpha(0f).setDuration(400).withEndAction {
                            splashLayout.visibility = View.GONE
                        }
                    }
                    WebAppConfig.syncCookies()
                }
            }
        )

        webChromeClient = NativeWebChromeClient(activity = this)
    }

    private fun setupWebView() {
        WebAppConfig.applyDefaultSettings(this, webView)
        webView.addJavascriptInterface(androidBridge, WebAppConfig.JS_BRIDGE_NAME)
        webView.webViewClient = webViewClient
        webView.webChromeClient = webChromeClient

        // ওয়েবভিউ থেকে ফাইল ডাউনলোডের লিসেনার
        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            downloadFeature.handleWebViewDownload(url, userAgent, contentDisposition, mimetype)
        }
    }

    /**
     * নেভিগেশন ব্যাক-প্রেস হ্যান্ডলিং:
     * ড্যাশবোর্ড বা হোমে থাকলে অ্যাপ কখনোই বন্ধ হবে না; পুরানো ক্যাশ মুছে সাইট ফ্রেশ রিলোড হবে।
     */
    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    qrScannerFeature.isScanningActive -> {
                        qrScannerFeature.stopScanner(isCancelled = true)
                    }
                    documentScannerFeature.isScanningActive -> {
                        documentScannerFeature.stopScanner(isCancelled = true)
                    }
                    else -> {
                        val currentUrl = webView.url ?: ""
                        if (WebAppConfig.isHomeOrDashboard(currentUrl) || !webView.canGoBack()) {
                            Toast.makeText(this@MainActivity, "রিফ্রেশ হচ্ছে...", Toast.LENGTH_SHORT).show()
                            WebAppConfig.forceRefreshWithCacheBypass(webView)
                        } else {
                            webView.goBack()
                        }
                    }
                }
            }
        })
    }

    // অ্যাপ মিনিমাইজ বা কেটে দিলে লগইন সেশন ও কুকি সংরক্ষণ
    override fun onPause() {
        super.onPause()
        WebAppConfig.syncCookies()
    }

    override fun onStop() {
        super.onStop()
        WebAppConfig.syncCookies()
    }

    // ---------------------------------------------------------------------------------------------
    // AndroidBridge.BridgeActionHandler ইমপ্লিমেন্টেশন
    // ---------------------------------------------------------------------------------------------

    override fun onScanQrRequested(payload: JSONObject, callback: String?) {
        qrScannerFeature.startScanner(payload, callback)
    }

    override fun onStartKycRequested(payload: JSONObject, callback: String?) {
        val actionType = payload.optString("actionType", "").lowercase()
        val docType = payload.optString("docType", "NID")

        if (actionType == "process_hybrid" || actionType == "submit") {
            ocrProcessorFeature.processSavedDocumentsHybrid(docType, callback)
        } else {
            documentScannerFeature.startScanner(payload, callback)
        }
    }

    override fun onDownloadFileRequested(payload: JSONObject, callback: String?) {
        downloadFeature.handleBridgeDownload(payload, callback, androidBridge)
    }

    override fun onShareContentRequested(payload: JSONObject, callback: String?) {
        shareFeature.handleBridgeShare(payload, callback, androidBridge)
    }

    override fun onShowToastRequested(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onOpenExternalUrlRequested(url: String) {
        try {
            val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(browserIntent)
        } catch (_: Exception) {
            Toast.makeText(this, "লিঙ্কটি ওপেন করা সম্ভব হয়নি", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCustomActionRequested(action: String, payload: JSONObject, callback: String?) {
        when (action) {
            "PICK_GALLERY_QR" -> {
                pendingGalleryQrCallback = callback
                val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply {
                    type = "image/*"
                }
                try {
                    @Suppress("DEPRECATION")
                    startActivityForResult(intent, RC_GALLERY_QR)
                } catch (_: Exception) {
                    Toast.makeText(this, "গ্যালারি ওপেন করা যায়নি", Toast.LENGTH_SHORT).show()
                }
            }
            "CLEAR_KYC_CACHE" -> {
                ocrProcessorFeature.clearCache()
                androidBridge.sendResponseToWeb(
                    response = BridgeResponse(action = action, status = Status.SUCCESS, message = "Cache cleared"),
                    callbackFunction = callback
                )
            }
            "PROCESS_SAVED_DOCUMENTS" -> {
                val docType = payload.optString("docType", "NID")
                ocrProcessorFeature.processSavedDocumentsHybrid(docType, callback)
            }
            "FORCE_REFRESH" -> {
                WebAppConfig.forceRefreshWithCacheBypass(webView)
            }
            else -> {
                androidBridge.sendResponseToWeb(
                    response = BridgeResponse(action = action, status = Status.ERROR, message = "Unknown action: $action"),
                    callbackFunction = callback
                )
            }
        }
    }

    // ---------------------------------------------------------------------------------------------
    // অ্যাক্টিভিটি রেজাল্ট ডেলিগেশন
    // ---------------------------------------------------------------------------------------------

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == NativeWebChromeClient.FILE_CHOOSER_REQUEST_CODE) {
            webChromeClient.handleFileChooserResult(requestCode, resultCode, data)
        } else if (requestCode == RC_GALLERY_QR) {
            if (resultCode == Activity.RESULT_OK && data?.data != null) {
                try {
                    val imageUri = data.data!!
                    contentResolver.openInputStream(imageUri)?.use { inputStream ->
                        val bitmap = BitmapFactory.decodeStream(inputStream)
                        if (bitmap != null) {
                            qrScannerFeature.decodeFromBitmap(bitmap, pendingGalleryQrCallback)
                        } else {
                            androidBridge.sendResponseToWeb(
                                response = BridgeResponse(action = Actions.SCAN_QR, status = Status.ERROR, message = "ইমেজ লোড ব্যর্থ"),
                                callbackFunction = pendingGalleryQrCallback
                            )
                        }
                    }
                } catch (e: Exception) {
                    androidBridge.sendResponseToWeb(
                        response = BridgeResponse(action = Actions.SCAN_QR, status = Status.ERROR, message = e.localizedMessage ?: "Error"),
                        callbackFunction = pendingGalleryQrCallback
                    )
                } finally {
                    pendingGalleryQrCallback = null
                }
            } else {
                pendingGalleryQrCallback = null
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        WebAppConfig.syncCookies()
        qrScannerFeature.destroy()
        documentScannerFeature.destroy()
        ocrProcessorFeature.destroy()

        try {
            webView.stopLoading()
            mainLayout.removeView(webView)
            webView.destroy()
        } catch (_: Exception) {}
    }
}
