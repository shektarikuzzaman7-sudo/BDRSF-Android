package com.bdrsf.app

import android.Manifest
import android.app.Activity
import android.app.DownloadManager
import android.content.ActivityNotFoundException
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
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
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
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var splashLayout: FrameLayout
    private lateinit var mainLayout: RelativeLayout

    private var scannerLayout: FrameLayout? = null
    private var previewView: PreviewView? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var bgProcessingExecutor: ExecutorService
    private var isScanningActive = false
    private val isImageCaptured = AtomicBoolean(false)
    private var isFirstPageLoad = true

    private var currentScanDocType = "nid_front"
    private var frontDocFile: File? = null
    private var backDocFile: File? = null

    @Volatile private var cachedFrontBase64: String = ""
    @Volatile private var cachedBackBase64: String = ""
    @Volatile private var cachedFaceBase64: String = ""
    @Volatile private var cachedBarcodeMap: Map<String, String> = emptyMap()
    @Volatile private var cachedBarcodeRaw: String = ""
    @Volatile private var localExtractedNidFront: String = ""

    private val isFrontProcessing = AtomicBoolean(false)
    private val isBackProcessing = AtomicBoolean(false)

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraImageFile: File? = null
    private var cameraImageUri: Uri? = null

    private val FILE_CHOOSER_REQUEST_CODE = 1001
    private val PERMISSION_REQUEST_CODE = 1002
    private val GALLERY_QR_REQUEST_CODE = 1004
    private val DOCUMENT_SCAN_REQUEST_CODE = 1005

    // PDF417 সহ সকল দরকারি বারকোড ও কিউআর রিডার অপশন
    private val barcodeScanner: BarcodeScanner by lazy {
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_PDF417,
                Barcode.FORMAT_QR_CODE,
                Barcode.FORMAT_CODE_128,
                Barcode.FORMAT_DATA_MATRIX,
                Barcode.FORMAT_AZTEC
            )
            .build()
        BarcodeScanning.getClient(options)
    }

    private val textRecognizer: TextRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

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
        } catch (e: Exception) { e.printStackTrace() }

        mainLayout = RelativeLayout(this).apply {
            layoutParams = RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.MATCH_PARENT)
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
            layoutParams = RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.MATCH_PARENT)
        }
        mainLayout.addView(webView)

        splashLayout = FrameLayout(this).apply {
            layoutParams = RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.parseColor("#0A2F1D"))
        }

        val loaderSizePx = (155 * resources.displayMetrics.density).toInt()
        val circularProgressBar = ProgressBar(this, null, android.R.attr.progressBarStyleLarge).apply {
            layoutParams = FrameLayout.LayoutParams(loaderSizePx, loaderSizePx).apply { gravity = Gravity.CENTER }
            try {
                @Suppress("DEPRECATION")
                indeterminateDrawable.setColorFilter(Color.parseColor("#FFD700"), PorterDuff.Mode.SRC_IN)
            } catch (e: Exception) {}
        }
        splashLayout.addView(circularProgressBar)

        val logoSizePx = (130 * resources.displayMetrics.density).toInt()
        val logoImageView = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(logoSizePx, logoSizePx).apply { gravity = Gravity.CENTER }
            val logoResId = resources.getIdentifier("ic_logo", "drawable", packageName)
            if (logoResId != 0) setImageResource(logoResId)
        }
        splashLayout.addView(logoImageView)

        mainLayout.addView(splashLayout)
        setContentView(mainLayout)

        cameraExecutor = Executors.newSingleThreadExecutor()
        bgProcessingExecutor = Executors.newFixedThreadPool(2)

        requestAllRequiredPermissions()
        hideSystemNavigationBar()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isScanningActive) {
                    stopLiveCameraScanner()
                } else if (::webView.isInitialized) {
                    val currentUrl = webView.url ?: ""
                    val homeBase = "https://bdrsf.com"
                    if (currentUrl.trimEnd('/').isNotEmpty() && currentUrl.trimEnd('/') != homeBase) {
                        webView.loadUrl("https://bdrsf.com/")
                        webView.clearHistory()
                    } else {
                        webView.reload()
                    }
                }
            }
        })

        setupWebView()
    }

    private fun setupWebView() {
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
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        webView.addJavascriptInterface(WebAppInterface(this), "AndroidBridge")

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimetype, _ ->
            try {
                val request = DownloadManager.Request(Uri.parse(url))
                val fileName = URLUtil.guessFileName(url, contentDisposition, mimetype)
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                request.setTitle(fileName)
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                val cookies = CookieManager.getInstance().getCookie(url)
                if (!cookies.isNullOrEmpty()) request.addRequestHeader("cookie", cookies)
                request.addRequestHeader("User-Agent", userAgent)
                val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                downloadManager.enqueue(request)
                Toast.makeText(this, "ডাউনলোড শুরু হয়েছে", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                Toast.makeText(this, "ডাউনলোডে সমস্যা হয়েছে", Toast.LENGTH_SHORT).show()
            }
        })

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest?) {
                request?.let { runOnUiThread { it.grant(it.resources) } }
            }
            override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: GeolocationPermissions.Callback?) {
                val hasLocationPermission = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
                callback?.invoke(origin, hasLocationPermission, false)
            }
            override fun onShowFileChooser(webView: WebView?, callback: ValueCallback<Array<Uri>>?, fileChooserParams: FileChooserParams?): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback
                if (!hasCameraPermission()) requestAllRequiredPermissions()
                try {
                    val contentSelectionIntent = Intent(Intent.ACTION_GET_CONTENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "*/*" }
                    val intentArray: Array<Intent> = if (hasCameraPermission()) {
                        val captureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                        val photoFile: File? = try { createImageFile() } catch (ex: IOException) { null }
                        if (photoFile != null) {
                            cameraImageFile = photoFile
                            cameraImageUri = FileProvider.getUriForFile(this@MainActivity, "${packageName}.fileprovider", photoFile)
                            captureIntent.putExtra(MediaStore.EXTRA_OUTPUT, cameraImageUri)
                            arrayOf(captureIntent)
                        } else emptyArray()
                    } else emptyArray()
                    val chooserIntent = Intent(Intent.ACTION_CHOOSER).apply {
                        putExtra(Intent.EXTRA_INTENT, contentSelectionIntent)
                        putExtra(Intent.EXTRA_TITLE, "ফাইল বা ছবি সিলেক্ট করুন")
                        if (intentArray.isNotEmpty()) putExtra(Intent.EXTRA_INITIAL_INTENTS, intentArray)
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
                return handleUrlLoading(view, request?.url?.toString() ?: "")
            }
            @Deprecated("Deprecated in Java")
            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                return handleUrlLoading(view, url ?: "")
            }
            private fun handleUrlLoading(view: WebView?, url: String): Boolean {
                try {
                    val uri = Uri.parse(url)
                    val scheme = uri.scheme ?: ""
                    if (scheme == "tel" || scheme == "mailto" || scheme == "whatsapp" || scheme == "intent") {
                        try { view?.context?.startActivity(Intent(Intent.ACTION_VIEW, uri)) } catch (e: Exception) {}
                        return true
                    }
                    val host = uri.host ?: ""
                    return if (host == "bdrsf.com" || host.endsWith(".bdrsf.com")) false else {
                        view?.context?.startActivity(Intent(Intent.ACTION_VIEW, uri))
                        true
                    }
                } catch (e: Exception) {}
                return false
            }
            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                CookieManager.getInstance().flush()
                if (isFirstPageLoad) {
                    isFirstPageLoad = false
                    splashLayout.animate().alpha(0f).setDuration(400).withEndAction { splashLayout.visibility = View.GONE }
                }
            }
        }
        webView.loadUrl("https://bdrsf.com/")
    }

    class WebAppInterface(private val activity: MainActivity) {
        @JavascriptInterface
        fun startNativeMLKitScanner() {
            activity.runOnUiThread { activity.startLiveCameraScanner("qr") }
        }
        @JavascriptInterface
        fun pickDocumentImage(slot: String, mode: String) {
            activity.runOnUiThread {
                activity.currentScanDocType = slot
                if (mode == "camera") {
                    activity.startLiveCameraScanner(slot)
                } else {
                    val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply { type = "image/*" }
                    try {
                        @Suppress("DEPRECATION")
                        activity.startActivityForResult(intent, activity.DOCUMENT_SCAN_REQUEST_CODE)
                    } catch (e: Exception) {
                        Toast.makeText(activity, "গ্যালারি ওপেন করা যাচ্ছে না", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        @JavascriptInterface
        fun triggerFullDocumentProcessing(docType: String) {
            activity.bgProcessingExecutor.execute { activity.processSavedDocumentsHybrid(docType) }
        }
        @JavascriptInterface
        fun startNativeGalleryPicker() {
            activity.runOnUiThread {
                val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply { type = "image/*" }
                try {
                    @Suppress("DEPRECATION")
                    activity.startActivityForResult(intent, activity.GALLERY_QR_REQUEST_CODE)
                } catch (e: Exception) {}
            }
        }
    }

    // ডকুমেন্ট ফোকাস বক্স ও গাইডলাইন ভিউ
    inner class DocumentOverlayView(context: Context, private val mode: String) : View(context) {
        private val boxPaint = Paint().apply {
            color = Color.parseColor("#00E676")
            style = Paint.Style.STROKE
            strokeWidth = 8f
            isAntiAlias = true
        }
        private val shadePaint = Paint().apply {
            color = Color.parseColor("#B3000000")
            style = Paint.Style.FILL
        }
        private val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 42f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
            setShadowLayer(6f, 0f, 0f, Color.BLACK)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()

            val boxWidth = w * 0.90f
            val boxHeight = boxWidth * 0.63f
            val left = (w - boxWidth) / 2f
            val top = (h - boxHeight) / 2f
            val right = left + boxWidth
            val bottom = top + boxHeight

            canvas.drawRect(0f, 0f, w, top, shadePaint)
            canvas.drawRect(0f, bottom, w, h, shadePaint)
            canvas.drawRect(0f, top, left, bottom, shadePaint)
            canvas.drawRect(right, top, w, bottom, shadePaint)

            canvas.drawRoundRect(RectF(left, top, right, bottom), 20f, 20f, boxPaint)

            val guideText = when(mode) {
                "nid_front" -> "কার্ডের সামনের অংশ ও ছবি বক্সের মধ্যে রাখুন"
                "nid_back" -> "কার্ডের পেছনের অংশ ও বারকোড বক্সের মধ্যে রাখুন"
                "passport" -> "পাসপোর্টের পৃষ্ঠা ও MRZ কোড ফ্রেমের মধ্যে রাখুন"
                else -> "ডকুমেন্টটি ফ্রেমের ভেতরে স্পষ্ট রাখুন"
            }
            canvas.drawText(guideText, w / 2f, top - 45f, textPaint)
        }
    }

    // লাইভ ক্যামেরা চালু এবং ফ্রেম চেক
    private fun startLiveCameraScanner(scanMode: String) {
        if (!hasCameraPermission()) { requestAllRequiredPermissions(); return }
        if (isScanningActive) return
        isScanningActive = true
        isImageCaptured.set(false)

        scannerLayout = FrameLayout(this).apply {
            layoutParams = RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.BLACK)
        }

        previewView = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        scannerLayout?.addView(previewView)

        if (scanMode != "qr") {
            scannerLayout?.addView(DocumentOverlayView(this, scanMode))
        }

        val closeButton = Button(this).apply {
            text = "✕ বাতিল"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#AA000000"))
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.END; topMargin = 80; rightMargin = 40
            }
            setOnClickListener { stopLiveCameraScanner() }
        }
        scannerLayout?.addView(closeButton)
        mainLayout.addView(scannerLayout)

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases(scanMode)
            } catch (e: Exception) { stopLiveCameraScanner() }
        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun bindCameraUseCases(scanMode: String) {
        val cameraProvider = cameraProvider ?: return
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView?.surfaceProvider) }

        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()

        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
            if (isImageCaptured.get()) { imageProxy.close(); return@setAnalyzer }
            val mediaImage = imageProxy.image

            if (mediaImage != null) {
                val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

                when (scanMode) {
                    "qr" -> {
                        barcodeScanner.process(image).addOnSuccessListener { barcodes ->
                            barcodes.firstOrNull { !it.rawValue.isNullOrEmpty() }?.rawValue?.let { value ->
                                if (isImageCaptured.compareAndSet(false, true)) {
                                    runOnUiThread {
                                        stopLiveCameraScanner()
                                        webView.evaluateJavascript("if(window.processScannedData) { window.processScannedData(${JSONObject.quote(value)}); }", null)
                                    }
                                }
                            }
                        }.addOnCompleteListener { imageProxy.close() }
                    }
                    "nid_front" -> {
                        textRecognizer.process(image).addOnSuccessListener { visionText ->
                            val rawText = visionText.text
                            val normalized = convertBanglaToEnglishDigits(rawText)
                            val cleanDigitsOnly = normalized.replace("\\s+".toRegex(), "")

                            val hasNidPattern = Regex("([0-9]{10}|[0-9]{13}|[0-9]{17})").containsMatchIn(cleanDigitsOnly)
                            val hasKeywords = rawText.contains("Name", true) ||
                                    rawText.contains("NID", true) ||
                                    rawText.contains("National", true) ||
                                    rawText.contains("নাম") ||
                                    rawText.contains("গণপ্রজাতন্ত্রী") ||
                                    rawText.contains("Date of Birth", true) ||
                                    rawText.contains("জন্ম")

                            if (hasNidPattern && hasKeywords && isImageCaptured.compareAndSet(false, true)) {
                                takeAutoCaptureAndCrop()
                            }
                        }.addOnCompleteListener { imageProxy.close() }
                    }
                    "nid_back" -> {
                        barcodeScanner.process(image).addOnSuccessListener { barcodes ->
                            if (barcodes.isNotEmpty() && isImageCaptured.compareAndSet(false, true)) {
                                takeAutoCaptureAndCrop()
                            } else {
                                textRecognizer.process(image).addOnSuccessListener { vText ->
                                    val t = vText.text
                                    val isBackKeyword = t.contains("ঠিকানা") || t.contains("ডাকঘর") ||
                                            t.contains("Blood") || t.contains("রক্তের") ||
                                            t.contains("Address") || t.contains("প্রদানকারী") ||
                                            t.contains("বাসা") || t.contains("গ্রাম")
                                    if (isBackKeyword && isImageCaptured.compareAndSet(false, true)) {
                                        takeAutoCaptureAndCrop()
                                    }
                                }
                            }
                        }.addOnCompleteListener { imageProxy.close() }
                    }
                    "passport" -> {
                        textRecognizer.process(image).addOnSuccessListener { visionText ->
                            val text = visionText.text
                            val hasMRZ = text.contains("P<") || text.contains("PASSPORT", true) || text.contains("PEOPLE'S REPUBLIC", true)
                            if (hasMRZ && isImageCaptured.compareAndSet(false, true)) {
                                takeAutoCaptureAndCrop()
                            }
                        }.addOnCompleteListener { imageProxy.close() }
                    }
                }
            } else {
                imageProxy.close()
            }
        }

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture, imageAnalysis)
        } catch (exc: Exception) { stopLiveCameraScanner() }
    }

    private fun takeAutoCaptureAndCrop() {
        val photoFile = createImageFile()
        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imageCapture?.takePicture(outputOptions, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                stopLiveCameraScanner()
                bgProcessingExecutor.execute {
                    cropCenterBoxFromImage(photoFile)
                    processDeepDocumentFile(photoFile, currentScanDocType)
                }
            }
            override fun onError(exception: ImageCaptureException) {
                stopLiveCameraScanner()
                runOnUiThread { Toast.makeText(this@MainActivity, "ছবি ক্যাপচার ব্যর্থ হয়েছে", Toast.LENGTH_SHORT).show() }
            }
        })
    }

    // অতিরিক্ত ব্যাকগ্রাউন্ড বাদ দিয়ে কার্ডের সাইজ অনুযায়ী নিখুঁত ক্রপ
    private fun cropCenterBoxFromImage(file: File) {
        try {
            val exif = ExifInterface(file.absolutePath)
            val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            var bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return

            val rotationDegrees = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
            if (rotationDegrees != 0) {
                val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            }

            val cropW = (bitmap.width * 0.90f).toInt()
            val cropH = (cropW * 0.63f).toInt().coerceAtMost(bitmap.height)
            val x = ((bitmap.width - cropW) / 2).coerceAtLeast(0)
            val y = ((bitmap.height - cropH) / 2).coerceAtLeast(0)

            val croppedBitmap = Bitmap.createBitmap(bitmap, x, y, cropW.coerceAtMost(bitmap.width - x), cropH.coerceAtMost(bitmap.height - y))
            FileOutputStream(file).use { croppedBitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        } catch (e: Exception) { e.printStackTrace() }
    }

    private fun stopLiveCameraScanner() {
        isScanningActive = false
        try { cameraProvider?.unbindAll() } catch (e: Exception) {}
        scannerLayout?.let { mainLayout.removeView(it); scannerLayout = null }
        previewView = null
    }

    // অফলাইন ভ্যালিডেশন এবং কার্ড ক্রস-ম্যাচিং লজিক
    private fun processDeepDocumentFile(file: File, targetSlot: String) {
        if (!file.exists()) return

        if (targetSlot == "nid_front" || targetSlot == "passport") {
            frontDocFile = file
            isFrontProcessing.set(true)
            bgProcessingExecutor.execute {
                try {
                    val (bmp, b64) = optimizeAndCompressToBase64(file)
                    val faceStr = extractFaceFromBitmap(bmp)

                    if (bmp != null) {
                        val inputImage = InputImage.fromBitmap(bmp, 0)
                        textRecognizer.process(inputImage)
                            .addOnSuccessListener { visionText ->
                                val allText = visionText.text
                                val normalizedAllText = convertBanglaToEnglishDigits(allText)
                                val cleanNumOnly = normalizedAllText.replace("\\s+".toRegex(), "")

                                val nidMatch = Regex("([0-9]{10}|[0-9]{13}|[0-9]{17})").find(cleanNumOnly)
                                val isPassport = allText.contains("P<") || allText.contains("PASSPORT", true) || allText.contains("BANGLADESH", true)

                                if (targetSlot == "nid_front") {
                                    val isNidText = allText.contains("Name", true) || allText.contains("NID", true) ||
                                            allText.contains("National", true) || allText.contains("নাম") ||
                                            allText.contains("গণপ্রজাতন্ত্রী")

                                    if (nidMatch == null && !isNidText) {
                                        notifyValidationFailed(targetSlot, "এটি সঠিক এনআইডি কার্ড নয়। পরিষ্কার ছবি দিন।")
                                        cachedFrontBase64 = ""
                                        return@addOnSuccessListener
                                    }
                                }

                                if (targetSlot == "passport" && !isPassport) {
                                    notifyValidationFailed(targetSlot, "এটি সঠিক পাসপোর্ট নয়। স্পষ্ট পৃষ্ঠা দিন।")
                                    cachedFrontBase64 = ""
                                    return@addOnSuccessListener
                                }

                                localExtractedNidFront = nidMatch?.groupValues?.getOrNull(1) ?: ""
                                cachedFrontBase64 = b64
                                cachedFaceBase64 = faceStr

                                runOnUiThread {
                                    Toast.makeText(this@MainActivity, "✔ ফ্রন্ট পার্ট সফলভাবে যাচাই হয়েছে", Toast.LENGTH_SHORT).show()
                                    // ওয়েবসাইটে সফল টিকচিহ্ন ও প্রিভিউ পাঠানো
                                    webView.evaluateJavascript("if(window.onDocumentValidated) { window.onDocumentValidated(${JSONObject.quote(targetSlot)}, true, ${JSONObject.quote(b64)}); }", null)
                                    webView.evaluateJavascript("if(window.onImageSelectedPreview) { window.onImageSelectedPreview(${JSONObject.quote(targetSlot)}, ${JSONObject.quote(b64)}); }", null)
                                }
                            }
                            .addOnFailureListener {
                                notifyValidationFailed(targetSlot, "ছবি অস্পষ্ট। লেখা পড়া যাচ্ছে না।")
                                cachedFrontBase64 = ""
                            }
                    }
                } finally {
                    isFrontProcessing.set(false)
                }
            }
        } else if (targetSlot == "nid_back") {
            backDocFile = file
            isBackProcessing.set(true)
            bgProcessingExecutor.execute {
                try {
                    val (bmp, b64) = optimizeAndCompressToBase64(file)
                    if (bmp != null) {
                        val inputImg = InputImage.fromBitmap(bmp, 0)

                        barcodeScanner.process(inputImg)
                            .addOnSuccessListener { barcodes ->
                                var rawUtf8 = ""
                                for (b in barcodes) {
                                    val decoded = b.rawBytes?.let {
                                        try { String(it, Charsets.UTF_8) } catch (e: Exception) { b.rawValue ?: "" }
                                    } ?: (b.rawValue ?: "")
                                    if (decoded.isNotEmpty()) rawUtf8 += "$decoded\n"
                                }

                                val parsedData = parseNidBarcodeData(rawUtf8)
                                val barcodeNid = parsedData["nid"] ?: ""

                                // বারকোডের সাথে ফ্রন্টের এনআইডি মিলানো
                                if (barcodeNid.isNotEmpty()) {
                                    if (localExtractedNidFront.isNotEmpty()) {
                                        val cleanFront = localExtractedNidFront.replace("\\D".toRegex(), "")
                                        val cleanBack = barcodeNid.replace("\\D".toRegex(), "")

                                        val isMatch = cleanFront == cleanBack || cleanFront.endsWith(cleanBack) || cleanBack.endsWith(cleanFront)
                                        if (!isMatch) {
                                            notifyValidationFailed(targetSlot, "ভুল কার্ড! সামনের এবং পেছনের অংশ দুটি ভিন্ন কার্ডের।")
                                            cachedBackBase64 = ""
                                            isBackProcessing.set(false)
                                            return@addOnSuccessListener
                                        }
                                    }

                                    cachedBackBase64 = b64
                                    cachedBarcodeRaw = rawUtf8
                                    cachedBarcodeMap = parsedData

                                    runOnUiThread {
                                        Toast.makeText(this@MainActivity, "✔ ব্যাক পার্ট সফলভাবে যাচাই হয়েছে", Toast.LENGTH_SHORT).show()
                                        webView.evaluateJavascript("if(window.onDocumentValidated) { window.onDocumentValidated(${JSONObject.quote(targetSlot)}, true, ${JSONObject.quote(b64)}); }", null)
                                        webView.evaluateJavascript("if(window.onImageSelectedPreview) { window.onImageSelectedPreview(${JSONObject.quote(targetSlot)}, ${JSONObject.quote(b64)}); }", null)
                                    }
                                    isBackProcessing.set(false)
                                } else {
                                    // বারকোড মিস হলেও ব্যাকপার্টের ঠিকানা দেখে কনফার্ম করা
                                    textRecognizer.process(inputImg).addOnSuccessListener { vText ->
                                        val t = vText.text
                                        val isBack = t.contains("ঠিকানা") || t.contains("ডাকঘর") || t.contains("Blood") || t.contains("রক্তের") || t.contains("Address")
                                        if (isBack) {
                                            cachedBackBase64 = b64
                                            runOnUiThread {
                                                Toast.makeText(this@MainActivity, "✔ ব্যাক পার্ট টেক্সট ওকে", Toast.LENGTH_SHORT).show()
                                                webView.evaluateJavascript("if(window.onDocumentValidated) { window.onDocumentValidated(${JSONObject.quote(targetSlot)}, true, ${JSONObject.quote(b64)}); }", null)
                                                webView.evaluateJavascript("if(window.onImageSelectedPreview) { window.onImageSelectedPreview(${JSONObject.quote(targetSlot)}, ${JSONObject.quote(b64)}); }", null)
                                            }
                                        } else {
                                            notifyValidationFailed(targetSlot, "পেছনের বারকোড বা ঠিকানার অংশ স্পষ্ট নয়! সঠিক ছবি দিন।")
                                            cachedBackBase64 = ""
                                        }
                                        isBackProcessing.set(false)
                                    }.addOnFailureListener {
                                        notifyValidationFailed(targetSlot, "পেছনের বারকোড স্ক্যান ব্যর্থ হয়েছে।")
                                        cachedBackBase64 = ""
                                        isBackProcessing.set(false)
                                    }
                                }
                            }
                            .addOnFailureListener {
                                notifyValidationFailed(targetSlot, "বারকোড রিড করা সম্ভব হয়নি।")
                                cachedBackBase64 = ""
                                isBackProcessing.set(false)
                            }
                    } else { isBackProcessing.set(false) }
                } catch (e: Exception) { isBackProcessing.set(false) }
            }
        }
    }

    private fun notifyValidationFailed(slot: String, message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            // ওয়েবসাইটে ক্রস চিহ্ন দেখানোর নোটিফিকেশন
            webView.evaluateJavascript("if(window.onDocumentValidated) { window.onDocumentValidated(${JSONObject.quote(slot)}, false, ''); }", null)
            webView.evaluateJavascript("if(window.onDocumentValidationFailed) { window.onDocumentValidationFailed(${JSONObject.quote(slot)}, ${JSONObject.quote(message)}); }", null)
        }
    }

    private fun convertBanglaToEnglishDigits(input: String): String {
        val banglaDigits = charArrayOf('০', '১', '২', '৩', '৪', '৫', '৬', '৭', '৮', '৯')
        val englishDigits = charArrayOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9')
        var result = input
        for (i in 0..9) {
            result = result.replace(banglaDigits[i], englishDigits[i])
        }
        return result
    }

    private fun optimizeAndCompressToBase64(file: File): Pair<Bitmap?, String> {
        try {
            if (!file.exists() || file.length() == 0L) return Pair(null, "")
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, options)
            val maxDim = max(options.outWidth, options.outHeight)
            var sampleSize = 1
            while (maxDim / sampleSize > 1500) { sampleSize *= 2 }
            var bitmap = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sampleSize }) ?: return Pair(null, "")

            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
            val bytes = stream.toByteArray()
            return Pair(bitmap, "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP))
        } catch (e: Exception) { return Pair(null, "") }
    }

    // আইডি কার্ড থেকে আলাদা ফেস ইমেজ ক্রপ করা
    private fun extractFaceFromBitmap(bitmap: Bitmap?): String {
        if (bitmap == null) return ""
        try {
            val width = if (bitmap.width % 2 == 0) bitmap.width else bitmap.width - 1
            val bmp565 = bitmap.copy(Bitmap.Config.RGB_565, false)
            val faces = arrayOfNulls<FaceDetector.Face>(1)
            if (FaceDetector(width, bitmap.height, 1).findFaces(bmp565, faces) > 0 && faces[0] != null) {
                val face = faces[0]!!
                val midPoint = PointF(); face.getMidPoint(midPoint)
                val eyeDist = face.eyesDistance()
                val fW = (eyeDist * 3.0f).toInt()
                val fH = (eyeDist * 3.8f).toInt()
                val x = (midPoint.x - fW / 2).toInt().coerceIn(0, bitmap.width - 1)
                val y = (midPoint.y - fH / 2.2f).toInt().coerceIn(0, bitmap.height - 1)
                val w = fW.coerceAtMost(bitmap.width - x)
                val h = fH.coerceAtMost(bitmap.height - y)
                if (w > 40 && h > 40) {
                    val stream = ByteArrayOutputStream()
                    Bitmap.createBitmap(bitmap, x, y, w, h).compress(Bitmap.CompressFormat.JPEG, 90, stream)
                    return "data:image/jpeg;base64," + Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
                }
            }
        } catch (e: Exception) {}

        return try {
            val x = (bitmap.width * 0.04).toInt()
            val y = (bitmap.height * 0.16).toInt()
            val w = (bitmap.width * 0.36).toInt().coerceAtMost(bitmap.width - x)
            val h = (bitmap.height * 0.65).toInt().coerceAtMost(bitmap.height - y)
            val stream = ByteArrayOutputStream()
            Bitmap.createBitmap(bitmap, x, y, w, h).compress(Bitmap.CompressFormat.JPEG, 90, stream)
            "data:image/jpeg;base64," + Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
        } catch (ex: Exception) { "" }
    }

    private fun parseNidBarcodeData(barcodeData: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        if (barcodeData.isEmpty()) return result
        val normalized = convertBanglaToEnglishDigits(barcodeData)

        val pinMatch = Regex("<pin>\\s*([0-9]{10,17})\\s*</pin>", RegexOption.IGNORE_CASE).find(normalized)
            ?: Regex("pin[:=]?\\s*([0-9]{10,17})", RegexOption.IGNORE_CASE).find(normalized)
            ?: Regex("([0-9]{10}|[0-9]{13}|[0-9]{17})").find(normalized)

        pinMatch?.groupValues?.getOrNull(1)?.let { result["nid"] = it }
        Regex("<name>\\s*([^<]+)\\s*</name>", RegexOption.IGNORE_CASE).find(barcodeData)?.groupValues?.getOrNull(1)?.let { result["name"] = it.trim() }
        Regex("<(nameBn|name_bn|banglaName)>\\s*([^<]+)\\s*<", RegexOption.IGNORE_CASE).find(barcodeData)?.groupValues?.getOrNull(2)?.let { result["name_bn"] = it.trim() }
        Regex("<DOB>\\s*([^<]+)\\s*</DOB>", RegexOption.IGNORE_CASE).find(barcodeData)?.groupValues?.getOrNull(1)?.let { result["dob"] = it.trim() }
        Regex("<(father|fname|fatherName)>\\s*([^<]+)\\s*<", RegexOption.IGNORE_CASE).find(barcodeData)?.groupValues?.getOrNull(2)?.let { result["father"] = it.trim() }
        Regex("<(mother|mname|motherName)>\\s*([^<]+)\\s*<", RegexOption.IGNORE_CASE).find(barcodeData)?.groupValues?.getOrNull(2)?.let { result["mother"] = it.trim() }
        return result
    }

    // ইউজার ওয়েবে নেক্সট বাটনে চাপ দিলে ডাটা সার্ভারে পাঠানো হবে
    private fun processSavedDocumentsHybrid(rawDocType: String) {
        val isPassport = rawDocType.contains("PASS", ignoreCase = true)
        val normalizedDocType = if (isPassport) "PASSPORT" else "NID"

        var waitCycles = 0
        while ((isFrontProcessing.get() || (!isPassport && isBackProcessing.get())) && waitCycles < 40) { Thread.sleep(50); waitCycles++ }

        if (cachedFrontBase64.isEmpty()) {
            runOnUiThread { webView.evaluateJavascript("if(window.onScanFailed) { window.onScanFailed('সামনের অংশের ছবি পাওয়া যায়নি বা এটি সঠিক কার্ড নয়!'); }", null) }
            return
        }

        if (!isPassport && cachedBackBase64.isEmpty()) {
            runOnUiThread { webView.evaluateJavascript("if(window.onScanFailed) { window.onScanFailed('পেছনের অংশ সঠিক নয় বা বারকোড পাওয়া যায়নি!'); }", null) }
            return
        }

        sendPreCachedDataToServerForOCR(cachedFrontBase64, if (isPassport) null else cachedBackBase64, cachedFaceBase64, cachedBarcodeMap, normalizedDocType)
    }

    private fun sendPreCachedDataToServerForOCR(frontBase64: String, backBase64: String?, faceBase64: String, barcodeMap: Map<String, String>, documentType: String) {
        try {
            val jsonBody = JSONObject().apply {
                put("action", "scan_document")
                put("document_type", documentType)
                put("front_image_base64", frontBase64)
                if (!backBase64.isNullOrEmpty()) put("back_image_base64", backBase64)
            }
            val request = Request.Builder().url("https://bdrsf.com/api/ocr_scan")
                .post(jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()))
                .addHeader("Accept", "application/json")
                .addHeader("Content-Type", "application/json")
                .build()

            okHttpClient.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    runOnUiThread { webView.evaluateJavascript("if(window.onScanFailed) { window.onScanFailed('সার্ভার কানেকশন ব্যর্থ হয়েছে।'); }", null) }
                }
                override fun onResponse(call: Call, response: Response) {
                    val responseBody = response.body?.string()
                    if (response.isSuccessful && responseBody != null) {
                        try {
                            val json = JSONObject(responseBody)
                            if (json.optString("status") == "success") {
                                val data = json.getJSONObject("data")
                                val rawBackText = json.optString("raw_text", "")
                                if (documentType == "PASSPORT") handlePassportServerResponse(data, faceBase64)
                                else handleNidServerResponse(data, rawBackText, barcodeMap, faceBase64)
                            } else {
                                runOnUiThread { webView.evaluateJavascript("if(window.onScanFailed) { window.onScanFailed(${JSONObject.quote(json.optString("message"))}); }", null) }
                            }
                        } catch (e: Exception) { runOnUiThread { webView.evaluateJavascript("if(window.onScanFailed) { window.onScanFailed('সঠিক তথ্য ডিকোড করা সম্ভব হয়নি।'); }", null) } }
                    } else { runOnUiThread { webView.evaluateJavascript("if(window.onScanFailed) { window.onScanFailed('সার্ভার এরর: HTTP ${response.code}'); }", null) } }
                }
            })
        } catch (e: Exception) {}
    }

    private fun handleNidServerResponse(ocrData: JSONObject, rawBackText: String, barcodeMap: Map<String, String>, faceBase64: String) {
        val ocrNid = ocrData.optString("nidNo", "").replace(Regex("\\D"), "")
        val barcodeNid = barcodeMap["nid"]?.replace(Regex("\\D"), "") ?: ""
        if (ocrNid.isEmpty() && barcodeNid.isEmpty()) {
            runOnUiThread { webView.evaluateJavascript("if(window.onScanFailed) { window.onScanFailed('কার্ডে কোনো বৈধ এনআইডি নম্বর পাওয়া যায়নি!'); }", null) }
            return
        }
        val displayName = barcodeMap["name_bn"]?.takeIf { it.isNotEmpty() } ?: barcodeMap["name"]?.takeIf { it.isNotEmpty() } ?: ocrData.optString("nameBn", "")
        val backObj = JSONObject().apply {
            put("holding", ocrData.optString("holding", "").trim())
            put("village", ocrData.optString("village", "").trim())
            put("postOffice", ocrData.optString("postOffice", "").trim())
            put("zipCode", ocrData.optString("zipCode", "").trim())
            put("upazila", ocrData.optString("upazila", "").trim())
            put("district", ocrData.optString("district", "").trim())
            put("bloodGroup", ocrData.optString("bloodGroup", "").trim())
            put("birthPlace", ocrData.optString("birthPlace", "").trim())
            put("issueDate", ocrData.optString("issueDate", "").trim())
        }
        val fullDetails = if (rawBackText.isNotBlank()) rawBackText else "ঠিকানা: বাসা/হোল্ডিং: ${backObj.optString("holding")}, গ্রাম/রাস্তা: ${backObj.optString("village")}, ডাকঘর: ${backObj.optString("postOffice")} ${backObj.optString("zipCode")}, উপজেলা: ${backObj.optString("upazila")}, জেলা: ${backObj.optString("district")}"
        runOnUiThread {
            webView.evaluateJavascript("if(window.onFullNIDParsed) { window.onFullNIDParsed(${JSONObject.quote(displayName)}, ${JSONObject.quote(barcodeMap["father"] ?: ocrData.optString("fatherName", ""))}, ${JSONObject.quote(barcodeMap["mother"] ?: ocrData.optString("motherName", ""))}, ${JSONObject.quote(barcodeMap["dob"]?.takeIf { it.isNotEmpty() } ?: ocrData.optString("dob", ""))}, ${JSONObject.quote(if (barcodeNid.isNotEmpty()) barcodeNid else ocrNid)}, ${JSONObject.quote(fullDetails)}, ${JSONObject.quote(faceBase64)}, ${JSONObject.quote(backObj.toString())}); }", null)
        }
    }

    private fun handlePassportServerResponse(ocrData: JSONObject, faceBase64: String) {
        val passportNo = ocrData.optString("passportNo", "").trim()
        val nameEn = ocrData.optString("nameEn", "").trim()
        if (passportNo.isEmpty() || passportNo.length < 7 || nameEn.isEmpty()) {
            runOnUiThread { webView.evaluateJavascript("if(window.onScanFailed) { window.onScanFailed('বৈধ পাসপোর্ট শনাক্ত করা যায়নি!'); }", null) }
            return
        }
        runOnUiThread { webView.evaluateJavascript("if(window.onFullPassportParsed) { window.onFullPassportParsed(${JSONObject.quote(nameEn)}, ${JSONObject.quote(passportNo)}, ${JSONObject.quote(faceBase64)}, ${JSONObject.quote(ocrData.optString("dob", "").trim())}); }", null) }
    }

    @Throws(IOException::class)
    private fun createImageFile(): File = File.createTempFile("KYC_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}_", ".jpg", cacheDir)

    private fun hasCameraPermission(): Boolean = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun requestAllRequiredPermissions() {
        val list = mutableListOf<String>()
        if (!hasCameraPermission()) list.add(Manifest.permission.CAMERA)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) list.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (list.isNotEmpty()) ActivityCompat.requestPermissions(this, list.toTypedArray(), PERMISSION_REQUEST_CODE)
    }

    private fun clientUiCallbackQR(scannedValue: String) {
        runOnUiThread {
            webView.evaluateJavascript("if(window.processScannedData) { window.processScannedData(${JSONObject.quote(scannedValue)}); }", null)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == GALLERY_QR_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK && data?.data != null) {
                try { barcodeScanner.process(InputImage.fromFilePath(this, data.data!!)).addOnSuccessListener { barcodes -> barcodes.firstOrNull { !it.rawValue.isNullOrEmpty() }?.rawValue?.let { clientUiCallbackQR(it) } } } catch (e: Exception) {}
            }
        } else if (requestCode == DOCUMENT_SCAN_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK) {
                val targetSlot = currentScanDocType
                val rawUri = data?.data
                bgProcessingExecutor.execute {
                    val selectedFile: File? = if (rawUri != null) {
                        try { val file = createImageFile(); contentResolver.openInputStream(rawUri)?.use { input -> FileOutputStream(file).use { output -> input.copyTo(output) } }; file } catch (e: Exception) { null }
                    } else null
                    if (selectedFile != null && selectedFile.exists()) {
                        processDeepDocumentFile(selectedFile, targetSlot)
                    }
                }
            }
        } else super.onActivityResult(requestCode, resultCode, data)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { barcodeScanner.close(); textRecognizer.close(); if (::cameraExecutor.isInitialized) cameraExecutor.shutdown(); if (::bgProcessingExecutor.isInitialized) bgProcessingExecutor.shutdown() } catch (e: Exception) {}
    }

    private fun hideSystemNavigationBar() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.let { it.hide(WindowInsets.Type.navigationBars()); it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE }
            } else { @Suppress("DEPRECATION") window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY) }
        } catch (e: Exception) {}
    }
}
