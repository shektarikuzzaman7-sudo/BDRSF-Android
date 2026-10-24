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
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.Rect
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
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
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
    private var cameraImageUri: Uri? = null
    private var documentScanImageUri: Uri? = null

    private val FILE_CHOOSER_REQUEST_CODE = 1001
    private val PERMISSION_REQUEST_CODE = 1002
    private val GALLERY_QR_REQUEST_CODE = 1004
    private val DOCUMENT_SCAN_REQUEST_CODE = 1005

    // ML Kit Clients (ইংরেজি ও বাংলা উভয় টেক্সট সাপোর্টের জন্য)
    private val barcodeScanner: BarcodeScanner by lazy { BarcodeScanning.getClient() }
    private val latinTextRecognizer: TextRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }
    private val banglaTextRecognizer: TextRecognizer by lazy {
        TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // অ্যাকশন বার বা টাইটেল হেডার থাকলে তা হাইড করা
        supportActionBar?.hide()
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        // স্ট্যাটাস বারের ঠিক নিচে ফুল-স্ক্রিন ভিউ ফিট করা এবং কীবোর্ড রিসাইজ সচল রাখা
        try {
            WindowCompat.setDecorFitsSystemWindows(window, true)
            window.statusBarColor = Color.parseColor("#0A2F1D")
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
        setupKeyboardListener(mainLayout)

        // নিখুঁত ব্যাক বাটন হ্যান্ডলিং
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

            // ওয়েবসাইটের লোকেশন অ্যাক্সেস সচল রাখার জন্য
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
                            cameraImageUri = FileProvider.getUriForFile(
                                this@MainActivity,
                                "${packageName}.fileprovider",
                                photoFile
                            )
                            captureIntent.putExtra(MediaStore.EXTRA_OUTPUT, cameraImageUri)
                            arrayOf(captureIntent)
                        } else {
                            cameraImageUri = null
                            emptyArray()
                        }
                    } else {
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
                        false // WebView নিজেই লোড করবে
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
                documentScanImageUri = FileProvider.getUriForFile(
                    this,
                    "${packageName}.fileprovider",
                    photoFile
                )
                captureIntent.putExtra(MediaStore.EXTRA_OUTPUT, documentScanImageUri)
            } else {
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
                topMargin = 60
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

    // নামের অক্ষর নষ্ট না করে শুধুমাত্র বাংলা সংখ্যাকে ইংরেজি সংখ্যায় রূপান্তর
    private fun convertBanglaDigitsOnly(input: String): String {
        val banglaDigits = charArrayOf('০', '১', '২', '৩', '৪', '৫', '৬', '৭', '৮', '৯')
        val englishDigits = charArrayOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9')
        var result = input
        for (i in banglaDigits.indices) {
            result = result.replace(banglaDigits[i], englishDigits[i])
        }
        return result
    }

    // শুধুমাত্র আইডি বা পাসপোর্ট নম্বর খোঁজার জন্য O->0 এবং I->1 রূপান্তর
    private fun normalizeForIdNumberSearch(input: String): String {
        return convertBanglaDigitsOnly(input)
            .replace('O', '0')
            .replace('o', '0')
            .replace('I', '1')
            .replace('l', '1')
    }

    private fun processDocumentImageFromUri(imageUri: Uri) {
        try {
            val image = InputImage.fromFilePath(this, imageUri)

            // প্রথমে বাংলা ও ইংরেজি উভয় টেক্সট রিকগনাইজার দিয়ে ছবি রিড করা হচ্ছে
            banglaTextRecognizer.process(image)
                .addOnSuccessListener { banglaVisionText ->
                    latinTextRecognizer.process(image)
                        .addOnSuccessListener { latinVisionText ->
                            val combinedRawText = "${latinVisionText.text}\n${banglaVisionText.text}"
                            handleExtractedDocumentText(combinedRawText, latinVisionText.text)
                        }
                        .addOnFailureListener {
                            handleExtractedDocumentText(banglaVisionText.text, banglaVisionText.text)
                        }
                }
                .addOnFailureListener {
                    latinTextRecognizer.process(image)
                        .addOnSuccessListener { latinVisionText ->
                            handleExtractedDocumentText(latinVisionText.text, latinVisionText.text)
                        }
                        .addOnFailureListener {
                            Toast.makeText(this, "ছবি থেকে টেক্সট রিড করতে ব্যর্থ হয়েছে", Toast.LENGTH_SHORT).show()
                        }
                }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "ছবি প্রসেস করতে সমস্যা হয়েছে", Toast.LENGTH_SHORT).show()
        }
    }

    private fun handleExtractedDocumentText(combinedRawText: String, latinOnlyText: String) {
        val cleanText = convertBanglaDigitsOnly(combinedRawText)
        val cleanLatinText = convertBanglaDigitsOnly(latinOnlyText)
        val numericFriendlyText = normalizeForIdNumberSearch(latinOnlyText)

        if (cleanText.isBlank()) {
            Toast.makeText(this, "ছবিতে কোনো লেখা পাওয়া যায়নি", Toast.LENGTH_SHORT).show()
            return
        }

        if (currentScanDocType == "passport") {
            var passportNo = ""
            var nameEn = ""

            val passportRegex = Regex("\\b[A-Z]{1,2}[0-9]{7,8}\\b")
            val match = passportRegex.find(cleanLatinText) ?: passportRegex.find(numericFriendlyText)
            passportNo = match?.value ?: ""

            // MRZ লাইন থেকে নিখুঁত নাম বের করা (এখানে O বা I পরিবর্তন করা হয়নি, তাই নাম ১০০% সঠিক থাকবে)
            val lines = cleanLatinText.split("\n")
            for (line in lines) {
                val cleanLine = line.trim().replace(" ", "")
                if (cleanLine.startsWith("P<") && cleanLine.length > 30) {
                    val withoutPrefix = if (cleanLine.length > 5) cleanLine.substring(5) else cleanLine.removePrefix("P<")
                    val parts = withoutPrefix.split("<<")
                    if (parts.isNotEmpty()) {
                        val surname = parts[0].replace("<", " ").trim()
                        val givenNames = if (parts.size > 1) parts[1].replace("<", " ").trim() else ""
                        nameEn = "$givenNames $surname".trim()
                    }
                }
            }

            runOnUiThread {
                val safeName = JSONObject.quote(nameEn)
                val safeDocNo = JSONObject.quote(passportNo)
                webView.evaluateJavascript("if(window.onScanCompleted) { window.onScanCompleted($safeName, $safeDocNo); }", null)
                Toast.makeText(this, "পাসপোর্ট সফলভাবে স্ক্যান হয়েছে!", Toast.LENGTH_SHORT).show()
            }

        } else if (currentScanDocType == "nid_front") {
            // ১০, ১৩ বা ১৭ ডিজিটের এনআইডি নম্বর খোঁজা (মাঝখানে স্পেস থাকলেও কাজ করবে)
            val compactNumericText = numericFriendlyText.replace(Regex("(?<=\\d)\\s+(?=\\d)"), "")
            val nidRegex = Regex("\\b(?:[0-9]{10}|[0-9]{13}|[0-9]{17})\\b")
            val nidMatch = nidRegex.find(compactNumericText)
            val nidNo = nidMatch?.value ?: ""

            val dobRegex = Regex("([0-9]{2}\\s*(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec|[0-9]{2})[a-zA-Z]*[-/\\.\\s]+[0-9]{4})", RegexOption.IGNORE_CASE)
            val dobMatch = dobRegex.find(cleanLatinText)
            val dobStr = dobMatch?.value ?: ""

            var nameEn = ""
            var fatherBn = ""
            var motherBn = ""

            // ইংরেজি নাম খোঁজা (cleanLatinText থেকে, যাতে O/I সংখ্যা না হয়ে যায়)
            val latinLines = cleanLatinText.split("\n")
            for (line in latinLines) {
                val t = line.trim().replace(Regex("^Name[:\\s]*", RegexOption.IGNORE_CASE), "").trim()
                if (t.matches(Regex("^[A-Z.\\s]{4,}$")) && nameEn.isEmpty() &&
                    !t.contains("BANGLADESH") && !t.contains("NATIONAL") &&
                    !t.contains("CARD") && !t.contains("GOVERNMENT") &&
                    !t.contains("COMMISSION") && !t.contains("REPUBLIC") && !t.contains("IDENTITY")) {
                    nameEn = t
                }
            }

            // বাংলা পিতা ও মাতার নাম খোঁজা
            val allLines = cleanText.split("\n")
            for (i in allLines.indices) {
                val t = allLines[i].trim()
                if ((t.contains("পিতা") || t.contains("Father", true)) && fatherBn.isEmpty()) {
                    val extracted = t.replace(Regex("^.*?(পিতা|Father)[\\s:.-]*", RegexOption.IGNORE_CASE), "").trim()
                    fatherBn = if (extracted.length > 2) extracted else if (i + 1 < allLines.size) allLines[i + 1].trim() else ""
                } else if ((t.contains("মাতা") || t.contains("Mother", true)) && motherBn.isEmpty()) {
                    val extracted = t.replace(Regex("^.*?(মাতা|Mother)[\\s:.-]*", RegexOption.IGNORE_CASE), "").trim()
                    motherBn = if (extracted.length > 2) extracted else if (i + 1 < allLines.size) allLines[i + 1].trim() else ""
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
                Toast.makeText(this, "এনআইডি সামনের অংশ স্ক্যান সম্পন্ন!", Toast.LENGTH_SHORT).show()
            }

        } else if (currentScanDocType == "nid_back") {
            var addressBn = ""
            val lines = cleanText.split("\n")
            for (line in lines) {
                val t = line.trim()
                if (t.contains("বাসা") || t.contains("গ্রাম") ||
                    t.contains("ডাকঘর") || t.contains("উপজেলা") ||
                    t.contains("জেলা") || t.contains("ঠিকানা") ||
                    t.contains("রোড") || t.contains("হোল্ডিং") || t.contains("পৌরসভা") || t.contains("সিটি")) {
                    addressBn += "$t "
                }
            }

            runOnUiThread {
                val safeAddress = JSONObject.quote(addressBn.trim())
                val jsCall = "if(window.onNIDBackScanCompleted) { window.onNIDBackScanCompleted($safeAddress); }"
                webView.evaluateJavascript(jsCall, null)
                Toast.makeText(this, "এনআইডি পেছনের অংশ স্ক্যান সম্পন্ন!", Toast.LENGTH_SHORT).show()
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
                    documentScanImageUri
                }
                targetUri?.let { processDocumentImageFromUri(it) }
            }
            documentScanImageUri = null
        } else if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            if (filePathCallback == null) return
            var results: Array<Uri>? = null

            if (resultCode == Activity.RESULT_OK) {
                if (data?.data != null) {
                    results = arrayOf(data.data!!)
                } else if (cameraImageUri != null) {
                    results = arrayOf(cameraImageUri!!)
                }
            }
            filePathCallback?.onReceiveValue(results)
            filePathCallback = null
            cameraImageUri = null
        } else {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }

    private fun setupKeyboardListener(rootView: View) {
        rootView.viewTreeObserver.addOnGlobalLayoutListener {
            try {
                val r = Rect()
                rootView.getWindowVisibleDisplayFrame(r)
                val screenHeight = rootView.rootView.height
                val keypadHeight = screenHeight - r.bottom
                if (keypadHeight < screenHeight * 0.15) {
                    hideSystemNavigationBar()
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
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
