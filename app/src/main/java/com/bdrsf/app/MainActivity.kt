// File: app/src/main/java/com/bdrsf/app/MainActivity.kt
package com.bdrsf.app

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.PorterDuff
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.view.Gravity
import android.view.HapticFeedbackConstants
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
 * bdrsf-bridge.js এর সমস্ত অ্যাকশন নির্ভুলভাবে রাউট করে।
 */
class MainActivity : AppCompatActivity(), AndroidBridge.BridgeActionHandler {

    companion object {
        private const val RC_GALLERY_QR = 6001
    }

    private lateinit var mainLayout: RelativeLayout
    private lateinit var splashLayout: FrameLayout
    private lateinit var webView: WebView

    private lateinit var androidBridge: AndroidBridge
    private lateinit var webViewClient: NativeWebViewClient
    private lateinit var webChromeClient: NativeWebChromeClient
    private lateinit var downloadFeature: DownloadFeature
    private lateinit var shareFeature: ShareFeature
    private lateinit var qrScannerFeature: QrScannerFeature
    private lateinit var documentScannerFeature: DocumentScannerFeature
    private lateinit var ocrProcessorFeature: OcrProcessorFeature

    private var pendingGalleryQrCallback: String? = null
    private var lastBackPressTime: Long = 0L

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
        webView.loadUrl(WebAppConfig.BASE_URL)
    }

    private fun setupSystemWindows() {
        try {
            val themeColor = Color.parseColor("#0A2F1D")
            WindowCompat.setDecorFitsSystemWindows(window, false)

            @Suppress("DEPRECATION")
            window.statusBarColor = themeColor

            @Suppress("DEPRECATION")
            window.navigationBarColor = themeColor

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                window.isNavigationBarContrastEnforced = false
                window.isStatusBarContrastEnforced = false
            }

            val insetsController = WindowInsetsControllerCompat(window, window.decorView)
            insetsController.isAppearanceLightStatusBars = false
            insetsController.isAppearanceLightNavigationBars = false

            @Suppress("DEPRECATION")
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        } catch (_: Exception) {}
    }

    private fun initViews() {
        mainLayout = RelativeLayout(this).apply {
            layoutParams = RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.parseColor("#0A2F1D"))
        }

        ViewCompat.setOnApplyWindowInsetsListener(mainLayout) { view, insets ->
            val statusBars = insets.getInsets(WindowInsetsCompat.Type.statusBars())
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())

            val bottomInset = if (ime.bottom > 0) ime.bottom else navBars.bottom
            view.setPadding(0, statusBars.top, 0, bottomInset)
            WindowInsetsCompat.CONSUMED
        }

        webView = WebView(this).apply {
            layoutParams = RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.MATCH_PARENT)
        }
        mainLayout.addView(webView)

        splashLayout = FrameLayout(this).apply {
            layoutParams = RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.parseColor("#0A2F1D"))
        }

        val density = resources.displayMetrics.density
        val loaderSizePx = (155 * density).toInt()
        val circularProgressBar = ProgressBar(this, null, android.R.attr.progressBarStyleLarge).apply {
            layoutParams = FrameLayout.LayoutParams(loaderSizePx, loaderSizePx).apply { gravity = Gravity.CENTER }
            try {
                @Suppress("DEPRECATION")
                indeterminateDrawable.setColorFilter(Color.parseColor("#FFD700"), PorterDuff.Mode.SRC_IN)
            } catch (_: Exception) {}
        }
        splashLayout.addView(circularProgressBar)

        val logoSizePx = (130 * density).toInt()
        val logoImageView = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(logoSizePx, logoSizePx).apply { gravity = Gravity.CENTER }
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
        qrScannerFeature = QrScannerFeature(this, mainLayout, androidBridge)
        ocrProcessorFeature = OcrProcessorFeature(this, androidBridge, webView)

        documentScannerFeature = DocumentScannerFeature(this, mainLayout, androidBridge) { capturedFile, scanMode, callback ->
            ocrProcessorFeature.processDocumentFile(capturedFile, scanMode, callback)
        }

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

        webChromeClient = NativeWebChromeClient(this)
    }

    private fun setupWebView() {
        WebAppConfig.applyDefaultSettings(this, webView)
        webView.addJavascriptInterface(androidBridge, WebAppConfig.JS_BRIDGE_NAME)
        webView.webViewClient = webViewClient
        webView.webChromeClient = webChromeClient

        webView.setDownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            downloadFeature.handleWebViewDownload(url, userAgent, contentDisposition, mimetype)
        }
    }

    /**
     * ব্যাক-বাটন হ্যান্ডলিং:
     * bdrsf-bridge.js এর window.onAndroidBackPressed() কে প্রথমে সুযোগ দেয়।
     * যদি পেজে কোনো ব্যাক-হ্যান্ডলার না থাকে তবে স্বাভাবিক হিস্ট্রি অথবা ডাবল-ট্যাপ এক্সিট রান করে।
     */
    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when {
                    qrScannerFeature.isScanningActive -> qrScannerFeature.stopScanner(isCancelled = true)
                    documentScannerFeature.isScanningActive -> documentScannerFeature.stopScanner(isCancelled = true)
                    else -> {
                        webView.evaluateJavascript(
                            "(function() { if (typeof window.onAndroidBackPressed === 'function') { window.onAndroidBackPressed(); return true; } return false; })();"
                        ) { result ->
                            val handledByWeb = result == "true"
                            if (!handledByWeb) {
                                runOnUiThread {
                                    if (WebAppConfig.isHomeOrDashboard(webView.url) || !webView.canGoBack()) {
                                        onHandleBackPressRequested()
                                    } else {
                                        webView.goBack()
                                    }
                                }
                            }
                        }
                    }
                }
            }
        })
    }

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

    override fun onPickGalleryQrRequested(payload: JSONObject, callback: String?) {
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

    override fun onStartKycRequested(payload: JSONObject, callback: String?) {
        val actionType = payload.optString("actionType", "").lowercase()
        val docType = payload.optString("documentType", payload.optString("docType", "NID"))

        if (actionType == "process_hybrid" || actionType == "submit") {
            ocrProcessorFeature.processSavedDocumentsHybrid(docType, callback)
        } else {
            documentScannerFeature.startScanner(payload, callback)
        }
    }

    override fun onClearKycCacheRequested(callback: String?) {
        ocrProcessorFeature.clearCache()
        androidBridge.sendResponseToWeb(
            BridgeResponse(action = Actions.CLEAR_KYC_CACHE, status = Status.SUCCESS, message = "Cache cleared"),
            callback
        )
    }

    override fun onDownloadFileRequested(payload: JSONObject, callback: String?) {
        downloadFeature.handleBridgeDownload(payload, callback, androidBridge)
    }

    override fun onShareContentRequested(payload: JSONObject, callback: String?) {
        shareFeature.handleBridgeShare(payload, callback, androidBridge)
    }

    override fun onShowToastRequested(message: String) {
        if (message.isNotBlank()) {
            Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
        }
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

    override fun onGetAppInfoRequested(callback: String?) {
        try {
            val pInfo = packageManager.getPackageInfo(packageName, 0)
            val info = JSONObject().apply {
                put("packageName", packageName)
                put("versionName", pInfo.versionName ?: "1.0.0")
                put("versionCode", if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) pInfo.longVersionCode else pInfo.versionCode.toLong())
                put("osVersion", Build.VERSION.RELEASE)
                put("sdkVersion", Build.VERSION.SDK_INT)
                put("deviceModel", "${Build.MANUFACTURER} ${Build.MODEL}")
                put("isNative", true)
            }
            val cb = if (!callback.isNullOrBlank()) callback else "onAppInfoReceived"
            androidBridge.sendResponseToWeb(
                BridgeResponse(action = Actions.GET_APP_INFO, status = Status.SUCCESS, data = info),
                cb
            )
        } catch (e: Exception) {
            androidBridge.sendResponseToWeb(
                BridgeResponse(action = Actions.GET_APP_INFO, status = Status.ERROR, message = e.localizedMessage ?: "Error"),
                callback
            )
        }
    }

    override fun onClearWebviewHistoryRequested() {
        webView.clearHistory()
    }

    override fun onHandleBackPressRequested() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastBackPressTime < 2000L) {
            finish()
        } else {
            lastBackPressTime = currentTime
            Toast.makeText(this, "অ্যাপ থেকে বের হতে আবার ব্যাক প্রেস করুন", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onSetSystemBarsRequested(statusBarColor: String, navBarColor: String, lightIcons: Boolean) {
        try {
            val sColor = Color.parseColor(statusBarColor)
            val nColor = Color.parseColor(navBarColor)
            window.statusBarColor = sColor
            window.navigationBarColor = nColor
            mainLayout.setBackgroundColor(sColor)
            val insetsController = WindowInsetsControllerCompat(window, window.decorView)
            insetsController.isAppearanceLightStatusBars = lightIcons
            insetsController.isAppearanceLightNavigationBars = lightIcons
        } catch (_: Exception) {}
    }

    override fun onTriggerHapticRequested(type: String) {
        try {
            when (type.lowercase()) {
                "light" -> mainLayout.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                "medium" -> mainLayout.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                "heavy" -> mainLayout.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                "error", "warning" -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        mainLayout.performHapticFeedback(HapticFeedbackConstants.REJECT)
                    } else {
                        mainLayout.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    }
                }
                else -> mainLayout.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
        } catch (_: Exception) {}
    }

    override fun onCustomActionRequested(action: String, payload: JSONObject, callback: String?) {
        when (action) {
            "FORCE_REFRESH" -> WebAppConfig.forceRefreshWithCacheBypass(webView)
            "PROCESS_SAVED_DOCUMENTS" -> {
                val docType = payload.optString("docType", "NID")
                ocrProcessorFeature.processSavedDocumentsHybrid(docType, callback)
            }
            else -> {
                androidBridge.sendResponseToWeb(
                    BridgeResponse(action = action, status = Status.ERROR, message = "Unknown action: $action"),
                    callback
                )
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == NativeWebChromeClient.FILE_CHOOSER_REQUEST_CODE) {
            webChromeClient.handleFileChooserResult(requestCode, resultCode, data)
        } else if (requestCode == RC_GALLERY_QR) {
            qrScannerFeature.handleGalleryResult(resultCode, data, pendingGalleryQrCallback)
            pendingGalleryQrCallback = null
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
