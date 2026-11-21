// File: app/src/main/java/com/bdrsf/app/MainActivity.kt
package com.bdrsf.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
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
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.bdrsf.app.bridge.AndroidBridge
import com.bdrsf.app.bridge.WebBridgeContract
import com.bdrsf.app.bridge.WebBridgeContract.Actions
import com.bdrsf.app.bridge.WebBridgeContract.BridgeResponse
import com.bdrsf.app.bridge.WebBridgeContract.Status
import com.bdrsf.app.config.WebAppConfig
import com.bdrsf.app.features.contact.ContactPickerFeature
import com.bdrsf.app.features.document.DocumentScannerFeature
import com.bdrsf.app.features.document.GenericOcrFeature
import com.bdrsf.app.features.document.NidProcessorFeature
import com.bdrsf.app.features.document.PassportProcessorFeature
import com.bdrsf.app.features.download.DownloadFeature
import com.bdrsf.app.features.qr.QrScannerFeature
import com.bdrsf.app.features.share.ShareFeature
import com.bdrsf.app.services.BdrsfFirebaseMessagingService
import com.bdrsf.app.utils.PermissionHelper
import com.bdrsf.app.webview.NativeWebChromeClient
import com.bdrsf.app.webview.NativeWebViewClient
import com.google.firebase.messaging.FirebaseMessaging
import org.json.JSONObject

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
    private lateinit var contactPickerFeature: ContactPickerFeature
    private lateinit var qrScannerFeature: QrScannerFeature
    private lateinit var documentScannerFeature: DocumentScannerFeature
    private lateinit var passportProcessorFeature: PassportProcessorFeature
    private lateinit var nidProcessorFeature: NidProcessorFeature
    private lateinit var genericOcrFeature: GenericOcrFeature

    private var pendingGalleryQrCallback: String? = null
    private var lastBackPressTime: Long = 0L
    private var isTokenReceiverRegistered = false
    private var isLivePushReceiverRegistered = false

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            fetchAndSyncFcmToken()
        }

    private val newTokenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.bdrsf.app.ACTION_NEW_FCM_TOKEN") {
                val token = intent.getStringExtra("fcm_token").orEmpty()
                if (token.isNotBlank()) {
                    BdrsfFirebaseMessagingService.saveTokenLocally(applicationContext, token)
                    pushTokenToWebView(token)
                }
            }
        }
    }

    private val livePushReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.bdrsf.app.ACTION_LIVE_PUSH_EVENT") {
                val title = intent.getStringExtra("title").orEmpty()
                val message = intent.getStringExtra("message").orEmpty()
                val type = intent.getStringExtra("type").orEmpty().ifBlank { "info" }
                val unread = intent.getIntExtra("unread_count", BdrsfFirebaseMessagingService.getUnreadCount(applicationContext))
                BdrsfFirebaseMessagingService.applyLauncherBadge(applicationContext, unread)
                if (::webView.isInitialized) {
                    val script = WebBridgeContract.buildPushAlertScript(title, message, type)
                    androidBridge.evaluateJavascript(script)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        setupSystemWindows()
        initViews()
        initFeatures()
        setupWebView()
        setupBackNavigation()
        registerPushReceivers()
        restorePersistedLauncherBadge()

        PermissionHelper.requestAllInitialPermissions(this)
        requestNotificationPermissionIfNeeded()

        val initialUrl = intent?.getStringExtra("fcm_target_url")?.takeIf { it.isNotBlank() }
            ?: WebAppConfig.BASE_URL
        webView.loadUrl(initialUrl)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        restorePersistedLauncherBadge()
        val targetUrl = intent.getStringExtra("fcm_target_url")?.takeIf { it.isNotBlank() }
        if (!targetUrl.isNullOrBlank() && ::webView.isInitialized) {
            webView.loadUrl(targetUrl)
        } else if (::webView.isInitialized) {
            triggerWebViewLiveSync()
        }
    }

    private fun restorePersistedLauncherBadge() {
        val currentUnread = BdrsfFirebaseMessagingService.getUnreadCount(applicationContext)
        BdrsfFirebaseMessagingService.applyLauncherBadge(applicationContext, currentUnread)
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private fun registerPushReceivers() {
        if (!isTokenReceiverRegistered) {
            val tokenFilter = IntentFilter("com.bdrsf.app.ACTION_NEW_FCM_TOKEN")
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(newTokenReceiver, tokenFilter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    registerReceiver(newTokenReceiver, tokenFilter)
                }
                isTokenReceiverRegistered = true
            } catch (_: Exception) {}
        }

        if (!isLivePushReceiverRegistered) {
            val pushFilter = IntentFilter("com.bdrsf.app.ACTION_LIVE_PUSH_EVENT")
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    registerReceiver(livePushReceiver, pushFilter, Context.RECEIVER_NOT_EXPORTED)
                } else {
                    registerReceiver(livePushReceiver, pushFilter)
                }
                isLivePushReceiverRegistered = true
            } catch (_: Exception) {}
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                return
            }
        }
        fetchAndSyncFcmToken()
    }

    private fun fetchAndSyncFcmToken() {
        val saved = BdrsfFirebaseMessagingService.getSavedToken(applicationContext)
        if (!saved.isNullOrBlank()) {
            pushTokenToWebView(saved)
        }

        try {
            FirebaseMessaging.getInstance().token.addOnCompleteListener { task ->
                if (!task.isSuccessful) return@addOnCompleteListener
                val token = task.result?.trim().orEmpty()
                if (token.isNotEmpty()) {
                    BdrsfFirebaseMessagingService.saveTokenLocally(applicationContext, token)
                    pushTokenToWebView(token)
                }
            }
        } catch (_: Exception) {}
    }

    private fun pushTokenToWebView(token: String) {
        if (!::webView.isInitialized || token.isBlank()) return
        val escaped = JSONObject.quote(token)
        runOnUiThread {
            try {
                webView.evaluateJavascript(
                    "(function(){ if (typeof window.bdrsfSyncFcmTokenToServer === 'function') { window.bdrsfSyncFcmTokenToServer($escaped); } })();",
                    null
                )
            } catch (_: Exception) {}
        }
    }

    private fun triggerWebViewLiveSync() {
        if (!::webView.isInitialized) return
        runOnUiThread {
            try {
                webView.evaluateJavascript(
                    "(function(){ if (window.BDRSF_BRIDGE && typeof window.BDRSF_BRIDGE.forceSync === 'function') { window.BDRSF_BRIDGE.forceSync(); } else if (typeof window.syncRealTimeData === 'function') { window.syncRealTimeData(true); } })();",
                    null
                )
            } catch (_: Exception) {}
        }
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
            layoutParams = FrameLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.MATCH_PARENT)
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
        contactPickerFeature = ContactPickerFeature(this, androidBridge)
        qrScannerFeature = QrScannerFeature(this, mainLayout, androidBridge)

        passportProcessorFeature = PassportProcessorFeature(this, androidBridge, webView)
        nidProcessorFeature = NidProcessorFeature(this, androidBridge, webView)
        genericOcrFeature = GenericOcrFeature(this, androidBridge, webView)

        documentScannerFeature = DocumentScannerFeature(this, mainLayout, androidBridge) { capturedFile, scanMode, policy, callback ->
            when (scanMode) {
                "passport" -> passportProcessorFeature.processPassportFile(capturedFile, policy, callback)
                "nid_front", "nid_back", "face_liveness" -> nidProcessorFeature.processNidSlot(capturedFile, scanMode, callback)
                else -> genericOcrFeature.processWithPolicy(capturedFile, policy, callback)
            }
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
                    restorePersistedLauncherBadge()
                    val token = BdrsfFirebaseMessagingService.getSavedToken(applicationContext)
                    if (!token.isNullOrBlank()) {
                        pushTokenToWebView(token)
                    }
                    triggerWebViewLiveSync()
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
                                    val currentUrl = webView.url ?: ""
                                    if (WebAppConfig.isHomeOrDashboard(currentUrl)) {
                                        onHandleBackPressRequested()
                                    } else if (webView.canGoBack()) {
                                        webView.goBack()
                                    } else {
                                        webView.loadUrl(WebAppConfig.BASE_URL)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        })
    }

    override fun onResume() {
        super.onResume()
        registerPushReceivers()
        restorePersistedLauncherBadge()
        WebAppConfig.syncCookies()
        triggerWebViewLiveSync()
    }

    override fun onPause() {
        super.onPause()
        restorePersistedLauncherBadge()
        WebAppConfig.syncCookies()
    }

    override fun onStop() {
        super.onStop()
        restorePersistedLauncherBadge()
        WebAppConfig.syncCookies()
    }

    override fun onScanQrRequested(payload: JSONObject, callback: String?) {
        qrScannerFeature.startScanner(payload, callback)
    }

    override fun onPickGalleryQrRequested(payload: JSONObject, callback: String?) {
        pendingGalleryQrCallback = callback
        qrScannerFeature.prepareGalleryScanner(payload, callback)
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
        val docType = payload.optString("docCategory", payload.optString("documentType", payload.optString("docType", "NID")))

        if (actionType == "process_hybrid" || actionType == "submit") {
            nidProcessorFeature.submitNidHybrid(callback)
        } else {
            val normalizedPayload = JSONObject(payload.toString()).apply {
                if (!has("mode") || optString("mode").isBlank()) {
                    put("mode", docType.lowercase())
                }
            }
            documentScannerFeature.startScanner(normalizedPayload, callback)
        }
    }

    override fun onClearKycCacheRequested(callback: String?) {
        nidProcessorFeature.clearCache()
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
            showExitConfirmationDialog()
        } else {
            lastBackPressTime = currentTime
            Toast.makeText(this, "রিফ্রেশ হচ্ছে...", Toast.LENGTH_SHORT).show()
            WebAppConfig.forceRefreshWithCacheBypass(webView)
        }
    }

    private fun showExitConfirmationDialog() {
        AlertDialog.Builder(this)
            .setTitle("প্রস্থান নিশ্চিতকরণ")
            .setMessage("আপনি কি নিশ্চিতভাবে অ্যাপ থেকে বের হতে চান?")
            .setPositiveButton("হ্যাঁ") { _, _ ->
                restorePersistedLauncherBadge()
                WebAppConfig.syncCookies()
                finishAffinity()
            }
            .setNegativeButton("না", null)
            .show()
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
            "PICK_CONTACT" -> contactPickerFeature.pickContact(callback)
            "FORCE_REFRESH" -> WebAppConfig.forceRefreshWithCacheBypass(webView)
            "SCAN_DOCUMENT_WITH_POLICY" -> {
                documentScannerFeature.startScanner(payload, callback)
            }
            "PICK_PASSPORT_GALLERY" -> {
                passportProcessorFeature.openGalleryPicker(payload, callback)
            }
            "PROCESS_SAVED_DOCUMENTS" -> {
                nidProcessorFeature.submitNidHybrid(callback)
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
        } else if (passportProcessorFeature.handleActivityResult(requestCode, resultCode, data)) {
            return
        } else if (contactPickerFeature.handleActivityResult(requestCode, resultCode, data)) {
            return
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (isTokenReceiverRegistered) {
            try {
                unregisterReceiver(newTokenReceiver)
            } catch (_: Exception) {}
            isTokenReceiverRegistered = false
        }
        if (isLivePushReceiverRegistered) {
            try {
                unregisterReceiver(livePushReceiver)
            } catch (_: Exception) {}
            isLivePushReceiverRegistered = false
        }
        restorePersistedLauncherBadge()
        WebAppConfig.syncCookies()
        qrScannerFeature.destroy()
        documentScannerFeature.destroy()
        passportProcessorFeature.destroy()
        nidProcessorFeature.destroy()
        genericOcrFeature.destroy()

        try {
            webView.stopLoading()
            mainLayout.removeView(webView)
            webView.destroy()
        } catch (_: Exception) {}
    }
}
