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
import android.graphics.Color
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
    var isStrictCheckEnabled: Boolean = false
    var currentScanConfig: JSONObject? = null

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraImageFile: File? = null
    private var cameraImageUri: Uri? = null

    @Volatile var cachedFrontBase64: String = ""
    @Volatile var cachedBackBase64: String = ""
    @Volatile var cachedFaceBase64: String = ""
    @Volatile var cachedBarcodeMap: Map<String, String> = emptyMap()

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
                Barcode.FORMAT_CODE_39
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
        } catch (_: Exception) {}

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
                    val currentUrl = webView.url ?: ""
                    if (WebAppConfig.isHomeUrl(currentUrl)) {
                        webView.reload()
                    } else {
                        WebAppConfig.loadHomePage(webView)
                        webView.clearHistory()
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

    fun processDeepDocumentFile(file: File, targetSlot: String) {
        if (!file.exists()) return

        bgProcessingExecutor.execute {
            try {
                val (bmp, b64) = DocumentUtils.optimizeAndCompressToBase64(file)
                if (b64.isNotEmpty()) {
                    if (targetSlot == "nid_front" || targetSlot == "passport") {
                        cachedFrontBase64 = b64
                        cachedFaceBase64 = DocumentUtils.extractFaceFromBitmap(bmp)
                    } else {
                        cachedBackBase64 = b64
                    }

                    runOnUiThread {
                        webView.evaluateJavascript(
                            "if(window.onImageCaptured) { window.onImageCaptured(${JSONObject.quote(targetSlot)}, ${JSONObject.quote(b64)}); }",
                            null
                        )
                        webView.evaluateJavascript(
                            "if(window.onImageSelectedPreview) { window.onImageSelectedPreview(${JSONObject.quote(targetSlot)}, ${JSONObject.quote(b64)}); }",
                            null
                        )
                    }
                }
            } catch (_: Exception) {}
        }
    }

    fun extractFaceFromBase64(base64Str: String) {
        val bmp = base64ToBitmap(base64Str)
        val faceB64 = if (bmp != null) DocumentUtils.extractFaceFromBitmap(bmp) else ""
        runOnUiThread {
            webView.evaluateJavascript(
                "if(window.onFaceExtracted) { window.onFaceExtracted(${JSONObject.quote(faceB64)}); }",
                null
            )
        }
    }

    fun readBarcodeFromBase64(base64Str: String) {
        val bmp = base64ToBitmap(base64Str)
        if (bmp != null) {
            val inputImg = InputImage.fromBitmap(bmp, 0)
            barcodeScanner.process(inputImg).addOnSuccessListener { barcodes ->
                var raw = barcodes.firstOrNull { !it.rawValue.isNullOrBlank() }?.rawValue ?: ""
                if (raw.isBlank()) raw = decodeBarcodeWithZxing(bmp) ?: ""

                val parsed = DocumentScanValidator.parseNidBarcodeData(raw)
                runOnUiThread {
                    webView.evaluateJavascript(
                        "if(window.onBarcodeRead) { window.onBarcodeRead(${JSONObject.quote(raw)}, ${JSONObject(parsed)}); }",
                        null
                    )
                }
            }.addOnFailureListener {
                val raw = decodeBarcodeWithZxing(bmp) ?: ""
                val parsed = DocumentScanValidator.parseNidBarcodeData(raw)
                runOnUiThread {
                    webView.evaluateJavascript(
                        "if(window.onBarcodeRead) { window.onBarcodeRead(${JSONObject.quote(raw)}, ${JSONObject(parsed)}); }",
                        null
                    )
                }
            }
        }
    }

    fun readTextFromBase64(base64Str: String) {
        val bmp = base64ToBitmap(base64Str)
        if (bmp != null) {
            val inputImg = InputImage.fromBitmap(bmp, 0)
            textRecognizer.process(inputImg).addOnSuccessListener { visionText ->
                val fullText = visionText.text
                val extracted = DocumentScanValidator.extractNidFrontData(fullText)
                runOnUiThread {
                    webView.evaluateJavascript(
                        "if(window.onTextRead) { window.onTextRead(${JSONObject.quote(fullText)}, ${JSONObject(extracted)}); }",
                        null
                    )
                }
            }
        }
    }

    fun processSavedDocumentsHybrid(rawDocType: String) {
        val isPassport = rawDocType.contains("PASS", ignoreCase = true)
        val normalizedDocType = if (isPassport) "PASSPORT" else "NID"

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
            onFailure = { errorMessage ->
                runOnUiThread {
                    webView.evaluateJavascript(
                        "if(window.onScanFailed) { window.onScanFailed(${JSONObject.quote(errorMessage)}); }",
                        null
                    )
                }
            }
        )
    }

    private fun handleNidServerResponse(ocrData: JSONObject, rawBackText: String, barcodeMap: Map<String, String>, faceBase64: String) {
        val ocrNid = ocrData.optString("nidNo", "").replace(Regex("\\D"), "")
        val barcodeNid = barcodeMap["nid"]?.replace(Regex("\\D"), "") ?: ""
        val finalNid = if (barcodeNid.isNotEmpty()) barcodeNid else ocrNid
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

        val fullDetails = if (rawBackText.isNotBlank()) rawBackText else "ঠিকানা: ${backObj.optString("village")}, ${backObj.optString("postOffice")}, ${backObj.optString("upazila")}, ${backObj.optString("district")}"

        runOnUiThread {
            webView.evaluateJavascript(
                "if(window.onFullNIDParsed) { window.onFullNIDParsed(" +
                        "${JSONObject.quote(displayName)}, " +
                        "${JSONObject.quote(barcodeMap["father"] ?: ocrData.optString("fatherName", ""))}, " +
                        "${JSONObject.quote(barcodeMap["mother"] ?: ocrData.optString("motherName", ""))}, " +
                        "${JSONObject.quote(barcodeMap["dob"]?.takeIf { it.isNotEmpty() } ?: ocrData.optString("dob", ""))}, " +
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
        val passportNo = ocrData.optString("passportNo", "").trim()
        val nameEn = ocrData.optString("nameEn", "").trim()

        runOnUiThread {
            webView.evaluateJavascript(
                "if(window.onFullPassportParsed) { window.onFullPassportParsed(" +
                        "${JSONObject.quote(nameEn)}, " +
                        "${JSONObject.quote(passportNo)}, " +
                        "${JSONObject.quote(faceBase64)}, " +
                        "${JSONObject.quote(ocrData.optString("dob", "").trim())}" +
                        "); }",
                null
            )
        }
    }

    private fun base64ToBitmap(base64Str: String): Bitmap? {
        return try {
            val clean = if (base64Str.contains(",")) base64Str.substringAfter(",") else base64Str
            val decodedBytes = Base64.decode(clean, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(decodedBytes, 0, decodedBytes.size)
        } catch (_: Exception) { null }
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
            MultiFormatReader().decode(binaryBitmap, hints).text
        } catch (_: Exception) { null }
    }

    @Throws(IOException::class)
    fun createImageFile(): File {
        return File.createTempFile("KYC_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}_", ".jpg", cacheDir)
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
        if (requestCode == GALLERY_QR_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK && data?.data != null) {
                try {
                    barcodeScanner.process(InputImage.fromFilePath(this, data.data!!)).addOnSuccessListener { barcodes ->
                        barcodes.firstOrNull { !it.rawValue.isNullOrEmpty() }?.rawValue?.let { scannedValue ->
                            runOnUiThread {
                                webView.evaluateJavascript("if(window.processScannedData) { window.processScannedData(${JSONObject.quote(scannedValue)}); }", null)
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        } else if (requestCode == DOCUMENT_SCAN_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK) {
                val targetSlot = currentScanDocType
                val rawUri = data?.data
                bgProcessingExecutor.execute {
                    val selectedFile: File? = if (rawUri != null) {
                        try {
                            val file = createImageFile()
                            contentResolver.openInputStream(rawUri)?.use { input ->
                                FileOutputStream(file).use { output -> input.copyTo(output) }
                            }
                            file
                        } catch (_: Exception) { null }
                    } else null

                    if (selectedFile != null && selectedFile.exists()) {
                        processDeepDocumentFile(selectedFile, targetSlot)
                    }
                }
            }
        } else {
            super.onActivityResult(requestCode, resultCode, data)
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
                window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
            }
        } catch (_: Exception) {}
    }
}
