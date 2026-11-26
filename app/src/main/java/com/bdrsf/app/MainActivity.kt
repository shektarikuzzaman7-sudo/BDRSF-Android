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
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.MediaStore
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebView
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.RelativeLayout
import android.widget.TextView
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
import com.bdrsf.app.features.download.DownloadFeature
import com.bdrsf.app.features.kyc.KycOperatingEngine
import com.bdrsf.app.features.ocr.ScreenshotOcrFeature
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
        private const val PREFS_BATTERY_PROMPT = "bdrsf_battery_prompt_prefs"
        private const val KEY_BATTERY_PROMPTED = "battery_exemption_prompted"
        private const val KEY_REMEMBERED_LOGIN_ID = "verified_login_id"
        private const val LOADER_PAINT_BUFFER_MS = 400L
        private const val LOADER_MAX_TIMEOUT_MS = 3000L
        private var isAppProcessActive = false
    }

    private lateinit var mainLayout: RelativeLayout
    private lateinit var splashLayout: FrameLayout
    private lateinit var networkErrorLayout: FrameLayout
    private lateinit var errorSubtitleText: TextView
    private lateinit var webView: WebView

    private lateinit var androidBridge: AndroidBridge
    private lateinit var webViewClient: NativeWebViewClient
    private lateinit var webChromeClient: NativeWebChromeClient
    private lateinit var downloadFeature: DownloadFeature
    private lateinit var shareFeature: ShareFeature
    private lateinit var contactPickerFeature: ContactPickerFeature
    private lateinit var qrScannerFeature: QrScannerFeature
    private lateinit var kycOperatingEngine: KycOperatingEngine
    private lateinit var screenshotOcrFeature: ScreenshotOcrFeature

    private val uiHandler = Handler(Looper.getMainLooper())
    private var loaderSafetyRunnable: Runnable? = null
    private var loaderHideRunnable: Runnable? = null

    private var lastLoadedOrFailedUrl: String = WebAppConfig.BASE_URL
    private var hasMainFrameError: Boolean = false
    private var pendingGalleryQrCallback: String? = null
    private var lastBackPressTime: Long = 0L
    private var isTokenReceiverRegistered = false
    private var isLivePushReceiverRegistered = false

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { _ ->
            fetchAndSyncFcmToken()
            requestBatteryOptimizationExemptionIfNeeded()
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

        val isColdStartAfterKill = !isAppProcessActive
        isAppProcessActive = true

        setupSystemWindows()
        initViews()
        initFeatures()
        setupWebView()
        setupBackNavigation()
        registerPushReceivers()
        restorePersistedLauncherBadge()

        PermissionHelper.requestAllInitialPermissions(this)
        requestNotificationPermissionIfNeeded()

        if (isColdStartAfterKill) {
            clearSessionOnAppKill()
        } else {
            val initialUrl = extractNotificationTargetUrl(intent) ?: WebAppConfig.BASE_URL
            loadUrlWithNetworkGuard(initialUrl)
        }
    }

    private fun isInternetAvailable(): Boolean {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val network = cm.activeNetwork ?: return false
                val caps = cm.getNetworkCapabilities(network) ?: return false
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            } else {
                @Suppress("DEPRECATION")
                cm.activeNetworkInfo?.isConnected == true
            }
        } catch (_: Exception) {
            true
        }
    }

    private fun loadUrlWithNetworkGuard(targetUrl: String) {
        lastLoadedOrFailedUrl = targetUrl
        if (!isInternetAvailable()) {
            showNetworkErrorScreen(
                "আপনার ডিভাইসে ইন্টারনেট সংযোগ নেই। অনুগ্রহ করে Wi-Fi বা মোবাইল ডাটা চালু করে পুনরায় চেষ্টা করুন।",
                targetUrl
            )
            return
        }
        hideNetworkErrorScreen()
        showPremiumTransitionLoader()
        webView.loadUrl(targetUrl)
    }

    private fun showNetworkErrorScreen(reason: String, failingUrl: String) {
        if (!::networkErrorLayout.isInitialized) return
        runOnUiThread {
            hasMainFrameError = true
            if (failingUrl.isNotBlank() && failingUrl != "about:blank") {
                lastLoadedOrFailedUrl = failingUrl
            }
            hidePremiumTransitionLoader(immediate = true)
            errorSubtitleText.text = reason.ifBlank {
                "ইন্টারনেট সংযোগ অথবা সার্ভারের সাথে যোগাযোগ বিচ্ছিন্ন হয়েছে। অনুগ্রহ করে আবার চেষ্টা করুন।"
            }
            if (networkErrorLayout.visibility != View.VISIBLE) {
                networkErrorLayout.alpha = 0f
                networkErrorLayout.visibility = View.VISIBLE
                networkErrorLayout.animate().alpha(1f).setDuration(220L).start()
            }
        }
    }

    private fun hideNetworkErrorScreen() {
        if (!::networkErrorLayout.isInitialized) return
        runOnUiThread {
            hasMainFrameError = false
            networkErrorLayout.animate().cancel()
            networkErrorLayout.visibility = View.GONE
        }
    }

    private fun showPremiumTransitionLoader() {
        if (!::splashLayout.isInitialized) return
        runOnUiThread {
            loaderHideRunnable?.let { uiHandler.removeCallbacks(it) }
            loaderSafetyRunnable?.let { uiHandler.removeCallbacks(it) }

            splashLayout.animate().cancel()
            if (splashLayout.visibility != View.VISIBLE) {
                splashLayout.alpha = 0f
                splashLayout.visibility = View.VISIBLE
                splashLayout.animate().alpha(1f).setDuration(130L).start()
            } else {
                splashLayout.alpha = 1f
            }

            // সর্বোচ্চ ৩ সেকেন্ডের সেফটি টাইমআউট যাতে স্লো নেটওয়ার্কেও স্ক্রিন আটকে না থাকে
            val safetyTask = Runnable { hidePremiumTransitionLoader(immediate = false) }
            loaderSafetyRunnable = safetyTask
            uiHandler.postDelayed(safetyTask, LOADER_MAX_TIMEOUT_MS)
        }
    }

    private fun hidePremiumTransitionLoader(immediate: Boolean = false) {
        if (!::splashLayout.isInitialized) return
        runOnUiThread {
            loaderSafetyRunnable?.let { uiHandler.removeCallbacks(it) }
            loaderHideRunnable?.let { uiHandler.removeCallbacks(it) }

            val fadeOutTask = Runnable {
                if (splashLayout.visibility == View.VISIBLE) {
                    splashLayout.animate()
                        .alpha(0f)
                        .setDuration(260L)
                        .withEndAction {
                            splashLayout.visibility = View.GONE
                        }
                        .start()
                }
            }

            if (immediate) {
                fadeOutTask.run()
            } else {
                // ব্যাকগ্রাউন্ড ইমেজ ও থিম কালার পেইন্ট হওয়ার জন্য ৪০০ms স্মুথ বাফার
                loaderHideRunnable = fadeOutTask
                uiHandler.postDelayed(fadeOutTask, LOADER_PAINT_BUFFER_MS)
            }
        }
    }

    private fun clearSessionOnAppKill() {
        try {
            val cookieManager = CookieManager.getInstance()
            val urlForCookies = WebAppConfig.BASE_URL
            val existingCookieStr = cookieManager.getCookie(urlForCookies).orEmpty()
            val preservedLoginId = existingCookieStr.split(";")
                .map { it.trim() }
                .firstOrNull { it.startsWith("$KEY_REMEMBERED_LOGIN_ID=") }
                ?.substringAfter("=")
                ?.trim()

            cookieManager.removeSessionCookies(null)
            if (!preservedLoginId.isNullOrBlank()) {
                cookieManager.setCookie(
                    urlForCookies,
                    "$KEY_REMEMBERED_LOGIN_ID=$preservedLoginId; path=/; SameSite=Lax"
                )
            }
            cookieManager.flush()
        } catch (_: Exception) {}

        if (!isInternetAvailable()) {
            showNetworkErrorScreen(
                "আপনার ডিভাইসে ইন্টারনেট সংযোগ নেই। অনুগ্রহ করে ইন্টারনেট চালু করে পুনরায় চেষ্টা করুন।",
                WebAppConfig.BASE_URL
            )
            return
        }

        showPremiumTransitionLoader()
        val baseClean = WebAppConfig.BASE_URL.substringBefore("?").trimEnd('/')
        webView.loadUrl("$baseClean/index.php?page=logout")
        webView.postDelayed({
            if (::webView.isInitialized) {
                loadUrlWithNetworkGuard(WebAppConfig.BASE_URL)
            }
        }, 300L)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        restorePersistedLauncherBadge()
        val targetUrl = extractNotificationTargetUrl(intent)
        if (!targetUrl.isNullOrBlank() && ::webView.isInitialized) {
            loadUrlWithNetworkGuard(targetUrl)
        } else if (::webView.isInitialized) {
            triggerWebViewLiveSync()
        }
    }

    private fun extractNotificationTargetUrl(sourceIntent: Intent?): String? {
        if (sourceIntent == null) return null
        val extras = sourceIntent.extras ?: return null

        val rawUrl = extras.getString("fcm_target_url")
            ?: extras.getString("url")
            ?: extras.getString("link_url")
            ?: sourceIntent.getStringExtra("fcm_target_url")

        val pageParam = extras.getString("page")
        val notifId = extras.getString("notif_id") ?: extras.getString("notification_id")

        val isFromPush = extras.getBoolean("from_notification", false) ||
            sourceIntent.action == "com.bdrsf.app.ACTION_OPEN_NOTIFICATION" ||
            sourceIntent.action == "FLUTTER_NOTIFICATION_CLICK" ||
            extras.containsKey("google.message_id") ||
            extras.containsKey("gcm.message_id") ||
            !rawUrl.isNullOrBlank() ||
            !pageParam.isNullOrBlank()

        if (!isFromPush) return null

        return BdrsfFirebaseMessagingService.resolveTargetUrl(rawUrl, pageParam, notifId)
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
        requestBatteryOptimizationExemptionIfNeeded()
    }

    @SuppressLint("BatteryLife")
    private fun requestBatteryOptimizationExemptionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
                if (powerManager.isIgnoringBatteryOptimizations(packageName)) return

                val prefs = getSharedPreferences(PREFS_BATTERY_PROMPT, Context.MODE_PRIVATE)
                if (prefs.getBoolean(KEY_BATTERY_PROMPTED, false)) return
                prefs.edit().putBoolean(KEY_BATTERY_PROMPTED, true).apply()

                val batteryIntent = Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:$packageName")
                )
                startActivity(batteryIntent)
            } catch (_: Exception) {}
        }
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
            WindowCompat.setDecorFitsSystemWindows(window, false)

            @Suppress("DEPRECATION")
            window.statusBarColor = Color.TRANSPARENT

            @Suppress("DEPRECATION")
            window.navigationBarColor = Color.TRANSPARENT

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
        val density = resources.displayMetrics.density

        mainLayout = RelativeLayout(this).apply {
            layoutParams = RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.TRANSPARENT)
        }

        // স্ট্যাটাস বার ও নেভিগেশন বার স্বচ্ছ রেখে ইনসেট ওয়েবভিউকে পাস করা (শুধুমাত্র কীবোর্ড খুললে নিচে প্যাডিং)
        ViewCompat.setOnApplyWindowInsetsListener(mainLayout) { view, insets ->
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(0, 0, 0, ime.bottom)
            insets
        }

        // ১. মেইন ওয়েবভিউ লেয়ার (স্বচ্ছ সিস্টেম বারের নিচ দিয়ে ওয়েবসাইটের ব্যাকগ্রাউন্ড/থিম কালার দৃশ্যমান থাকবে)
        webView = WebView(this).apply {
            layoutParams = RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.TRANSPARENT)
        }
        mainLayout.addView(webView)

        // ২. প্রিমিয়াম নেটওয়ার্ক এরর স্ক্রিন
        networkErrorLayout = buildPremiumNetworkErrorView(density)
        mainLayout.addView(networkErrorLayout)

        // ৩. প্রিমিয়াম লোগো + গোল্ডেন সার্কুলার ট্রানজিশন লোডার
        splashLayout = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.parseColor("#04130F"))
            isClickable = true
            isFocusable = true
        }

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

    private fun buildPremiumNetworkErrorView(density: Float): FrameLayout {
        val rootOverlay = FrameLayout(this).apply {
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.MATCH_PARENT
            )
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#06231B"), Color.parseColor("#03100C"))
            )
            visibility = View.GONE
            isClickable = true
            isFocusable = true
        }

        val cardBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            val padH = (24 * density).toInt()
            val padV = (28 * density).toInt()
            setPadding(padH, padV, padH, padV)
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(Color.parseColor("#0C352A"), Color.parseColor("#051A14"))
            ).apply {
                cornerRadius = 26f * density
                setStroke((1.5f * density).toInt(), Color.parseColor("#34D399"))
            }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER
                val margin = (24 * density).toInt()
                setMargins(margin, 0, margin, 0)
            }
        }

        val iconCircleSize = (76 * density).toInt()
        val iconChamber = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(iconCircleSize, iconCircleSize).apply {
                bottomMargin = (18 * density).toInt()
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.parseColor("#1A3B31"))
                setStroke((2f * density).toInt(), Color.parseColor("#FACC15"))
            }
        }

        val statusIcon = ImageView(this).apply {
            val iconSize = (38 * density).toInt()
            layoutParams = FrameLayout.LayoutParams(iconSize, iconSize).apply {
                gravity = Gravity.CENTER
            }
            setImageResource(android.R.drawable.stat_sys_warning)
            setColorFilter(Color.parseColor("#FACC15"), PorterDuff.Mode.SRC_IN)
        }
        iconChamber.addView(statusIcon)
        cardBox.addView(iconChamber)

        val titleView = TextView(this).apply {
            text = "নেটওয়ার্ক সংযোগ বিচ্ছিন্ন!"
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            setTypeface(null, Typeface.BOLD)
            gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (8 * density).toInt()
            }
        }
        cardBox.addView(titleView)

        errorSubtitleText = TextView(this).apply {
            text = "আপনার ইন্টারনেট সংযোগ অথবা সার্ভারের সাথে যোগাযোগ করা যাচ্ছে না। অনুগ্রহ করে সংযোগ পরীক্ষা করে আবার চেষ্টা করুন।"
            setTextColor(Color.parseColor("#CBD5E1"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
            gravity = Gravity.CENTER
            setLineSpacing(4f * density, 1f)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = (24 * density).toInt()
            }
        }
        cardBox.addView(errorSubtitleText)

        val retryButton = Button(this).apply {
            text = "পুনরায় চেষ্টা করুন  •  TRY AGAIN"
            isAllCaps = false
            setTextColor(Color.parseColor("#04130F"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            setTypeface(null, Typeface.BOLD)
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.parseColor("#FDE68A"), Color.parseColor("#F59E0B"))
            ).apply {
                cornerRadius = 14f * density
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (48 * density).toInt()
            )
            setOnClickListener {
                onTriggerHapticRequested("medium")
                val retryTarget = lastLoadedOrFailedUrl.ifBlank { WebAppConfig.BASE_URL }
                loadUrlWithNetworkGuard(retryTarget)
            }
        }
        cardBox.addView(retryButton)

        rootOverlay.addView(cardBox)
        return rootOverlay
    }

    private fun initFeatures() {
        androidBridge = AndroidBridge(this, webView, this)
        downloadFeature = DownloadFeature(this)
        shareFeature = ShareFeature(this)
        contactPickerFeature = ContactPickerFeature(this, androidBridge)
        qrScannerFeature = QrScannerFeature(this, mainLayout, androidBridge)
        kycOperatingEngine = KycOperatingEngine(this, mainLayout, androidBridge, webView)
        screenshotOcrFeature = ScreenshotOcrFeature(this, androidBridge)

        webViewClient = NativeWebViewClient(
            activity = this,
            onPageStartedListener = {
                hasMainFrameError = false
                showPremiumTransitionLoader()
            },
            onPageFinishedListener = {
                runOnUiThread {
                    if (!hasMainFrameError) {
                        hideNetworkErrorScreen()
                    }
                    hidePremiumTransitionLoader(immediate = false)
                    WebAppConfig.syncCookies()
                    restorePersistedLauncherBadge()
                    val token = BdrsfFirebaseMessagingService.getSavedToken(applicationContext)
                    if (!token.isNullOrBlank()) {
                        pushTokenToWebView(token)
                    }
                    triggerWebViewLiveSync()
                }
            },
            onErrorReceivedListener = { _, description, failingUrl ->
                showNetworkErrorScreen(description, failingUrl)
            }
        )

        webChromeClient = NativeWebChromeClient(this)
    }

    private fun setupWebView() {
        WebAppConfig.applyDefaultSettings(this, webView)
        webView.settings.cacheMode = WebSettings.LOAD_DEFAULT
        webView.settings.domStorageEnabled = true
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
                    networkErrorLayout.visibility == View.VISIBLE -> {
                        if (isInternetAvailable()) {
                            loadUrlWithNetworkGuard(WebAppConfig.BASE_URL)
                        } else {
                            onHandleBackPressRequested()
                        }
                    }
                    qrScannerFeature.isScanningActive -> qrScannerFeature.stopScanner(isCancelled = true)
                    kycOperatingEngine.handleBackPressed() -> Unit
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
                                        showPremiumTransitionLoader()
                                        webView.goBack()
                                    } else {
                                        loadUrlWithNetworkGuard(WebAppConfig.BASE_URL)
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
        kycOperatingEngine.handleStartKyc(payload, callback)
    }

    override fun onClearKycCacheRequested(callback: String?) {
        kycOperatingEngine.clearCache()
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
            if (!isInternetAvailable()) {
                showNetworkErrorScreen(
                    "আপনার ডিভাইসে ইন্টারনেট সংযোগ নেই। অনুগ্রহ করে ইন্টারনেট চালু করে পুনরায় চেষ্টা করুন।",
                    webView.url ?: WebAppConfig.BASE_URL
                )
            } else {
                showPremiumTransitionLoader()
                WebAppConfig.forceRefreshWithCacheBypass(webView)
            }
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
            // হার্ডকোডেড কালার পরিহার করে কাচের মতো স্বচ্ছ সিস্টেম বার বজায় রাখা
            @Suppress("DEPRECATION")
            window.statusBarColor = Color.TRANSPARENT

            @Suppress("DEPRECATION")
            window.navigationBarColor = Color.TRANSPARENT

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
            "SHOW_TRANSITION_LOADER" -> showPremiumTransitionLoader()
            "HIDE_TRANSITION_LOADER" -> hidePremiumTransitionLoader(immediate = true)
            "PICK_CONTACT" -> contactPickerFeature.pickContact(callback)
            "FORCE_REFRESH" -> {
                showPremiumTransitionLoader()
                WebAppConfig.forceRefreshWithCacheBypass(webView)
            }
            "SCAN_SCREENSHOT_OCR" -> {
                val base64Img = payload.optString("image_base64", "")
                if (base64Img.isNotBlank()) {
                    screenshotOcrFeature.scanBase64Image(base64Img, callback)
                } else {
                    androidBridge.sendResponseToWeb(
                        BridgeResponse(action = action, status = Status.ERROR, message = "Empty screenshot data"),
                        callback
                    )
                }
            }
            "START_HD_FACE_CAMERA", "FACE_VERIFICATION", "START_FACE_LIVENESS" -> {
                kycOperatingEngine.startHdFaceCamera(callback)
            }
            "PICK_KYC_GALLERY", "PICK_NID_GALLERY", "PICK_PASSPORT_GALLERY" -> {
                val slot = payload.optString("slot", payload.optString("mode", "nid_front"))
                kycOperatingEngine.pickGallery(slot, callback)
            }
            "SCAN_DOCUMENT_WITH_POLICY" -> {
                kycOperatingEngine.handleStartKyc(payload, callback)
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
        } else if (kycOperatingEngine.handleActivityResult(requestCode, resultCode, data)) {
            return
        } else if (contactPickerFeature.handleActivityResult(requestCode, resultCode, data)) {
            return
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        loaderSafetyRunnable?.let { uiHandler.removeCallbacks(it) }
        loaderHideRunnable?.let { uiHandler.removeCallbacks(it) }

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
        kycOperatingEngine.destroy()

        try {
            webView.stopLoading()
            mainLayout.removeView(webView)
            webView.destroy()
        } catch (_: Exception) {}
    }
}
