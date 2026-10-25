package com.bdrsf.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.media.FaceDetector
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.RelativeLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.exifinterface.media.ExifInterface
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var splashLayout: FrameLayout
    private lateinit var mainLayout: RelativeLayout

    private var scannerContainer: FrameLayout? = null
    private var previewView: PreviewView? = null
    private var cardOverlayView: CardOverlayView? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService

    private var isScanningActive = false
    private val isQRProcessed = AtomicBoolean(false)
    private val isCapturingNow = AtomicBoolean(false)
    private var isFirstPageLoad = true

    private var currentScanDocType = "nid_front"
    private var frontDocFile: File? = null
    private var backDocFile: File? = null
    private var extractedFaceBase64: String = ""

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraImageUri: Uri? = null

    private val FILE_CHOOSER_REQUEST_CODE = 1001
    private val PERMISSION_REQUEST_CODE = 1002
    private val GALLERY_QR_REQUEST_CODE = 1004
    private val DOCUMENT_SCAN_REQUEST_CODE = 1005

    private val barcodeScanner: BarcodeScanner by lazy { BarcodeScanning.getClient() }
    private var steadyFramesCount = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        supportActionBar?.hide()
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        try {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            @Suppress("DEPRECATION")
            window.statusBarColor = Color.parseColor("#0A2F1D")
            @Suppress("DEPRECATION")
            window.navigationBarColor = Color.BLACK
            WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false
            @Suppress("DEPRECATION")
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        mainLayout = RelativeLayout(this).apply {
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#0A2F1D"))
        }

        ViewCompat.setOnApplyWindowInsetsListener(mainLayout) { view, windowInsets ->
            val statusBars = windowInsets.getInsets(WindowInsetsCompat.Type.statusBars())
            val ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            view.setPadding(0, statusBars.top, 0, ime.bottom)
            if (ime.bottom == 0) hideSystemNavigationBar()
            WindowInsetsCompat.CONSUMED
        }

        webView = WebView(this).apply {
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.MATCH_PARENT
            )
        }
        mainLayout.addView(webView)

        splashLayout = FrameLayout(this).apply {
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.parseColor("#0A2F1D"))
        }

        val logoSizePx = (130 * resources.displayMetrics.density).toInt()
        val loaderSizePx = (155 * resources.displayMetrics.density).toInt()

        val circularProgressBar = ProgressBar(this, null, android.R.attr.progressBarStyleLarge).apply {
            layoutParams = FrameLayout.LayoutParams(loaderSizePx, loaderSizePx).apply { gravity = Gravity.CENTER }
            try {
                @Suppress("DEPRECATION")
                indeterminateDrawable.setColorFilter(Color.parseColor("#FFD700"), PorterDuff.Mode.SRC_IN)
            } catch (e: Exception) { e.printStackTrace() }
        }
        splashLayout.addView(circularProgressBar)

        val logoImageView = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(logoSizePx, logoSizePx).apply { gravity = Gravity.CENTER }
            val logoResId = resources.getIdentifier("ic_logo", "drawable", packageName)
            if (logoResId != 0) setImageResource(logoResId)
        }
        splashLayout.addView(logoImageView)

        mainLayout.addView(splashLayout)
        setContentView(mainLayout)

        cameraExecutor = Executors.newSingleThreadExecutor()
        requestAllRequiredPermissions()
        hideSystemNavigationBar()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isScanningActive) {
                    stopCustomCameraScanner()
                } else if (::webView.isInitialized) {
                    val currentUrl = webView.url ?: ""
                    val normalizedUrl = currentUrl.trimEnd('/')
                    val homeBase = "https://bdrsf.com"

                    if (normalizedUrl.isNotEmpty() && normalizedUrl != homeBase) {
                        webView.loadUrl("https://bdrsf.com/")
                        webView.clearHistory()
                    } else {
                        webView.reload()
                    }
                }
            }
        })

        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT
            loadWithOverviewMode = true
            useWideViewPort = true
            allowFileAccess = true
            allowContentAccess = true
            setGeolocationEnabled(true)
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        webView.addJavascriptInterface(WebAppInterface(this), "AndroidBridge")

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)
        clearAppSessionKeepSmartId()

        webView.setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            try {
                if (url.startsWith("blob:")) {
                    Toast.makeText(this, "ব্লব ফাইল সরাসরি ডাউনলোড করা যায় না", Toast.LENGTH_SHORT).show()
                    return@DownloadListener
                }
                val request = DownloadManager.Request(Uri.parse(url))
                val fileName = URLUtil.guessFileName(url, contentDisposition, mimetype)
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                request.setTitle(fileName)
                request.setDescription("ফাইল ডাউনলোড হচ্ছে...")
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)

                val cookies = CookieManager.getInstance().getCookie(url)
                if (!cookies.isNullOrEmpty()) request.addRequestHeader("cookie", cookies)
                request.addRequestHeader("User-Agent", userAgent)

                val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                downloadManager.enqueue(request)
                Toast.makeText(this, "ডাউনলোড শুরু হয়েছে: $fileName", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(this, "ডাউনলোড করতে সমস্যা হয়েছে", Toast.LENGTH_SHORT).show()
            }
        })

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest?) {
                request?.let { runOnUiThread { it.grant(it.resources) } }
            }
            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
                val hasLocationPermission = ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
                callback?.invoke(origin, hasLocationPermission, false)
            }
            override fun onShowFileChooser(webView: WebView?, callback: ValueCallback<Array<Uri>>?, fileChooserParams: FileChooserParams?): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                if (!hasCameraPermission()) requestAllRequiredPermissions()
                try {
                    val contentSelectionIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    }
                    val chooserIntent = Intent(Intent.ACTION_CHOOSER).apply {
                        putExtra(Intent.EXTRA_INTENT, contentSelectionIntent)
                        putExtra(Intent.EXTRA_TITLE, "ফাইল বা ছবি সিলেক্ট করুন")
                    }
                    @Suppress("DEPRECATION")
                    startActivityForResult(chooserIntent, FILE_CHOOSER_REQUEST_CODE)
                } catch (e: ActivityNotFoundException) {
                    filePathCallback?.onReceiveValue(null)
                    filePathCallback = null
                    return false
                }
                return true
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (isFirstPageLoad) splashLayout.visibility = View.VISIBLE
            }
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return false
                return handleUrlLoading(view, url)
            }
            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (url == null) return false
                return handleUrlLoading(view, url)
            }
            private fun handleUrlLoading(view: WebView?, url: String): Boolean {
                try {
                    val uri = Uri.parse(url)
                    val scheme = uri.scheme ?: ""
                    if (scheme == "tel" || scheme == "mailto" || scheme == "whatsapp" || scheme == "intent") {
                        try {
                            val intent = Intent(Intent.ACTION_VIEW, uri)
                            view?.context?.startActivity(intent)
                        } catch (e: ActivityNotFoundException) {
                            Toast.makeText(this@MainActivity, "উপযুক্ত অ্যাপ ইনস্টল করা নেই", Toast.LENGTH_SHORT).show()
                        }
                        return true
                    }
                    val host = uri.host ?: ""
                    return if (host == "bdrsf.com" || host.endsWith(".bdrsf.com")) {
                        false
                    } else {
                        val intent = Intent(Intent.ACTION_VIEW, uri)
                        view?.context?.startActivity(intent)
                        true
                    }
                } catch (e: Exception) { e.printStackTrace() }
                return false
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                CookieManager.getInstance().flush()
                if (isFirstPageLoad) {
                    isFirstPageLoad = false
                    splashLayout.animate().alpha(0f).setDuration(400).withEndAction {
                        splashLayout.visibility = View.GONE
                    }
                }
            }
        }
        webView.loadUrl("https://bdrsf.com/")
    }

    class WebAppInterface(private val activity: MainActivity) {
        @JavascriptInterface
        fun startNativeMLKitScanner() {
            activity.runOnUiThread { activity.startQRScanner() }
        }

        @JavascriptInterface
        fun pickDocumentImage(slot: String, mode: String) {
            activity.runOnUiThread {
                activity.currentScanDocType = slot
                if (mode == "camera") {
                    activity.startCardAutoCameraScanner(slot)
                } else {
                    activity.launchDirectGalleryForDocument()
                }
            }
        }

        @JavascriptInterface
        fun startNativeGalleryPicker() {
            activity.runOnUiThread { activity.openGalleryForQR() }
        }

        @JavascriptInterface
        fun saveBase64Image(base64Data: String, fileName: String) {
            activity.runOnUiThread {
                try {
                    val cleanBase64 = if (base64Data.contains(",")) base64Data.split(",")[1] else base64Data
                    val decodedBytes = Base64.decode(cleanBase64, Base64.DEFAULT)
                    val contentValues = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/BDRSF")
                        }
                    }
                    val resolver = activity.contentResolver
                    val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, contentValues)
                    uri?.let {
                        resolver.openOutputStream(it)?.use { outputStream ->
                            outputStream.write(decodedBytes)
                            outputStream.flush()
                        }
                        Toast.makeText(activity, "সফলভাবে সেভ হয়েছে", Toast.LENGTH_LONG).show()
                    }
                } catch (e: Exception) {
                    Toast.makeText(activity, "সেভ করতে সমস্যা হয়েছে", Toast.LENGTH_SHORT).show()
                }
            }
        }

        @JavascriptInterface
        fun shareBase64Image(base64Data: String, fileName: String) {
            activity.runOnUiThread {
                try {
                    val cleanBase64 = if (base64Data.contains(",")) base64Data.split(",")[1] else base64Data
                    val decodedBytes = Base64.decode(cleanBase64, Base64.DEFAULT)
                    val safeFileName = fileName.replace(Regex("[^a-zA-Z0-9._-]"), "_")
                    val cacheFile = File(activity.cacheDir, safeFileName)
                    FileOutputStream(cacheFile).use { it.write(decodedBytes); it.flush() }
                    val imageUri = FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", cacheFile)
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "image/png"
                        putExtra(Intent.EXTRA_STREAM, imageUri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    activity.startActivity(Intent.createChooser(shareIntent, "শেয়ার করুন"))
                } catch (e: Exception) {
                    Toast.makeText(activity, "শেয়ার করতে সমস্যা হয়েছে", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ==============================================================
    // ১. কার্ড ভিউফাইন্ডার ফ্রেম ও অটোমেটিক শার্পনেস ক্যামেরা
    // ==============================================================
    private fun startCardAutoCameraScanner(slot: String) {
        if (!hasCameraPermission()) { requestAllRequiredPermissions(); return }
        if (isScanningActive) return
        isScanningActive = true
        isCapturingNow.set(false)
        steadyFramesCount = 0

        scannerContainer = FrameLayout(this).apply {
            layoutParams = RelativeLayout.LayoutParams(
                RelativeLayout.LayoutParams.MATCH_PARENT,
                RelativeLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
        }

        previewView = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        scannerContainer?.addView(previewView)

        cardOverlayView = CardOverlayView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            val titleText = when (slot) {
                "nid_front" -> "এনআইডি সামনের অংশ ফ্রেমের মাঝে রাখুন"
                "nid_back" -> "এনআইডি পেছনের অংশ ফ্রেমের মাঝে রাখুন"
                else -> "পাসপোর্টের ডাটা পেজ ফ্রেমের মাঝে রাখুন"
            }
            setInstruction(titleText)
        }
        scannerContainer?.addView(cardOverlayView)

        val closeBtn = Button(this).apply {
            text = "✕ বন্ধ করুন"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#80000000"))
            setPadding(35, 15, 35, 15)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = (45 * resources.displayMetrics.density).toInt()
                rightMargin = (20 * resources.displayMetrics.density).toInt()
            }
            setOnClickListener { stopCustomCameraScanner() }
        }
        scannerContainer?.addView(closeBtn)

        val shutterBtn = Button(this).apply {
            text = "ছবি তুলুন (ক্লিক)"
            setTextColor(Color.BLACK)
            setBackgroundColor(Color.parseColor("#10B981"))
            textSize = 14f
            setPadding(40, 20, 40, 20)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = (35 * resources.displayMetrics.density).toInt()
            }
            setOnClickListener {
                if (!isCapturingNow.get()) triggerCardImageCapture(slot)
            }
        }
        scannerContainer?.addView(shutterBtn)
        mainLayout.addView(scannerContainer)

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraForDocument(slot)
            } catch (e: Exception) {
                stopCustomCameraScanner()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCameraForDocument(slot: String) {
        val provider = cameraProvider ?: return
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView?.surfaceProvider)
        }

        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()

        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
            if (isCapturingNow.get()) {
                imageProxy.close()
                return@setAnalyzer
            }

            val (sharpness, brightness) = calculateSharpnessAndBrightness(imageProxy)
            imageProxy.close()

            if (brightness in 50.0..220.0 && sharpness > 110.0) {
                steadyFramesCount++
                if (steadyFramesCount >= 4 && isCapturingNow.compareAndSet(false, true)) {
                    runOnUiThread {
                        cardOverlayView?.setStatusText("স্পষ্ট কার্ড পাওয়া গেছে! ক্যাপচার হচ্ছে...")
                        triggerCardImageCapture(slot)
                    }
                }
            } else {
                steadyFramesCount = max(0, steadyFramesCount - 1)
            }
        }

        try {
            provider.unbindAll()
            provider.bindToLifecycle(
                this,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageCapture,
                imageAnalysis
            )
        } catch (e: Exception) {
            stopCustomCameraScanner()
        }
    }

    private fun calculateSharpnessAndBrightness(imageProxy: ImageProxy): Pair<Double, Double> {
        val plane = imageProxy.planes[0]
        val buffer = plane.buffer
        val width = imageProxy.width
        val height = imageProxy.height
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride

        val startX = (width * 0.25).toInt()
        val endX = (width * 0.75).toInt()
        val startY = (height * 0.25).toInt()
        val endY = (height * 0.75).toInt()

        var sumBrightness = 0L
        var pixelCount = 0L
        var sumLaplacian = 0.0
        var sumLaplacianSq = 0.0

        val step = 4
        for (y in startY + 1 until endY - 1 step step) {
            for (x in startX + 1 until endX - 1 step step) {
                val center = buffer.get(y * rowStride + x * pixelStride).toInt() and 0xFF
                val left = buffer.get(y * rowStride + (x - 1) * pixelStride).toInt() and 0xFF
                val right = buffer.get(y * rowStride + (x + 1) * pixelStride).toInt() and 0xFF
                val up = buffer.get((y - 1) * rowStride + x * pixelStride).toInt() and 0xFF
                val down = buffer.get((y + 1) * rowStride + x * pixelStride).toInt() and 0xFF

                val lap = (4 * center - left - right - up - down).toDouble()
                sumLaplacian += lap
                sumLaplacianSq += lap * lap

                sumBrightness += center
                pixelCount++
            }
        }

        if (pixelCount == 0L) return Pair(0.0, 0.0)
        val meanBrightness = sumBrightness.toDouble() / pixelCount
        val meanLap = sumLaplacian / pixelCount
        val varianceLap = (sumLaplacianSq / pixelCount) - (meanLap * meanLap)
        return Pair(varianceLap, meanBrightness)
    }

    private fun triggerCardImageCapture(slot: String) {
        val capture = imageCapture ?: return
        val targetFile = try { createImageFile() } catch (e: Exception) { return }

        val outputOptions = ImageCapture.OutputFileOptions.Builder(targetFile).build()
        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(this),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    processCapturedCardFile(targetFile, slot)
                    stopCustomCameraScanner()
                }

                override fun onError(exception: ImageCaptureException) {
                    isCapturingNow.set(false)
                    Toast.makeText(this@MainActivity, "ছবি তুলতে সমস্যা হয়েছে", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun stopCustomCameraScanner() {
        isScanningActive = false
        isCapturingNow.set(false)
        try { cameraProvider?.unbindAll() } catch (e: Exception) { e.printStackTrace() }
        scannerContainer?.let {
            mainLayout.removeView(it)
            scannerContainer = null
        }
        previewView = null
        cardOverlayView = null
    }

    // ==============================================================
    // ২. স্মার্ট অটো-ক্রপ ও ফেস আলাদা করে লোকাল স্টোরেজে রাখা
    // ==============================================================
    private fun processCapturedCardFile(file: File, slot: String) {
        try {
            fixImageOrientation(file)
            cropDocumentCard(file)

            val prefix = when (slot) {
                "nid_front" -> "NID_FRONT"
                "nid_back" -> "NID_BACK"
                else -> "PASSPORT"
            }
            val savedVaultFile = saveToLocalVault(file, prefix)

            if (slot == "nid_front" || slot == "passport") {
                frontDocFile = savedVaultFile
                val faceB64 = extractAndSaveFace(savedVaultFile)
                if (faceB64.isNotEmpty()) {
                    extractedFaceBase64 = faceB64
                }
            } else if (slot == "nid_back") {
                backDocFile = savedVaultFile
            }

            // ওয়েবভিউতে শুধুমাত্র সুন্দর ক্রপ করা ডকুমেন্টের প্রিভিউ দেখানো
            val croppedBitmap = BitmapFactory.decodeFile(savedVaultFile.absolutePath)
            if (croppedBitmap != null) {
                val stream = ByteArrayOutputStream()
                croppedBitmap.compress(Bitmap.CompressFormat.JPEG, 75, stream)
                val base64Data = "data:image/jpeg;base64," + Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)

                runOnUiThread {
                    val safeSlot = JSONObject.quote(slot)
                    val safeB64 = JSONObject.quote(base64Data)
                    val safeFace = JSONObject.quote(extractedFaceBase64)
                    webView.evaluateJavascript(
                        "if(window.onImageSelectedPreview) { window.onImageSelectedPreview($safeSlot, $safeB64, $safeFace); }",
                        null
                    )
                    Toast.makeText(this, "ডকুমেন্ট সঠিকভাবে সেট হয়েছে", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            runOnUiThread {
                Toast.makeText(this, "ইমেজ প্রসেসিং ব্যর্থ হয়েছে", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun cropDocumentCard(file: File) {
        try {
            val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return
            val w = bitmap.width
            val h = bitmap.height

            val targetRatio = 1.58f
            var cropW = (w * 0.88f).toInt()
            var cropH = (cropW / targetRatio).toInt()

            if (cropH > h * 0.85f) {
                cropH = (h * 0.85f).toInt()
                cropW = (cropH * targetRatio).toInt()
            }

            val startX = max(0, (w - cropW) / 2)
            val startY = max(0, (h - cropH) / 2)
            val safeW = min(w - startX, cropW)
            val safeH = min(h - startY, cropH)

            if (safeW > 250 && safeH > 180) {
                val cropped = Bitmap.createBitmap(bitmap, startX, startY, safeW, safeH)
                FileOutputStream(file).use { out ->
                    cropped.compress(Bitmap.CompressFormat.JPEG, 85, out)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun extractAndSaveFace(docFile: File): String {
        try {
            if (!docFile.exists() || docFile.length() == 0L) return ""
            val bitmap = BitmapFactory.decodeFile(docFile.absolutePath) ?: return ""

            val width = if (bitmap.width % 2 == 0) bitmap.width else bitmap.width - 1
            val height = bitmap.height
            val bmp565 = bitmap.copy(Bitmap.Config.RGB_565, true)

            val detector = FaceDetector(width, height, 1)
            val faces = arrayOfNulls<FaceDetector.Face>(1)
            val count = detector.findFaces(bmp565, faces)

            var faceBitmap: Bitmap? = null

            if (count > 0 && faces[0] != null) {
                val face = faces[0]!!
                val midPoint = PointF()
                face.getMidPoint(midPoint)
                val eyeDistance = face.eyesDistance()

                val faceW = (eyeDistance * 2.8f).toInt()
                val faceH = (eyeDistance * 3.5f).toInt()
                val x = (midPoint.x - faceW / 2).toInt().coerceIn(0, bitmap.width - 1)
                val y = (midPoint.y - faceH / 2.1f).toInt().coerceIn(0, bitmap.height - 1)
                val w = faceW.coerceAtMost(bitmap.width - x)
                val h = faceH.coerceAtMost(bitmap.height - y)

                if (w > 40 && h > 40) {
                    faceBitmap = Bitmap.createBitmap(bitmap, x, y, w, h)
                }
            }

            if (faceBitmap == null) {
                val x = (bitmap.width * 0.04).toInt()
                val y = (bitmap.height * 0.18).toInt()
                val w = (bitmap.width * 0.36).toInt().coerceAtMost(bitmap.width - x)
                val h = (bitmap.height * 0.62).toInt().coerceAtMost(bitmap.height - y)
                faceBitmap = Bitmap.createBitmap(bitmap, x, y, w, h)
            }

            faceBitmap?.let { fbmp ->
                val faceFile = File(getKycVaultDir(), "EXTRACTED_FACE_${System.currentTimeMillis()}.jpg")
                FileOutputStream(faceFile).use { out ->
                    fbmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
                }

                val stream = ByteArrayOutputStream()
                fbmp.compress(Bitmap.CompressFormat.JPEG, 85, stream)
                return "data:image/jpeg;base64," + Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return ""
    }

    private fun fixImageOrientation(file: File) {
        try {
            val exif = ExifInterface(file.absolutePath)
            val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            val rotationInDegrees = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }

            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, options)

            val maxDim = max(options.outWidth, options.outHeight)
            var sampleSize = 1
            while (maxDim / sampleSize > 1600) {
                sampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            var bitmap = BitmapFactory.decodeFile(file.absolutePath, decodeOptions) ?: return

            if (rotationInDegrees != 0) {
                val matrix = Matrix().apply { postRotate(rotationInDegrees.toFloat()) }
                bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            }

            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun getKycVaultDir(): File {
        val dir = File(filesDir, "kyc_vault")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    private fun saveToLocalVault(sourceFile: File, prefix: String): File {
        val destFile = File(getKycVaultDir(), "${prefix}_${System.currentTimeMillis()}.jpg")
        sourceFile.copyTo(destFile, overwrite = true)
        return destFile
    }

    private fun launchDirectGalleryForDocument() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply { type = "image/*" }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, DOCUMENT_SCAN_REQUEST_CODE)
        } catch (e: Exception) {
            val fallback = Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" }
            @Suppress("DEPRECATION")
            startActivityForResult(fallback, DOCUMENT_SCAN_REQUEST_CODE)
        }
    }

    private fun openGalleryForQR() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply { type = "image/*" }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, GALLERY_QR_REQUEST_CODE)
        } catch (e: Exception) {
            val fallback = Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" }
            @Suppress("DEPRECATION")
            startActivityForResult(fallback, GALLERY_QR_REQUEST_CODE)
        }
    }

    private fun startQRScanner() {
        if (!hasCameraPermission()) { requestAllRequiredPermissions(); return }
        if (isScanningActive) return
        isScanningActive = true
        isQRProcessed.set(false)

        val qrContainer = FrameLayout(this).apply {
            layoutParams = RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.BLACK)
        }
        val qrPreview = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        qrContainer.addView(qrPreview)

        val closeBtn = Button(this).apply {
            text = "বন্ধ করুন"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#80000000"))
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.END; topMargin = 50; rightMargin = 40
            }
            setOnClickListener {
                isScanningActive = false
                cameraProvider?.unbindAll()
                mainLayout.removeView(qrContainer)
            }
        }
        qrContainer.addView(closeBtn)
        mainLayout.addView(qrContainer)

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val provider = cameraProviderFuture.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(qrPreview.surfaceProvider) }
                val imageAnalysis = ImageAnalysis.Builder().setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build()

                imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                    val mediaImage = imageProxy.image
                    if (mediaImage != null && !isQRProcessed.get()) {
                        val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                        barcodeScanner.process(image)
                            .addOnSuccessListener { barcodes ->
                                for (barcode in barcodes) {
                                    val scannedValue = barcode.rawValue
                                    if (!scannedValue.isNullOrEmpty() && isQRProcessed.compareAndSet(false, true)) {
                                        runOnUiThread {
                                            isScanningActive = false
                                            provider.unbindAll()
                                            mainLayout.removeView(qrContainer)
                                            val safeVal = JSONObject.quote(scannedValue)
                                            webView.evaluateJavascript("if(window.processScannedData) { window.processScannedData($safeVal); }", null)
                                        }
                                        break
                                    }
                                }
                            }
                            .addOnCompleteListener { imageProxy.close() }
                    } else {
                        imageProxy.close()
                    }
                }
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis)
            } catch (e: Exception) {
                isScanningActive = false
                mainLayout.removeView(qrContainer)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == DOCUMENT_SCAN_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK && data?.data != null) {
                try {
                    val file = createImageFile()
                    contentResolver.openInputStream(data.data!!)?.use { input ->
                        FileOutputStream(file).use { output -> input.copyTo(output) }
                    }
                    processCapturedCardFile(file, currentScanDocType)
                } catch (e: Exception) {
                    Toast.makeText(this, "ফাইল নির্বাচন ব্যর্থ হয়েছে", Toast.LENGTH_SHORT).show()
                }
            }
        } else if (requestCode == GALLERY_QR_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK && data?.data != null) {
                try {
                    val image = InputImage.fromFilePath(this, data.data!!)
                    barcodeScanner.process(image).addOnSuccessListener { barcodes ->
                        val code = barcodes.firstOrNull()?.rawValue ?: ""
                        if (code.isNotEmpty()) {
                            val safeCode = JSONObject.quote(code)
                            webView.evaluateJavascript("if(window.processScannedData) { window.processScannedData($safeCode); }", null)
                        }
                    }
                } catch (e: Exception) { e.printStackTrace() }
            }
        } else if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            if (filePathCallback == null) return
            var results: Array<Uri>? = null
            if (resultCode == Activity.RESULT_OK) {
                if (data?.data != null) results = arrayOf(data.data!!)
                else if (cameraImageUri != null) results = arrayOf(cameraImageUri!!)
            }
            filePathCallback?.onReceiveValue(results)
            filePathCallback = null
            cameraImageUri = null
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    @Throws(IOException::class)
    private fun createImageFile(): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        return File.createTempFile("DOC_${timeStamp}_", ".jpg", cacheDir)
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    private fun requestAllRequiredPermissions() {
        val list = mutableListOf<String>()
        if (!hasCameraPermission()) list.add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) {
                list.add(Manifest.permission.READ_MEDIA_IMAGES)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                list.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            list.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (list.isNotEmpty()) ActivityCompat.requestPermissions(this, list.toTypedArray(), PERMISSION_REQUEST_CODE)
    }

    private fun clearAppSessionKeepSmartId() {
        try {
            val cookieManager = CookieManager.getInstance()
            val cookies = cookieManager.getCookie("https://bdrsf.com/")
            if (!cookies.isNullOrEmpty()) {
                val cookieArray = cookies.split(";")
                var smartIdValue = ""
                for (cookie in cookieArray) {
                    val trimmed = cookie.trim()
                    if (trimmed.startsWith("verified_login_id=")) {
                        smartIdValue = trimmed
                        break
                    }
                }
                cookieManager.removeSessionCookies(null)
                if (smartIdValue.isNotEmpty()) {
                    cookieManager.setCookie("https://bdrsf.com/", "$smartIdValue; Path=/; Secure")
                }
                cookieManager.flush()
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            barcodeScanner.close()
            if (::cameraExecutor.isInitialized) cameraExecutor.shutdown()
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun hideSystemNavigationBar() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.let { controller ->
                    controller.hide(WindowInsets.Type.navigationBars())
                    controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
            }
        } catch (e: Exception) { e.printStackTrace() }
    }

    // ভিউফাইন্ডার বর্ডার ওভারলে
    class CardOverlayView(context: Context) : View(context) {
        private val maskPaint = Paint().apply { color = Color.parseColor("#B3000000") }
        private val transparentPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
        private val borderPaint = Paint().apply {
            color = Color.parseColor("#10B981")
            style = Paint.Style.STROKE
            strokeWidth = 5f
            isAntiAlias = true
        }
        private val cornerPaint = Paint().apply {
            color = Color.parseColor("#FFD700")
            style = Paint.Style.STROKE
            strokeWidth = 10f
            isAntiAlias = true
        }
        private val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 38f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
            isFakeBoldText = true
        }
        private val subTextPaint = Paint().apply {
            color = Color.parseColor("#10B981")
            textSize = 30f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
            isFakeBoldText = true
        }

        private var instructionText = "কার্ডটি ফ্রেমের মাঝে সোজাভাবে ধরুন"
        private var statusText = "লেখা স্পষ্ট ও স্থির রাখুন... অটো ক্যাপচার হবে"

        fun setInstruction(text: String) { instructionText = text; invalidate() }
        fun setStatusText(text: String) { statusText = text; invalidate() }

        @SuppressLint("DrawAllocation")
        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()

            val frameW = w * 0.88f
            val frameH = frameW / 1.58f

            val left = (w - frameW) / 2f
            val top = (h - frameH) / 2f - 40f
            val right = left + frameW
            val bottom = top + frameH

            val layer = canvas.saveLayer(0f, 0f, w, h, null)
            canvas.drawRect(0f, 0f, w, h, maskPaint)
            val cardRect = RectF(left, top, right, bottom)
            canvas.drawRoundRect(cardRect, 20f, 20f, transparentPaint)
            canvas.restoreToCount(layer)

            canvas.drawRoundRect(cardRect, 20f, 20f, borderPaint)

            val cornerLen = 45f
            canvas.drawLine(left, top, left + cornerLen, top, cornerPaint)
            canvas.drawLine(left, top, left, top + cornerLen, cornerPaint)
            canvas.drawLine(right, top, right - cornerLen, top, cornerPaint)
            canvas.drawLine(right, top, right, top + cornerLen, cornerPaint)
            canvas.drawLine(left, bottom, left + cornerLen, bottom, cornerPaint)
            canvas.drawLine(left, bottom, left, bottom - cornerLen, cornerPaint)
            canvas.drawLine(right, bottom, right - cornerLen, bottom, cornerPaint)
            canvas.drawLine(right, bottom, right, bottom - cornerLen, cornerPaint)

            canvas.drawText(instructionText, w / 2f, top - 35f, textPaint)
            canvas.drawText(statusText, w / 2f, bottom + 55f, subTextPaint)
        }
    }
}
