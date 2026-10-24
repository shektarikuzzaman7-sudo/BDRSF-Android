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
import android.graphics.PorterDuff
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

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraImageFile: File? = null
    private var cameraImageUri: Uri? = null
    private var documentScanFile: File? = null
    private var documentScanImageUri: Uri? = null

    private val FILE_CHOOSER_REQUEST_CODE = 1001
    private val PERMISSION_REQUEST_CODE = 1002
    private val GALLERY_QR_REQUEST_CODE = 1004
    private val DOCUMENT_SCAN_REQUEST_CODE = 1005

    // ML Kit Clients (বারকোড, ইংরেজি ও বাংলা টেক্সট রিকগনাইজার)
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

        // Android 15 (SDK 35) সহ সকল ভার্সনে স্ট্যাটাস বারের ঠিক নিচে ফিট করা এবং কীবোর্ড উঠলে অ্যাডজাস্ট করার গ্যারান্টিড সিস্টেম
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

        // নিখুঁত ব্যাক বাটন হ্যান্ডলিং: সাব-পেজে থাকলে হোমপেজে নিয়ে যাবে ও হিস্ট্রি ক্লিন করবে, আর হোমপেজে থাকলে পেজ রিফ্রেশ করবে
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
                callback?.invoke(origin, true, false)
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
                        val intent = Intent(Intent.ACTION_VIEW, uri)
                        view?.context?.startActivity(intent)
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

        @JavascriptInterface
        fun startNativeDocumentScan(docType: String) {
            activity.runOnUiThread {
                activity.currentScanDocType = docType
                activity.openImagePickerForDocumentScan()
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

    private fun openImagePickerForDocumentScan() {
        if (!hasCameraPermission()) {
            requestAllRequiredPermissions()
            Toast.makeText(this, "ক্যামেরা পারমিশন প্রয়োজন, পারমিশন দিয়ে আবার চেষ্টা করুন", Toast.LENGTH_SHORT).show()
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
                documentScanFile = photoFile
                documentScanImageUri = FileProvider.getUriForFile(
                    this,
                    "${packageName}.fileprovider",
                    photoFile
                )
                captureIntent.putExtra(MediaStore.EXTRA_OUTPUT, documentScanImageUri)
            } else {
                documentScanFile = null
                documentScanImageUri = null
            }

            val titleText = when (currentScanDocType) {
                "nid_front" -> "এনআইডি কার্ডের সামনের (Front) ছবি তুলুন"
                "nid_back" -> "এনআইডি কার্ডের পেছনের (Back) ছবি তুলুন"
                else -> "পাসপোর্টের ছবি তুলুন"
            }

            val chooserIntent = Intent(Intent.ACTION_CHOOSER).apply {
                putExtra(Intent.EXTRA_INTENT, contentSelectionIntent)
                putExtra(Intent.EXTRA_TITLE, titleText)
                putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(captureIntent))
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

            // মেমরি ক্র্যাশ এড়িয়ে HD রেজোলিউশনে (Max ~1920px) ডিকোড করা
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
    // ৩ নম্বর থিওরি: Dual OCR + PDF417 Barcode + Passport MRZ Checksum Verification
    // =========================================================================

    private fun processDocumentImageFromUri(imageUri: Uri) {
        try {
            val image = InputImage.fromFilePath(this, imageUri)

            // ধাপ ১: প্রথমে ছবিতে কোনো এনআইডি বারকোড (PDF417) আছে কিনা তা স্ক্যান করা হচ্ছে
            barcodeScanner.process(image)
                .addOnSuccessListener { barcodes ->
                    var barcodeRawData = ""
                    for (barcode in barcodes) {
                        val raw = barcode.rawValue ?: ""
                        if (raw.isNotEmpty()) {
                            barcodeRawData += "$raw\n"
                        }
                    }
                    // বারকোড রেজাল্ট পাওয়ার পর বাংলা ও ইংরেজি টেক্সট (OCR) স্ক্যান করা হচ্ছে
                    runDualTextRecognition(image, barcodeRawData.trim())
                }
                .addOnFailureListener {
                    // বারকোড না পেলেও টেক্সট স্ক্যান চলবে
                    runDualTextRecognition(image, "")
                }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "ছবি প্রসেস করতে সমস্যা হয়েছে", Toast.LENGTH_SHORT).show()
        }
    }

    private fun runDualTextRecognition(image: InputImage, barcodeRawData: String) {
        banglaTextRecognizer.process(image)
            .addOnSuccessListener { banglaVisionText ->
                latinTextRecognizer.process(image)
                    .addOnSuccessListener { latinVisionText ->
                        val combinedRawText = "${latinVisionText.text}\n${banglaVisionText.text}"
                        handleExtractedDocumentText(combinedRawText, latinVisionText.text, barcodeRawData)
                    }
                    .addOnFailureListener {
                        handleExtractedDocumentText(banglaVisionText.text, banglaVisionText.text, barcodeRawData)
                    }
            }
            .addOnFailureListener {
                latinTextRecognizer.process(image)
                    .addOnSuccessListener { latinVisionText ->
                        handleExtractedDocumentText(latinVisionText.text, latinVisionText.text, barcodeRawData)
                    }
                    .addOnFailureListener {
                        Toast.makeText(this, "ছবি থেকে টেক্সট রিড করতে ব্যর্থ হয়েছে", Toast.LENGTH_SHORT).show()
                    }
            }
    }

    // পাসপোর্টের MRZ কোডের আন্তর্জাতিক গাণিতিক চেক-ডিজিট (ICAO 9303 7-3-1 Weight) যাচাই করার ফাংশন
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

    // এনআইডির পেছনের PDF417 বারকোড থেকে লুকানো তথ্য (PIN/NID, Name, DOB) বের করার ফাংশন
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

    private fun handleExtractedDocumentText(
        combinedRawText: String,
        latinOnlyText: String,
        barcodeRawData: String
    ) {
        val cleanText = convertBanglaDigitsOnly(combinedRawText)
        val cleanLatinText = convertBanglaDigitsOnly(latinOnlyText)
        val numericFriendlyText = normalizeForIdNumberSearch(latinOnlyText)

        val barcodeMap = parseNidBarcodeData(barcodeRawData)
        val hasNidBarcode = barcodeRawData.isNotEmpty() &&
                (barcodeRawData.contains("<pin>", true) || barcodeMap.containsKey("nid") || barcodeRawData.length > 25)

        if (cleanText.isBlank() && !hasNidBarcode) {
            Toast.makeText(this, "রিজেক্টেড! ছবিতে কোনো বৈধ ডকুমেন্ট বা লেখা পাওয়া যায়নি।", Toast.LENGTH_LONG).show()
            return
        }

        // ডকুমেন্ট চেনার সিগনেচার ফ্ল্যাগ
        val isPassportDoc = cleanLatinText.contains("PASSPORT", true) ||
                cleanLatinText.contains("P<", true) ||
                cleanLatinText.contains("REPUBLIC OF BANGLADESH", true) && cleanLatinText.contains("TYPE", true)

        val isNidFrontDoc = cleanText.contains("National ID", true) ||
                cleanText.contains("জাতীয় পরিচয়পত্র") ||
                cleanText.contains("জাতীয় পরিচয়পত্র") ||
                cleanText.contains("পিতা") ||
                cleanText.contains("মাতা") ||
                (cleanText.contains("Name", true) && cleanText.contains("Date of Birth", true))

        val isNidBackDoc = hasNidBarcode ||
                cleanText.contains("ঠিকানা") ||
                cleanText.contains("বাসা/হোল্ডিং") ||
                cleanText.contains("গ্রাম/রাস্তা") ||
                cleanText.contains("ডাকঘর") ||
                cleanText.contains("Blood Group", true) ||
                cleanText.contains("রক্তের গ্রুপ")

        // =========================================================================
        // ১. পাসপোর্ট (PASSPORT) + MRZ Check-Digit ভেরিফিকেশন
        // =========================================================================
        if (currentScanDocType == "passport") {
            if ((isNidFrontDoc || isNidBackDoc) && !cleanLatinText.contains("P<")) {
                runOnUiThread {
                    Toast.makeText(this, "ভুল ডকুমেন্ট! এটি এনআইডি কার্ড। দয়া করে পাসপোর্টের ছবি দিন।", Toast.LENGTH_LONG).show()
                }
                return
            }

            var passportNo = ""
            var nameEn = ""
            var isMrzVerified = false

            val lines = cleanLatinText.split("\n")
            for (i in lines.indices) {
                val cleanLine = lines[i].trim().replace(" ", "").uppercase()
                if (cleanLine.startsWith("P<") && cleanLine.length > 25) {
                    val withoutPrefix = if (cleanLine.length > 5) cleanLine.substring(5) else cleanLine.removePrefix("P<")
                    val parts = withoutPrefix.split("<<")
                    if (parts.isNotEmpty()) {
                        val surname = parts[0].replace("<", " ").replace(Regex("[^A-Z\\s]"), "").trim()
                        val givenNames = if (parts.size > 1) parts[1].replace("<", " ").replace(Regex("[^A-Z\\s]"), "").trim() else ""
                        nameEn = "$givenNames $surname".trim()
                    }

                    // MRZ ২য় লাইন থেকে পাসপোর্ট নম্বর ও গাণিতিক চেক-ডিজিট ভেরিফিকেশন
                    if (i + 1 < lines.size) {
                        val rawLine2 = lines[i + 1].trim().replace(" ", "").uppercase()
                        if (rawLine2.length >= 10) {
                            // প্রথম ২ অক্ষর লেটার/ডিজিট ঠিক রেখে পরের অংশে সংখ্যা নরমালাইজেশন
                            val prefix2 = rawLine2.substring(0, 2)
                            val numberAndCheckPart = normalizeForIdNumberSearch(rawLine2.substring(2, 10))
                            val candidateField9 = prefix2 + numberAndCheckPart.substring(0, 7)
                            val candidateCheckDigit = numberAndCheckPart[7]

                            if (verifyPassportMrzChecksum(candidateField9, candidateCheckDigit)) {
                                isMrzVerified = true
                                passportNo = candidateField9.replace("<", "").trim()
                            } else if (verifyPassportMrzChecksum(rawLine2.substring(0, 9), rawLine2[9])) {
                                isMrzVerified = true
                                passportNo = rawLine2.substring(0, 9).replace("<", "").trim()
                            }
                        }
                    }
                }
            }

            // যদি MRZ লাইন থেকে না পাওয়া যায় তবে পাসপোর্ট নম্বর ফরম্যাট দিয়ে খোঁজা
            if (passportNo.isEmpty()) {
                val passportRegex = Regex("\\b[A-Z]{1,2}[0-9]{7,8}\\b")
                val match = passportRegex.find(cleanLatinText) ?: passportRegex.find(numericFriendlyText)
                passportNo = match?.value ?: ""
            }

            if (!isPassportDoc || passportNo.isEmpty()) {
                runOnUiThread {
                    Toast.makeText(this, "রিজেক্টেড! বৈধ পাসপোর্ট পাওয়া যায়নি। পরিষ্কার পাসপোর্টের ছবি দিন।", Toast.LENGTH_LONG).show()
                }
                return
            }

            runOnUiThread {
                val safeName = JSONObject.quote(nameEn)
                val safeDocNo = JSONObject.quote(passportNo)
                webView.evaluateJavascript("if(window.onScanCompleted) { window.onScanCompleted($safeName, $safeDocNo); }", null)
                val verifyMsg = if (isMrzVerified) "পাসপোর্ট (MRZ ভেরিফাইড) স্ক্যান সফল!" else "পাসপোর্ট যাচাই ও স্ক্যান সফল!"
                Toast.makeText(this, verifyMsg, Toast.LENGTH_SHORT).show()
            }

        // =========================================================================
        // ২. এনআইডি সামনের অংশ (NID FRONT) ভেরিফিকেশন ও তথ্য সংগ্রহ
        // =========================================================================
        } else if (currentScanDocType == "nid_front") {
            if (isPassportDoc && !isNidFrontDoc) {
                runOnUiThread {
                    Toast.makeText(this, "ভুল ডকুমেন্ট! আপনি পাসপোর্টের ছবি দিয়েছেন। এনআইডির সামনের ছবি দিন।", Toast.LENGTH_LONG).show()
                }
                return
            }
            if ((isNidBackDoc || hasNidBarcode) && !isNidFrontDoc) {
                runOnUiThread {
                    Toast.makeText(this, "ভুল ডকুমেন্ট! এটি এনআইডির পেছনের অংশ। দয়া করে সামনের (Front) ছবি দিন।", Toast.LENGTH_LONG).show()
                }
                return
            }

            // ১০, ১৩ বা ১৭ ডিজিটের এনআইডি নম্বর খোঁজা (স্মার্ট কার্ডে মাঝখানে স্পেস থাকলেও কাজ করবে)
            val compactNumericText = numericFriendlyText.replace(Regex("(?<=\\d)[\\s-]+(?=\\d)"), "")
            val nidRegex = Regex("\\b(?:[0-9]{10}|[0-9]{13}|[0-9]{17})\\b")
            val nidMatch = nidRegex.find(compactNumericText)
            val nidNo = nidMatch?.value ?: barcodeMap["nid"] ?: ""

            if (nidNo.isEmpty() || (!isNidFrontDoc && !cleanText.contains("Birth", true))) {
                runOnUiThread {
                    Toast.makeText(this, "রিজেক্টেড! সঠিক এনআইডি কার্ড (সামনের অংশ) পাওয়া যায়নি। পরিষ্কার ছবি দিন।", Toast.LENGTH_LONG).show()
                }
                return
            }

            val dobRegex = Regex("([0-9]{1,2}[\\s-/\\.]+(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec|[0-9]{2})[a-zA-Z]*[\\s-/\\.]+[0-9]{4})", RegexOption.IGNORE_CASE)
            val dobMatch = dobRegex.find(cleanLatinText)
            val dobStr = dobMatch?.value?.trim() ?: barcodeMap["dob"] ?: ""

            var nameEn = barcodeMap["name"] ?: ""
            var fatherBn = ""
            var motherBn = ""

            val latinLines = cleanLatinText.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
            if (nameEn.isEmpty()) {
                for (i in latinLines.indices) {
                    val line = latinLines[i]
                    if (line.startsWith("Name", true)) {
                        val afterLabel = line.replace(Regex("^Name[\\s:.-]*", RegexOption.IGNORE_CASE), "").trim()
                        if (afterLabel.length >= 3 && afterLabel.matches(Regex("^[A-Za-z.\\s-]+$"))) {
                            nameEn = afterLabel.uppercase()
                            break
                        } else if (i + 1 < latinLines.size) {
                            val nextLine = latinLines[i + 1]
                            if (nextLine.matches(Regex("^[A-Za-z.\\s-]{3,}$")) && !nextLine.contains("Date", true)) {
                                nameEn = nextLine.uppercase()
                                break
                            }
                        }
                    }
                }
            }

            if (nameEn.isEmpty()) {
                for (line in latinLines) {
                    val t = line.replace(Regex("^Name[:\\s]*", RegexOption.IGNORE_CASE), "").trim()
                    if (t.matches(Regex("^[A-Z.\\s]{4,}$")) &&
                        !t.contains("BANGLADESH") && !t.contains("NATIONAL") &&
                        !t.contains("CARD") && !t.contains("GOVERNMENT") &&
                        !t.contains("COMMISSION") && !t.contains("REPUBLIC") &&
                        !t.contains("IDENTITY") && !t.contains("SERVICE")) {
                        nameEn = t
                        break
                    }
                }
            }

            val allLines = cleanText.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
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

            runOnUiThread {
                val safeName = JSONObject.quote(nameEn)
                val safeFather = JSONObject.quote(fatherBn)
                val safeMother = JSONObject.quote(motherBn)
                val safeDob = JSONObject.quote(dobStr)
                val safeNid = JSONObject.quote(nidNo)

                val jsCall = "if(window.onNIDFrontScanCompleted) { window.onNIDFrontScanCompleted($safeName, $safeFather, $safeMother, $safeDob, $safeNid); }"
                webView.evaluateJavascript(jsCall, null)
                Toast.makeText(this, "সঠিক এনআইডি (সামনের অংশ) যাচাই ও স্ক্যান সম্পন্ন!", Toast.LENGTH_SHORT).show()
            }

        // =========================================================================
        // ৩. এনআইডি পেছনের অংশ (NID BACK) + PDF417 Barcode ভেরিফিকেশন
        // =========================================================================
        } else if (currentScanDocType == "nid_back") {
            if (isNidFrontDoc && !isNidBackDoc && !hasNidBarcode) {
                runOnUiThread {
                    Toast.makeText(this, "ভুল ডকুমেন্ট! এটি এনআইডির সামনের অংশ। দয়া করে পেছনের (Back) ছবি দিন।", Toast.LENGTH_LONG).show()
                }
                return
            }
            if (isPassportDoc && !isNidBackDoc) {
                runOnUiThread {
                    Toast.makeText(this, "ভুল ডকুমেন্ট! এটি পাসপোর্ট। এনআইডির পেছনের (Back) ছবি দিন।", Toast.LENGTH_LONG).show()
                }
                return
            }

            var addressBn = ""
            val lines = cleanText.split("\n")
            for (line in lines) {
                val t = line.trim()
                if (t.contains("বাসা") || t.contains("গ্রাম") ||
                    t.contains("ডাকঘর") || t.contains("উপজেলা") ||
                    t.contains("জেলা") || t.contains("ঠিকানা") ||
                    t.contains("রোড") || t.contains("হোল্ডিং") ||
                    t.contains("পৌরসভা") || t.contains("সিটি") || t.contains("থানা")) {
                    val cleanAddrLine = t.replace(Regex("^ঠিকানা[\\s:.-]*"), "").trim()
                    addressBn += "$cleanAddrLine, "
                }
            }

            addressBn = addressBn.trim().trimEnd(',')

            if (!isNidBackDoc || (addressBn.length < 5 && !hasNidBarcode)) {
                runOnUiThread {
                    Toast.makeText(this, "রিজেক্টেড! এনআইডির পেছনের অংশের ঠিকানা বা বারকোড পাওয়া যায়নি। সঠিক ছবি দিন।", Toast.LENGTH_LONG).show()
                }
                return
            }

            runOnUiThread {
                val safeAddress = JSONObject.quote(addressBn)
                val jsCall = "if(window.onNIDBackScanCompleted) { window.onNIDBackScanCompleted($safeAddress); }"
                webView.evaluateJavascript(jsCall, null)

                val msg = if (hasNidBarcode) {
                    "এনআইডি পেছনের অংশ (বারকোড ভেরিফাইড) স্ক্যান সম্পন্ন!"
                } else {
                    "এনআইডি পেছনের অংশ যাচাই ও স্ক্যান সম্পন্ন!"
                }
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
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
                                Toast.makeText(this, "নির্বাচিত ছবিতে কোনো কিউআর কোড পাওয়া যায়নি", Toast.LENGTH_LONG).show()
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
                val targetUri: Uri? = if (data?.data != null) {
                    data.data
                } else {
                    // ক্যামেরা দিয়ে তোলা HD ছবি হলে রোটেশন ও সাইজ অপটিমাইজ করা হচ্ছে
                    documentScanFile?.let { optimizeAndFixCameraImage(it) }
                    documentScanImageUri
                }
                targetUri?.let { processDocumentImageFromUri(it) }
            }
            documentScanFile = null
            documentScanImageUri = null
        } else if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            if (filePathCallback == null) return
            var results: Array<Uri>? = null

            if (resultCode == Activity.RESULT_OK) {
                if (data?.data != null) {
                    results = arrayOf(data.data!!)
                } else if (cameraImageUri != null) {
                    // ক্যামেরা দিয়ে তোলা HD ছবি হলে ওয়েবসাইটে পাঠানোর আগে অপটিমাইজ করা হচ্ছে
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
            banglaTextRecognizer.close()
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
