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
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
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

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var splashLayout: FrameLayout
    private lateinit var mainLayout: RelativeLayout

    private var scannerLayout: FrameLayout? = null
    private var previewView: PreviewView? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private lateinit var cameraExecutor: ExecutorService
    private var isScanningActive = false
    private val isQRProcessed = AtomicBoolean(false)
    private var isFirstPageLoad = true

    private var currentScanDocType = "nid_front"

    private var frontDocFile: File? = null
    private var backDocFile: File? = null
    private var tempDocumentFile: File? = null
    private var tempDocumentUri: Uri? = null

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraImageFile: File? = null
    private var cameraImageUri: Uri? = null

    private val FILE_CHOOSER_REQUEST_CODE = 1001
    private val PERMISSION_REQUEST_CODE = 1002
    private val GALLERY_QR_REQUEST_CODE = 1004
    private val DOCUMENT_SCAN_REQUEST_CODE = 1005

    // ML Kit Clients
    private val barcodeScanner: BarcodeScanner by lazy { BarcodeScanning.getClient() }
    private val latinTextRecognizer: TextRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }
    private val banglaTextRecognizer: TextRecognizer by lazy {
        TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
    }

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
                } catch (e: Exception) {
                    e.printStackTrace()
                }
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
            activity.runOnUiThread { activity.processSavedDocuments(docType) }
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
            } catch (e: Exception) {
                stopLiveCameraScanner()
            }
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
        } catch (exc: Exception) {
            stopLiveCameraScanner()
        }
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

    private fun optimizeAndFixCameraImage(file: File) {
        try {
            if (!file.exists() || file.length() == 0L) return
            val exif = ExifInterface(file.absolutePath)
            val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            val rotationInDegrees = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
            var bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return
            if (rotationInDegrees != 0) {
                val matrix = Matrix().apply { postRotate(rotationInDegrees.toFloat()) }
                bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            }
            FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out) }
        } catch (e: Exception) { e.printStackTrace() }
    }

    // কার্ডের ছবি (Face Photo) ক্রপ করা
    private fun extractFacePhotoBase64(file: File): String {
        try {
            if (!file.exists() || file.length() == 0L) return ""
            val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return ""

            try {
                val width = if (bitmap.width % 2 == 0) bitmap.width else bitmap.width - 1
                val height = bitmap.height
                val bmp565 = bitmap.copy(Bitmap.Config.RGB_565, true)
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
                        croppedFace.compress(Bitmap.CompressFormat.JPEG, 85, stream)
                        return "data:image/jpeg;base64," + Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
                    }
                }
            } catch (e: Exception) { e.printStackTrace() }

            // ফলব্যাক: কার্ডের বাম পাশের ফটো সেকশন ক্রপ করা
            try {
                val x = (bitmap.width * 0.03).toInt()
                val y = (bitmap.height * 0.20).toInt()
                val w = (bitmap.width * 0.35).toInt().coerceAtMost(bitmap.width - x)
                val h = (bitmap.height * 0.60).toInt().coerceAtMost(bitmap.height - y)
                val fallbackCrop = Bitmap.createBitmap(bitmap, x, y, w, h)
                val stream = ByteArrayOutputStream()
                fallbackCrop.compress(Bitmap.CompressFormat.JPEG, 85, stream)
                return "data:image/jpeg;base64," + Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
            } catch (ex: Exception) { ex.printStackTrace() }
        } catch (e: Exception) { e.printStackTrace() }
        return ""
    }

    // =========================================================================
    // "পরবর্তী ধাপ"-এ চাপ দিলে সম্পূর্ণ স্ক্যান ও পার্সিং লজিক
    // =========================================================================
    private fun processSavedDocuments(docType: String) {
        if (frontDocFile == null || !frontDocFile!!.exists()) {
            Toast.makeText(this, "সামনের অংশের ছবি পাওয়া যায়নি", Toast.LENGTH_SHORT).show()
            webView.evaluateJavascript("if(window.onScanFailed) { window.onScanFailed('সামনের অংশের ছবি পাওয়া যায়নি'); }", null)
            return
        }

        try {
            val frontImage = InputImage.fromFilePath(this, Uri.fromFile(frontDocFile))
            val facePhotoBase64 = extractFacePhotoBase64(frontDocFile!!)

            if (docType.equals("passport", ignoreCase = true)) {
                latinTextRecognizer.process(frontImage)
                    .addOnSuccessListener { parseAndDispatchPassport(it.text, facePhotoBase64) }
                    .addOnFailureListener {
                        webView.evaluateJavascript("if(window.onScanFailed) { window.onScanFailed('পাসপোর্ট রিড করা যায়নি'); }", null)
                    }
            } else {
                latinTextRecognizer.process(frontImage)
                    .addOnSuccessListener { latinFrontResult ->
                        val frontLatinText = latinFrontResult.text

                        banglaTextRecognizer.process(frontImage)
                            .addOnSuccessListener { devanagariFrontResult ->
                                // OCR Artifact Cleaner: 'ভ' বা 'ম' এর ভুল মুছে ফেলা
                                val combinedFrontText = "$frontLatinText\n${devanagariFrontResult.text}"
                                    .replace(Regex("(?<=(নাম|Name|পিতা|Father|মাতা|Mother|মা তা))\\s*[:;\\-]?\\s*[ভম]\\s*"), ": ")
                                processBackPartAndDispatch(combinedFrontText, frontLatinText, facePhotoBase64)
                            }
                            .addOnFailureListener {
                                val cleanedFrontText = frontLatinText.replace(Regex("(?<=(নাম|Name|পিতা|Father|মাতা|Mother|মা তা))\\s*[:;\\-]?\\s*[ভম]\\s*"), ": ")
                                processBackPartAndDispatch(cleanedFrontText, frontLatinText, facePhotoBase64)
                            }
                    }
                    .addOnFailureListener {
                        webView.evaluateJavascript("if(window.onScanFailed) { window.onScanFailed('এনআইডি কার্ডের লেখা রিড করা যায়নি'); }", null)
                    }
            }
        } catch (e: Exception) {
            webView.evaluateJavascript("if(window.onScanFailed) { window.onScanFailed('ডকুমেন্ট প্রসেস করতে সমস্যা হয়েছে'); }", null)
        }
    }

    private fun processBackPartAndDispatch(
        combinedFrontText: String,
        latinFrontText: String,
        facePhotoBase64: String
    ) {
        if (backDocFile != null && backDocFile!!.exists()) {
            val backImage = InputImage.fromFilePath(this, Uri.fromFile(backDocFile))

            barcodeScanner.process(backImage)
                .addOnSuccessListener { barcodes ->
                    var barcodeRawUtf8 = ""
                    for (b in barcodes) {
                        val decoded = b.rawBytes?.let { bytes ->
                            try { String(bytes, Charsets.UTF_8) } catch (e: Exception) { b.rawValue ?: "" }
                        } ?: (b.rawValue ?: "")

                        if (decoded.isNotEmpty()) barcodeRawUtf8 += "$decoded\n"
                    }

                    latinTextRecognizer.process(backImage)
                        .addOnSuccessListener { backTextResult ->
                            banglaTextRecognizer.process(backImage)
                                .addOnSuccessListener { backDevaResult ->
                                    val combinedBack = "${backTextResult.text}\n${backDevaResult.text}"
                                        .replace(Regex("(?<=(ঠিকানা|Address|বাসা/হোল্ডিং|বাসা|হোল্ডিং|গ্রাম/রাস্তা|গ্রাম|রাস্তা|ডাকঘর|উপজেলা|জেলা))\\s*[:;\\-]?\\s*[ভম]\\s*"), ": ")
                                    parseAndDispatchNID(combinedFrontText, latinFrontText, combinedBack, barcodeRawUtf8, facePhotoBase64)
                                }
                                .addOnFailureListener {
                                    val cleanedBack = backTextResult.text.replace(Regex("(?<=(ঠিকানা|Address|বাসা/হোল্ডিং|বাসা|হোল্ডিং|গ্রাম/রাস্তা|গ্রাম|রাস্তা|ডাকঘর|উপজেলা|জেলা))\\s*[:;\\-]?\\s*[ভম]\\s*"), ": ")
                                    parseAndDispatchNID(combinedFrontText, latinFrontText, cleanedBack, barcodeRawUtf8, facePhotoBase64)
                                }
                        }
                        .addOnFailureListener {
                            parseAndDispatchNID(combinedFrontText, latinFrontText, "", barcodeRawUtf8, facePhotoBase64)
                        }
                }
                .addOnFailureListener {
                    latinTextRecognizer.process(backImage)
                        .addOnSuccessListener { backResult ->
                            banglaTextRecognizer.process(backImage)
                                .addOnSuccessListener { backDevaResult ->
                                    val combinedBack = "${backResult.text}\n${backDevaResult.text}"
                                        .replace(Regex("(?<=(ঠিকানা|Address|বাসা/হোল্ডিং|বাসা|হোল্ডিং|গ্রাম/রাস্তা|গ্রাম|রাস্তা|ডাকঘর|উপজেলা|জেলা))\\s*[:;\\-]?\\s*[ভম]\\s*"), ": ")
                                    parseAndDispatchNID(combinedFrontText, latinFrontText, combinedBack, "", facePhotoBase64)
                                }
                                .addOnFailureListener {
                                    val cleanedBack = backResult.text.replace(Regex("(?<=(ঠিকানা|Address|বাসা/হোল্ডিং|বাসা|হোল্ডিং|গ্রাম/রাস্তা|গ্রাম|রাস্তা|ডাকঘর|উপজেলা|জেলা))\\s*[:;\\-]?\\s*[ভম]\\s*"), ": ")
                                    parseAndDispatchNID(combinedFrontText, latinFrontText, cleanedBack, "", facePhotoBase64)
                                }
                        }
                        .addOnFailureListener {
                            parseAndDispatchNID(combinedFrontText, latinFrontText, "", "", facePhotoBase64)
                        }
                }
        } else {
            parseAndDispatchNID(combinedFrontText, latinFrontText, "", "", facePhotoBase64)
        }
    }

    private fun parseAndDispatchNID(
        combinedFrontText: String,
        latinFrontText: String,
        backRawText: String,
        barcodeRaw: String,
        facePhotoBase64: String
    ) {
        val upperFront = combinedFrontText.uppercase()
        // ১. জেনুইন এনআইডি কার্ড যাচাই
        val hasGovtKeywords = upperFront.contains("BANGLADESH") ||
                upperFront.contains("GOVERNMENT") ||
                upperFront.contains("REPUBLIC") ||
                upperFront.contains("NATIONAL ID") ||
                upperFront.contains("ELECTION") ||
                combinedFrontText.contains("জাতীয় পরিচয়পত্র") ||
                combinedFrontText.contains("জাতীয় পরিচয়পত্র")

        val hasValidBarcode = barcodeRaw.isNotEmpty() && (barcodeRaw.contains("<pin>", true) || barcodeRaw.length > 25)

        // ২. এনআইডি নম্বর ডিটেকশন (স্পেসযুক্ত নম্বর বা যে ফরম্যাটেই থাকুক)
        var nidNo = ""
        val numericFront = combinedFrontText.replace(Regex("[^0-9\\s]"), "")
        val lines = numericFront.split("\n")
        for (line in lines) {
            val onlyDigits = line.filter { it.isDigit() }
            if (onlyDigits.length == 10 || onlyDigits.length == 13 || onlyDigits.length == 17) {
                nidNo = onlyDigits
                break
            }
        }
        if (nidNo.isEmpty()) {
            val compact = numericFront.replace(Regex("(?<=\\d)[\\s-]+(?=\\d)"), "")
            nidNo = Regex("\\b(?:[0-9]{10}|[0-9]{13}|[0-9]{17})\\b").find(compact)?.value ?: ""
        }

        val hasDobSignature = combinedFrontText.contains("Birth", true) || combinedFrontText.contains("DOB", true) || barcodeRaw.contains("<dob>", true)

        if (!hasGovtKeywords && !hasValidBarcode && (nidNo.isEmpty() || !hasDobSignature)) {
            runOnUiThread {
                webView.evaluateJavascript("if(window.onScanFailed) { window.onScanFailed('এটি কোনো বৈধ এনআইডি কার্ড নয়! আসল কার্ডের ছবি দিন।'); }", null)
                Toast.makeText(this, "অবৈধ এনআইডি ডকুমেন্ট!", Toast.LENGTH_LONG).show()
            }
            return
        }

        // ৩. জন্ম তারিখ
        var dobStr = ""
        val dobRegex = Regex("([0-9]{1,2}[\\s-/\\.]+(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec|[0-9]{2})[a-zA-Z]*[\\s-/\\.]+[0-9]{4})", RegexOption.IGNORE_CASE)
        dobStr = dobRegex.find(combinedFrontText)?.value?.trim() ?: ""

        // ৪. ইংরেজি পূর্ণ নাম
        var nameEn = ""
        val rawLines = latinFrontText.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        for (i in rawLines.indices) {
            val line = rawLines[i]
            val nameRegex = Regex("""(?i)\b(?:Name|Namc|Narme|Nane|Nam)\b[\s:.-]*(.*)""")
            val match = nameRegex.find(line)
            if (match != null) {
                val candidate = match.groupValues[1].replace(Regex("[^A-Za-z.\\s-]"), " ").trim()
                if (candidate.length >= 3) {
                    nameEn = candidate.uppercase()
                    break
                } else if (i + 1 < rawLines.size) {
                    val nextCandidate = rawLines[i + 1].replace(Regex("[^A-Za-z.\\s-]"), " ").trim()
                    if (nextCandidate.length >= 3 && !nextCandidate.contains("Date", true) && !nextCandidate.contains("Birth", true)) {
                        nameEn = nextCandidate.uppercase()
                        break
                    }
                }
            }
        }

        if (nameEn.isEmpty()) {
            for (line in rawLines) {
                val cleanCandidate = line.replace(Regex("[^A-Za-z.\\s-]"), "").trim()
                val upper = cleanCandidate.uppercase()
                if (cleanCandidate.length >= 4 && cleanCandidate.split(" ").size >= 2 &&
                    !upper.contains("BANGLADESH") && !upper.contains("GOVERNMENT") &&
                    !upper.contains("REPUBLIC") && !upper.contains("NATIONAL") &&
                    !upper.contains("IDENTITY") && !upper.contains("CARD") &&
                    !upper.contains("ELECTION") && !upper.contains("COMMISSION") &&
                    !upper.contains("DATE") && !upper.contains("BIRTH")) {
                    nameEn = upper
                    break
                }
            }
        }

        // ৫. পিতা ও মাতার নাম এবং বাংলা নাম
        var nameBn = ""
        var fatherName = ""
        var motherName = ""
        var fatherLineIdx = -1

        val allFrontLines = combinedFrontText.split("\n").map { it.trim() }

        for (i in allFrontLines.indices) {
            val t = allFrontLines[i]
            
            // পিতার নাম
            if ((t.contains("পিতা") || t.contains("Father", true)) && fatherName.isEmpty()) {
                val extracted = t.replace(Regex("^.*?(পিতা|Father's Name|Father)[\\s:.-]*", RegexOption.IGNORE_CASE), "").trim()
                if (extracted.length > 2) {
                    fatherName = extracted
                    fatherLineIdx = i
                } else if (i + 1 < allFrontLines.size) {
                    val next = allFrontLines[i + 1].trim()
                    if (!next.contains("মাতা") && !next.contains("Mother", true) && !next.contains("Date", true)) {
                        fatherName = next
                        fatherLineIdx = i + 1
                    }
                }
            }
            
            // মাতার নাম (সরাসরি লাইনে পেলে)
            if ((t.contains("মাতা") || t.contains("Mother", true) || t.contains("মা তা")) && motherName.isEmpty()) {
                val extracted = t.replace(Regex("^.*?(মাতা|Mother's Name|Mother|মা তা)[\\s:.-]*", RegexOption.IGNORE_CASE), "").trim()
                if (extracted.length > 2 && !extracted.contains("Date", true)) {
                    motherName = extracted
                } else if (i + 1 < allFrontLines.size) {
                    val next = allFrontLines[i + 1].trim()
                    if (!next.contains("Date", true) && !next.contains("Birth", true) && next.length >= 2) {
                        motherName = next
                    }
                }
            }

            // বাংলা নাম
            if ((t.contains("নাম") || t.contains("Name", true)) && nameBn.isEmpty()) {
                val extracted = t.replace(Regex("^.*?(নাম|Name)[\\s:.-]*", RegexOption.IGNORE_CASE), "").trim()
                if (extracted.any { it in '\u0980'..'\u09FF' }) {
                    nameBn = extracted
                }
            }
        }

        // মাতার নামের শক্তিশালী ফলব্যাক (পিতার নামের নিচের লাইন থেকে নেওয়া)
        if (motherName.isEmpty() && fatherLineIdx != -1 && fatherLineIdx + 1 < allFrontLines.size) {
            val nextLine = allFrontLines[fatherLineIdx + 1]
            if (!nextLine.contains("Date", true) && !nextLine.contains("Birth", true) && nextLine.length > 2) {
                motherName = nextLine.replace(Regex("^.*?(মাতা|Mother's Name|Mother|মা তা)[\\s:.-]*", RegexOption.IGNORE_CASE), "").trim()
            }
        }

        if (motherName == fatherName) motherName = ""
        val displayName = if (nameEn.isNotEmpty()) nameEn else nameBn

        // ৬. ব্যাকপার্টের সম্পূর্ণ কাঁচা টেক্সট সরাসরি ওয়েবসাইটে পাঠানো হবে (পিএইচপিতে পার্স হবে)
        val fullDetailsBuilder = java.lang.StringBuilder()
        if (barcodeRaw.isNotBlank()) {
            fullDetailsBuilder.append("--- BARCODE DATA ---\n").append(barcodeRaw.trim()).append("\n\n")
        }
        fullDetailsBuilder.append("--- BACK DETAILS ---\n").append(backRawText.trim())
        val fullDetails = fullDetailsBuilder.toString().trim()

        runOnUiThread {
            // User requested to send Name, DOB, NID No, Back Details, Face. (Father/Mother optionally empty)
            // Sending everything to Javascript; PHP script will handle parsing the back text.
            val safeName = JSONObject.quote(displayName)
            val safeFather = JSONObject.quote(fatherName)
            val safeMother = JSONObject.quote(motherName)
            val safeDob = JSONObject.quote(dobStr)
            val safeNid = JSONObject.quote(nidNo)
            val safeDetails = JSONObject.quote(fullDetails)
            val safeFace = JSONObject.quote(facePhotoBase64)

            webView.evaluateJavascript("if(window.onFullNIDParsed) { window.onFullNIDParsed($safeName, $safeFather, $safeMother, $safeDob, $safeNid, $safeDetails, $safeFace); }", null)
            Toast.makeText(this, "এনআইডি স্ক্যান সফল হয়েছে!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun parseAndDispatchPassport(cleanText: String, facePhotoBase64: String) {
        var passportNo = ""
        var nameEn = ""

        val lines = cleanText.split("\n")
        for (line in lines) {
            val cleanLine = line.trim().replace(" ", "").uppercase()
            if (cleanLine.startsWith("P<") && cleanLine.length > 20) {
                val parts = cleanLine.removePrefix("P<").split("<<")
                if (parts.isNotEmpty()) {
                    val surname = parts[0].replace("<", " ").trim()
                    val givenNames = if (parts.size > 1) parts[1].replace("<", " ").trim() else ""
                    nameEn = "$givenNames $surname".trim()
                }
            }
        }

        if (passportNo.isEmpty()) {
            val passportRegex = Regex("\\b[A-Z]{1,2}[0-9]{7,8}\\b")
            passportNo = passportRegex.find(cleanText)?.value ?: ""
        }

        val isPassportDoc = cleanText.contains("PASSPORT", true) || cleanText.contains("P<", true)

        runOnUiThread {
            if (!isPassportDoc || passportNo.isEmpty()) {
                webView.evaluateJavascript("if(window.onScanFailed) { window.onScanFailed('এটি কোনো বৈধ পাসপোর্ট নয়!'); }", null)
                return@runOnUiThread
            }
            val safeName = JSONObject.quote(nameEn)
            val safeDocNo = JSONObject.quote(passportNo)
            val safeFace = JSONObject.quote(facePhotoBase64)

            webView.evaluateJavascript("if(window.onFullPassportParsed) { window.onFullPassportParsed($safeName, $safeDocNo, $safeFace); }", null)
            Toast.makeText(this, "পাসপোর্ট স্ক্যান সফল!", Toast.LENGTH_SHORT).show()
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
                val selectedFile: File? = if (data?.data != null) {
                    try {
                        val file = createImageFile()
                        contentResolver.openInputStream(data.data!!)?.use { input ->
                            FileOutputStream(file).use { output -> input.copyTo(output) }
                        }
                        optimizeAndFixCameraImage(file)
                        file
                    } catch (e: Exception) { null }
                } else if (tempDocumentFile != null && tempDocumentFile!!.exists()) {
                    optimizeAndFixCameraImage(tempDocumentFile!!)
                    tempDocumentFile
                } else null

                if (selectedFile != null && selectedFile.exists()) {
                    if (currentScanDocType == "nid_front" || currentScanDocType == "passport") {
                        frontDocFile = selectedFile
                    } else if (currentScanDocType == "nid_back") {
                        backDocFile = selectedFile
                    }

                    try {
                        val bmp = BitmapFactory.decodeFile(selectedFile.absolutePath)
                        if (bmp != null) {
                            val stream = ByteArrayOutputStream()
                            bmp.compress(Bitmap.CompressFormat.JPEG, 70, stream)
                            val base64 = "data:image/jpeg;base64," + Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
                            val safeSlot = JSONObject.quote(currentScanDocType)
                            val safeB64 = JSONObject.quote(base64)
                            webView.evaluateJavascript("if(window.onImageSelectedPreview) { window.onImageSelectedPreview($safeSlot, $safeB64); }", null)
                        }
                    } catch (e: Exception) { e.printStackTrace() }

                    Toast.makeText(this, "ছবি সেট করা হয়েছে", Toast.LENGTH_SHORT).show()
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
                    cameraImageFile?.let { optimizeAndFixCameraImage(it) }
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
            latinTextRecognizer.close()
            banglaTextRecognizer.close()
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
}
