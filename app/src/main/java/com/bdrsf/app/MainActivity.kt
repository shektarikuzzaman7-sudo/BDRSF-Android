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
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.RelativeLayout
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var splashLayout: FrameLayout
    private lateinit var mainLayout: RelativeLayout
    
    // লাইভ কিউআর কোড স্ক্যানারের জন্য ভেরিয়েবল
    private var scannerLayout: FrameLayout? = null
    private var previewView: PreviewView? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private lateinit var cameraExecutor: ExecutorService
    private var isScanningActive = false

    // ডকুমেন্ট স্ক্যান (NID/Passport) এর জন্য টাইপ
    private var currentScanDocType = "nid"

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraImageUri: Uri? = null
    private var documentScanImageUri: Uri? = null

    private val FILE_CHOOSER_REQUEST_CODE = 1001
    private val GALLERY_QR_REQUEST_CODE = 1004
    private val DOCUMENT_SCAN_REQUEST_CODE = 1005
    private val PERMISSION_REQUEST_CODE = 1002

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        
        try {
            window.setFlags(
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                window.attributes.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            }

            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        mainLayout = RelativeLayout(this)
        mainLayout.layoutParams = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.MATCH_PARENT,
            RelativeLayout.LayoutParams.MATCH_PARENT
        )

        webView = WebView(this)
        val webViewParams = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.MATCH_PARENT,
            RelativeLayout.LayoutParams.MATCH_PARENT
        )
        webView.layoutParams = webViewParams
        mainLayout.addView(webView)

        splashLayout = FrameLayout(this)
        val splashParams = RelativeLayout.LayoutParams(
            RelativeLayout.LayoutParams.MATCH_PARENT,
            RelativeLayout.LayoutParams.MATCH_PARENT
        )
        splashLayout.layoutParams = splashParams
        splashLayout.setBackgroundColor(Color.parseColor("#0A2F1D"))

        val logoSizePx = (130 * resources.displayMetrics.density).toInt()
        val loaderSizePx = (155 * resources.displayMetrics.density).toInt()

        val circularProgressBar = ProgressBar(this, null, android.R.attr.progressBarStyleLarge)
        val progressParams = FrameLayout.LayoutParams(loaderSizePx, loaderSizePx)
        progressParams.gravity = Gravity.CENTER
        circularProgressBar.layoutParams = progressParams
        
        try {
            circularProgressBar.indeterminateDrawable.setColorFilter(
                Color.parseColor("#FFD700"),
                PorterDuff.Mode.SRC_IN
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
        splashLayout.addView(circularProgressBar)

        val logoImageView = ImageView(this)
        val logoParams = FrameLayout.LayoutParams(logoSizePx, logoSizePx)
        logoParams.gravity = Gravity.CENTER
        logoImageView.layoutParams = logoParams
        
        val logoResId = resources.getIdentifier("ic_logo", "drawable", packageName)
        if (logoResId != 0) {
            logoImageView.setImageResource(logoResId)
        }
        splashLayout.addView(logoImageView)

        mainLayout.addView(splashLayout)
        setContentView(mainLayout)

        cameraExecutor = Executors.newSingleThreadExecutor()

        requestAllRequiredPermissions()
        hideSystemNavigationBar()
        setupKeyboardListener(mainLayout)

        webView.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        val webSettings: WebSettings = webView.settings
        webSettings.javaScriptEnabled = true
        webSettings.domStorageEnabled = true
        webSettings.databaseEnabled = true
        webSettings.cacheMode = WebSettings.LOAD_DEFAULT
        webSettings.loadWithOverviewMode = true
        webSettings.useWideViewPort = true
        webSettings.allowFileAccess = true
        webSettings.allowContentAccess = true
        webSettings.mediaPlaybackRequiresUserGesture = false

        webView.addJavascriptInterface(WebAppInterface(this), "AndroidBridge")

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        clearAppSessionKeepSmartId()

        webView.setDownloadListener(DownloadListener { url, userAgent, contentDisposition, mimetype, contentLength ->
            try {
                if (url.startsWith("blob:")) {
                    Toast.makeText(this, "ব্লব ফাইল সরাসরি ডাউনলোড করা যায় না", Toast.LENGTH_SHORT).show()
                    return@DownloadListener
                }

                val request = DownloadManager.Request(Uri.parse(url))
                val fileName = URLUtil.guessFileName(url, contentDisposition, mimetype)
                
                request.allowScanningByMediaScanner()
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                request.setTitle(fileName)
                request.setDescription("ফাইল ডাউনলোড হচ্ছে...")
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
                
                val cookies = CookieManager.getInstance().getCookie(url)
                if (!cookies.isNullOrEmpty()) {
                    request.addRequestHeader("cookie", cookies)
                }
                
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
                if (request != null) {
                    runOnUiThread {
                        request.grant(request.resources)
                    }
                }
            }

            override fun onShowFileChooser(
                webView: WebView?,
                callback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                if (!hasCameraPermission()) {
                    requestAllRequiredPermissions()
                    Toast.makeText(this@MainActivity, "ক্যামেরা পারমিশন প্রয়োজন।", Toast.LENGTH_LONG).show()
                    callback?.onReceiveValue(null)
                    return false
                }

                if (filePathCallback != null) {
                    filePathCallback?.onReceiveValue(null)
                }
                filePathCallback = callback

                try {
                    val contentSelectionIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*" 
                    }

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
                    } else {
                        cameraImageUri = null
                    }

                    val chooserIntent = Intent(Intent.ACTION_CHOOSER).apply {
                        putExtra(Intent.EXTRA_INTENT, contentSelectionIntent)
                        putExtra(Intent.EXTRA_TITLE, "ফাইল বা ছবি সিলেক্ট করুন")
                        putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(captureIntent))
                    }

                    startActivityForResult(chooserIntent, FILE_CHOOSER_REQUEST_CODE)
                } catch (e: ActivityNotFoundException) {
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
                splashLayout.visibility = View.VISIBLE
            }

            override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                if (url != null && url.contains("bdrsf.com")) {
                    view?.loadUrl(url)
                    return true
                }
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                CookieManager.getInstance().flush()

                splashLayout.animate()
                    .alpha(0f)
                    .setDuration(400)
                    .withEndAction {
                        splashLayout.visibility = View.GONE
                    }
            }
        }

        webView.loadUrl("https://bdrsf.com/")
    }

    class WebAppInterface(private val activity: MainActivity) {
        // ১. লাইভ কিউআর কোড স্ক্যানার (আগের মতো বহাল আছে)
        @JavascriptInterface
        fun startNativeMLKitScanner() {
            activity.runOnUiThread {
                activity.startLiveCameraScanner()
            }
        }

        // ২. এনআইডি বা পাসপোর্ট স্ক্যান করার জন্য ছবি তোলা বা গ্যালারি থেকে নেওয়ার অপশন
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

                    val cacheFile = File(activity.cacheDir, fileName)
                    val fos = FileOutputStream(cacheFile)
                    fos.write(decodedBytes)
                    fos.flush()
                    fos.close()

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
            startActivityForResult(intent, GALLERY_QR_REQUEST_CODE)
        } catch (e: Exception) {
            try {
                val fallbackIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "image/*"
                }
                startActivityForResult(fallbackIntent, GALLERY_QR_REQUEST_CODE)
            } catch (ex: Exception) {
                Toast.makeText(this, "গ্যালারি ওপেন করা যাচ্ছে না", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // এনআইডি বা পাসপোর্ট স্ক্যানের জন্য ক্যামেরা বা গ্যালারি থেকে ছবি নেওয়ার ইনটেন্ট
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
                documentScanImageUri = FileProvider.getUriForFile(
                    this,
                    "${packageName}.fileprovider",
                    photoFile
                )
                captureIntent.putExtra(MediaStore.EXTRA_OUTPUT, documentScanImageUri)
            } else {
                documentScanImageUri = null
            }

            val titleText = if (currentScanDocType == "passport") "পাসপোর্টের ছবি তুলুন বা সিলেক্ট করুন" else "এনআইডি কার্ডের ছবি তুলুন বা সিলেক্ট করুন"
            val chooserIntent = Intent(Intent.ACTION_CHOOSER).apply {
                putExtra(Intent.EXTRA_INTENT, contentSelectionIntent)
                putExtra(Intent.EXTRA_TITLE, titleText)
                putExtra(Intent.EXTRA_INITIAL_INTENTS, arrayOf(captureIntent))
            }

            startActivityForResult(chooserIntent, DOCUMENT_SCAN_REQUEST_CODE)
        } catch (e: Exception) {
            Toast.makeText(this, "ইমেজ সিলেক্টর ওপেন করা যাচ্ছে না", Toast.LENGTH_SHORT).show()
        }
    }

    // লাইভ কিউআর কোড স্ক্যানার (সম্পূর্ণ বহাল রাখা হয়েছে)
    private fun startLiveCameraScanner() {
        if (!hasCameraPermission()) {
            requestAllRequiredPermissions()
            Toast.makeText(this, "ক্যামেরা পারমিশন প্রয়োজন", Toast.LENGTH_SHORT).show()
            return
        }

        if (isScanningActive) return
        isScanningActive = true

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
            val params = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = 100
                rightMargin = 50
            }
            layoutParams = params
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
            if (mediaImage != null) {
                val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
                val scanner = BarcodeScanning.getClient()
                scanner.process(image)
                    .addOnSuccessListener { barcodes ->
                        for (barcode in barcodes) {
                            val scannedValue = barcode.rawValue
                            if (!scannedValue.isNullOrEmpty()) {
                                runOnUiThread {
                                    stopLiveCameraScanner()
                                    webView.evaluateJavascript("javascript:processScannedData('$scannedValue');", null)
                                }
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

    // বাংলা সংখ্যাকে ইংরেজিতে কনভার্ট করার হেল্পার ফাংশন
    private fun convertBanglaToEnglishDigits(input: String): String {
        val banglaDigits = charArrayOf('০', '১', '২', '৩', '৪', '৫', '৬', '৭', '৮', '৯')
        val englishDigits = charArrayOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9')
        var result = input
        for (i in banglaDigits.indices) {
            result = result.replace(banglaDigits[i], englishDigits[i])
        }
        return result
    }

    // সিলেক্ট করা ছবি বা তোলা ছবি থেকে NID অথবা Passport স্ক্যান করে ডেটা বের করার ফাংশন
    private fun processDocumentImageFromUri(imageUri: Uri) {
        try {
            val image = InputImage.fromFilePath(this, imageUri)
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

            recognizer.process(image)
                .addOnSuccessListener { visionText ->
                    val rawText = visionText.text
                    val fullText = convertBanglaToEnglishDigits(rawText)

                    if (fullText.isNullOrEmpty()) {
                        Toast.makeText(this, "ছবিতে কোনো লেখা পাওয়া যায়নি", Toast.LENGTH_SHORT).show()
                        return@addOnSuccessListener
                    }

                    if (currentScanDocType == "passport") {
                        // পাসপোর্ট প্রসেসিং
                        var passportNo = ""
                        var nameEn = ""

                        val passportRegex = Regex("[A-Z][0-9]{7,8}")
                        val match = passportRegex.find(fullText)
                        passportNo = match?.value ?: ""

                        val lines = fullText.split("\n")
                        for (line in lines) {
                            val cleanLine = line.trim().replace(" ", "")
                            if (cleanLine.startsWith("P<") && cleanLine.length > 30) {
                                val parts = cleanLine.split("<")
                                if (parts.size > 2) {
                                    nameEn = "${parts[1]} ${parts[2]}".replace("<", " ").trim()
                                }
                            }
                        }

                        runOnUiThread {
                            val safeName = nameEn.replace("'", "\\'")
                            val safeDocNo = passportNo.replace("'", "\\'")
                            webView.evaluateJavascript("javascript:if(window.onScanCompleted) { window.onScanCompleted('$safeName', '$safeDocNo'); }", null)
                            Toast.makeText(this, "পাসপোর্ট সফলভাবে স্ক্যান করা হয়েছে!", Toast.LENGTH_SHORT).show()
                        }

                    } else {
                        // এনআইডি (NID) প্রসেসিং
                        val nidRegex = Regex("\\b[0-9]{10,17}\\b")
                        val nidMatch = nidRegex.find(fullText)
                        val nidNo = nidMatch?.value ?: ""

                        val dobRegex = Regex("([0-9]{2}[-/\\.][0-9]{2}[-/\\.][0-9]{4})")
                        val dobMatch = dobRegex.find(fullText)
                        val dobStr = dobMatch?.value ?: ""

                        var nameEn = ""
                        var fatherBn = ""
                        var motherBn = ""

                        val lines = fullText.split("\n")
                        for (line in lines) {
                            val t = line.trim()
                            if (t.matches(Regex("^[A-Z\\s]{4,}$")) && nameEn.isEmpty() && !t.contains("BANGLADESH") && !t.contains("NID") && !t.contains("GOVERNMENT")) {
                                nameEn = t
                            } 
                            else if (t.contains("পিতা", true) || t.contains("Father", true)) {
                                fatherBn = t.replace(Regex("^(পিতা|Father)[\\s:]*", RegexOption.IGNORE_CASE), "").trim()
                            } 
                            else if (t.contains("মাতা", true) || t.contains("Mother", true)) {
                                motherBn = t.replace(Regex("^(মাতা|Mother)[\\s:]*", RegexOption.IGNORE_CASE), "").trim()
                            }
                        }

                        runOnUiThread {
                            val safeName = nameEn.replace("'", "\\'")
                            val safeFather = fatherBn.replace("'", "\\'")
                            val safeMother = motherBn.replace("'", "\\'")
                            val safeDob = dobStr.replace("'", "\\'")
                            val safeNid = nidNo.replace("'", "\\'")

                            val jsCall = "if(window.onNIDScanCompleted) { window.onNIDScanCompleted('$safeName', '$safeFather', '$safeMother', '$safeDob', '$safeNid', ''); }"
                            webView.evaluateJavascript("javascript:$jsCall", null)
                            Toast.makeText(this, "এনআইডি (NID) সফলভাবে স্ক্যান করা হয়েছে!", Toast.LENGTH_SHORT).show()
                        }
                    }
                }
                .addOnFailureListener {
                    Toast.makeText(this, "ছবি থেকে টেক্সট রিড করতে ব্যর্থ হয়েছে", Toast.LENGTH_SHORT).show()
                }
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "ছবি প্রসেস করতে সমস্যা হয়েছে", Toast.LENGTH_SHORT).show()
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

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == GALLERY_QR_REQUEST_CODE) {
            if (resultCode == Activity.RESULT_OK && data != null && data.data != null) {
                val imageUri = data.data!!
                try {
                    val image = InputImage.fromFilePath(this, imageUri)
                    val scanner = BarcodeScanning.getClient()
                    scanner.process(image)
                        .addOnSuccessListener { barcodes ->
                            var scannedValue = ""
                            for (barcode in barcodes) {
                                scannedValue = barcode.rawValue ?: ""
                                if (scannedValue.isNotEmpty()) break
                            }
                            if (scannedValue.isNotEmpty()) {
                                webView.post {
                                    webView.evaluateJavascript("javascript:processScannedData('$scannedValue');", null)
                                }
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
                if (data == null || data.dataString == null) {
                    if (documentScanImageUri != null) {
                        processDocumentImageFromUri(documentScanImageUri!!)
                    }
                } else {
                    data.dataString?.let { uriString ->
                        processDocumentImageFromUri(Uri.parse(uriString))
                    }
                }
            }
            documentScanImageUri = null
        } else if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            if (filePathCallback == null) return
            var results: Array<Uri>? = null

            if (resultCode == Activity.RESULT_OK) {
                if (data == null || data.dataString == null) {
                    if (cameraImageUri != null) {
                        results = arrayOf(cameraImageUri!!)
                    }
                } else {
                    data.dataString?.let {
                        results = arrayOf(Uri.parse(it))
                    }
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

    @Suppress("DEPRECATION")
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
                    cookieManager.setCookie("https://bdrsf.com/", smartIdValue)
                }
                cookieManager.flush()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::cameraExecutor.isInitialized) {
            cameraExecutor.shutdown()
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
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (isScanningActive) {
            stopLiveCameraScanner()
        } else if (::webView.isInitialized && webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
