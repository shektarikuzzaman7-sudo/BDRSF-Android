package com.bdrsf.app

import android.Manifest
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
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.PointF
import android.graphics.PorterDuff
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
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var bgProcessingExecutor: ExecutorService
    private var isScanningActive = false
    private val isQRProcessed = AtomicBoolean(false)
    private var isFirstPageLoad = true

    private var currentScanDocType = "nid_front"

    private var frontDocFile: File? = null
    private var backDocFile: File? = null
    private var tempDocumentFile: File? = null
    private var tempDocumentUri: Uri? = null

    @Volatile private var cachedFrontBase64: String = ""
    @Volatile private var cachedBackBase64: String = ""
    @Volatile private var cachedFaceBase64: String = ""
    @Volatile private var cachedBarcodeMap: Map<String, String> = emptyMap()
    @Volatile private var cachedBarcodeRaw: String = ""
    private val isFrontProcessing = AtomicBoolean(false)
    private val isBackProcessing = AtomicBoolean(false)

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraImageFile: File? = null
    private var cameraImageUri: Uri? = null

    private val FILE_CHOOSER_REQUEST_CODE = 1001
    private val PERMISSION_REQUEST_CODE = 1002
    private val GALLERY_QR_REQUEST_CODE = 1004
    private val DOCUMENT_SCAN_REQUEST_CODE = 1005

    private val barcodeScanner: BarcodeScanner by lazy { BarcodeScanning.getClient() }

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
        bgProcessingExecutor = Executors.newFixedThreadPool(2)

        requestAllRequiredPermissions()
        hideSystemNavigationBar()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (isScanningActive) {
                    stopLiveCameraScanner()
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
            activity.runOnUiThread { activity.startLiveCameraScanner() }
        }
        @JavascriptInterface
        fun pickDocumentImage(slot: String, mode: String) {
            activity.runOnUiThread {
                activity.currentScanDocType = slot
                if (mode == "camera") {
                    activity.launchDirectCameraForDocument()
                } else {
                    activity.launchDirectGalleryForDocument()
                }
            }
        }
        @JavascriptInterface
        fun triggerFullDocumentProcessing(docType: String) {
            activity.bgProcessingExecutor.execute {
                activity.processSavedDocumentsHybrid(docType)
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

    private fun openGalleryForQR() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply { type = "image/*" }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, GALLERY_QR_REQUEST_CODE)
        } catch (e: Exception) {
            try {
                val fallback = Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" }
                @Suppress("DEPRECATION")
                startActivityForResult(fallback, GALLERY_QR_REQUEST_CODE)
            } catch (ex: Exception) {
                Toast.makeText(this, "গ্যালারি ওপেন করা যাচ্ছে না", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun launchDirectCameraForDocument() {
        if (!hasCameraPermission()) { requestAllRequiredPermissions(); return }
        try {
            val photoFile = createImageFile()
            tempDocumentFile = photoFile
            tempDocumentUri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", photoFile)
            val captureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                putExtra(MediaStore.EXTRA_OUTPUT, tempDocumentUri)
            }
            @Suppress("DEPRECATION")
            startActivityForResult(captureIntent, DOCUMENT_SCAN_REQUEST_CODE)
        } catch (e: Exception) {
            Toast.makeText(this, "ক্যামেরা ওপেন করা যাচ্ছে না", Toast.LENGTH_SHORT).show()
        }
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

    private fun startLiveCameraScanner() {
        if (!hasCameraPermission()) { requestAllRequiredPermissions(); return }
        if (isScanningActive) return
        isScanningActive = true
        isQRProcessed.set(false)

        scannerLayout = FrameLayout(this).apply {
            layoutParams = RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.BLACK)
        }
        previewView = PreviewView(this).apply {
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        scannerLayout?.addView(previewView)

        val closeButton = Button(this).apply {
            text = "বন্ধ করুন (Close)"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#AA000000"))
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.END; topMargin = 50; rightMargin = 40
            }
            setOnClickListener { stopLiveCameraScanner() }
        }
        scannerLayout?.addView(closeButton)
        mainLayout.addView(scannerLayout)

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases()
            } catch (e: Exception) { stopLiveCameraScanner() }
        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider ?: return
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView?.surfaceProvider) }
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
                                clientUiCallbackQR(scannedValue)
                                break
                            }
                        }
                    }
                    .addOnCompleteListener { imageProxy.close() }
            } else {
                imageProxy.close()
            }
        }
        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis)
        } catch (exc: Exception) { stopLiveCameraScanner() }
    }

    private fun clientUiCallbackQR(scannedValue: String) {
        runOnUiThread {
            stopLiveCameraScanner()
            val safeValue = JSONObject.quote(scannedValue)
            webView.evaluateJavascript("if(window.processScannedData) { window.processScannedData($safeValue); }", null)
        }
    }

    private fun stopLiveCameraScanner() {
        isScanningActive = false
        try { cameraProvider?.unbindAll() } catch (e: Exception) { e.printStackTrace() }
        scannerLayout?.let { mainLayout.removeView(it); scannerLayout = null }
        previewView = null
    }

    private fun optimizeAndCompressToBase64(file: File): Pair<Bitmap?, String> {
        try {
            if (!file.exists() || file.length() == 0L) return Pair(null, "")
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
            while (maxDim / sampleSize > 1150) {
                sampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            var bitmap = BitmapFactory.decodeFile(file.absolutePath, decodeOptions) ?: return Pair(null, "")

            if (rotationInDegrees != 0) {
                val matrix = Matrix().apply { postRotate(rotationInDegrees.toFloat()) }
                bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            }

            val currentMax = max(bitmap.width, bitmap.height)
            if (currentMax > 1150) {
                val ratio = 1150f / currentMax
                val newW = (bitmap.width * ratio).toInt()
                val newH = (bitmap.height * ratio).toInt()
                bitmap = Bitmap.createScaledBitmap(bitmap, newW, newH, true)
            }

            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 68, stream)
            val bytes = stream.toByteArray()

            FileOutputStream(file).use { it.write(bytes) }
            val base64Str = "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
            return Pair(bitmap, base64Str)
        } catch (e: Exception) {
            e.printStackTrace()
            return Pair(null, "")
        }
    }

    private fun extractFaceFromBitmap(bitmap: Bitmap?): String {
        if (bitmap == null) return ""
        try {
            val width = if (bitmap.width % 2 == 0) bitmap.width else bitmap.width - 1
            val height = bitmap.height
            val bmp565 = bitmap.copy(Bitmap.Config.RGB_565, false)
            val detector = FaceDetector(width, height, 1)
            val faces = arrayOfNulls<FaceDetector.Face>(1)
            val count = detector.findFaces(bmp565, faces)

            if (count > 0 && faces[0] != null) {
                val face = faces[0]!!
                val midPoint = PointF()
                face.getMidPoint(midPoint)
                val eyeDistance = face.eyesDistance()

                val faceWidth = (eyeDistance * 2.8f).toInt()
                val faceHeight = (eyeDistance * 3.4f).toInt()
                val x = (midPoint.x - faceWidth / 2).toInt().coerceIn(0, bitmap.width - 1)
                val y = (midPoint.y - faceHeight / 2.2f).toInt().coerceIn(0, bitmap.height - 1)
                val w = faceWidth.coerceAtMost(bitmap.width - x)
                val h = faceHeight.coerceAtMost(bitmap.height - y)

                if (w > 30 && h > 30) {
                    val croppedFace = Bitmap.createBitmap(bitmap, x, y, w, h)
                    val stream = ByteArrayOutputStream()
                    croppedFace.compress(Bitmap.CompressFormat.JPEG, 78, stream)
                    return "data:image/jpeg;base64," + Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
                }
            }
        } catch (e: Exception) { e.printStackTrace() }

        return try {
            val x = (bitmap.width * 0.03).toInt()
            val y = (bitmap.height * 0.18).toInt()
            val w = (bitmap.width * 0.38).toInt().coerceAtMost(bitmap.width - x)
            val h = (bitmap.height * 0.62).toInt().coerceAtMost(bitmap.height - y)
            val fallbackCrop = Bitmap.createBitmap(bitmap, x, y, w, h)
            val stream = ByteArrayOutputStream()
            fallbackCrop.compress(Bitmap.CompressFormat.JPEG, 78, stream)
            "data:image/jpeg;base64," + Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
        } catch (ex: Exception) { "" }
    }

    private fun parseNidBarcodeData(barcodeData: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        if (barcodeData.isEmpty()) return result
        val pinMatch = Regex("<pin>\\s*([0-9]{10,17})\\s*</pin>", RegexOption.IGNORE_CASE).find(barcodeData)
            ?: Regex("\\b([0-9]{10}|[0-9]{13}|[0-9]{17})\\b").find(barcodeData)
        pinMatch?.groupValues?.getOrNull(1)?.let { result["nid"] = it }
        val nameMatch = Regex("<name>\\s*([^<]+)\\s*</name>", RegexOption.IGNORE_CASE).find(barcodeData)
        nameMatch?.groupValues?.getOrNull(1)?.let { result["name"] = it.trim() }
        val nameBnMatch = Regex("<(nameBn|name_bn|banglaName)>\\s*([^<]+)\\s*<", RegexOption.IGNORE_CASE).find(barcodeData)
        nameBnMatch?.groupValues?.getOrNull(2)?.let { result["name_bn"] = it.trim() }
        val dobMatch = Regex("<DOB>\\s*([^<]+)\\s*</DOB>", RegexOption.IGNORE_CASE).find(barcodeData)
        dobMatch?.groupValues?.getOrNull(1)?.let { result["dob"] = it.trim() }
        val fatherMatch = Regex("<(father|fname|fatherName|father_name)>\\s*([^<]+)\\s*<", RegexOption.IGNORE_CASE).find(barcodeData)
        fatherMatch?.groupValues?.getOrNull(2)?.let { result["father"] = it.trim() }
        val motherMatch = Regex("<(mother|mname|motherName|mother_name)>\\s*([^<]+)\\s*<", RegexOption.IGNORE_CASE).find(barcodeData)
        motherMatch?.groupValues?.getOrNull(2)?.let { result["mother"] = it.trim() }
        return result
    }

    private fun preProcessDocumentInBackground(slot: String, file: File) {
        if (slot == "nid_front" || slot == "passport") {
            isFrontProcessing.set(true)
            bgProcessingExecutor.execute {
                try {
                    val (bmp, b64) = optimizeAndCompressToBase64(file)
                    cachedFrontBase64 = b64
                    runOnUiThread {
                        val safeSlot = JSONObject.quote(slot)
                        val safeB64 = JSONObject.quote(b64)
                        webView.evaluateJavascript("if(window.onImageSelectedPreview) { window.onImageSelectedPreview($safeSlot, $safeB64); }", null)
                    }
                    cachedFaceBase64 = extractFaceFromBitmap(bmp)
                } finally {
                    isFrontProcessing.set(false)
                }
            }
        } else if (slot == "nid_back") {
            isBackProcessing.set(true)
            bgProcessingExecutor.execute {
                try {
                    val (bmp, b64) = optimizeAndCompressToBase64(file)
                    cachedBackBase64 = b64
                    runOnUiThread {
                        val safeSlot = JSONObject.quote(slot)
                        val safeB64 = JSONObject.quote(b64)
                        webView.evaluateJavascript("if(window.onImageSelectedPreview) { window.onImageSelectedPreview($safeSlot, $safeB64); }", null)
                    }
                    if (bmp != null) {
                        val inputImg = InputImage.fromBitmap(bmp, 0)
                        barcodeScanner.process(inputImg)
                            .addOnSuccessListener { barcodes ->
                                var rawUtf8 = ""
                                for (b in barcodes) {
                                    val decoded = b.rawBytes?.let { bytes ->
                                        try { String(bytes, Charsets.UTF_8) } catch (e: Exception) { b.rawValue ?: "" }
                                    } ?: (b.rawValue ?: "")
                                    if (decoded.isNotEmpty()) rawUtf8 += "$decoded\n"
                                }
                                cachedBarcodeRaw = rawUtf8
                                cachedBarcodeMap = parseNidBarcodeData(rawUtf8)
                                isBackProcessing.set(false)
                            }
                            .addOnFailureListener {
                                cachedBarcodeRaw = ""
                                cachedBarcodeMap = emptyMap()
                                isBackProcessing.set(false)
                            }
                    } else {
                        isBackProcessing.set(false)
                    }
                } catch (e: Exception) {
                    isBackProcessing.set(false)
                }
            }
        }
    }

    private fun processSavedDocumentsHybrid(rawDocType: String) {
        val isPassport = rawDocType.contains("PASS", ignoreCase = true)
        val normalizedDocType = if (isPassport) "PASSPORT" else "NID"

        var waitCycles = 0
        while ((isFrontProcessing.get() || (!isPassport && isBackProcessing.get())) && waitCycles < 40) {
            Thread.sleep(50)
            waitCycles++
        }

        if (cachedFrontBase64.isEmpty()) {
            val missingMsg = if (isPassport) "পাসপোর্টের ছবি পাওয়া যায়নি!" else "এনআইডির সামনের অংশের ছবি পাওয়া যায়নি!"
            runOnUiThread {
                Toast.makeText(this, missingMsg, Toast.LENGTH_SHORT).show()
                webView.evaluateJavascript("if(window.onScanFailed) { window.onScanFailed('$missingMsg'); }", null)
            }
            return
        }

        if (!isPassport && cachedBackBase64.isEmpty()) {
            val backMissingMsg = "এনআইডি কার্ডের পেছনের অংশের ছবি পাওয়া যায়নি!"
            runOnUiThread {
                Toast.makeText(this, backMissingMsg, Toast.LENGTH_SHORT).show()
                webView.evaluateJavascript("if(window.onScanFailed) { window.onScanFailed('$backMissingMsg'); }", null)
            }
            return
        }

        sendPreCachedDataToServerForOCR(
            frontBase64 = cachedFrontBase64,
            backBase64 = if (isPassport) null else cachedBackBase64,
            faceBase64 = cachedFaceBase64,
            barcodeMap = cachedBarcodeMap,
            barcodeRawText = cachedBarcodeRaw,
            documentType = normalizedDocType
        )
    }

    private fun sendPreCachedDataToServerForOCR(
        frontBase64: String,
        backBase64: String?,
        faceBase64: String,
        barcodeMap: Map<String, String>,
        barcodeRawText: String,
        documentType: String
    ) {
        try {
            val jsonBody = JSONObject().apply {
                put("action", "scan_document")
                put("document_type", documentType)
                put("front_image_base64", frontBase64)
                if (!backBase64.isNullOrEmpty()) {
                    put("back_image_base64", backBase64)
                }
            }

            val body = jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())

            val request = Request.Builder()
                .url("https://bdrsf.com/api/ocr_scan")
                .post(body)
                .addHeader("Accept", "application/json")
                .addHeader("Content-Type", "application/json")
                .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36")
                .build()

            okHttpClient.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    runOnUiThread {
                        webView.evaluateJavascript("if(window.onScanFailed) { window.onScanFailed('সার্ভার কানেকশন ব্যর্থ হয়েছে। ইন্টারনেট সংযোগ চেক করুন।'); }", null)
                    }
                }

                override fun onResponse(call: Call, response: Response) {
                    val responseBody = response.body?.string()
                    if (response.isSuccessful && responseBody != null) {
                        try {
                            val json = JSONObject(responseBody)
                            if (json.optString("status") == "success") {
                                val data = json.getJSONObject("data")
                                val rawBackText = json.optString("raw_text", "")

                                if (documentType == "PASSPORT") {
                                    handlePassportServerResponse(data, faceBase64)
                                } else {
                                    handleNidServerResponse(data, rawBackText, barcodeMap, barcodeRawText, faceBase64)
                                }
                            } else {
                                val msg = json.optString("message", "ডকুমেন্ট ভেরিফিকেশন ব্যর্থ হয়েছে।")
                                runOnUiThread {
                                    val safeMsg = JSONObject.quote(msg)
                                    webView.evaluateJavascript("if(window.onScanFailed) { window.onScanFailed($safeMsg); }", null)
                                }
                            }
                        } catch (e: Exception) {
                            runOnUiThread { webView.evaluateJavascript("if(window.onScanFailed) { window.onScanFailed('সার্ভার থেকে সঠিক তথ্য পাওয়া যায়নি।'); }", null) }
                        }
                    } else {
                        val code = response.code
                        runOnUiThread { webView.evaluateJavascript("if(window.onScanFailed) { window.onScanFailed('সার্ভার এরর: HTTP $code'); }", null) }
                    }
                }
            })
        } catch (e: Exception) {
            runOnUiThread {
                webView.evaluateJavascript("if(window.onScanFailed) { window.onScanFailed('ইমেজ প্রসেস করতে সমস্যা হয়েছে।'); }", null)
            }
        }
    }

    private fun handleNidServerResponse(
        ocrData: JSONObject,
        rawBackText: String,
        barcodeMap: Map<String, String>,
        barcodeRaw: String,
        faceBase64: String
    ) {
        val ocrNid = ocrData.optString("nidNo", "").replace(Regex("\\D"), "")
        val barcodeNid = barcodeMap["nid"]?.replace(Regex("\\D"), "") ?: ""

        if (ocrNid.isEmpty() && barcodeNid.isEmpty()) {
            runOnUiThread {
                webView.evaluateJavascript("if(window.onScanFailed) { window.onScanFailed('কার্ডে কোনো বৈধ এনআইডি নম্বর পাওয়া যায়নি! অরিজিনাল কার্ড আপলোড করুন।'); }", null)
            }
            return
        }

        if (ocrNid.isNotEmpty() && barcodeNid.isNotEmpty()) {
            val isMatch = (ocrNid == barcodeNid) || ocrNid.endsWith(barcodeNid) || barcodeNid.endsWith(ocrNid)
            if (!isMatch) {
                runOnUiThread {
                    webView.evaluateJavascript("if(window.onScanFailed) { window.onScanFailed('প্রতারণা সনাক্ত হয়েছে! সামনের অংশ এবং পেছনের অংশ একই এনআইডি কার্ডের নয়।'); }", null)
                }
                return
            }
        }

        val finalNidNo = if (barcodeNid.isNotEmpty()) barcodeNid else ocrNid
        val finalDob = barcodeMap["dob"]?.takeIf { it.isNotEmpty() } ?: ocrData.optString("dob", "")
        val finalNameBn = barcodeMap["name_bn"]?.takeIf { it.isNotEmpty() } ?: ocrData.optString("nameBn", "")
        val finalNameEn = barcodeMap["name"]?.takeIf { it.isNotEmpty() } ?: ocrData.optString("nameEn", "")
        val fatherName = barcodeMap["father"]?.takeIf { it.isNotEmpty() } ?: ocrData.optString("fatherName", "")
        val motherName = barcodeMap["mother"]?.takeIf { it.isNotEmpty() } ?: ocrData.optString("motherName", "")

        val displayName = if (finalNameBn.isNotEmpty()) finalNameBn else finalNameEn

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

        val fullDetails = if (rawBackText.isNotBlank()) rawBackText else {
            "ঠিকানা: বাসা/হোল্ডিং: ${backObj.optString("holding")}, গ্রাম/রাস্তা: ${backObj.optString("village")}, ডাকঘর: ${backObj.optString("postOffice")} ${backObj.optString("zipCode")}, উপজেলা: ${backObj.optString("upazila")}, জেলা: ${backObj.optString("district")}"
        }

        runOnUiThread {
            val safeName = JSONObject.quote(displayName)
            val safeFather = JSONObject.quote(fatherName)
            val safeMother = JSONObject.quote(motherName)
            val safeDob = JSONObject.quote(finalDob)
            val safeNid = JSONObject.quote(finalNidNo)
            val safeDetails = JSONObject.quote(fullDetails)
            val safeFace = JSONObject.quote(faceBase64)
            val safeBackJson = JSONObject.quote(backObj.toString())

            webView.evaluateJavascript("if(window.onFullNIDParsed) { window.onFullNIDParsed($safeName, $safeFather, $safeMother, $safeDob, $safeNid, $safeDetails, $safeFace, $safeBackJson); }", null)
        }
    }

    private fun handlePassportServerResponse(ocrData: JSONObject, faceBase64: String) {
        val passportNo = ocrData.optString("passportNo", "").trim()
        val nameEn = ocrData.optString("nameEn", "").trim()
        val dob = ocrData.optString("dob", "").trim()

        if (passportNo.isEmpty() || passportNo.length < 7 || nameEn.isEmpty()) {
            runOnUiThread {
                webView.evaluateJavascript("if(window.onScanFailed) { window.onScanFailed('বৈধ পাসপোর্ট শনাক্ত করা যায়নি! পাসপোর্টের মূল পেজ পরিষ্কারভাবে আপলোড করুন।'); }", null)
            }
            return
        }

        runOnUiThread {
            val safeName = JSONObject.quote(nameEn)
            val safePassNo = JSONObject.quote(passportNo)
            val safeFace = JSONObject.quote(faceBase64)
            val safeDob = JSONObject.quote(dob)

            webView.evaluateJavascript("if(window.onFullPassportParsed) { window.onFullPassportParsed($safeName, $safePassNo, $safeFace, $safeDob); }", null)
        }
    }

    @Throws(IOException::class)
    private fun createImageFile(): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        return File.createTempFile("KYC_${timeStamp}_", ".jpg", cacheDir)
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

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == GALLERY_QR_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK && data?.data != null) {
                val imageUri = data.data!!
                try {
                    val image = InputImage.fromFilePath(this, imageUri)
                    barcodeScanner.process(image)
                        .addOnSuccessListener { barcodes ->
                            var scannedValue = ""
                            for (barcode in barcodes) {
                                scannedValue = barcode.rawValue ?: ""
                                if (scannedValue.isNotEmpty()) break
                            }
                            if (scannedValue.isNotEmpty()) clientUiCallbackQR(scannedValue)
                        }
                } catch (e: Exception) { e.printStackTrace() }
            }
        } else if (requestCode == DOCUMENT_SCAN_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK) {
                val targetSlot = currentScanDocType
                val rawUri = data?.data
                val capturedFile = tempDocumentFile

                bgProcessingExecutor.execute {
                    val selectedFile: File? = if (rawUri != null) {
                        try {
                            val file = createImageFile()
                            contentResolver.openInputStream(rawUri)?.use { input ->
                                FileOutputStream(file).use { output -> input.copyTo(output) }
                            }
                            file
                        } catch (e: Exception) { null }
                    } else if (capturedFile != null && capturedFile.exists()) {
                        capturedFile
                    } else null

                    if (selectedFile != null && selectedFile.exists()) {
                        if (targetSlot == "nid_front" || targetSlot == "passport") {
                            frontDocFile = selectedFile
                        } else if (targetSlot == "nid_back") {
                            backDocFile = selectedFile
                        }
                        preProcessDocumentInBackground(targetSlot, selectedFile)
                    }
                }
            }
            tempDocumentFile = null
            tempDocumentUri = null
        } else if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            if (filePathCallback == null) return
            var results: Array<Uri>? = null
            if (resultCode == Activity.RESULT_OK) {
                if (data?.data != null) results = arrayOf(data.data!!)
                else if (cameraImageUri != null) {
                    cameraImageFile?.let { optimizeAndCompressToBase64(it) }
                    results = arrayOf(cameraImageUri!!)
                }
            }
            filePathCallback?.onReceiveValue(results)
            filePathCallback = null
            cameraImageFile = null
            cameraImageUri = null
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
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
            if (::bgProcessingExecutor.isInitialized) bgProcessingExecutor.shutdown()
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
}
