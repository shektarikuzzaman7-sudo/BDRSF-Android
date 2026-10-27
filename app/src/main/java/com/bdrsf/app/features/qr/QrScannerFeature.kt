package com.bdrsf.app.features.qr

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.bdrsf.app.bridge.AndroidBridge
import com.bdrsf.app.bridge.WebBridgeContract.Actions
import com.bdrsf.app.bridge.WebBridgeContract.BridgeResponse
import com.bdrsf.app.bridge.WebBridgeContract.Status
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * BDRSF সার্বজনীন QR ও মাল্টি-ফরম্যাট বারকোড স্ক্যানার ফিচার।
 * যেকোনো ওয়েব পেজ থেকে ক্যামেরা ও গ্যালারি উভয় মাধ্যমে স্ক্যান করতে পারে।
 */
class QrScannerFeature(
    private val activity: Activity,
    private val rootContainer: ViewGroup,
    private val bridge: AndroidBridge
) {

    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var previewView: PreviewView? = null
    private var scannerOverlayLayout: FrameLayout? = null
    private var hudView: ScannerHudView? = null

    private var activeCallback: String? = null
    private var isTorchOn: Boolean = false
    private val isProcessingResult = AtomicBoolean(false)

    @Volatile
    var isScanningActive: Boolean = false
        private set

    // Google ML Kit মাল্টি-ফরম্যাট স্ক্যানার (QR + PDF417 + 1D Barcodes)
    private val mlKitScanner: BarcodeScanner by lazy {
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_QR_CODE,
                Barcode.FORMAT_PDF417,
                Barcode.FORMAT_CODE_128,
                Barcode.FORMAT_CODE_39,
                Barcode.FORMAT_DATA_MATRIX,
                Barcode.FORMAT_AZTEC,
                Barcode.FORMAT_EAN_13,
                Barcode.FORMAT_EAN_8,
                Barcode.FORMAT_UPC_A,
                Barcode.FORMAT_UPC_E
            )
            .build()
        BarcodeScanning.getClient(options)
    }

    /**
     * ক্যামেরা ভিউ সহ লাইভ স্ক্যানার চালু করে
     */
    fun startScanner(payload: JSONObject, callback: String?) {
        if (!hasCameraPermission()) {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.CAMERA),
                1001
            )
            return
        }

        if (isScanningActive) return

        activity.runOnUiThread {
            isScanningActive = true
            isProcessingResult.set(false)
            activeCallback = callback
            isTorchOn = false

            val promptText = payload.optString("prompt", "ফ্রেমের ভেতরে QR বা বারকোডটি রাখুন")

            buildScannerLayout(promptText)
            bindCameraProvider()
        }
    }

    private fun buildScannerLayout(promptText: String) {
        val density = activity.resources.displayMetrics.density

        scannerOverlayLayout = FrameLayout(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            setBackgroundColor(Color.BLACK)
        }

        previewView = PreviewView(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        scannerOverlayLayout?.addView(previewView)

        hudView = ScannerHudView(activity, promptText)
        scannerOverlayLayout?.addView(hudView)

        // উপরের বার: বন্ধ করার বোতাম এবং ফ্ল্যাশলাইট বোতাম
        val topControlsLayout = FrameLayout(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                (60 * density).toInt()
            ).apply {
                topMargin = (35 * density).toInt()
                leftMargin = (20 * density).toInt()
                rightMargin = (20 * density).toInt()
            }
        }

        val closeButton = TextView(activity).apply {
            text = "✕ বাতিল"
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding((14 * density).toInt(), (8 * density).toInt(), (14 * density).toInt(), (8 * density).toInt())
            setBackgroundColor(Color.parseColor("#80000000"))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
            }
            setOnClickListener { stopScanner(isCancelled = true) }
        }
        topControlsLayout.addView(closeButton)

        val flashButton = TextView(activity).apply {
            text = "⚡ টর্চ"
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding((14 * density).toInt(), (8 * density).toInt(), (14 * density).toInt(), (8 * density).toInt())
            setBackgroundColor(Color.parseColor("#80000000"))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
            }
            setOnClickListener { toggleTorch(this) }
        }
        topControlsLayout.addView(flashButton)

        scannerOverlayLayout?.addView(topControlsLayout)
        rootContainer.addView(scannerOverlayLayout)
    }

    private fun bindCameraProvider() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(activity)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                setupCameraUseCases()
            } catch (e: Exception) {
                Log.e("QrScannerFeature", "Camera provider error", e)
                stopScanner(isCancelled = true)
            }
        }, ContextCompat.getMainExecutor(activity))
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun setupCameraUseCases() {
        val provider = cameraProvider ?: return
        val lifecycleOwner = activity as? LifecycleOwner ?: return

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView?.surfaceProvider)
        }

        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
            if (!isScanningActive || isProcessingResult.get()) {
                imageProxy.close()
                return@setAnalyzer
            }

            val mediaImage = imageProxy.image
            if (mediaImage == null) {
                imageProxy.close()
                return@setAnalyzer
            }

            val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

            mlKitScanner.process(inputImage)
                .addOnSuccessListener { barcodes ->
                    val matchedBarcode = barcodes.firstOrNull { !it.rawValue.isNullOrBlank() }
                    if (matchedBarcode != null && isProcessingResult.compareAndSet(false, true)) {
                        val rawContent = matchedBarcode.rawValue ?: ""
                        val formatName = getBarcodeFormatName(matchedBarcode.format)
                        handleScanSuccess(rawContent, formatName)
                    }
                }
                .addOnFailureListener {
                    // ফ্রেম প্রসেসিং এরর হলে নীরবে পরবর্তী ফ্রেম নিবে
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        }

        try {
            provider.unbindAll()
            camera = provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageAnalysis
            )
        } catch (e: Exception) {
            Log.e("QrScannerFeature", "bindToLifecycle failed", e)
            stopScanner(isCancelled = true)
        }
    }

    private fun toggleTorch(button: TextView) {
        val cam = camera ?: return
        if (cam.cameraInfo.hasFlashUnit()) {
            isTorchOn = !isTorchOn
            cam.cameraControl.enableTorch(isTorchOn)
            button.text = if (isTorchOn) "☀ বন্ধ" else "⚡ টর্চ"
            button.setBackgroundColor(if (isTorchOn) Color.parseColor("#FFD700") else Color.parseColor("#80000000"))
            button.setTextColor(if (isTorchOn) Color.BLACK else Color.WHITE)
        }
    }

    private fun handleScanSuccess(rawContent: String, formatName: String) {
        activity.runOnUiThread {
            stopScanner(isCancelled = false)

            val data = JSONObject().apply {
                put("content", rawContent)
                put("format", formatName)
            }

            bridge.sendResponseToWeb(
                response = BridgeResponse(
                    action = Actions.SCAN_QR,
                    status = Status.SUCCESS,
                    data = data,
                    message = "Code scanned successfully"
                ),
                callbackFunction = activeCallback
            )
        }
    }

    /**
     * গ্যালারি থেকে সিলেক্ট করা Bitmap ডিকোড করে (ML Kit ও ZXing ডুয়াল ইঞ্জিন)
     */
    fun decodeFromBitmap(bitmap: Bitmap, callback: String?) {
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        mlKitScanner.process(inputImage)
            .addOnSuccessListener { barcodes ->
                val matched = barcodes.firstOrNull { !it.rawValue.isNullOrBlank() }
                if (matched != null) {
                    val data = JSONObject().apply {
                        put("content", matched.rawValue)
                        put("format", getBarcodeFormatName(matched.format))
                    }
                    bridge.sendResponseToWeb(
                        response = BridgeResponse(action = Actions.SCAN_QR, status = Status.SUCCESS, data = data),
                        callbackFunction = callback
                    )
                } else {
                    // ML Kit না পারলে ZXing MultiFormat ফলব্যাক চেষ্টা করবে
                    val zxingResult = decodeWithZxingFallback(bitmap)
                    if (zxingResult != null) {
                        val data = JSONObject().apply {
                            put("content", zxingResult)
                            put("format", "ZXING_FALLBACK")
                        }
                        bridge.sendResponseToWeb(
                            response = BridgeResponse(action = Actions.SCAN_QR, status = Status.SUCCESS, data = data),
                            callbackFunction = callback
                        )
                    } else {
                        bridge.sendResponseToWeb(
                            response = BridgeResponse(action = Actions.SCAN_QR, status = Status.ERROR, message = "ছবিতে কোনো কোড সনাক্ত করা যায়নি"),
                            callbackFunction = callback
                        )
                    }
                }
            }
            .addOnFailureListener {
                val zxingResult = decodeWithZxingFallback(bitmap)
                if (zxingResult != null) {
                    val data = JSONObject().apply {
                        put("content", zxingResult)
                        put("format", "ZXING_FALLBACK")
                    }
                    bridge.sendResponseToWeb(
                        response = BridgeResponse(action = Actions.SCAN_QR, status = Status.SUCCESS, data = data),
                        callbackFunction = callback
                    )
                } else {
                    bridge.sendResponseToWeb(
                        response = BridgeResponse(action = Actions.SCAN_QR, status = Status.ERROR, message = "বারকোড প্রসেসিং ব্যর্থ হয়েছে"),
                        callbackFunction = callback
                    )
                }
            }
    }

    private fun decodeWithZxingFallback(bitmap: Bitmap): String? {
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
                    com.google.zxing.BarcodeFormat.QR_CODE,
                    com.google.zxing.BarcodeFormat.PDF_417,
                    com.google.zxing.BarcodeFormat.CODE_128,
                    com.google.zxing.BarcodeFormat.CODE_39
                )
            )

            MultiFormatReader().decode(binaryBitmap, hints).text
        } catch (_: Exception) {
            null
        }
    }

    fun stopScanner(isCancelled: Boolean = false) {
        isScanningActive = false
        hudView?.stopAnimation()

        try {
            cameraProvider?.unbindAll()
        } catch (_: Exception) {}

        scannerOverlayLayout?.let {
            rootContainer.removeView(it)
            scannerOverlayLayout = null
        }
        previewView = null
        hudView = null
        camera = null

        if (isCancelled && activeCallback != null) {
            bridge.sendResponseToWeb(
                response = BridgeResponse(
                    action = Actions.SCAN_QR,
                    status = Status.CANCELLED,
                    message = "Scanner closed by user"
                ),
                callbackFunction = activeCallback
            )
            activeCallback = null
        }
    }

    fun destroy() {
        stopScanner(isCancelled = false)
        try {
            mlKitScanner.close()
            cameraExecutor.shutdown()
        } catch (_: Exception) {}
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            activity,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun getBarcodeFormatName(format: Int): String {
        return when (format) {
            Barcode.FORMAT_QR_CODE -> "QR_CODE"
            Barcode.FORMAT_PDF417 -> "PDF417"
            Barcode.FORMAT_CODE_128 -> "CODE_128"
            Barcode.FORMAT_CODE_39 -> "CODE_39"
            Barcode.FORMAT_DATA_MATRIX -> "DATA_MATRIX"
            Barcode.FORMAT_EAN_13 -> "EAN_13"
            else -> "BARCODE_$format"
        }
    }

    /**
     * কাস্টম ভিউফাইন্ডার ওভারলে: অ্যানিমেটেড স্ক্যানিং লেজার ও ফোকাস কর্নার
     */
    private inner class ScannerHudView(context: Context, private val guideText: String) : View(context) {

        private val shadePaint = Paint().apply {
            color = Color.parseColor("#99000000")
            style = Paint.Style.FILL
        }

        private val cornerPaint = Paint().apply {
            color = Color.parseColor("#00E676")
            style = Paint.Style.STROKE
            strokeWidth = 10f
            strokeCap = Paint.Cap.ROUND
            isAntiAlias = true
        }

        private val laserPaint = Paint().apply {
            isAntiAlias = true
            strokeWidth = 6f
        }

        private val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 40f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
            setShadowLayer(8f, 0f, 0f, Color.BLACK)
        }

        private var scanBox = RectF()
        private var laserY = 0f
        private var laserAnimator: ValueAnimator? = null

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            val boxSize = (w * 0.72f).coerceAtMost(h * 0.72f)
            val left = (w - boxSize) / 2f
            val top = (h - boxSize) / 2f
            scanBox.set(left, top, left + boxSize, top + boxSize)

            startLaserAnimation()
        }

        private fun startLaserAnimation() {
            laserAnimator?.cancel()
            laserAnimator = ValueAnimator.ofFloat(scanBox.top, scanBox.bottom).apply {
                duration = 2000
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener {
                    laserY = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }

        fun stopAnimation() {
            laserAnimator?.cancel()
            laserAnimator = null
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()

            // ১. চারপাশের অন্ধকার শেড
            canvas.drawRect(0f, 0f, w, scanBox.top, shadePaint)
            canvas.drawRect(0f, scanBox.bottom, w, h, shadePaint)
            canvas.drawRect(0f, scanBox.top, scanBox.left, scanBox.bottom, shadePaint)
            canvas.drawRect(scanBox.right, scanBox.top, w, scanBox.bottom, shadePaint)

            // ২. চার কোণার মার্কার
            val cornerLen = 50f
            // Top-Left
            canvas.drawLine(scanBox.left, scanBox.top, scanBox.left + cornerLen, scanBox.top, cornerPaint)
            canvas.drawLine(scanBox.left, scanBox.top, scanBox.left, scanBox.top + cornerLen, cornerPaint)
            // Top-Right
            canvas.drawLine(scanBox.right, scanBox.top, scanBox.right - cornerLen, scanBox.top, cornerPaint)
            canvas.drawLine(scanBox.right, scanBox.top, scanBox.right, scanBox.top + cornerLen, cornerPaint)
            // Bottom-Left
            canvas.drawLine(scanBox.left, scanBox.bottom, scanBox.left + cornerLen, scanBox.bottom, cornerPaint)
            canvas.drawLine(scanBox.left, scanBox.bottom, scanBox.left, scanBox.bottom - cornerLen, cornerPaint)
            // Bottom-Right
            canvas.drawLine(scanBox.right, scanBox.bottom, scanBox.right - cornerLen, scanBox.bottom, cornerPaint)
            canvas.drawLine(scanBox.right, scanBox.bottom, scanBox.right, scanBox.bottom - cornerLen, cornerPaint)

            // ৩. অ্যানিমেটেড লেজার রশ্মি
            if (laserY in scanBox.top..scanBox.bottom) {
                laserPaint.shader = LinearGradient(
                    scanBox.left, laserY, scanBox.right, laserY,
                    intArrayOf(Color.TRANSPARENT, Color.parseColor("#00E676"), Color.TRANSPARENT),
                    null,
                    Shader.TileMode.CLAMP
                )
                canvas.drawLine(scanBox.left + 10f, laserY, scanBox.right - 10f, laserY, laserPaint)
            }

            // ৪. নির্দেশনামূলক টেক্সট
            canvas.drawText(guideText, w / 2f, scanBox.bottom + 70f, textPaint)
        }
    }
}
