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
import android.graphics.Color
import android.graphics.PorterDuff
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.RelativeLayout
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
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

    lateinit var webView: WebView
    lateinit var splashLayout: FrameLayout
    lateinit var mainLayout: RelativeLayout

    lateinit var cameraScannerController: CameraScannerController
    private lateinit var nativeWebViewClient: NativeWebViewClient
    private lateinit var androidBridge: AndroidBridge
    private lateinit var apiClient: ApiClient

    lateinit var bgProcessingExecutor: ExecutorService

    var currentScanDocType: String = "nid_front"
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraImageFile: File? = null
    private var cameraImageUri: Uri? = null

    @Volatile private var cachedFrontBase64: String = ""
    @Volatile private var cachedBackBase64: String = ""
    @Volatile private var cachedFaceBase64: String = ""
    @Volatile private var cachedBarcodeMap: Map<String, String> = emptyMap()
    @Volatile private var cachedFrontData: Map<String, String> = emptyMap()
    @Volatile private var cachedBackAddressData: Map<String, String> = emptyMap()

    private val isFrontProcessing = AtomicBoolean(false)
    private val isBackProcessing = AtomicBoolean(false)

    private val FILE_CHOOSER_REQUEST_CODE = 1001
    private val PERMISSION_REQUEST_CODE = 1002
    private val GALLERY_QR_REQUEST_CODE = 1004
    private val DOCUMENT_SCAN_REQUEST_CODE = 1005

    private val barcodeScanner: BarcodeScanner by lazy {
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_PDF417,
                Barcode.FORMAT_QR_CODE,
                Barcode.FORMAT_CODE_128,
                Barcode.FORMAT_CODE_39,
                Barcode.FORMAT_DATA_MATRIX,
                Barcode.FORMAT_AZTEC
            )
            .build()
        BarcodeScanning.getClient(options)
    }

    private val textRecognizer: TextRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
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

        val loaderSizePx = (155 * resources.displayMetrics.density).toInt()
        val circularProgressBar = ProgressBar(this, null, android.R.attr.progressBarStyleLarge).apply {
            layoutParams = FrameLayout.LayoutParams(loaderSizePx, loaderSizePx).apply {
                gravity = Gravity.CENTER
            }
            try {
                @Suppress("DEPRECATION")
                indeterminateDrawable.setColorFilter(Color.parseColor("#FFD700"), PorterDuff.Mode.SRC_IN)
            } catch (_: Exception) {}
        }
        splashLayout.addView(circularProgressBar)

        val logoSizePx = (130 * resources.displayMetrics.density).toInt()
        val logoImageView = ImageView(this).apply {
            layoutParams = FrameLayout.LayoutParams(logoSizePx, logoSizePx).apply {
                gravity = Gravity.CENTER
            }
            val logoResId = resources.getIdentifier("ic_logo", "drawable", packageName)
            if (logoResId != 0) setImageResource(logoResId)
        }
        splashLayout.addView(logoImageView)

        mainLayout.addView(splashLayout)
        setContentView(mainLayout)

        bgProcessingExecutor = Executors.newFixedThreadPool(3)

        nativeWebViewClient = NativeWebViewClient {
            runOnUiThread {
                splashLayout.animate().alpha(0f).setDuration(400).withEndAction {
                    splashLayout.visibility = View.GONE
                }
            }
        }

        androidBridge = AndroidBridge(this)
        cameraScannerController = CameraScannerController(this)
        apiClient = ApiClient()

        requestAllRequiredPermissions()
        hideSystemNavigationBar()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (cameraScannerController.isScanningActive) {
                    stopLiveCameraScanner()
                } else if (::webView.isInitialized) {
                    if (webView.canGoBack()) {
                        webView.goBack()
                    } else {
                        val currentUrl = webView.url ?: ""
                        if (!WebAppConfig.isHomeUrl(currentUrl)) {
                            WebAppConfig.loadHomePage(webView)
                            webView.clearHistory()
                        }
                    }
                }
            }
        })

        setupWebView()
    }

    private fun setupWebView() {
        WebAppConfig.configureWebView(webView)
        webView.addJavascriptInterface(androidBridge, WebAppConfig.JS_BRIDGE_NAME)
        webView.webViewClient = nativeWebViewClient

        val cookieManager = CookieManager.getInstance()
        AppSecurityConfig.configureCookiePolicy(webView, cookieManager)

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
            } catch (_: Exception) {
                Toast.makeText(this, "ডাউনলোডে সমস্যা হয়েছে", Toast.LENGTH_SHORT).show()
            }
        })

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest?) {
                request?.let { req ->
                    runOnUiThread {
                        val originHost = req.origin?.host
                        if (AppSecurityConfig.isTrustedHost(originHost)) {
                            req.grant(req.resources)
                        } else {
                            req.deny()
                        }
                    }
                }
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
                        val photoFile: File? = try { createImageFile() } catch (_: IOException) { null }

                        if (photoFile != null) {
                            cameraImageFile = photoFile
                            cameraImageUri = FileProvider.getUriForFile(
                                this@MainActivity,
                                "${packageName}.fileprovider",
                                photoFile
                            )
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
                } catch (_: ActivityNotFoundException) {
                    filePathCallback?.onReceiveValue(null)
                    filePathCallback = null
                    return false
                }
                return true
            }
        }

        WebAppConfig.loadHomePage(webView)
    }

    fun updateCachedFaceIfNeeded(faceB64: String) {
        if (cachedFaceBase64.isEmpty() && faceB64.isNotBlank()) {
            cachedFaceBase64 = faceB64
        }
    }

    fun startLiveCameraScanner(scanMode: String) {
        currentScanDocType = scanMode
        cameraScannerController.startLiveCameraScanner(scanMode)
    }

    fun stopLiveCameraScanner() {
        cameraScannerController.stopLiveCameraScanner()
    }

    fun openGalleryForDocument(slot: String) {
        currentScanDocType = slot
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply {
            type = "image/*"
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, DOCUMENT_SCAN_REQUEST_CODE)
        } catch (_: Exception) {
            Toast.makeText(this, "গ্যালারি ওপেন করা যাচ্ছে না", Toast.LENGTH_SHORT).show()
        }
    }

    fun openGalleryForQrScan() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply {
            type = "image/*"
        }
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(intent, GALLERY_QR_REQUEST_CODE)
        } catch (_: Exception) {
            Toast.makeText(this, "গ্যালারি ওপেন করা যাচ্ছে না", Toast.LENGTH_SHORT).show()
        }
    }

    // ধাপ ৩: লাইভ সেলফি ক্যাপচার করার পর এনআইডি ছবির সাথে অফলাইন + অনলাইন বায়োমেট্রিক তুলনা
    fun processLiveSelfieAndVerify(selfieFile: File) {
        try {
            val (_, liveSelfieB64) = DocumentUtils.processSelfieFileToBase64(selfieFile)
            if (liveSelfieB64.isEmpty()) {
                runOnUiThread {
                    webView.evaluateJavascript("if(window.onLivenessCompleted) { window.onLivenessCompleted(false, '', 0); }", null)
                }
                return
            }

            val localScore = if (cachedFaceBase64.isNotEmpty()) {
                DocumentUtils.compareFacesBiometrically(cachedFaceBase64, liveSelfieB64)
            } else {
                92.5
            }

            apiClient.sendFaceVerifyRequest(
                idFaceBase64 = cachedFaceBase64,
                liveSelfieBase64 = liveSelfieB64,
                localBiometricScore = localScore,
                onResult = { isPassed, finalScore ->
                    runOnUiThread {
                        webView.evaluateJavascript(
                            "if(window.onLivenessCompleted) { window.onLivenessCompleted($isPassed, ${JSONObject.quote(liveSelfieB64)}, $finalScore); }",
                            null
                        )
                    }
                }
            )
        } catch (_: Exception) {
            runOnUiThread {
                webView.evaluateJavascript("if(window.onLivenessCompleted) { window.onLivenessCompleted(false, '', 0); }", null)
            }
        } finally {
            try { selfieFile.delete() } catch (_: Exception) {}
        }
    }

    fun processDeepDocumentFile(file: File, targetSlot: String) {
        if (!file.exists()) return

        runOnUiThread {
            webView.evaluateJavascript("if(window.onImageScanningStarted) { window.onImageScanningStarted(${JSONObject.quote(targetSlot)}); }", null)
        }

        if (targetSlot == "nid_front") {
            isFrontProcessing.set(true)
            bgProcessingExecutor.execute {
                try {
                    val (bmp, b64) = DocumentUtils.optimizeAndCompressToBase64(file)
                    val faceStr = DocumentUtils.extractFaceFromBitmap(bmp)

                    if (bmp != null) {
                        val inputImage = InputImage.fromBitmap(bmp, 0)
                        textRecognizer.process(inputImage)
                            .addOnSuccessListener { visionText ->
                                val allText = visionText.text

                                if (DocumentScanValidator.isPassport(allText)) {
                                    notifyValidationFailed(targetSlot, "ভুল ডকুমেন্ট! আপনি এনআইডির জায়গায় পাসপোর্টের ছবি দিয়েছেন।")
                                    cachedFrontBase64 = ""
                                    return@addOnSuccessListener
                                }

                                val extracted = DocumentScanValidator.extractNidFrontData(allText)
                                val nid = extracted["nid"] ?: ""

                                if (nid.isEmpty() && !DocumentScanValidator.isNidFront(allText)) {
                                    notifyValidationFailed(targetSlot, "এটি সঠিক এনআইডি কার্ডের সামনের অংশ নয়। পরিষ্কার ছবি দিন।")
                                    cachedFrontBase64 = ""
                                    return@addOnSuccessListener
                                }

                                cachedFrontData = extracted
                                cachedFrontBase64 = b64
                                cachedFaceBase64 = faceStr

                                runOnUiThread {
                                    Toast.makeText(this@MainActivity, "✔ সামনের অংশ যাচাই সম্পন্ন", Toast.LENGTH_SHORT).show()
                                    if (faceStr.isNotEmpty()) {
                                        webView.evaluateJavascript("if(window.onFaceExtracted) { window.onFaceExtracted(${JSONObject.quote(faceStr)}); }", null)
                                    }
                                    webView.evaluateJavascript("if(window.onDocumentValidated) { window.onDocumentValidated(${JSONObject.quote(targetSlot)}, true, ${JSONObject.quote(b64)}); }", null)
                                }
                            }
                            .addOnFailureListener {
                                notifyValidationFailed(targetSlot, "লেখা পড়া যায়নি, পরিষ্কার ছবি দিন।")
                                cachedFrontBase64 = ""
                            }
                    }
                } finally {
                    isFrontProcessing.set(false)
                    try { file.delete() } catch (_: Exception) {}
                }
            }
        } else if (targetSlot == "nid_back") {
            isBackProcessing.set(true)
            bgProcessingExecutor.execute {
                try {
                    val (bmp, b64) = DocumentUtils.optimizeAndCompressToBase64(file)
                    if (bmp != null) {
                        val inputImg = InputImage.fromBitmap(bmp, 0)

                        textRecognizer.process(inputImg).addOnSuccessListener { vText ->
                            val backText = vText.text

                            if (DocumentScanValidator.isPassport(backText)) {
                                notifyValidationFailed(targetSlot, "ভুল ডকুমেন্ট! আপনি এনআইডির পেছনে পাসপোর্টের ছবি দিয়েছেন।")
                                cachedBackBase64 = ""
                                isBackProcessing.set(false)
                                return@addOnSuccessListener
                            }

                            cachedBackAddressData = DocumentScanValidator.extractNidBackAddressData(backText)

                            barcodeScanner.process(inputImg)
                                .addOnSuccessListener { barcodes ->
                                    var rawBarcode = ""
                                    for (b in barcodes) {
                                        val decoded = b.rawBytes?.let {
                                            try { String(it, Charsets.UTF_8) } catch (_: Exception) { b.rawValue ?: "" }
                                        } ?: (b.rawValue ?: "")
                                        if (decoded.isNotEmpty()) rawBarcode += "$decoded\n"
                                    }

                                    if (rawBarcode.isBlank()) {
                                        val zxingResult = decodeBarcodeWithZxing(bmp)
                                        if (!zxingResult.isNullOrBlank()) {
                                            rawBarcode = zxingResult
                                        }
                                    }

                                    val parsedBarcode = DocumentScanValidator.parseNidBarcodeData(rawBarcode)

                                    if (rawBarcode.isBlank() && !DocumentScanValidator.isNidBack(backText, false)) {
                                        notifyValidationFailed(targetSlot, "পেছনের অংশে কোনো বৈধ বারকোড বা ঠিকানা পাওয়া যায়নি!")
                                        cachedBackBase64 = ""
                                        isBackProcessing.set(false)
                                        return@addOnSuccessListener
                                    }

                                    if (cachedFrontData.isNotEmpty() && rawBarcode.isNotBlank()) {
                                        val isMatch = DocumentScanValidator.validateNidCrossMatch(cachedFrontData, parsedBarcode, rawBarcode)
                                        if (!isMatch) {
                                            notifyValidationFailed(targetSlot, "কার্ডের সামনের এবং পেছনের অংশ দুটি ভিন্ন কার্ডের!")
                                            cachedBackBase64 = ""
                                            isBackProcessing.set(false)
                                            return@addOnSuccessListener
                                        }
                                    }

                                    cachedBackBase64 = b64
                                    cachedBarcodeMap = parsedBarcode

                                    runOnUiThread {
                                        Toast.makeText(this@MainActivity, "✔ পেছনের অংশ যাচাই সম্পন্ন", Toast.LENGTH_SHORT).show()
                                        webView.evaluateJavascript("if(window.onDocumentValidated) { window.onDocumentValidated(${JSONObject.quote(targetSlot)}, true, ${JSONObject.quote(b64)}); }", null)
                                    }
                                    isBackProcessing.set(false)
                                }
                                .addOnFailureListener {
                                    if (DocumentScanValidator.isNidBack(backText, false)) {
                                        cachedBackBase64 = b64
                                        runOnUiThread {
                                            Toast.makeText(this@MainActivity, "✔ পেছনের অংশ যাচাই সম্পন্ন", Toast.LENGTH_SHORT).show()
                                            webView.evaluateJavascript("if(window.onDocumentValidated) { window.onDocumentValidated(${JSONObject.quote(targetSlot)}, true, ${JSONObject.quote(b64)}); }", null)
                                        }
                                    } else {
                                        notifyValidationFailed(targetSlot, "পেছনের অংশ পড়া যায়নি বা ভুল কার্ড।")
                                        cachedBackBase64 = ""
                                    }
                                    isBackProcessing.set(false)
                                }
                        }
                    } else {
                        isBackProcessing.set(false)
                    }
                } catch (_: Exception) {
                    isBackProcessing.set(false)
                } finally {
                    try { file.delete() } catch (_: Exception) {}
                }
            }
        } else if (targetSlot == "passport") {
            isFrontProcessing.set(true)
            bgProcessingExecutor.execute {
                try {
                    val (bmp, b64) = DocumentUtils.optimizeAndCompressToBase64(file)
                    val faceStr = DocumentUtils.extractFaceFromBitmap(bmp)

                    if (bmp != null) {
                        val inputImage = InputImage.fromBitmap(bmp, 0)
                        textRecognizer.process(inputImage)
                            .addOnSuccessListener { visionText ->
                                val allText = visionText.text

                                if (DocumentScanValidator.isNidFront(allText)) {
                                    notifyValidationFailed(targetSlot, "ভুল ডকুমেন্ট! আপনি পাসপোর্টের জায়গায় এনআইডি কার্ড দিয়েছেন।")
                                    cachedFrontBase64 = ""
                                    return@addOnSuccessListener
                                }

                                if (!DocumentScanValidator.isPassport(allText)) {
                                    notifyValidationFailed(targetSlot, "এটি সঠিক পাসপোর্ট নয়! পৃষ্ঠাটি স্পষ্ট রাখুন।")
                                    cachedFrontBase64 = ""
                                    return@addOnSuccessListener
                                }

                                val passData = DocumentScanValidator.extractPassportData(allText)
                                val isValid = DocumentScanValidator.validatePassportCrossMatch(passData, allText)

                                if (!isValid) {
                                    notifyValidationFailed(targetSlot, "পাসপোর্টের MRZ কোড পড়া যায়নি বা অস্পষ্ট ছবি!")
                                    cachedFrontBase64 = ""
                                    return@addOnSuccessListener
                                }

                                cachedFrontBase64 = b64
                                cachedFaceBase64 = faceStr
                                cachedFrontData = passData

                                runOnUiThread {
                                    Toast.makeText(this@MainActivity, "✔ পাসপোর্ট যাচাই সম্পন্ন", Toast.LENGTH_SHORT).show()
                                    if (faceStr.isNotEmpty()) {
                                        webView.evaluateJavascript("if(window.onFaceExtracted) { window.onFaceExtracted(${JSONObject.quote(faceStr)}); }", null)
                                    }
                                    webView.evaluateJavascript("if(window.onDocumentValidated) { window.onDocumentValidated(${JSONObject.quote(targetSlot)}, true, ${JSONObject.quote(b64)}); }", null)
                                }
                            }
                            .addOnFailureListener {
                                notifyValidationFailed(targetSlot, "পাসপোর্টের পৃষ্ঠা পড়া সম্ভব হয়নি।")
                                cachedFrontBase64 = ""
                            }
                    }
                } finally {
                    isFrontProcessing.set(false)
                    try { file.delete() } catch (_: Exception) {}
                }
            }
        }
    }

    private fun decodeBarcodeWithZxing(bitmap: Bitmap): String? {
        return try {
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            val source = RGBLuminanceSource(width, height, pixels)
            val binaryBitmap = BinaryBitmap(HybridBinarizer(source))

            val hints = mapOf(
                DecodeHintType.TRY_HARDER to true,
                DecodeHintType.POSSIBLE_FORMATS to listOf(
                    com.google.zxing.BarcodeFormat.PDF_417,
                    com.google.zxing.BarcodeFormat.QR_CODE,
                    com.google.zxing.BarcodeFormat.CODE_128,
                    com.google.zxing.BarcodeFormat.CODE_39
                )
            )

            val result = MultiFormatReader().decode(binaryBitmap, hints)
            result.text
        } catch (_: Exception) {
            null
        }
    }

    private fun notifyValidationFailed(slot: String, message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            webView.evaluateJavascript("if(window.onDocumentValidated) { window.onDocumentValidated(${JSONObject.quote(slot)}, false, ''); }", null)
            webView.evaluateJavascript("if(window.onDocumentValidationFailed) { window.onDocumentValidationFailed(${JSONObject.quote(slot)}, ${JSONObject.quote(message)}); }", null)
        }
    }

    fun processSavedDocumentsHybrid(rawDocType: String) {
        val isPassport = rawDocType.contains("PASS", ignoreCase = true)
        val normalizedDocType = if (isPassport) "PASSPORT" else "NID"

        var waitCycles = 0
        while ((isFrontProcessing.get() || (!isPassport && isBackProcessing.get())) && waitCycles < 40) {
            Thread.sleep(50)
            waitCycles++
        }

        if (cachedFrontBase64.isEmpty()) {
            runOnUiThread {
                webView.evaluateJavascript("if(window.onScanFailed) { window.onScanFailed('সামনের অংশের ছবি পাওয়া যায়নি বা এটি সঠিক কার্ড নয়!'); }", null)
            }
            return
        }

        if (!isPassport && cachedBackBase64.isEmpty()) {
            runOnUiThread {
                webView.evaluateJavascript("if(window.onScanFailed) { window.onScanFailed('পেছনের অংশ সঠিক নয়!'); }", null)
            }
            return
        }

        apiClient.sendOcrRequest(
            documentType = normalizedDocType,
            frontImageBase64 = cachedFrontBase64,
            backImageBase64 = if (isPassport) null else cachedBackBase64,
            faceImageBase64 = cachedFaceBase64,
            onSuccess = { json ->
                runOnUiThread {
                    val data = json.optJSONObject("data") ?: JSONObject()
                    val rawBackText = json.optString("raw_text", "")
                    if (normalizedDocType == "PASSPORT") {
                        handlePassportServerResponse(data, cachedFaceBase64)
                    } else {
                        handleNidServerResponse(data, rawBackText, cachedBarcodeMap, cachedFaceBase64)
                    }
                }
            },
            onFailure = {
                // সার্ভার অফলাইন থাকলেও অ্যাপের নিজস্ব ML Kit + Barcode ডেটা দিয়ে ফর্ম পূরণ করে দেবে
                runOnUiThread {
                    if (normalizedDocType == "PASSPORT") {
                        handlePassportServerResponse(JSONObject(), cachedFaceBase64)
                    } else {
                        handleNidServerResponse(JSONObject(), cachedBackAddressData["rawAddress"] ?: "", cachedBarcodeMap, cachedFaceBase64)
                    }
                }
            }
        )
    }

    private fun handleNidServerResponse(
        ocrData: JSONObject,
        rawBackText: String,
        barcodeMap: Map<String, String>,
        faceBase64: String
    ) {
        val ocrNid = ocrData.optString("nidNo", "").replace(Regex("\\D"), "")
        val barcodeNid = barcodeMap["nid"]?.replace(Regex("\\D"), "") ?: ""
        val finalNid = barcodeNid.ifEmpty { cachedFrontData["nid"] ?: ocrNid }

        val displayName = barcodeMap["name"]?.takeIf { it.isNotEmpty() }
            ?: barcodeMap["name_bn"]?.takeIf { it.isNotEmpty() }
            ?: cachedFrontData["name"]?.takeIf { it.isNotEmpty() }
            ?: ocrData.optString("nameEn", "").ifEmpty { ocrData.optString("nameBn", "") }

        val fatherName = barcodeMap["father"]?.takeIf { it.isNotEmpty() }
            ?: cachedFrontData["father"]?.takeIf { it.isNotEmpty() }
            ?: ocrData.optString("fatherName", "")

        val motherName = barcodeMap["mother"]?.takeIf { it.isNotEmpty() }
            ?: cachedFrontData["mother"]?.takeIf { it.isNotEmpty() }
            ?: ocrData.optString("motherName", "")

        val backObj = JSONObject().apply {
            put("holding", ocrData.optString("holding", cachedBackAddressData["holding"] ?: "").trim())
            put("village", ocrData.optString("village", cachedBackAddressData["village"] ?: "").trim())
            put("postOffice", ocrData.optString("postOffice", cachedBackAddressData["postOffice"] ?: "").trim())
            put("zipCode", ocrData.optString("zipCode", cachedBackAddressData["zipCode"] ?: "").trim())
            put("upazila", ocrData.optString("upazila", cachedBackAddressData["upazila"] ?: "").trim())
            put("district", ocrData.optString("district", cachedBackAddressData["district"] ?: "").trim())
            put("bloodGroup", ocrData.optString("bloodGroup", barcodeMap["bloodGroup"] ?: cachedBackAddressData["bloodGroup"] ?: "").trim())
            put("issueDate", ocrData.optString("issueDate", cachedBackAddressData["issueDate"] ?: "").trim())
        }

        val fullDetails = if (rawBackText.isNotBlank()) {
            rawBackText
        } else {
            listOfNotNull(
                backObj.optString("holding").takeIf { it.isNotEmpty() }?.let { "Holding: $it" },
                backObj.optString("village").takeIf { it.isNotEmpty() }?.let { "Village/Road: $it" },
                backObj.optString("postOffice").takeIf { it.isNotEmpty() }?.let { "Post Office: $it" },
                backObj.optString("upazila").takeIf { it.isNotEmpty() }?.let { "Upazila: $it" },
                backObj.optString("district").takeIf { it.isNotEmpty() }?.let { "District: $it" }
            ).joinToString(", ")
        }

        runOnUiThread {
            webView.evaluateJavascript(
                "if(window.onFullNIDParsed) { window.onFullNIDParsed(" +
                    "${JSONObject.quote(displayName)}, " +
                    "${JSONObject.quote(fatherName)}, " +
                    "${JSONObject.quote(motherName)}, " +
                    "${JSONObject.quote(barcodeMap["dob"]?.takeIf { it.isNotEmpty() } ?: cachedFrontData["dob"] ?: ocrData.optString("dob", ""))}, " +
                    "${JSONObject.quote(finalNid)}, " +
                    "${JSONObject.quote(fullDetails)}, " +
                    "${JSONObject.quote(faceBase64)}, " +
                    "${JSONObject.quote(backObj.toString())}" +
                    "); }",
                null
            )
        }
    }

    private fun handlePassportServerResponse(ocrData: JSONObject, faceBase64: String) {
        val passportNo = ocrData.optString("passportNo", "").trim().ifEmpty { cachedFrontData["passportNo"] ?: "" }
        val nameEn = ocrData.optString("nameEn", "").trim().ifEmpty { cachedFrontData["name"] ?: "" }
        val dob = ocrData.optString("dob", "").trim().ifEmpty { cachedFrontData["dob"] ?: "" }

        runOnUiThread {
            webView.evaluateJavascript(
                "if(window.onFullPassportParsed) { window.onFullPassportParsed(" +
                    "${JSONObject.quote(nameEn)}, " +
                    "${JSONObject.quote(passportNo)}, " +
                    "${JSONObject.quote(faceBase64)}, " +
                    "${JSONObject.quote(dob)}" +
                    "); }",
                null
            )
        }
    }

    @Throws(IOException::class)
    fun createImageFile(): File {
        return File.createTempFile(
            "KYC_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}_",
            ".jpg",
            cacheDir
        )
    }

    fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    fun requestAllRequiredPermissions() {
        val list = mutableListOf<String>()
        if (!hasCameraPermission()) list.add(Manifest.permission.CAMERA)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            list.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        if (list.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, list.toTypedArray(), PERMISSION_REQUEST_CODE)
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        when (requestCode) {
            FILE_CHOOSER_REQUEST_CODE -> {
                if (filePathCallback == null) return
                var results: Array<Uri>? = null
                if (resultCode == Activity.RESULT_OK) {
                    if (data?.dataString != null) {
                        results = arrayOf(Uri.parse(data.dataString))
                    } else if (cameraImageUri != null) {
                        results = arrayOf(cameraImageUri!!)
                    }
                }
                filePathCallback?.onReceiveValue(results)
                filePathCallback = null
            }

            GALLERY_QR_REQUEST_CODE -> {
                if (resultCode == Activity.RESULT_OK && data?.data != null) {
                    try {
                        barcodeScanner.process(InputImage.fromFilePath(this, data.data!!)).addOnSuccessListener { barcodes ->
                            barcodes.firstOrNull { !it.rawValue.isNullOrEmpty() }?.rawValue?.let { scannedValue ->
                                runOnUiThread {
                                    webView.evaluateJavascript(
                                        "if(window.processScannedData) { window.processScannedData(${JSONObject.quote(scannedValue)}); }",
                                        null
                                    )
                                }
                            }
                        }
                    } catch (_: Exception) {}
                }
            }

            DOCUMENT_SCAN_REQUEST_CODE -> {
                if (resultCode == Activity.RESULT_OK) {
                    val targetSlot = currentScanDocType
                    val rawUri = data?.data
                    bgProcessingExecutor.execute {
                        val selectedFile: File? = if (rawUri != null) {
                            try {
                                val file = createImageFile()
                                contentResolver.openInputStream(rawUri)?.use { input ->
                                    FileOutputStream(file).use { output ->
                                        input.copyTo(output)
                                    }
                                }
                                file
                            } catch (_: Exception) {
                                null
                            }
                        } else {
                            null
                        }

                        if (selectedFile != null && selectedFile.exists()) {
                            processDeepDocumentFile(selectedFile, targetSlot)
                        }
                    }
                }
            }

            else -> super.onActivityResult(requestCode, resultCode, data)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraScannerController.destroy()
        try {
            barcodeScanner.close()
            textRecognizer.close()
        } catch (_: Exception) {}
        if (this::bgProcessingExecutor.isInitialized) {
            bgProcessingExecutor.shutdown()
        }
    }

    private fun hideSystemNavigationBar() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                window.insetsController?.let {
                    it.hide(WindowInsets.Type.navigationBars())
                    it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } else {
                @Suppress("DEPRECATION")
                window.decorView.systemUiVisibility =
                    (View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
            }
        } catch (_: Exception) {}
    }
}
