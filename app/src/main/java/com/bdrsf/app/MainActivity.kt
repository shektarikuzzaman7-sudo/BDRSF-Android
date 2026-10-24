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

    // ডকুমেন্ট টাইপ: "nid_front", "nid_back", অথবা "passport"
    private var currentScanDocType = "nid_front"

    // ডকুমেন্ট স্ক্যানিংয়ের জন্য ছবি সংরক্ষণের ফাইল ভেরিয়েবল
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        supportActionBar?.hide()
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        // স্ট্যাটাস বার কালার এবং আইকন সেটআপ
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

            if (ime.bottom == 0) {
                hideSystemNavigationBar()
            }
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
            layoutParams = FrameLayout.LayoutParams(loaderSizePx, loaderSizePx).apply {
                gravity = Gravity.CENTER
            }
            try {
                @Suppress("DEPRECATION")
                indeterminateDrawable.setColorFilter(
                    Color.parseColor("#FFD700"),
                    PorterDuff.Mode.SRC_IN
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        splashLayout.addView(circularProgressBar)

        val logoImageView = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(logoSizePx, logoSizePx).apply {
                gravity = Gravity.CENTER
            }
            val logoResId = resources.getIdentifier("ic_logo", "drawable", packageName)
            if (logoResId != 0) {
                setImageResource(logoResId)
            }
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
                if (!cookies.isNullOrEmpty()) {
                    request.addRequestHeader("cookie", cookies)
                }
                request.addRequestHeader("User-Agent", userAgent)

                val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
                downloadManager.enqueue(request)

                Toast.makeText(this, "ডাউনলোড শুরু হয়েছে: $fileName", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this, "ডাউনলোড করতে সমস্যা হয়েছে", Toast.LENGTH_SHORT).show()
            }
        })

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest?) {
                request?.let {
                    runOnUiThread {
                        it.grant(it.resources)
                    }
                }
            }

            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                val hasLocationPermission = ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
                callback?.invoke(origin, hasLocationPermission, false)
            }

            override fun onShowFileChooser(
                webView: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = callback

                if (!hasCameraPermission()) {
                    requestAllRequiredPermissions()
                }

                try {
                    val contentSelectionIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                    }

                    val intentArray: Array<Intent> = if (hasCameraPermission()) {
                        val captureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
                        val photoFile: File? = try {
                            createImageFile()
                        } catch (ex: IOException) {
                            null
                        }

                        if (photoFile != null) {
                            cameraImageFile = photoFile
                            cameraImageUri = FileProvider.getUriForFile(
                                this@MainActivity,
                                "${packageName}.fileprovider",
                                photoFile
                            )
                            captureIntent.putExtra(MediaStore.EXTRA_OUTPUT, cameraImageUri)
                            arrayOf(captureIntent)
                        } else {
                            cameraImageFile = null
                            cameraImageUri = null
                            emptyArray()
                        }
                    } else {
                        cameraImageFile = null
                        cameraImageUri = null
                        emptyArray()
                    }

                    val chooserIntent = Intent(Intent.ACTION_CHOOSER).apply {
                        putExtra(Intent.EXTRA_INTENT, contentSelectionIntent)
                        putExtra(Intent.EXTRA_TITLE, "ফাইল বা ছবি সিলেক্ট করুন")
                        if (intentArray.isNotEmpty()) {
                            putExtra(Intent.EXTRA_INITIAL_INTENTS, intentArray)
                        }
                    }

                    @Suppress("DEPRECATION")
                    startActivityForResult(chooserIntent, FILE_CHOOSER_REQUEST_CODE)
                } catch (e: ActivityNotFoundException) {
                    filePathCallback?.onReceiveValue(null)
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
                if (isFirstPageLoad) {
                    splashLayout.visibility = View.VISIBLE
                }
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
                    splashLayout.animate()
                        .alpha(0f)
                        .setDuration(400)
                        .withEndAction {
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
            activity.runOnUiThread {
                activity.startLiveCameraScanner()
            }
        }

        // ছবি তোলার/বাছাই করার ডায়ালগ ওপেন করবে
        @JavascriptInterface
        fun startNativeDocumentScan(docType: String) {
            activity.runOnUiThread {
                activity.currentScanDocType = docType
                activity.openImagePickerForDocumentScan()
            }
        }

        // ক্যামেরা বা গ্যালারি সরাসরি স্পেসিফিক মোডে ওপেন করার মেথড
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

        // "পরবর্তী ধাপ"-এ চাপ দিলে সম্পূর্ণ স্ক্যান ও ফিল্ড ফিলাপ ট্রিগার করার মেথড
        @JavascriptInterface
        fun triggerFullDocumentProcessing(docType: String) {
            activity.runOnUiThread {
                activity.processSavedDocuments(docType)
            }
        }

        @JavascriptInterface
        fun triggerDocumentScan(docType: String) {
            activity.runOnUiThread {
                activity.processSavedDocuments(docType)
            }
        }

        @JavascriptInterface
        fun startNativeGalleryPicker() {
            activity.runOnUiThread {
                activity.openGalleryForQR()
            }
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
                        Toast.makeText(activity, "সফলভাবে সেভ হয়েছে: Pictures/BDRSF", Toast.LENGTH_LONG).show()
                    } ?: throw Exception("URI create failed")

                } catch (e: Exception) {
                    e.printStackTrace()
                    Toast.makeText(activity, "সেভ করতে সমস্যা হয়েছে: ${e.message}", Toast.LENGTH_SHORT).show()
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
                    FileOutputStream(cacheFile).use { fos ->
                        fos.write(decodedBytes)
                        fos.flush()
                    }

                    val imageUri = FileProvider.getUriForFile(
                        activity,
                        "${activity.packageName}.fileprovider",
                        cacheFile
                    )

                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "image/png"
                        putExtra(Intent.EXTRA_STREAM, imageUri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }

                    val chooser = Intent.createChooser(shareIntent, "কিউআর কার্ড শেয়ার করুন")
                    activity.startActivity(chooser)

                } catch (e: Exception) {
                    e.printStackTrace()
                    Toast.makeText(activity, "শেয়ার করতে সমস্যা হয়েছে: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun openGalleryForQR() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply {
            type = "image/*"
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, GALLERY_QR_REQUEST_CODE)
        } catch (e: Exception) {
            try {
                val fallbackIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "image/*"
                }
                @Suppress("DEPRECATION")
                startActivityForResult(fallbackIntent, GALLERY_QR_REQUEST_CODE)
            } catch (ex: Exception) {
                Toast.makeText(this, "গ্যালারি ওপেন করা যাচ্ছে না", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun launchDirectCameraForDocument() {
        if (!hasCameraPermission()) {
            requestAllRequiredPermissions()
            return
        }
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
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply {
            type = "image/*"
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, DOCUMENT_SCAN_REQUEST_CODE)
        } catch (e: Exception) {
            val fallback = Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" }
            @Suppress("DEPRECATION")
            startActivityForResult(fallback, DOCUMENT_SCAN_REQUEST_CODE)
        }
    }

    private fun openImagePickerForDocumentScan() {
        if (!hasCameraPermission()) {
            requestAllRequiredPermissions()
            Toast.makeText(this, "ক্যামেরা পারমিশন প্রয়োজন", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val contentSelectionIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
            }

            val captureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            val photoFile: File? = try {
                createImageFile()
            } catch (ex: IOException) {
                null
            }

            if (photoFile != null) {
                tempDocumentFile = photoFile
                tempDocumentUri = FileProvider.getUriForFile(
                    this,
                    "${packageName}.fileprovider",
                    photoFile
                )
                captureIntent.putExtra(MediaStore.EXTRA_OUTPUT, tempDocumentUri)
            } else {
                tempDocumentFile = null
                tempDocumentUri = null
            }

            val titleText = when (currentScanDocType) {
                "nid_front" -> "এনআইডি সামনের অংশের ছবি নিন"
                "nid_back" -> "এনআইডি পেছনের অংশের ছবি নিন"
                else -> "পাসপোর্টের ছবি নিন"
            }

            val chooserIntent = Intent(Intent.ACTION_CHOOSER).apply {
                putExtra(Intent.EXTRA_INTENT, contentSelectionIntent)
                putExtra(Intent.EXTRA_TITLE, titleText)
                if (photoFile != null) {
                    putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(captureIntent))
                }
            }

            @Suppress("DEPRECATION")
            startActivityForResult(chooserIntent, DOCUMENT_SCAN_REQUEST_CODE)
        } catch (e: Exception) {
            Toast.makeText(this, "ইমেজ সিলেক্টর ওপেন করা যাচ্ছে না", Toast.LENGTH_SHORT).show()
        }
    }

    private fun startLiveCameraScanner() {
        if (!hasCameraPermission()) {
            requestAllRequiredPermissions()
            Toast.makeText(this, "ক্যামেরা পারমিশন প্রয়োজন", Toast.LENGTH_SHORT).show()
            return
        }

        if (isScanningActive) return
        isScanningActive = true
        isQRProcessed.set(false)

        scannerLayout = FrameLayout(this).apply {
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
        scannerLayout?.addView(previewView)

        val closeButton = Button(this).apply {
            text = "বন্ধ করুন (Close)"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#AA000000"))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = 50
                rightMargin = 40
            }
            setOnClickListener {
                stopLiveCameraScanner()
            }
        }
        scannerLayout?.addView(closeButton)

        mainLayout.addView(scannerLayout)

        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases()
            } catch (e: Exception) {
                e.printStackTrace()
                stopLiveCameraScanner()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun bindCameraUseCases() {
        val cameraProvider = cameraProvider ?: return

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView?.surfaceProvider)
        }

        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

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
                    .addOnCompleteListener {
                        imageProxy.close()
                    }
            } else {
                imageProxy.close()
            }
        }

        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                this, cameraSelector, preview, imageAnalysis
            )
        } catch (exc: Exception) {
            Toast.makeText(this, "ক্যামেরা চালু করতে সমস্যা হয়েছে", Toast.LENGTH_SHORT).show()
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
        if (!isScanningActive) return
        isScanningActive = false
        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        scannerLayout?.let {
            mainLayout.removeView(it)
            scannerLayout = null
        }
        previewView = null
    }

    // HD ক্যামেরা ছবির রোটেশন সোজা করা এবং ফাইল সাইজ অপটিমাইজ করার ফাংশন
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

            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, boundsOptions)

            var inSampleSize = 1
            val maxDimension = 1920
            if (boundsOptions.outHeight > maxDimension || boundsOptions.outWidth > maxDimension) {
                val halfHeight = boundsOptions.outHeight / 2
                val halfWidth = boundsOptions.outWidth / 2
                while ((halfHeight / inSampleSize) >= maxDimension && (halfWidth / inSampleSize) >= maxDimension) {
                    inSampleSize *= 2
                }
            }

            val decodeOptions = BitmapFactory.Options().apply { this.inSampleSize = inSampleSize }
            var bitmap = BitmapFactory.decodeFile(file.absolutePath, decodeOptions) ?: return

            if (rotationInDegrees != 0) {
                val matrix = Matrix().apply { postRotate(rotationInDegrees.toFloat()) }
                val rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                if (rotatedBitmap != bitmap) {
                    bitmap.recycle()
                }
                bitmap = rotatedBitmap
            }

            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
                out.flush()
            }
            bitmap.recycle()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun convertBanglaDigitsOnly(input: String): String {
        val banglaDigits = charArrayOf('০', '১', '২', '৩', '৪', '৫', '৬', '৭', '৮', '৯')
        val englishDigits = charArrayOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9')
        var result = input
        for (i in banglaDigits.indices) {
            result = result.replace(banglaDigits[i], englishDigits[i])
        }
        return result
    }

    private fun normalizeForIdNumberSearch(input: String): String {
        return convertBanglaDigitsOnly(input)
            .replace('O', '0')
            .replace('o', '0')
            .replace('I', '1')
            .replace('l', '1')
    }

    // =========================================================================
    // ডকুমেন্ট ফেস (ছবি) ক্রপ করার ফাংশন
    // =========================================================================
    private fun extractFacePhotoBase64(file: File): String {
        try {
            if (!file.exists() || file.length() == 0L) return ""
            val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return ""

            // Android SDK এর বিল্ট-ইন FaceDetector দিয়ে মুখমণ্ডল খোঁজা
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
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // যদি ডিটেক্টর সরাসরি মুখ না পায়, তবে NID ও পাসপোর্টের বাম পাশের নির্দিষ্ট ছবি অংশটি ক্রপ করা
            try {
                val x = (bitmap.width * 0.03).toInt()
                val y = (bitmap.height * 0.20).toInt()
                val w = (bitmap.width * 0.35).toInt().coerceAtMost(bitmap.width - x)
                val h = (bitmap.height * 0.60).toInt().coerceAtMost(bitmap.height - y)
                val fallbackCrop = Bitmap.createBitmap(bitmap, x, y, w, h)
                val stream = ByteArrayOutputStream()
                fallbackCrop.compress(Bitmap.CompressFormat.JPEG, 85, stream)
                return "data:image/jpeg;base64," + Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
            } catch (ex: Exception) {
                ex.printStackTrace()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return ""
    }

    // পাসপোর্টের MRZ কোডের আন্তর্জাতিক গাণিতিক চেক-ডিজিট যাচাই
    private fun verifyPassportMrzChecksum(passportField9Chars: String, checkDigitChar: Char): Boolean {
        if (passportField9Chars.length != 9 || !checkDigitChar.isDigit()) return false
        val weights = intArrayOf(7, 3, 1)
        var sum = 0
        for (i in 0 until 9) {
            val ch = passportField9Chars[i]
            val value = when {
                ch in '0'..'9' -> ch - '0'
                ch in 'A'..'Z' -> ch - 'A' + 10
                ch == '<' -> 0
                else -> return false
            }
            sum += value * weights[i % 3]
        }
        val calculatedCheckDigit = sum % 10
        val actualCheckDigit = checkDigitChar - '0'
        return calculatedCheckDigit == actualCheckDigit
    }

    // এনআইডির পেছনের PDF417 বারকোড পার্সার
    private fun parseNidBarcodeData(barcodeData: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        if (barcodeData.isEmpty()) return result

        val pinMatch = Regex("<pin>\\s*([0-9]{10,17})\\s*</pin>", RegexOption.IGNORE_CASE).find(barcodeData)
            ?: Regex("\\b([0-9]{10}|[0-9]{13}|[0-9]{17})\\b").find(barcodeData)
        pinMatch?.groupValues?.getOrNull(1)?.let { result["nid"] = it }

        val nameMatch = Regex("<name>\\s*([^<]+)\\s*</name>", RegexOption.IGNORE_CASE).find(barcodeData)
        nameMatch?.groupValues?.getOrNull(1)?.let { result["name"] = it.trim() }

        val dobMatch = Regex("<DOB>\\s*([^<]+)\\s*</DOB>", RegexOption.IGNORE_CASE).find(barcodeData)
        dobMatch?.groupValues?.getOrNull(1)?.let { result["dob"] = it.trim() }

        return result
    }

    // =========================================================================
    // "পরবর্তী ধাপ"-এ চাপ দিলে সম্পূর্ণ স্ক্যান ও পার্সিং লজিক
    // =========================================================================
    private fun processSavedDocuments(docType: String) {
        if (frontDocFile == null || !frontDocFile!!.exists()) {
            Toast.makeText(this, "সামনের অংশের ছবি পাওয়া যায়নি", Toast.LENGTH_SHORT).show()
            webView.evaluateJavascript("if(window.onScanFailed) { window.onScanFailed('সামনের অংশের ছবি দেওয়া হয়নি'); }", null)
            return
        }

        try {
            val frontImage = InputImage.fromFilePath(this, Uri.fromFile(frontDocFile))
            val facePhotoBase64 = extractFacePhotoBase64(frontDocFile!!)

            if (docType == "passport" || docType == "PASSPORT") {
                // পাসপোর্ট স্ক্যান
                latinTextRecognizer.process(frontImage)
                    .addOnSuccessListener { textResult ->
                        parseAndDispatchPassport(textResult.text, facePhotoBase64)
                    }
                    .addOnFailureListener {
                        webView.evaluateJavascript("if(window.onScanFailed) { window.onScanFailed('পাসপোর্ট রিড করা যায়নি'); }", null)
                    }
            } else {
                // এনআইডি স্ক্যান (সামনের টেক্সট + পেছনের বারকোড ও ঠিকানা)
                latinTextRecognizer.process(frontImage)
                    .addOnSuccessListener { frontTextResult ->
                        val frontText = frontTextResult.text

                        if (backDocFile != null && backDocFile!!.exists()) {
                            val backImage = InputImage.fromFilePath(this, Uri.fromFile(backDocFile))

                            barcodeScanner.process(backImage)
                                .addOnSuccessListener { barcodes ->
                                    var barcodeRaw = ""
                                    for (b in barcodes) {
                                        b.rawValue?.let { barcodeRaw += "$it\n" }
                                    }
                                    latinTextRecognizer.process(backImage)
                                        .addOnSuccessListener { backTextResult ->
                                            parseAndDispatchNID(frontText, backTextResult.text, barcodeRaw, facePhotoBase64)
                                        }
                                        .addOnFailureListener {
                                            parseAndDispatchNID(frontText, "", barcodeRaw, facePhotoBase64)
                                        }
                                }
                                .addOnFailureListener {
                                    latinTextRecognizer.process(backImage)
                                        .addOnSuccessListener { backTextResult ->
                                            parseAndDispatchNID(frontText, backTextResult.text, "", facePhotoBase64)
                                        }
                                        .addOnFailureListener {
                                            parseAndDispatchNID(frontText, "", "", facePhotoBase64)
                                        }
                                }
                        } else {
                            parseAndDispatchNID(frontText, "", "", facePhotoBase64)
                        }
                    }
                    .addOnFailureListener {
                        webView.evaluateJavascript("if(window.onScanFailed) { window.onScanFailed('এনআইডি কার্ডের লেখা রিড করা যায়নি'); }", null)
                    }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            webView.evaluateJavascript("if(window.onScanFailed) { window.onScanFailed('ডকুমেন্ট প্রসেস করতে সমস্যা হয়েছে'); }", null)
        }
    }

    private fun parseAndDispatchNID(
        frontRawText: String,
        backRawText: String,
        barcodeRaw: String,
        facePhotoBase64: String
    ) {
        val cleanFront = convertBanglaDigitsOnly(frontRawText)
        val numericFront = normalizeForIdNumberSearch(cleanFront)
        val barcodeMap = parseNidBarcodeData(barcodeRaw)

        // ১. এনআইডি নম্বর খোঁজা (১০, ১৩ বা ১৭ ডিজিট)
        var nidNo = barcodeMap["nid"] ?: ""
        if (nidNo.isEmpty()) {
            val compactNumeric = numericFront.replace(Regex("(?<=\\d)[\\s-]+(?=\\d)"), "")
            val match = Regex("\\b(?:[0-9]{10}|[0-9]{13}|[0-9]{17})\\b").find(compactNumeric)
            nidNo = match?.value ?: ""
        }

        // ২. জন্ম তারিখ
        var dobStr = barcodeMap["dob"] ?: ""
        if (dobStr.isEmpty()) {
            val dobRegex = Regex("([0-9]{1,2}[\\s-/\\.]+(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec|[0-9]{2})[a-zA-Z]*[\\s-/\\.]+[0-9]{4})", RegexOption.IGNORE_CASE)
            val dobMatch = dobRegex.find(cleanFront)
            dobStr = dobMatch?.value?.trim() ?: ""
        }

        // ৩. ইংরেজি পূর্ণ নাম
        var nameEn = barcodeMap["name"] ?: ""
        if (nameEn.isEmpty()) {
            val lines = cleanFront.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
            for (i in lines.indices) {
                val line = lines[i]
                if (line.startsWith("Name", true)) {
                    val afterLabel = line.replace(Regex("^Name[\\s:.-]*", RegexOption.IGNORE_CASE), "").trim()
                    if (afterLabel.length >= 3 && afterLabel.matches(Regex("^[A-Za-z.\\s-]+$"))) {
                        nameEn = afterLabel.uppercase()
                        break
                    } else if (i + 1 < lines.size) {
                        val nextLine = lines[i + 1]
                        if (nextLine.matches(Regex("^[A-Za-z.\\s-]{3,}$")) && !nextLine.contains("Date", true)) {
                            nameEn = nextLine.uppercase()
                            break
                        }
                    }
                }
            }
        }

        if (nameEn.isEmpty()) {
            val lines = cleanFront.split("\n").map { it.trim() }
            for (line in lines) {
                if (line.matches(Regex("^[A-Z.\\s]{4,}$")) &&
                    !line.contains("BANGLADESH") && !line.contains("NATIONAL") &&
                    !line.contains("CARD") && !line.contains("GOVERNMENT") &&
                    !line.contains("REPUBLIC") && !line.contains("IDENTITY")) {
                    nameEn = line
                    break
                }
            }
        }

        // ৪. পিতা ও মাতার নাম
        var fatherBn = ""
        var motherBn = ""
        val allLines = cleanFront.split("\n").map { it.trim() }
        for (i in allLines.indices) {
            val t = allLines[i]
            if ((t.contains("পিতা") || t.contains("Father", true)) && fatherBn.isEmpty()) {
                val extracted = t.replace(Regex("^.*?(পিতা|Father's Name|Father)[\\s:.-]*", RegexOption.IGNORE_CASE), "").trim()
                fatherBn = if (extracted.length > 2) extracted else if (i + 1 < allLines.size) allLines[i + 1] else ""
            } else if ((t.contains("মাতা") || t.contains("Mother", true)) && motherBn.isEmpty()) {
                val extracted = t.replace(Regex("^.*?(মাতা|Mother's Name|Mother)[\\s:.-]*", RegexOption.IGNORE_CASE), "").trim()
                motherBn = if (extracted.length > 2) extracted else if (i + 1 < allLines.size) allLines[i + 1] else ""
            }
        }

        // ৫. সম্পূর্ণ ঠিকানা (পেছনের অংশ থেকে)
        var fullAddress = ""
        val backLines = backRawText.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        for (line in backLines) {
            if (line.length > 4 && !line.contains("Blood", true) && !line.contains("Sign", true)) {
                fullAddress += "$line, "
            }
        }
        fullAddress = fullAddress.trimEnd(',', ' ')

        runOnUiThread {
            if (nidNo.isEmpty() && nameEn.isEmpty()) {
                webView.evaluateJavascript("if(window.onScanFailed) { window.onScanFailed('এনআইডির তথ্য স্পষ্ট নয়। সোজা ও পরিষ্কার ছবি দিন।'); }", null)
                Toast.makeText(this, "এনআইডির তথ্য পরিষ্কার নয়। পরিষ্কার ছবি দিন।", Toast.LENGTH_LONG).show()
                return@runOnUiThread
            }

            val safeName = JSONObject.quote(nameEn)
            val safeFather = JSONObject.quote(fatherBn)
            val safeMother = JSONObject.quote(motherBn)
            val safeDob = JSONObject.quote(dobStr)
            val safeNid = JSONObject.quote(nidNo)
            val safeAddress = JSONObject.quote(fullAddress)
            val safeFace = JSONObject.quote(facePhotoBase64)

            // নতুন কম্বাইন্ড কলব্যাক
            webView.evaluateJavascript("if(window.onFullNIDParsed) { window.onFullNIDParsed($safeName, $safeFather, $safeMother, $safeDob, $safeNid, $safeAddress, $safeFace); }", null)

            // পুরোনো কলব্যাক সাপোর্ট
            webView.evaluateJavascript("if(window.onNIDFrontScanCompleted) { window.onNIDFrontScanCompleted($safeName, $safeFather, $safeMother, $safeDob, $safeNid, $safeFace); }", null)
            if (fullAddress.isNotEmpty()) {
                webView.evaluateJavascript("if(window.onNIDBackScanCompleted) { window.onNIDBackScanCompleted($safeAddress); }", null)
            }

            Toast.makeText(this, "এনআইডি স্ক্যান সফল হয়েছে!", Toast.LENGTH_SHORT).show()
        }
    }

    private fun parseAndDispatchPassport(cleanText: String, facePhotoBase64: String) {
        val clean = convertBanglaDigitsOnly(cleanText)
        var passportNo = ""
        var nameEn = ""

        val lines = clean.split("\n")
        for (i in lines.indices) {
            val cleanLine = lines[i].trim().replace(" ", "").uppercase()
            if (cleanLine.startsWith("P<") && cleanLine.length > 20) {
                val withoutPrefix = if (cleanLine.length > 5) cleanLine.substring(5) else cleanLine.removePrefix("P<")
                val parts = withoutPrefix.split("<<")
                if (parts.isNotEmpty()) {
                    val surname = parts[0].replace("<", " ").replace(Regex("[^A-Z\\s]"), "").trim()
                    val givenNames = if (parts.size > 1) parts[1].replace("<", " ").replace(Regex("[^A-Z\\s]"), "").trim() else ""
                    nameEn = "$givenNames $surname".trim()
                }

                if (i + 1 < lines.size) {
                    val rawLine2 = lines[i + 1].trim().replace(" ", "").uppercase()
                    if (rawLine2.length >= 10) {
                        val prefix2 = rawLine2.substring(0, 2)
                        val numberAndCheckPart = normalizeForIdNumberSearch(rawLine2.substring(2, 10))
                        val candidateField9 = prefix2 + numberAndCheckPart.substring(0, 7)
                        val candidateCheckDigit = numberAndCheckPart[7]

                        if (verifyPassportMrzChecksum(candidateField9, candidateCheckDigit)) {
                            passportNo = candidateField9.replace("<", "").trim()
                        } else if (verifyPassportMrzChecksum(rawLine2.substring(0, 9), rawLine2[9])) {
                            passportNo = rawLine2.substring(0, 9).replace("<", "").trim()
                        }
                    }
                }
            }
        }

        if (passportNo.isEmpty()) {
            val passportRegex = Regex("\\b[A-Z]{1,2}[0-9]{7,8}\\b")
            val match = passportRegex.find(clean) ?: passportRegex.find(normalizeForIdNumberSearch(clean))
            passportNo = match?.value ?: ""
        }

        runOnUiThread {
            if (passportNo.isEmpty()) {
                webView.evaluateJavascript("if(window.onScanFailed) { window.onScanFailed('বৈধ পাসপোর্ট নম্বর পাওয়া যায়নি।'); }", null)
                Toast.makeText(this, "পাসপোর্ট সনাক্ত করা যায়নি", Toast.LENGTH_SHORT).show()
                return@runOnUiThread
            }

            val safeName = JSONObject.quote(nameEn)
            val safeDocNo = JSONObject.quote(passportNo)
            val safeFace = JSONObject.quote(facePhotoBase64)

            webView.evaluateJavascript("if(window.onFullPassportParsed) { window.onFullPassportParsed($safeName, $safeDocNo, $safeFace); }", null)
            webView.evaluateJavascript("if(window.onScanCompleted) { window.onScanCompleted($safeName, $safeDocNo, $safeFace); }", null)

            Toast.makeText(this, "পাসপোর্ট স্ক্যান সফল হয়েছে!", Toast.LENGTH_SHORT).show()
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
                            if (scannedValue.isNotEmpty()) {
                                clientUiCallbackQR(scannedValue)
                            } else {
                                Toast.makeText(this, "কিউআর কোড পাওয়া যায়নি", Toast.LENGTH_LONG).show()
                            }
                        }
                        .addOnFailureListener {
                            Toast.makeText(this, "কিউআর স্ক্যান ব্যর্থ হয়েছে", Toast.LENGTH_SHORT).show()
                        }
                } catch (e: Exception) {
                    Toast.makeText(this, "গ্যালারি ইমেজ প্রসেস করতে সমস্যা হয়েছে", Toast.LENGTH_SHORT).show()
                }
            }
        } else if (requestCode == DOCUMENT_SCAN_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK) {
                // ছবি পাওয়া গেলে তা সেভ করে অপটিমাইজ করা হবে
                val selectedFile: File? = if (data?.data != null) {
                    try {
                        val file = createImageFile()
                        contentResolver.openInputStream(data.data!!)?.use { input ->
                            FileOutputStream(file).use { output ->
                                input.copyTo(output)
                            }
                        }
                        optimizeAndFixCameraImage(file)
                        file
                    } catch (e: Exception) {
                        null
                    }
                } else if (tempDocumentFile != null && tempDocumentFile!!.exists()) {
                    optimizeAndFixCameraImage(tempDocumentFile!!)
                    tempDocumentFile
                } else {
                    null
                }

                if (selectedFile != null && selectedFile.exists()) {
                    // স্লট অনুযায়ী ফাইল ধরে রাখা
                    if (currentScanDocType == "nid_front" || currentScanDocType == "passport") {
                        frontDocFile = selectedFile
                    } else if (currentScanDocType == "nid_back") {
                        backDocFile = selectedFile
                    }

                    // ছবিটিকে শুধুমাত্র প্রিভিউ হিসেবে ওয়েবে পাঠানো হবে (কোনো স্ক্যান হবে না)
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
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }

                    Toast.makeText(this, "ছবি সেট করা হয়েছে", Toast.LENGTH_SHORT).show()
                }
            }
            tempDocumentFile = null
            tempDocumentUri = null
        } else if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            if (filePathCallback == null) return
            var results: Array<Uri>? = null

            if (resultCode == Activity.RESULT_OK) {
                if (data?.data != null) {
                    results = arrayOf(data.data!!)
                } else if (cameraImageUri != null) {
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
                    val trimmedCookie = cookie.trim()
                    if (trimmedCookie.startsWith("verified_login_id=")) {
                        smartIdValue = trimmedCookie
                        break
                    }
                }
                cookieManager.removeSessionCookies(null)
                if (smartIdValue.isNotEmpty()) {
                    cookieManager.setCookie("https://bdrsf.com/", "$smartIdValue; Path=/; Secure")
                }
                cookieManager.flush()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            barcodeScanner.close()
            latinTextRecognizer.close()
            if (::cameraExecutor.isInitialized) {
                cameraExecutor.shutdown()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
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
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
