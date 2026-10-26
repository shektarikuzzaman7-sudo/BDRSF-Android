package com.bdrsf.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import android.widget.Toast
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONObject
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

class CameraScannerController(
    private val activity: MainActivity
) {
    private var scannerLayout: FrameLayout? = null
    private var previewView: PreviewView? = null
    private var overlayView: DocumentOverlayView? = null
    private var manualCaptureBtn: Button? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    @Volatile
    var isScanningActive: Boolean = false
        private set

    private val isImageCaptured = AtomicBoolean(false)

    // ৩ডি লাইভনেস স্টেট মেশিন (0: Center, 1: Turn Left, 2: Turn Right, 3: Blink Eyes, 4: Done)
    @Volatile
    private var livenessStage: Int = 0

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

    private val liveFaceDetector: FaceDetector by lazy {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setMinFaceSize(0.30f)
            .build()
        FaceDetection.getClient(options)
    }

    fun startLiveCameraScanner(scanMode: String) {
        if (!hasCameraPermission()) {
            activity.requestAllRequiredPermissions()
            return
        }
        if (isScanningActive) {
            stopLiveCameraScanner()
        }

        isScanningActive = true
        isImageCaptured.set(false)
        livenessStage = 0

        scannerLayout = FrameLayout(activity).apply {
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

        scannerLayout?.addView(previewView)

        overlayView = DocumentOverlayView(activity, scanMode)
        scannerLayout?.addView(overlayView)

        val density = activity.resources.displayMetrics.density
        val topMarginPx = (42 * density).toInt()
        val rightMarginPx = (20 * density).toInt()
        val bottomMarginPx = (36 * density).toInt()

        val closeButton = Button(activity).apply {
            text = "✕ Close"
            setTextColor(Color.WHITE)
            textSize = 13f
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#B3000000"))
                cornerRadius = 24f * density
                setStroke((1.5f * density).toInt(), Color.parseColor("#55FFFFFF"))
            }
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                (40 * density).toInt()
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = topMarginPx
                rightMargin = rightMarginPx
            }
            setOnClickListener {
                stopLiveCameraScanner()
            }
        }
        scannerLayout?.addView(closeButton)

        if (scanMode == "nid_front" || scanMode == "nid_back" || scanMode == "passport") {
            manualCaptureBtn = Button(activity).apply {
                text = "📸 ছবি তুলুন / Capture"
                setTextColor(Color.BLACK)
                textSize = 14f
                isAllCaps = false
                background = GradientDrawable().apply {
                    setColor(Color.parseColor("#10B981"))
                    cornerRadius = 30f * density
                    setStroke((3 * density).toInt(), Color.WHITE)
                }
                layoutParams = FrameLayout.LayoutParams(
                    (210 * density).toInt(),
                    (52 * density).toInt()
                ).apply {
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    bottomMargin = bottomMarginPx
                }
                setOnClickListener {
                    if (isImageCaptured.compareAndSet(false, true)) {
                        isEnabled = false
                        text = "প্রসেসিং হচ্ছে..."
                        takeAutoCaptureAndCrop(scanMode)
                    }
                }
            }
            scannerLayout?.addView(manualCaptureBtn)
        }

        activity.mainLayout.addView(scannerLayout)

        val cameraProviderFuture = ProcessCameraProvider.getInstance(activity)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                bindCameraUseCases(scanMode)
            } catch (e: Exception) {
                Log.e("CameraScannerController", "Camera init failed", e)
                stopLiveCameraScanner()
            }
        }, ContextCompat.getMainExecutor(activity))
    }

    @androidx.annotation.OptIn(androidx.camera.core.ExperimentalGetImage::class)
    private fun bindCameraUseCases(scanMode: String) {
        val provider = cameraProvider ?: return

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView?.surfaceProvider)
        }

        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()

        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
            if (!isScanningActive || isImageCaptured.get()) {
                imageProxy.close()
                return@setAnalyzer
            }

            val mediaImage = imageProxy.image
            if (mediaImage == null) {
                imageProxy.close()
                return@setAnalyzer
            }

            val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

            when (scanMode) {
                "qr" -> {
                    barcodeScanner.process(inputImage)
                        .addOnSuccessListener { barcodes ->
                            val value = barcodes.firstOrNull { !it.rawValue.isNullOrEmpty() }?.rawValue
                            if (!value.isNullOrBlank() && isImageCaptured.compareAndSet(false, true)) {
                                activity.runOnUiThread {
                                    stopLiveCameraScanner()
                                    activity.webView.evaluateJavascript(
                                        "if(window.processScannedData) { window.processScannedData(${JSONObject.quote(value)}); }",
                                        null
                                    )
                                }
                            }
                        }
                        .addOnCompleteListener { imageProxy.close() }
                }

                "nid_front" -> {
                    textRecognizer.process(inputImage)
                        .addOnSuccessListener { visionText ->
                            val lineCount = visionText.textBlocks.sumOf { it.lines.size }
                            if (DocumentScanValidator.isNidFrontClearForCapture(visionText.text, lineCount)) {
                                if (isImageCaptured.compareAndSet(false, true)) {
                                    takeAutoCaptureAndCrop(scanMode)
                                }
                            }
                        }
                        .addOnCompleteListener { imageProxy.close() }
                }

                "nid_back" -> {
                    // PDF417 বারকোড এবং টেক্সট কঠোরভাবে স্ক্যান করা
                    barcodeScanner.process(inputImage)
                        .addOnSuccessListener { barcodes ->
                            val hasValidBarcode = barcodes.any { !it.rawValue.isNullOrEmpty() || (it.rawBytes != null && it.rawBytes!!.isNotEmpty()) }
                            if (hasValidBarcode) {
                                if (isImageCaptured.compareAndSet(false, true)) {
                                    takeAutoCaptureAndCrop(scanMode)
                                }
                                imageProxy.close()
                            } else {
                                textRecognizer.process(inputImage)
                                    .addOnSuccessListener { visionText ->
                                        val lineCount = visionText.textBlocks.sumOf { it.lines.size }
                                        if (DocumentScanValidator.isNidBackClearForCapture(visionText.text, lineCount, false)) {
                                            if (isImageCaptured.compareAndSet(false, true)) {
                                                takeAutoCaptureAndCrop(scanMode)
                                            }
                                        }
                                    }
                                    .addOnCompleteListener { imageProxy.close() }
                            }
                        }
                        .addOnFailureListener {
                            imageProxy.close()
                        }
                }

                "passport" -> {
                    textRecognizer.process(inputImage)
                        .addOnSuccessListener { visionText ->
                            val lineCount = visionText.textBlocks.sumOf { it.lines.size }
                            if (DocumentScanValidator.isPassportClearForCapture(visionText.text, lineCount)) {
                                if (isImageCaptured.compareAndSet(false, true)) {
                                    takeAutoCaptureAndCrop(scanMode)
                                }
                            }
                        }
                        .addOnCompleteListener { imageProxy.close() }
                }

                "face_liveness" -> {
                    liveFaceDetector.process(inputImage)
                        .addOnSuccessListener { faces ->
                            if (faces.isNullOrEmpty()) {
                                updateFaceOverlayPrompt("Position your face inside the oval frame", 10, "#F59E0B")
                                return@addOnSuccessListener
                            }

                            val face = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() } ?: faces[0]
                            val yaw = face.headEulerAngleY   // ডানে/বামে ঘোরার কোণ (-) বাম, (+) ডানে
                            val pitch = face.headEulerAngleX // উপরে/নিচে
                            val leftEyeOpen = face.leftEyeOpenProbability ?: 1.0f
                            val rightEyeOpen = face.rightEyeOpenProbability ?: 1.0f

                            // কঠোর লাইভনেস স্টেট মেশিন
                            when (livenessStage) {
                                0 -> {
                                    // ধাপ ১: সোজা তাকানো
                                    if (abs(yaw) < 10f && abs(pitch) < 12f) {
                                        livenessStage = 1
                                        updateFaceOverlayPrompt("Step 2/4: Slowly turn head LEFT ⬅️", 35, "#F59E0B", "pose-left")
                                    } else {
                                        updateFaceOverlayPrompt("Step 1/4: Look straight into camera", 15, "#F59E0B", "")
                                    }
                                }
                                1 -> {
                                    // ধাপ ২: বামে ঘোরানো (yaw < -18f)
                                    if (yaw < -18f) {
                                        livenessStage = 2
                                        updateFaceOverlayPrompt("Step 3/4: Slowly turn head RIGHT ➡️", 65, "#F59E0B", "pose-right")
                                    }
                                }
                                2 -> {
                                    // ধাপ ৩: ডানে ঘোরানো (yaw > 18f)
                                    if (yaw > 18f) {
                                        livenessStage = 3
                                        updateFaceOverlayPrompt("Step 4/4: Blink your eyes to capture 👁️", 88, "#10B981", "pose-blink")
                                    }
                                }
                                3 -> {
                                    // ধাপ ৪: চোখ পলক ফেলা (Blink) এবং সোজা থাকা
                                    val isStraight = abs(yaw) < 12f
                                    val isBlinking = leftEyeOpen < 0.38f || rightEyeOpen < 0.38f
                                    if (isStraight && isBlinking) {
                                        livenessStage = 4
                                        updateFaceOverlayPrompt("✔ Verified! Capturing biometric frame...", 100, "#10B981", "")
                                        if (isImageCaptured.compareAndSet(false, true)) {
                                            takeAutoCaptureAndCrop("face_liveness")
                                        }
                                    }
                                }
                            }
                        }
                        .addOnCompleteListener { imageProxy.close() }
                }

                else -> imageProxy.close()
            }
        }

        val cameraSelector = if (scanMode == "face_liveness") {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        try {
            provider.unbindAll()
            provider.bindToLifecycle(
                activity,
                cameraSelector,
                preview,
                imageCapture,
                imageAnalysis
            )
        } catch (e: Exception) {
            Log.e("CameraScannerController", "bindToLifecycle failed", e)
            stopLiveCameraScanner()
        }
    }

    private fun updateFaceOverlayPrompt(prompt: String, progress: Int, colorHex: String, poseClass: String = "") {
        activity.runOnUiThread {
            overlayView?.updateLivenessHud(prompt, progress, colorHex)
            activity.webView.evaluateJavascript(
                "if(window.onLivenessStepUpdate) { window.onLivenessStepUpdate(${JSONObject.quote(poseClass)}, ${JSONObject.quote(prompt)}, $progress); }",
                null
            )
        }
    }

    private fun takeAutoCaptureAndCrop(scanMode: String) {
        val photoFile = try {
            activity.createImageFile()
        } catch (e: Exception) {
            null
        }

        if (photoFile == null) {
            isImageCaptured.set(false)
            return
        }

        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
        imageCapture?.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(activity),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    stopLiveCameraScanner()
                    activity.bgProcessingExecutor.execute {
                        if (scanMode == "face_liveness") {
                            activity.processLiveSelfieAndVerify(photoFile)
                        } else {
                            DocumentUtils.cropCenterBoxFromImage(photoFile)
                            activity.processDeepDocumentFile(photoFile, scanMode)
                        }
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    stopLiveCameraScanner()
                    isImageCaptured.set(false)
                    activity.runOnUiThread {
                        Toast.makeText(
                            activity,
                            "Capture failed, please try again",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                }
            }
        )
    }

    fun stopLiveCameraScanner() {
        isScanningActive = false
        try {
            cameraProvider?.unbindAll()
        } catch (_: Exception) {
        }

        scannerLayout?.let {
            activity.mainLayout.removeView(it)
            scannerLayout = null
        }
        previewView = null
        overlayView = null
        manualCaptureBtn = null
    }

    fun destroy() {
        stopLiveCameraScanner()
        try {
            barcodeScanner.close()
            textRecognizer.close()
            liveFaceDetector.close()
            cameraExecutor.shutdown()
        } catch (_: Exception) {
        }
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            activity,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    inner class DocumentOverlayView(context: Context, private val mode: String) : View(context) {
        private var dynamicPrompt: String = "Position your face inside the oval"
        private var dynamicProgress: Int = 15
        private var ringColor: Int = Color.parseColor("#10B981")

        private val boxPaint = Paint().apply {
            color = Color.parseColor("#10B981")
            style = Paint.Style.STROKE
            strokeWidth = 8f
            isAntiAlias = true
        }

        private val meshPaint = Paint().apply {
            color = Color.parseColor("#6610B981")
            style = Paint.Style.STROKE
            strokeWidth = 3f
            isAntiAlias = true
            pathEffect = DashPathEffect(floatArrayOf(12f, 8f), 0f)
        }

        // মুখের বৃত্তের বাইরের অংশ রঙিন/ডার্ক মাস্ক দিয়ে ঢেকে দেওয়ার শেড
        private val shadePaint = Paint().apply {
            color = Color.parseColor("#CC01120D")
            style = Paint.Style.FILL
        }

        private val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 38f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
            setShadowLayer(8f, 0f, 0f, Color.BLACK)
        }

        private val subTextPaint = Paint().apply {
            color = Color.parseColor("#10B981")
            textSize = 30f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
            setShadowLayer(6f, 0f, 0f, Color.BLACK)
        }

        fun updateLivenessHud(prompt: String, progress: Int, colorHex: String) {
            dynamicPrompt = prompt
            dynamicProgress = progress
            try {
                ringColor = Color.parseColor(colorHex)
            } catch (_: Exception) {}
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()

            if (mode == "face_liveness") {
                // বড় এবং পরিষ্কার ফেস ওভাল ফ্রেম
                val ovalW = w * 0.78f
                val ovalH = ovalW * 1.30f
                val left = (w - ovalW) / 2f
                val top = (h - ovalH) / 2f - (h * 0.03f)
                val right = left + ovalW
                val bottom = top + ovalH
                val ovalRect = RectF(left, top, right, bottom)

                // বাইরের অংশ রঙিন মাস্ক দিয়ে ঢেকে দেওয়া (Vignette Mask)
                canvas.drawRect(0f, 0f, w, top, shadePaint)
                canvas.drawRect(0f, bottom, w, h, shadePaint)
                canvas.drawRect(0f, top, left, bottom, shadePaint)
                canvas.drawRect(right, top, w, bottom, shadePaint)

                boxPaint.color = ringColor
                boxPaint.strokeWidth = 12f
                canvas.drawOval(ovalRect, boxPaint)

                // গাইড লাইন
                canvas.drawLine(w / 2f, top + 40f, w / 2f, bottom - 40f, meshPaint)
                canvas.drawLine(left + 40f, (top + bottom) / 2f, right - 40f, (top + bottom) / 2f, meshPaint)

                canvas.drawText(dynamicPrompt, w / 2f, bottom + 65f, textPaint)
                canvas.drawText("3D BIOMETRIC LIVENESS ($dynamicProgress%)", w / 2f, top - 35f, subTextPaint)
                return
            }

            val boxWidth = w * 0.90f
            val boxHeight = boxWidth * 0.63f
            val left = (w - boxWidth) / 2f
            val top = (h - boxHeight) / 2f - (h * 0.04f)
            val right = left + boxWidth
            val bottom = top + boxHeight

            canvas.drawRect(0f, 0f, w, top, shadePaint)
            canvas.drawRect(0f, bottom, w, h, shadePaint)
            canvas.drawRect(0f, top, left, bottom, shadePaint)
            canvas.drawRect(right, top, w, bottom, shadePaint)

            canvas.drawRoundRect(RectF(left, top, right, bottom), 24f, 24f, boxPaint)

            val guideText = when (mode) {
                "nid_front" -> "Keep NID front side inside the frame"
                "nid_back" -> "Keep NID back side inside the frame"
                "passport" -> "Keep passport data page inside the frame"
                else -> "Keep document clearly inside the frame"
            }

            canvas.drawText(guideText, w / 2f, top - 45f, textPaint)
            canvas.drawText("Auto-scanning or tap shutter below", w / 2f, bottom + 55f, subTextPaint)
        }
    }
}
