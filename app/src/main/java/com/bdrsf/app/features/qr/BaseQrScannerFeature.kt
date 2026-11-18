// File: app/src/main/java/com/bdrsf/app/features/qr/BaseQrScannerFeature.kt
package com.bdrsf.app.features.qr

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.LifecycleOwner
import com.bdrsf.app.bridge.AndroidBridge
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * BDRSF-Android পিওর হার্ডওয়্যার ও ML Kit / ZXing স্ক্যানিং ইঞ্জিন।
 * দায়িত্ব:
 *  - CameraX লাইভ প্রিভিউ ও ইমেজ অ্যানালাইসিস পাইপলাইন পরিচালনা
 *  - Google ML Kit ও ZXing ডুয়াল-ইঞ্জিন বারকোড/কিউআর ডিকোডিং
 *  - গ্যালারি ইমেজ EXIF রোটেশন ও মেমোরি-সেফ স্কেলিং
 *  - ডাইনামিক প্রোভাইডার কালার ও গাইড টেক্সটসহ HUD ওভারলে রেন্ডারিং
 *  - জিরো মেমোরি লিক নিশ্চিতকরণ
 */
abstract class BaseQrScannerFeature(
    protected val activity: Activity,
    private val rootContainer: ViewGroup,
    protected val bridge: AndroidBridge
) {

    data class QrDecodeResult(
        val isValid: Boolean,
        val payloadJson: String = "",
        val errorMessage: String = "",
        val isProviderMatch: Boolean = false
    )

    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var previewView: PreviewView? = null
    private var scannerOverlayLayout: FrameLayout? = null
    private var hudView: ScannerHudView? = null

    protected var activeCallback: String? = null
    protected var activeErrorCallback: String? = null
    protected var activeExpectedMode: String = ""
    protected var activeContext: String = ""
    protected var activeUserRole: String = ""
    private var isTorchOn: Boolean = false
    private val isProcessingResult = AtomicBoolean(false)
    private var lastZxingFallbackTime: Long = 0L

    @Volatile
    var isScanningActive: Boolean = false
        private set

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

    private val zxingHints: Map<DecodeHintType, Any> by lazy {
        mapOf(
            DecodeHintType.TRY_HARDER to true,
            DecodeHintType.ALSO_INVERTED to true,
            DecodeHintType.POSSIBLE_FORMATS to listOf(
                BarcodeFormat.QR_CODE,
                BarcodeFormat.PDF_417,
                BarcodeFormat.CODE_128,
                BarcodeFormat.CODE_39,
                BarcodeFormat.DATA_MATRIX,
                BarcodeFormat.AZTEC,
                BarcodeFormat.EAN_13,
                BarcodeFormat.EAN_8
            )
        )
    }

    protected abstract fun getDefaultPromptText(mode: String, context: String): String
    protected abstract fun getHudAccentColor(): Int

    abstract fun decodeProviderQr(
        rawContent: String,
        expectedMode: String,
        context: String
    ): QrDecodeResult

    fun configureContext(payload: JSONObject, callback: String?) {
        activeCallback = if (!callback.isNullOrBlank()) {
            callback
        } else {
            payload.optString("callback", "processScannedData").ifBlank { "processScannedData" }
        }
        activeErrorCallback = payload.optString("errorCallback", "onScanFailed").ifBlank { "onScanFailed" }
        activeExpectedMode = payload.optString("mode", "").trim().lowercase()
        activeContext = payload.optString("context", "").trim().lowercase()
        activeUserRole = payload.optString("userRole", payload.optString("accountType", "")).trim().lowercase()
    }

    fun startScanner(payload: JSONObject, callback: String?) {
        if (!hasCameraPermission()) {
            ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.CAMERA), 1001)
            return
        }

        if (isScanningActive) return

        activity.runOnUiThread {
            isScanningActive = true
            isProcessingResult.set(false)
            lastZxingFallbackTime = 0L
            configureContext(payload, callback)
            isTorchOn = false

            val defaultPrompt = getDefaultPromptText(activeExpectedMode, activeContext)
            val promptText = payload.optString("prompt", "").ifBlank { defaultPrompt }
            buildScannerLayout(promptText, getHudAccentColor())
            bindCameraProvider()
        }
    }

    private fun buildScannerLayout(promptText: String, accentColor: Int) {
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

        hudView = ScannerHudView(activity, promptText, accentColor)
        scannerOverlayLayout?.addView(hudView)

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
                Log.e("BaseQrScannerFeature", "Camera provider error", e)
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
                    if (matchedBarcode != null) {
                        if (isProcessingResult.compareAndSet(false, true)) {
                            handleScanSuccess(matchedBarcode.rawValue ?: "")
                        }
                    } else if (!isProcessingResult.get()) {
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastZxingFallbackTime >= 400L) {
                            lastZxingFallbackTime = now
                            val fallbackText = decodeImageProxyWithZxing(imageProxy)
                            if (!fallbackText.isNullOrBlank() && isProcessingResult.compareAndSet(false, true)) {
                                handleScanSuccess(fallbackText)
                            }
                        }
                    }
                }
                .addOnCompleteListener { imageProxy.close() }
        }

        try {
            provider.unbindAll()
            camera = provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalysis)
        } catch (e: Exception) {
            Log.e("BaseQrScannerFeature", "bindToLifecycle failed", e)
            stopScanner(isCancelled = true)
        }
    }

    private fun decodeImageProxyWithZxing(imageProxy: ImageProxy): String? {
        return try {
            val plane = imageProxy.planes[0]
            val buffer = plane.buffer
            val remaining = buffer.remaining()
            val requiredSize = plane.rowStride * imageProxy.height
            val data = ByteArray(maxOf(remaining, requiredSize))
            buffer.get(data, 0, remaining)

            val source = PlanarYUVLuminanceSource(
                data,
                plane.rowStride,
                imageProxy.height,
                0,
                0,
                imageProxy.width,
                imageProxy.height,
                false
            )
            val binaryBitmap = BinaryBitmap(HybridBinarizer(source))
            MultiFormatReader().decode(binaryBitmap, zxingHints).text
        } catch (_: Exception) {
            null
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

    private fun handleScanSuccess(rawContent: String) {
        val cb = activeCallback
        val errCb = activeErrorCallback
        val expectedMode = activeExpectedMode
        val ctx = activeContext
        activity.runOnUiThread {
            stopScanner(isCancelled = false)
            dispatchDecodedResult(rawContent, cb, errCb, expectedMode, ctx)
        }
    }

    protected open fun dispatchDecodedResult(
        rawContent: String,
        successCallback: String?,
        errorCallback: String?,
        expectedMode: String,
        context: String
    ) {
        val result = decodeProviderQr(rawContent, expectedMode, context)
        if (result.isValid) {
            bridge.sendQrSuccessToWeb(successCallback, result.payloadJson)
        } else {
            bridge.sendQrErrorToWeb(errorCallback, result.errorMessage)
        }
    }

    fun handleGalleryResult(resultCode: Int, data: Intent?, callback: String?) {
        val cb = if (!callback.isNullOrBlank()) callback else (activeCallback ?: "processScannedData")
        if (resultCode != Activity.RESULT_OK || data?.data == null) {
            bridge.sendQrErrorToWeb(activeErrorCallback ?: "onScanFailed", "গ্যালারি থেকে কোনো ছবি নির্বাচন করা হয়নি")
            return
        }

        try {
            val imageUri = data.data!!
            val bitmap = loadNormalizedBitmapFromUri(imageUri)
            if (bitmap != null) {
                decodeFromBitmap(bitmap, cb)
            } else {
                bridge.sendQrErrorToWeb(activeErrorCallback ?: "onScanFailed", "ছবি লোড করা সম্ভব হয়নি")
            }
        } catch (e: Exception) {
            bridge.sendQrErrorToWeb(activeErrorCallback ?: "onScanFailed", e.localizedMessage ?: "গ্যালারি প্রসেসিং ত্রুটি")
        }
    }

    private fun loadNormalizedBitmapFromUri(uri: Uri): Bitmap? {
        return try {
            val boundsOptions = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            activity.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, boundsOptions)
            }

            val maxSide = max(boundsOptions.outWidth, boundsOptions.outHeight)
            var sampleSize = 1
            while (maxSide / sampleSize > 1800) {
                sampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply {
                inSampleSize = sampleSize
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val rawBitmap = activity.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, decodeOptions)
            } ?: return null

            var rotation = 0
            try {
                activity.contentResolver.openInputStream(uri)?.use { exifStream ->
                    val exif = ExifInterface(exifStream)
                    rotation = when (exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> 90
                        ExifInterface.ORIENTATION_ROTATE_180 -> 180
                        ExifInterface.ORIENTATION_ROTATE_270 -> 270
                        else -> 0
                    }
                }
            } catch (_: Exception) {}

            if (rotation != 0) {
                val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
                val rotated = Bitmap.createBitmap(rawBitmap, 0, 0, rawBitmap.width, rawBitmap.height, matrix, true)
                if (rotated !== rawBitmap && !rawBitmap.isRecycled) rawBitmap.recycle()
                rotated
            } else {
                rawBitmap
            }
        } catch (_: Exception) {
            null
        }
    }

    fun decodeFromBitmap(bitmap: Bitmap, callback: String?) {
        val cb = if (!callback.isNullOrBlank()) callback else (activeCallback ?: "processScannedData")
        val errCb = activeErrorCallback ?: "onScanFailed"
        val inputImage = InputImage.fromBitmap(bitmap, 0)
        mlKitScanner.process(inputImage)
            .addOnSuccessListener { barcodes ->
                val matched = barcodes.firstOrNull { !it.rawValue.isNullOrBlank() }
                if (matched != null) {
                    dispatchDecodedResult(matched.rawValue ?: "", cb, errCb, activeExpectedMode, activeContext)
                } else {
                    val zxingResult = decodeWithZxingFallback(bitmap)
                    if (zxingResult != null) {
                        dispatchDecodedResult(zxingResult, cb, errCb, activeExpectedMode, activeContext)
                    } else {
                        bridge.sendQrErrorToWeb(errCb, "ছবিতে কোনো সঠিক কিউআর বা বারকোড পাওয়া যায়নি")
                    }
                }
                if (!bitmap.isRecycled) bitmap.recycle()
            }
            .addOnFailureListener {
                val zxingResult = decodeWithZxingFallback(bitmap)
                if (zxingResult != null) {
                    dispatchDecodedResult(zxingResult, cb, errCb, activeExpectedMode, activeContext)
                } else {
                    bridge.sendQrErrorToWeb(errCb, "বারকোড প্রসেসিং ব্যর্থ হয়েছে")
                }
                if (!bitmap.isRecycled) bitmap.recycle()
            }
    }

    private fun decodeWithZxingFallback(bitmap: Bitmap): String? {
        return try {
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            val source = RGBLuminanceSource(width, height, pixels)
            val reader = MultiFormatReader()
            try {
                reader.decode(BinaryBitmap(HybridBinarizer(source)), zxingHints).text
            } catch (_: Exception) {
                reader.decode(BinaryBitmap(GlobalHistogramBinarizer(source)), zxingHints).text
            }
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
            bridge.sendQrErrorToWeb(activeErrorCallback, "স্ক্যানার ব্যবহারকারী দ্বারা বাতিল করা হয়েছে")
            activeCallback = null
            activeErrorCallback = null
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
        return ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    private inner class ScannerHudView(
        context: Context,
        private val guideText: String,
        private val accentColor: Int
    ) : View(context) {
        private val shadePaint = Paint().apply {
            color = Color.parseColor("#99000000")
            style = Paint.Style.FILL
        }
        private val cornerPaint = Paint().apply {
            color = accentColor
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
            textSize = 36f
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

            canvas.drawRect(0f, 0f, w, scanBox.top, shadePaint)
            canvas.drawRect(0f, scanBox.bottom, w, h, shadePaint)
            canvas.drawRect(0f, scanBox.top, scanBox.left, scanBox.bottom, shadePaint)
            canvas.drawRect(scanBox.right, scanBox.top, w, scanBox.bottom, shadePaint)

            val cornerLen = 50f
            canvas.drawLine(scanBox.left, scanBox.top, scanBox.left + cornerLen, scanBox.top, cornerPaint)
            canvas.drawLine(scanBox.left, scanBox.top, scanBox.left, scanBox.top + cornerLen, cornerPaint)
            canvas.drawLine(scanBox.right, scanBox.top, scanBox.right - cornerLen, scanBox.top, cornerPaint)
            canvas.drawLine(scanBox.right, scanBox.top, scanBox.right, scanBox.top + cornerLen, cornerPaint)
            canvas.drawLine(scanBox.left, scanBox.bottom, scanBox.left + cornerLen, scanBox.bottom, cornerPaint)
            canvas.drawLine(scanBox.left, scanBox.bottom, scanBox.left, scanBox.bottom - cornerLen, cornerPaint)
            canvas.drawLine(scanBox.right, scanBox.bottom, scanBox.right - cornerLen, scanBox.bottom, cornerPaint)
            canvas.drawLine(scanBox.right, scanBox.bottom, scanBox.right, scanBox.bottom - cornerLen, cornerPaint)

            if (laserY in scanBox.top..scanBox.bottom) {
                laserPaint.shader = LinearGradient(
                    scanBox.left, laserY, scanBox.right, laserY,
                    intArrayOf(Color.TRANSPARENT, accentColor, Color.TRANSPARENT),
                    null,
                    Shader.TileMode.CLAMP
                )
                canvas.drawLine(scanBox.left + 10f, laserY, scanBox.right - 10f, laserY, laserPaint)
            }

            canvas.drawText(guideText, w / 2f, scanBox.bottom + 70f, textPaint)
        }
    }
}
