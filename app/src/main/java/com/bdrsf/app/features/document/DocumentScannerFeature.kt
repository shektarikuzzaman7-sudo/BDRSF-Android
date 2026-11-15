// File: app/src/main/java/com/bdrsf/app/features/document/DocumentScannerFeature.kt
package com.bdrsf.app.features.document

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Shader
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
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
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * BDRSF সার্বজনীন ডকুমেন্ট, পাসপোর্ট এবং লাইভ ফেস ভেরিফিকেশন ক্যামেরা ইঞ্জিন।
 * এটি CameraX এবং ML Kit দ্বারা নিয়ন্ত্রিত সম্পূর্ণ রিয়েল-টাইম লাইভনেস ও ডকুমেন্ট ফ্রেম ডিটেকশন নিশ্চিত করে।
 */
class DocumentScannerFeature(
    private val activity: Activity,
    private val rootContainer: ViewGroup,
    private val bridge: AndroidBridge,
    private val onDocumentCapturedListener: (capturedFile: File, scanMode: String, callback: String?) -> Unit
) {

    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var previewView: PreviewView? = null
    private var imageCapture: ImageCapture? = null
    private var scannerOverlayLayout: FrameLayout? = null
    private var hudOverlayView: View? = null

    private var activeScanMode: String = "card"
    private var activeCallback: String? = null
    private var isTorchOn: Boolean = false
    private val isCapturing = AtomicBoolean(false)

    private var stableFrameCount: Int = 0
    private val requiredStableFrames: Int = 6

    @Volatile
    var isScanningActive: Boolean = false
        private set

    private val faceDetector: FaceDetector by lazy {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .build()
        FaceDetection.getClient(options)
    }

    private val textRecognizer: TextRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private fun resolveNormalizedScanMode(payload: JSONObject): String {
        val rawMode = payload.optString("mode", "")
            .ifBlank { payload.optString("documentType", "") }
            .ifBlank { payload.optString("docType", "") }
            .ifBlank { payload.optString("slot", "card") }
            .trim()
            .lowercase(Locale.ROOT)

        return when {
            rawMode.contains("pass") -> "passport"
            rawMode.contains("face") || rawMode.contains("selfie") || rawMode.contains("live") -> "face_liveness"
            rawMode.contains("back") -> "nid_back"
            rawMode.contains("nid") || rawMode.contains("front") || rawMode.contains("card") -> "nid_front"
            else -> rawMode
        }
    }

    fun startScanner(payload: JSONObject, callback: String?) {
        if (!hasCameraPermission()) {
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.CAMERA),
                2001
            )
            return
        }

        if (isScanningActive) return

        activity.runOnUiThread {
            isScanningActive = true
            isCapturing.set(false)
            stableFrameCount = 0
            isTorchOn = false
            activeCallback = callback
            activeScanMode = resolveNormalizedScanMode(payload)

            buildScannerLayout()
            bindCameraProvider()
        }
    }

    private fun buildScannerLayout() {
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

        hudOverlayView = if (isFaceMode()) {
            FaceLivenessHudView(activity)
        } else {
            DocumentCardHudView(activity, activeScanMode)
        }
        scannerOverlayLayout?.addView(hudOverlayView)

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

        if (!isFaceMode()) {
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
        }

        scannerOverlayLayout?.addView(topControlsLayout)

        val manualCaptureButton = TextView(activity).apply {
            text = "● ক্যাপচার"
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding((24 * density).toInt(), (10 * density).toInt(), (24 * density).toInt(), (10 * density).toInt())
            setBackgroundColor(Color.parseColor("#9900E676"))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = (30 * density).toInt()
            }
            setOnClickListener {
                if (isScanningActive && isCapturing.compareAndSet(false, true)) {
                    triggerTakePicture()
                }
            }
        }
        scannerOverlayLayout?.addView(manualCaptureButton)

        rootContainer.addView(scannerOverlayLayout)
    }

    private fun bindCameraProvider() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(activity)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()
                setupCameraPipelines()
            } catch (e: Exception) {
                Log.e("DocScannerFeature", "Camera provider error", e)
                stopScanner(isCancelled = true)
            }
        }, ContextCompat.getMainExecutor(activity))
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun setupCameraPipelines() {
        val provider = cameraProvider ?: return
        val lifecycleOwner = activity as? LifecycleOwner ?: return

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView?.surfaceProvider)
        }

        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()

        val imageAnalysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        val cameraSelector = if (isFaceMode()) {
            CameraSelector.DEFAULT_FRONT_CAMERA
        } else {
            CameraSelector.DEFAULT_BACK_CAMERA
        }

        imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
            if (!isScanningActive || isCapturing.get()) {
                imageProxy.close()
                return@setAnalyzer
            }

            val mediaImage = imageProxy.image
            if (mediaImage == null) {
                imageProxy.close()
                return@setAnalyzer
            }

            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            val inputImage = InputImage.fromMediaImage(mediaImage, rotationDegrees)

            if (isFaceMode()) {
                analyzeFaceLiveness(inputImage, imageProxy)
            } else {
                analyzeDocumentFrame(inputImage, imageProxy)
            }
        }

        try {
            provider.unbindAll()
            camera = provider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageCapture,
                imageAnalysis
            )
        } catch (e: Exception) {
            Log.e("DocScannerFeature", "Lifecycle binding failed", e)
            stopScanner(isCancelled = true)
        }
    }

    private fun analyzeFaceLiveness(inputImage: InputImage, imageProxy: androidx.camera.core.ImageProxy) {
        faceDetector.process(inputImage)
            .addOnSuccessListener { faces ->
                if (!isScanningActive || isCapturing.get()) return@addOnSuccessListener

                val faceView = hudOverlayView as? FaceLivenessHudView
                if (faces.isEmpty()) {
                    stableFrameCount = 0
                    faceView?.updateGuidance("ফ্রেমের ভেতরে আপনার মুখমণ্ডল সোজা রাখুন", Color.WHITE, 0f)
                    return@addOnSuccessListener
                }

                val face = faces[0]
                val yaw = face.headEulerAngleY
                val pitch = face.headEulerAngleX
                val leftEyeOpen = face.leftEyeOpenProbability ?: -1f
                val rightEyeOpen = face.rightEyeOpenProbability ?: -1f

                when {
                    yaw > 12f -> {
                        stableFrameCount = 0
                        faceView?.updateGuidance("মাথা সামান্য বামে ঘোরান", Color.YELLOW, 0.2f)
                    }
                    yaw < -12f -> {
                        stableFrameCount = 0
                        faceView?.updateGuidance("মাথা সামান্য ডানে ঘোরান", Color.YELLOW, 0.2f)
                    }
                    pitch > 14f -> {
                        stableFrameCount = 0
                        faceView?.updateGuidance("মাথা সামান্য নিচে নামান", Color.YELLOW, 0.3f)
                    }
                    pitch < -12f -> {
                        stableFrameCount = 0
                        faceView?.updateGuidance("মাথা সামান্য উপরে তুলুন", Color.YELLOW, 0.3f)
                    }
                    leftEyeOpen in 0f..0.55f || rightEyeOpen in 0f..0.55f -> {
                        stableFrameCount = 0
                        faceView?.updateGuidance("চোখ পুরোপুরি খোলা রাখুন", Color.parseColor("#FFA500"), 0.5f)
                    }
                    else -> {
                        stableFrameCount++
                        val progress = (stableFrameCount.toFloat() / requiredStableFrames).coerceIn(0f, 1f)
                        faceView?.updateGuidance("স্থির থাকুন... ছবি তোলা হচ্ছে", Color.parseColor("#00E676"), progress)

                        if (stableFrameCount >= requiredStableFrames) {
                            if (isCapturing.compareAndSet(false, true)) {
                                triggerTakePicture()
                            }
                        }
                    }
                }
            }
            .addOnFailureListener {
                stableFrameCount = 0
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    private fun analyzeDocumentFrame(inputImage: InputImage, imageProxy: androidx.camera.core.ImageProxy) {
        textRecognizer.process(inputImage)
            .addOnSuccessListener { visionText ->
                if (!isScanningActive || isCapturing.get()) return@addOnSuccessListener

                val cardView = hudOverlayView as? DocumentCardHudView
                val rawText = visionText.text
                val totalLines = visionText.textBlocks.sumOf { it.lines.size }

                if (activeScanMode == "passport") {
                    val hasMrzOrPassport = rawText.contains("P<", ignoreCase = true) ||
                        rawText.contains("PASSPORT", ignoreCase = true) ||
                        rawText.contains("BANGLADESH", ignoreCase = true)

                    if (hasMrzOrPassport && totalLines >= 4) {
                        stableFrameCount++
                        cardView?.setCardDetected(true)
                        if (stableFrameCount >= 4) {
                            if (isCapturing.compareAndSet(false, true)) {
                                triggerTakePicture()
                            }
                        }
                    } else {
                        stableFrameCount = 0
                        cardView?.setCardDetected(false)
                    }
                } else {
                    if (totalLines >= 3) {
                        stableFrameCount++
                        cardView?.setCardDetected(true)
                        if (stableFrameCount >= 5) {
                            if (isCapturing.compareAndSet(false, true)) {
                                triggerTakePicture()
                            }
                        }
                    } else {
                        stableFrameCount = 0
                        cardView?.setCardDetected(false)
                    }
                }
            }
            .addOnFailureListener {
                stableFrameCount = 0
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    private fun triggerTakePicture() {
        val photoFile = try {
            createTempScanFile()
        } catch (e: Exception) {
            isCapturing.set(false)
            return
        }

        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        imageCapture?.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(activity),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    val callback = activeCallback
                    val mode = activeScanMode

                    stopScanner(isCancelled = false)
                    onDocumentCapturedListener(photoFile, mode, callback)
                }

                override fun onError(exception: ImageCaptureException) {
                    isCapturing.set(false)
                    Toast.makeText(activity, "ক্যাপচার ব্যর্থ হয়েছে: ${exception.message}", Toast.LENGTH_SHORT).show()
                }
            }
        )
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

    fun stopScanner(isCancelled: Boolean = false) {
        isScanningActive = false
        (hudOverlayView as? DocumentCardHudView)?.stopAnimation()

        try {
            cameraProvider?.unbindAll()
        } catch (_: Exception) {}

        scannerOverlayLayout?.let {
            rootContainer.removeView(it)
            scannerOverlayLayout = null
        }
        previewView = null
        hudOverlayView = null
        camera = null
        imageCapture = null

        if (isCancelled && activeCallback != null) {
            bridge.sendResponseToWeb(
                response = BridgeResponse(
                    action = Actions.START_KYC,
                    status = Status.CANCELLED,
                    message = "Scanning cancelled by user"
                ),
                callbackFunction = activeCallback
            )
            activeCallback = null
        }
    }

    fun destroy() {
        stopScanner(isCancelled = false)
        try {
            faceDetector.close()
            textRecognizer.close()
            cameraExecutor.shutdown()
        } catch (_: Exception) {}
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            activity,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun isFaceMode(): Boolean {
        return activeScanMode == "face_liveness" || activeScanMode == "selfie"
    }

    @Throws(IOException::class)
    private fun createTempScanFile(): File {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "SCAN_${activeScanMode.uppercase(Locale.ROOT)}_$timestamp"
        return File.createTempFile(fileName, ".jpg", activity.cacheDir)
    }

    private inner class DocumentCardHudView(context: Context, private val mode: String) : View(context) {

        private val shadePaint = Paint().apply {
            color = Color.parseColor("#B3000000")
            style = Paint.Style.FILL
        }

        private val borderPaint = Paint().apply {
            color = Color.parseColor("#00E676")
            style = Paint.Style.STROKE
            strokeWidth = 6f
            isAntiAlias = true
        }

        private val cornerPaint = Paint().apply {
            color = Color.parseColor("#00E676")
            style = Paint.Style.STROKE
            strokeWidth = 12f
            strokeCap = Paint.Cap.ROUND
            isAntiAlias = true
        }

        private val mrzLinePaint = Paint().apply {
            color = Color.parseColor("#FFFF00")
            style = Paint.Style.STROKE
            strokeWidth = 3f
            pathEffect = DashPathEffect(floatArrayOf(15f, 10f), 0f)
            isAntiAlias = true
        }

        private val mrzTextPaint = Paint().apply {
            color = Color.parseColor("#FFD700")
            textSize = 30f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
            setShadowLayer(6f, 0f, 0f, Color.BLACK)
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

        private val cardBox = RectF()
        private var laserY = 0f
        private var laserAnimator: ValueAnimator? = null

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            val boxWidth = w * 0.92f
            val boxHeight = if (mode == "passport") {
                (boxWidth / 1.38f).coerceAtMost(h * 0.56f)
            } else {
                boxWidth / 1.58f
            }

            val left = (w - boxWidth) / 2f
            val top = (h - boxHeight) / 2f - 30f
            cardBox.set(left, top, left + boxWidth, top + boxHeight)

            startLaserAnimation()
        }

        private fun startLaserAnimation() {
            laserAnimator?.cancel()
            laserAnimator = ValueAnimator.ofFloat(cardBox.top, cardBox.bottom).apply {
                duration = 2200
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

        fun setCardDetected(detected: Boolean) {
            val color = if (detected) Color.parseColor("#00E676") else Color.parseColor("#FFD700")
            borderPaint.color = color
            cornerPaint.color = color
            postInvalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()

            canvas.drawRect(0f, 0f, w, cardBox.top, shadePaint)
            canvas.drawRect(0f, cardBox.bottom, w, h, shadePaint)
            canvas.drawRect(0f, cardBox.top, cardBox.left, cardBox.bottom, shadePaint)
            canvas.drawRect(cardBox.right, cardBox.top, w, cardBox.bottom, shadePaint)

            canvas.drawRoundRect(cardBox, 20f, 20f, borderPaint)

            val cl = 55f
            canvas.drawLine(cardBox.left, cardBox.top, cardBox.left + cl, cardBox.top, cornerPaint)
            canvas.drawLine(cardBox.left, cardBox.top, cardBox.left, cardBox.top + cl, cornerPaint)
            canvas.drawLine(cardBox.right, cardBox.top, cardBox.right - cl, cardBox.top, cornerPaint)
            canvas.drawLine(cardBox.right, cardBox.top, cardBox.right, cardBox.top + cl, cornerPaint)
            canvas.drawLine(cardBox.left, cardBox.bottom, cardBox.left + cl, cardBox.bottom, cornerPaint)
            canvas.drawLine(cardBox.left, cardBox.bottom, cardBox.left, cardBox.bottom - cl, cornerPaint)
            canvas.drawLine(cardBox.right, cardBox.bottom, cardBox.right - cl, cardBox.bottom, cornerPaint)
            canvas.drawLine(cardBox.right, cardBox.bottom, cardBox.right, cardBox.bottom - cl, cornerPaint)

            if (mode == "passport") {
                val mrzY = cardBox.bottom - (cardBox.height() * 0.23f)
                canvas.drawLine(cardBox.left + 20f, mrzY, cardBox.right - 20f, mrzY, mrzLinePaint)
                canvas.drawText("নিচের অংশে পাসপোর্টের MRZ (P<BGD) কোড রাখুন", w / 2f, mrzY - 14f, mrzTextPaint)
            }

            if (laserY in cardBox.top..cardBox.bottom) {
                laserPaint.shader = LinearGradient(
                    cardBox.left, laserY, cardBox.right, laserY,
                    intArrayOf(Color.TRANSPARENT, Color.parseColor("#00E676"), Color.TRANSPARENT),
                    null,
                    Shader.TileMode.CLAMP
                )
                canvas.drawLine(cardBox.left + 15f, laserY, cardBox.right - 15f, laserY, laserPaint)
            }

            val guide = when (mode) {
                "nid_front" -> "এনআইডি কার্ডের সামনের অংশ ফ্রেমে সোজা রাখুন"
                "nid_back" -> "এনআইডি কার্ডের পেছনের অংশ ফ্রেমে রাখুন"
                "passport" -> "পাসপোর্টের পুরো ডাটা পেজটি বড় ফ্রেমে রাখুন"
                else -> "ডকুমেন্টটি ফ্রেমের ভেতরে সোজা রাখুন"
            }
            canvas.drawText(guide, w / 2f, cardBox.bottom + 65f, textPaint)
        }
    }

    private inner class FaceLivenessHudView(context: Context) : View(context) {

        private val clearPaint = Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            isAntiAlias = true
        }

        private val backgroundPaint = Paint().apply {
            color = Color.parseColor("#CC000000")
            style = Paint.Style.FILL
        }

        private val ringBackgroundPaint = Paint().apply {
            color = Color.parseColor("#44FFFFFF")
            style = Paint.Style.STROKE
            strokeWidth = 8f
            isAntiAlias = true
        }

        private val ringProgressPaint = Paint().apply {
            color = Color.parseColor("#00E676")
            style = Paint.Style.STROKE
            strokeWidth = 10f
            strokeCap = Paint.Cap.ROUND
            isAntiAlias = true
        }

        private val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 42f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
            setShadowLayer(8f, 0f, 0f, Color.BLACK)
        }

        private val ovalBounds = RectF()
        private var currentGuidance: String = "ফ্রেমের ভেতরে আপনার মুখমণ্ডল সোজা রাখুন"
        private var progressPercent: Float = 0f

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            setLayerType(LAYER_TYPE_SOFTWARE, null)

            val ovalWidth = w * 0.75f
            val ovalHeight = ovalWidth * 1.35f
            val left = (w - ovalWidth) / 2f
            val top = (h - ovalHeight) / 2f - 40f
            ovalBounds.set(left, top, left + ovalWidth, top + ovalHeight)
        }

        fun updateGuidance(message: String, textColor: Int, progress: Float) {
            currentGuidance = message
            textPaint.color = textColor
            progressPercent = progress
            postInvalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()

            canvas.drawRect(0f, 0f, w, h, backgroundPaint)
            canvas.drawOval(ovalBounds, clearPaint)
            canvas.drawOval(ovalBounds, ringBackgroundPaint)

            if (progressPercent > 0f) {
                val sweep = progressPercent * 360f
                canvas.drawArc(ovalBounds, -90f, sweep, false, ringProgressPaint)
            }

            canvas.drawText(currentGuidance, w / 2f, ovalBounds.bottom + 80f, textPaint)
        }
    }
}
