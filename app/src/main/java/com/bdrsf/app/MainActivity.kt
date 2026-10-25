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

    private val barcodeScanner: BarcodeScanner by lazy { BarcodeScanning.getClient() }

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
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
                    if (normalizedUrl.isNotEmpty() && normalizedUrl != "https://bdrsf.com") {
                        webView.loadUrl("https://bdrsf.com/")
                        webView.clearHistory()
                    } else webView.reload()
                }
            }
        })

        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        webView.settings.apply {
            javaScriptEnabled = true; domStorageEnabled = true; databaseEnabled = true
            cacheMode = WebSettings.LOAD_DEFAULT; loadWithOverviewMode = true; useWideViewPort = true
            allowFileAccess = true; allowContentAccess = true; setGeolocationEnabled(true)
            mediaPlaybackRequiresUserGesture = false; mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        }

        webView.addJavascriptInterface(WebAppInterface(this), "AndroidBridge")
        CookieManager.getInstance().apply { setAcceptCookie(true); setAcceptThirdPartyCookies(webView, true) }
        clearAppSessionKeepSmartId()

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest?) { request?.let { runOnUiThread { it.grant(it.resources) } } }
            override fun onShowFileChooser(webView: WebView?, callback: ValueCallback<Array<Uri>>?, fileChooserParams: FileChooserParams?): Boolean {
                filePathCallback?.onReceiveValue(null); filePathCallback = callback
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
                        putExtra(Intent.EXTRA_TITLE, "Select File")
                        if (intentArray.isNotEmpty()) putExtra(Intent.EXTRA_INITIAL_INTENTS, intentArray)
                    }
                    @Suppress("DEPRECATION")
                    startActivityForResult(chooserIntent, FILE_CHOOSER_REQUEST_CODE)
                } catch (e: ActivityNotFoundException) { filePathCallback?.onReceiveValue(null); filePathCallback = null; return false }
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
                        view?.context?.startActivity(Intent(Intent.ACTION_VIEW, uri))
                        return true
                    }
                    val host = uri.host ?: ""
                    return if (host == "bdrsf.com" || host.endsWith(".bdrsf.com")) false else {
                        view?.context?.startActivity(Intent(Intent.ACTION_VIEW, uri))
                        true
                    }
                } catch (e: Exception) { return false }
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
        @JavascriptInterface fun startNativeMLKitScanner() { activity.runOnUiThread { activity.startLiveCameraScanner() } }
        @JavascriptInterface fun pickDocumentImage(slot: String, mode: String) {
            activity.runOnUiThread {
                activity.currentScanDocType = slot
                if (mode == "camera") activity.launchDirectCameraForDocument() else activity.launchDirectGalleryForDocument()
            }
        }
        @JavascriptInterface fun triggerFullDocumentProcessing(docType: String) { activity.runOnUiThread { activity.processSavedDocumentsHybrid(docType) } }
        @JavascriptInterface fun startNativeGalleryPicker() { activity.runOnUiThread { activity.openGalleryForQR() } }
    }

    private fun openGalleryForQR() {
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply { type = "image/*" }, GALLERY_QR_REQUEST_CODE)
        } catch (e: Exception) {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" }, GALLERY_QR_REQUEST_CODE)
        }
    }

    private fun launchDirectCameraForDocument() {
        if (!hasCameraPermission()) { requestAllRequiredPermissions(); return }
        try {
            val photoFile = createImageFile()
            tempDocumentFile = photoFile
            tempDocumentUri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", photoFile)
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply { putExtra(MediaStore.EXTRA_OUTPUT, tempDocumentUri) }, DOCUMENT_SCAN_REQUEST_CODE)
        } catch (e: Exception) {}
    }

    private fun launchDirectGalleryForDocument() {
        try {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply { type = "image/*" }, DOCUMENT_SCAN_REQUEST_CODE)
        } catch (e: Exception) {
            @Suppress("DEPRECATION")
            startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*" }, DOCUMENT_SCAN_REQUEST_CODE)
        }
    }

    private fun startLiveCameraScanner() {
        if (!hasCameraPermission()) { requestAllRequiredPermissions(); return }
        if (isScanningActive) return
        isScanningActive = true; isQRProcessed.set(false)

        scannerLayout = FrameLayout(this).apply {
            layoutParams = RelativeLayout.LayoutParams(RelativeLayout.LayoutParams.MATCH_PARENT, RelativeLayout.LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.BLACK)
        }
        previewView = PreviewView(this).apply { layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT) }
        scannerLayout?.addView(previewView)

        val closeButton = Button(this).apply {
            text = "Close"
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
            try { cameraProvider = cameraProviderFuture.get(); bindCameraUseCases() } catch (e: Exception) { stopLiveCameraScanner() }
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
                barcodeScanner.process(image).addOnSuccessListener { barcodes ->
                    for (barcode in barcodes) {
                        val scannedValue = barcode.rawValue
                        if (!scannedValue.isNullOrEmpty() && isQRProcessed.compareAndSet(false, true)) {
                            clientUiCallbackQR(scannedValue); break
                        }
                    }
                }.addOnCompleteListener { imageProxy.close() }
            } else imageProxy.close()
        }
        try { cameraProvider.unbindAll(); cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis) } catch (exc: Exception) { stopLiveCameraScanner() }
    }

    private fun clientUiCallbackQR(scannedValue: String) {
        runOnUiThread {
            stopLiveCameraScanner()
            webView.evaluateJavascript("if(window.processScannedData) { window.processScannedData(${JSONObject.quote(scannedValue)}); }", null)
        }
    }

    private fun stopLiveCameraScanner() {
        isScanningActive = false
        try { cameraProvider?.unbindAll() } catch (e: Exception) {}
        scannerLayout?.let { mainLayout.removeView(it); scannerLayout = null }; previewView = null
    }

    private fun optimizeAndFixCameraImage(file: File) {
        try {
            if (!file.exists() || file.length() == 0L) return
            val exif = ExifInterface(file.absolutePath)
            val rotationInDegrees = when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, options)
            val maxDim = max(options.outWidth, options.outHeight)
            var sampleSize = 1
            while (maxDim / sampleSize > 1600) sampleSize *= 2
            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            var bitmap = BitmapFactory.decodeFile(file.absolutePath, decodeOptions) ?: return

            if (rotationInDegrees != 0) {
                val matrix = Matrix().apply { postRotate(rotationInDegrees.toFloat()) }
                bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            }
            FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 75, out) }
        } catch (e: Exception) {}
    }

    private fun extractFacePhotoBase64(file: File): String {
        try {
            if (!file.exists() || file.length() == 0L) return ""
            val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return ""
            try {
                val bmp565 = bitmap.copy(Bitmap.Config.RGB_565, true)
                val detector = FaceDetector(if (bitmap.width % 2 == 0) bitmap.width else bitmap.width - 1, bitmap.height, 1)
                val faces = arrayOfNulls<FaceDetector.Face>(1)
                if (detector.findFaces(bmp565, faces) > 0 && faces[0] != null) {
                    val face = faces[0]!!
                    val midPoint = PointF(); face.getMidPoint(midPoint)
                    val eyeDistance = face.eyesDistance()
                    val faceWidth = (eyeDistance * 2.8f).toInt()
                    val faceHeight = (eyeDistance * 3.4f).toInt()
                    val x = (midPoint.x - faceWidth / 2).toInt().coerceIn(0, bitmap.width - 1)
                    val y = (midPoint.y - faceHeight / 2.2f).toInt().coerceIn(0, bitmap.height - 1)
                    val w = faceWidth.coerceAtMost(bitmap.width - x)
                    val h = faceHeight.coerceAtMost(bitmap.height - y)
                    if (w > 30 && h > 30) {
                        val croppedFace = Bitmap.createBitmap(bitmap, x, y, w, h)
                        val stream = ByteArrayOutputStream(); croppedFace.compress(Bitmap.CompressFormat.JPEG, 80, stream)
                        return "data:image/jpeg;base64," + Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
                    }
                }
            } catch (e: Exception) {}
            try {
                val x = (bitmap.width * 0.03).toInt(); val y = (bitmap.height * 0.18).toInt()
                val w = (bitmap.width * 0.38).toInt().coerceAtMost(bitmap.width - x)
                val h = (bitmap.height * 0.62).toInt().coerceAtMost(bitmap.height - y)
                val fallbackCrop = Bitmap.createBitmap(bitmap, x, y, w, h)
                val stream = ByteArrayOutputStream(); fallbackCrop.compress(Bitmap.CompressFormat.JPEG, 80, stream)
                return "data:image/jpeg;base64," + Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
            } catch (ex: Exception) {}
        } catch (e: Exception) {}
        return ""
    }

    private fun parseNidBarcodeData(barcodeData: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        if (barcodeData.isEmpty()) return result
        Regex("<pin>\\s*([0-9]{10,17})\\s*</pin>", RegexOption.IGNORE_CASE).find(barcodeData)?.groupValues?.getOrNull(1)?.let { result["nid"] = it } ?: Regex("\\b([0-9]{10}|[0-9]{13}|[0-9]{17})\\b").find(barcodeData)?.groupValues?.getOrNull(1)?.let { result["nid"] = it }
        Regex("<name>\\s*([^<]+)\\s*</name>", RegexOption.IGNORE_CASE).find(barcodeData)?.groupValues?.getOrNull(1)?.let { result["name"] = it.trim() }
        Regex("<(nameBn|name_bn|banglaName)>\\s*([^<]+)\\s*<", RegexOption.IGNORE_CASE).find(barcodeData)?.groupValues?.getOrNull(2)?.let { result["name_bn"] = it.trim() }
        Regex("<DOB>\\s*([^<]+)\\s*</DOB>", RegexOption.IGNORE_CASE).find(barcodeData)?.groupValues?.getOrNull(1)?.let { result["dob"] = it.trim() }
        Regex("<(father|fname|fatherName|father_name)>\\s*([^<]+)\\s*<", RegexOption.IGNORE_CASE).find(barcodeData)?.groupValues?.getOrNull(2)?.let { result["father"] = it.trim() }
        Regex("<(mother|mname|motherName|mother_name)>\\s*([^<]+)\\s*<", RegexOption.IGNORE_CASE).find(barcodeData)?.groupValues?.getOrNull(2)?.let { result["mother"] = it.trim() }
        return result
    }

    private fun processSavedDocumentsHybrid(rawDocType: String) {
        val isPassport = rawDocType.contains("PASS", ignoreCase = true)
        val normalizedDocType = if (isPassport) "PASSPORT" else "NID"

        if (frontDocFile == null || !frontDocFile!!.exists()) return sendErrorToWeb("Front image missing.")
        if (!isPassport && (backDocFile == null || !backDocFile!!.exists())) return sendErrorToWeb("Back image missing.")

        val facePhotoBase64 = extractFacePhotoBase64(frontDocFile!!)
        
        if (!isPassport && backDocFile != null && backDocFile!!.exists()) {
            val backImage = InputImage.fromFilePath(this, Uri.fromFile(backDocFile!!))
            barcodeScanner.process(backImage).addOnSuccessListener { barcodes ->
                var barcodeRawUtf8 = ""
                for (b in barcodes) {
                    val decoded = b.rawBytes?.let { try { String(it, Charsets.UTF_8) } catch (e: Exception) { b.rawValue ?: "" } } ?: (b.rawValue ?: "")
                    if (decoded.isNotEmpty()) barcodeRawUtf8 += "$decoded\n"
                }
                sendImageToServerForOCR(frontDocFile!!, backDocFile, facePhotoBase64, parseNidBarcodeData(barcodeRawUtf8), barcodeRawUtf8, normalizedDocType)
            }.addOnFailureListener {
                sendImageToServerForOCR(frontDocFile!!, backDocFile, facePhotoBase64, emptyMap(), "", normalizedDocType)
            }
        } else {
            sendImageToServerForOCR(frontDocFile!!, null, facePhotoBase64, emptyMap(), "", normalizedDocType)
        }
    }

    private fun sendErrorToWeb(msg: String) {
        runOnUiThread { webView.evaluateJavascript("if(window.onScanFailed) { window.onScanFailed(${JSONObject.quote(msg)}); }", null) }
    }

    private fun sendImageToServerForOCR(frontFile: File, backFile: File?, faceBase64: String, barcodeMap: Map<String, String>, barcodeRawText: String, documentType: String) {
        try {
            optimizeAndFixCameraImage(frontFile)
            val frontBase64 = "data:image/jpeg;base64," + Base64.encodeToString(frontFile.readBytes(), Base64.NO_WRAP)

            val jsonBody = JSONObject().apply {
                put("action", "scan_document")
                put("document_type", documentType)
                put("front_image_base64", frontBase64)
                if (backFile != null && backFile.exists()) {
                    optimizeAndFixCameraImage(backFile)
                    put("back_image_base64", "data:image/jpeg;base64," + Base64.encodeToString(backFile.readBytes(), Base64.NO_WRAP))
                }
            }

            val request = Request.Builder().url("https://bdrsf.com/api/ocr_scan")
                .post(jsonBody.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()))
                .addHeader("Accept", "application/json")
                .addHeader("Content-Type", "application/json").build()

            okHttpClient.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) { sendErrorToWeb("Connection error.") }
                override fun onResponse(call: Call, response: Response) {
                    val responseBody = response.body?.string()
                    if (response.isSuccessful && responseBody != null) {
                        try {
                            val json = JSONObject(responseBody)
                            if (json.optString("status") == "success") {
                                val data = json.getJSONObject("data")
                                if (documentType == "PASSPORT") handlePassportServerResponse(data, faceBase64)
                                else handleNidServerResponse(data, json.optString("raw_text", ""), barcodeMap, barcodeRawText, faceBase64)
                            } else {
                                sendErrorToWeb(json.optString("message", "Invalid document."))
                            }
                        } catch (e: Exception) { sendErrorToWeb("Scan failed.") }
                    } else sendErrorToWeb("Connection error.")
                }
            })
        } catch (e: Exception) { sendErrorToWeb("Scan failed.") }
    }

    private fun handleNidServerResponse(ocrData: JSONObject, rawBackText: String, barcodeMap: Map<String, String>, barcodeRaw: String, faceBase64: String) {
        val ocrNid = ocrData.optString("nidNo", "").replace(Regex("\\D"), "")
        val barcodeNid = barcodeMap["nid"]?.replace(Regex("\\D"), "") ?: ""

        if (ocrNid.isEmpty() && barcodeNid.isEmpty()) return sendErrorToWeb("Invalid NID.")
        
        if (ocrNid.isNotEmpty() && barcodeNid.isNotEmpty()) {
            val isMatch = (ocrNid == barcodeNid) || ocrNid.endsWith(barcodeNid) || barcodeNid.endsWith(ocrNid)
            if (!isMatch) return sendErrorToWeb("Fraud detected.")
        }

        val finalNidNo = if (barcodeNid.isNotEmpty()) barcodeNid else ocrNid
        val finalDob = barcodeMap["dob"]?.takeIf { it.isNotEmpty() } ?: ocrData.optString("dob", "")
        val finalNameBn = barcodeMap["name_bn"]?.takeIf { it.isNotEmpty() } ?: ocrData.optString("nameBn", "")
        val finalNameEn = barcodeMap["name"]?.takeIf { it.isNotEmpty() } ?: ocrData.optString("nameEn", "")
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
            "Address: ${backObj.optString("holding")}, ${backObj.optString("village")}, ${backObj.optString("postOffice")} ${backObj.optString("zipCode")}, ${backObj.optString("upazila")}, ${backObj.optString("district")}"
        }

        runOnUiThread {
            webView.evaluateJavascript("if(window.onFullNIDParsed) { window.onFullNIDParsed(${JSONObject.quote(displayName)}, ${JSONObject.quote(barcodeMap["father"] ?: ocrData.optString("fatherName", ""))}, ${JSONObject.quote(barcodeMap["mother"] ?: ocrData.optString("motherName", ""))}, ${JSONObject.quote(finalDob)}, ${JSONObject.quote(finalNidNo)}, ${JSONObject.quote(fullDetails)}, ${JSONObject.quote(faceBase64)}, ${JSONObject.quote(backObj.toString())}); }", null)
        }
    }

    private fun handlePassportServerResponse(ocrData: JSONObject, faceBase64: String) {
        val passportNo = ocrData.optString("passportNo", "").trim()
        val nameEn = ocrData.optString("nameEn", "").trim()
        val dob = ocrData.optString("dob", "").trim()

        if (passportNo.isEmpty() || passportNo.length < 7 || nameEn.isEmpty()) return sendErrorToWeb("Invalid passport.")
        
        runOnUiThread { webView.evaluateJavascript("if(window.onFullPassportParsed) { window.onFullPassportParsed(${JSONObject.quote(nameEn)}, ${JSONObject.quote(passportNo)}, ${JSONObject.quote(faceBase64)}, ${JSONObject.quote(dob)}); }", null) }
    }

    @Throws(IOException::class)
    private fun createImageFile(): File = File.createTempFile("KYC_${SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())}_", ".jpg", cacheDir)
    private fun hasCameraPermission(): Boolean = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    private fun requestAllRequiredPermissions() {
        val list = mutableListOf<String>()
        if (!hasCameraPermission()) list.add(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES) != PackageManager.PERMISSION_GRANTED) list.add(Manifest.permission.READ_MEDIA_IMAGES)
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) list.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) list.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (list.isNotEmpty()) ActivityCompat.requestPermissions(this, list.toTypedArray(), PERMISSION_REQUEST_CODE)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == GALLERY_QR_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK && data?.data != null) {
                try {
                    barcodeScanner.process(InputImage.fromFilePath(this, data.data!!)).addOnSuccessListener { barcodes ->
                        for (barcode in barcodes) {
                            val v = barcode.rawValue ?: ""
                            if (v.isNotEmpty()) { clientUiCallbackQR(v); break }
                        }
                    }
                } catch (e: Exception) {}
            }
        } else if (requestCode == DOCUMENT_SCAN_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK) {
                val selectedFile: File? = if (data?.data != null) {
                    try {
                        val file = createImageFile()
                        contentResolver.openInputStream(data.data!!)?.use { i -> FileOutputStream(file).use { o -> i.copyTo(o) } }
                        optimizeAndFixCameraImage(file); file
                    } catch (e: Exception) { null }
                } else if (tempDocumentFile?.exists() == true) { optimizeAndFixCameraImage(tempDocumentFile!!); tempDocumentFile } else null

                if (selectedFile?.exists() == true) {
                    if (currentScanDocType == "nid_front" || currentScanDocType == "passport") frontDocFile = selectedFile
                    else if (currentScanDocType == "nid_back") backDocFile = selectedFile
                    try {
                        val bmp = BitmapFactory.decodeFile(selectedFile.absolutePath)
                        if (bmp != null) {
                            val stream = ByteArrayOutputStream(); bmp.compress(Bitmap.CompressFormat.JPEG, 70, stream)
                            webView.evaluateJavascript("if(window.onImageSelectedPreview) { window.onImageSelectedPreview(${JSONObject.quote(currentScanDocType)}, ${JSONObject.quote("data:image/jpeg;base64," + Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP))}); }", null)
                        }
                    } catch (e: Exception) {}
                }
            }
            tempDocumentFile = null; tempDocumentUri = null
        } else if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            if (filePathCallback == null) return
            var results: Array<Uri>? = null
            if (resultCode == Activity.RESULT_OK) {
                if (data?.data != null) results = arrayOf(data.data!!)
                else if (cameraImageUri != null) { cameraImageFile?.let { optimizeAndFixCameraImage(it) }; results = arrayOf(cameraImageUri!!) }
            }
            filePathCallback?.onReceiveValue(results); filePathCallback = null; cameraImageFile = null; cameraImageUri = null
        } else super.onActivityResult(requestCode, resultCode, data)
    }

    private fun clearAppSessionKeepSmartId() {
        try {
            val cm = CookieManager.getInstance(); val cookies = cm.getCookie("https://bdrsf.com/")
            if (!cookies.isNullOrEmpty()) {
                val smartIdValue = cookies.split(";").map { it.trim() }.firstOrNull { it.startsWith("verified_login_id=") } ?: ""
                cm.removeSessionCookies(null)
                if (smartIdValue.isNotEmpty()) { cm.setCookie("https://bdrsf.com/", "$smartIdValue; Path=/; Secure") }
                cm.flush()
            }
        } catch (e: Exception) {}
    }

    override fun onDestroy() { super.onDestroy(); try { barcodeScanner.close(); if (::cameraExecutor.isInitialized) cameraExecutor.shutdown() } catch (e: Exception) {} }
    private fun hideSystemNavigationBar() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) { window.insetsController?.apply { hide(WindowInsets.Type.navigationBars()); systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE } }
            else { @Suppress("DEPRECATION") window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY) }
        } catch (e: Exception) {}
    }
}
