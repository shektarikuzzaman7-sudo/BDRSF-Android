package com.bdrsf.app.features.kyc

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
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
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * BDRSF সর্বোচ্চ লেভেলের Google ML Kit রিয়েল হিউম্যান ফেস ডিটেকশন ও HD ক্যাপচার ক্যামেরা:
 * - PERFORMANCE_MODE_ACCURATE + LANDMARK_MODE_ALL + CONTOUR_MODE_ALL + CLASSIFICATION_MODE_ALL + Face Tracking।
 * - শুধুমাত্র ক্যামেরা অন হবে (কোনো গ্যালারি অপশন নেই)।
 * - রিয়েল হিউম্যান মুখমণ্ডল, দুই চোখ খোলা, ফেস ল্যান্ডমার্ক এবং ন্যাচারাল মাইক্রো-মুভমেন্ট যাচাই করে
 *   CAPTURE_MODE_MAXIMIZE_QUALITY দিয়ে হাই-রেজোলিউশন ফেস ইমেজ ক্যাপচার করে।
 */
class HumanFaceLivenessCameraFeature(
    private val activity: Activity,
    private val rootContainer: ViewGroup,
    private val bridge: AndroidBridge,
    private val webView: WebView,
    private val referenceCroppedFaceProvider: () -> String
) {

    companion object {
        private const val RC_CAMERA = 2103
        private const val REQUIRED_LIVENESS_FRAMES = 7
    }

    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var previewView: PreviewView? = null
    private var imageCapture: ImageCapture? = null
    private var overlayLayout: FrameLayout? = null
    private var hudView: RealFaceOvalHudView? = null

    private var activeCallback: String? = null
    private val isCapturing = AtomicBoolean(false)
    private var validLiveFrames = 0
    private var trackedFaceId: Int? = null
    private var observedNaturalMovement = false
    private var initialYaw = 0f
    private var initialPitch = 0f

    @Volatile
    private var destroyed = false

    @Volatile
    var isScanningActive: Boolean = false
        private set

    private val accurateFaceDetector by lazy {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(0.22f)
            .enableTracking()
            .build()
        FaceDetection.getClient(options)
    }

    fun startCamera(callback: String?) {
        if (destroyed) return
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.CAMERA), RC_CAMERA)
            return
        }
        if (isScanningActive) return

        activity.runOnUiThread {
            if (destroyed || isScanningActive) return@runOnUiThread
            isScanningActive = true
            isCapturing.set(false)
            validLiveFrames = 0
            trackedFaceId = null
            observedNaturalMovement = false
            activeCallback = callback

            buildCameraUi()
            bindFrontCamera()
        }
    }

    private fun buildCameraUi() {
        val density = activity.resources.displayMetrics.density
        overlayLayout = FrameLayout(activity).apply {
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
        overlayLayout?.addView(previewView)

        hudView = RealFaceOvalHudView(activity)
        overlayLayout?.addView(hudView)

        val closeBtn = TextView(activity).apply {
            text = "✕ বাতিল"
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding((14 * density).toInt(), (8 * density).toInt(), (14 * density).toInt(), (8 * density).toInt())
            setBackgroundColor(Color.parseColor("#88000000"))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                topMargin = (34 * density).toInt()
                leftMargin = (18 * density).toInt()
            }
            setOnClickListener { stopScanner(isCancelled = true) }
        }
        overlayLayout?.addView(closeBtn)

        rootContainer.addView(overlayLayout)
    }

    private fun bindFrontCamera() {
        val future = ProcessCameraProvider.getInstance(activity)
        future.addListener({
            if (destroyed || !isScanningActive) return@addListener
            try {
                cameraProvider = future.get()
                setupPipelines()
            } catch (_: Exception) {
                stopScanner(isCancelled = true)
            }
        }, ContextCompat.getMainExecutor(activity))
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun setupPipelines() {
        val provider = cameraProvider ?: return
        val owner = activity as? LifecycleOwner ?: return

        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView?.surfaceProvider)
        }

        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()

        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()

        analysis.setAnalyzer(cameraExecutor) { proxy ->
            if (!isScanningActive || isCapturing.get() || destroyed) {
                proxy.close()
                return@setAnalyzer
            }
            val mediaImage = proxy.image
            if (mediaImage == null) {
                proxy.close()
                return@setAnalyzer
            }

            val input = InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
            accurateFaceDetector.process(input)
                .addOnSuccessListener { faces ->
                    if (!isScanningActive || isCapturing.get()) return@addOnSuccessListener
                    if (faces.isEmpty()) {
                        resetTracking("ওভাল ফ্রেমের ভেতরে আপনার মুখমণ্ডল রাখুন", Color.WHITE, 0f)
                        return@addOnSuccessListener
                    }
                    if (faces.size > 1) {
                        resetTracking("ফ্রেমে শুধুমাত্র একজন ব্যক্তির মুখ রাখুন", Color.YELLOW, 0f)
                        return@addOnSuccessListener
                    }

                    val face = faces[0]
                    val currentId = face.trackingId
                    if (trackedFaceId == null || trackedFaceId != currentId) {
                        trackedFaceId = currentId
                        validLiveFrames = 0
                        observedNaturalMovement = false
                        initialYaw = face.headEulerAngleY
                        initialPitch = face.headEulerAngleX
                    }

                    // রিয়েল হিউম্যান ফেস ল্যান্ডমার্ক যাচাই (চোখ, নাক, মুখ)
                    val hasLandmarks = face.getLandmark(FaceLandmark.LEFT_EYE) != null &&
                        face.getLandmark(FaceLandmark.RIGHT_EYE) != null &&
                        face.getLandmark(FaceLandmark.NOSE_BASE) != null &&
                        face.getLandmark(FaceLandmark.MOUTH_BOTTOM) != null

                    if (!hasLandmarks) {
                        resetTracking("পুরো মুখমণ্ডল স্পষ্টভাবে ফ্রেমে রাখুন", Color.YELLOW, 0.1f)
                        return@addOnSuccessListener
                    }

                    val yaw = face.headEulerAngleY
                    val pitch = face.headEulerAngleX
                    val leftEye = face.leftEyeOpenProbability ?: -1f
                    val rightEye = face.rightEyeOpenProbability ?: -1f

                    // মাইক্রো-মুভমেন্ট ট্র্যাকিং (স্থির ছবি/স্ক্রিন প্রতিরোধ)
                    if (abs(yaw - initialYaw) >= 1.2f || abs(pitch - initialPitch) >= 1.2f) {
                        observedNaturalMovement = true
                    }

                    when {
                        abs(yaw) > 13f || abs(pitch) > 14f -> {
                            validLiveFrames = 0
                            hudView?.updateState("ক্যামেরার দিকে সোজা তাকান", Color.YELLOW, 0.25f)
                        }
                        leftEye in 0f..0.60f || rightEye in 0f..0.60f -> {
                            observedNaturalMovement = true // স্বাভাবিক চোখের পলক
                            hudView?.updateState("দুই চোখ খোলা রেখে সোজা তাকান", Color.parseColor("#FFA500"), 0.50f)
                        }
                        else -> {
                            validLiveFrames++
                            val progress = (validLiveFrames.toFloat() / REQUIRED_LIVENESS_FRAMES).coerceIn(0f, 1f)
                            hudView?.updateState(
                                "রিয়েল ফেস শনাক্ত হয়েছে — HD ছবি তোলা হচ্ছে...",
                                Color.parseColor("#00E676"),
                                progress
                            )
                            if (validLiveFrames >= REQUIRED_LIVENESS_FRAMES &&
                                (observedNaturalMovement || validLiveFrames >= REQUIRED_LIVENESS_FRAMES + 2) &&
                                isCapturing.compareAndSet(false, true)
                            ) {
                                captureHighResHumanFace()
                            }
                        }
                    }
                }
                .addOnCompleteListener { proxy.close() }
        }

        try {
            provider.unbindAll()
            provider.bindToLifecycle(owner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, imageCapture, analysis)
        } catch (_: Exception) {
            stopScanner(isCancelled = true)
        }
    }

    private fun resetTracking(msg: String, color: Int, progress: Float) {
        validLiveFrames = 0
        trackedFaceId = null
        observedNaturalMovement = false
        hudView?.updateState(msg, color, progress)
    }

    private fun captureHighResHumanFace() {
        val capture = imageCapture ?: run {
            isCapturing.set(false)
            return
        }
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outFile = File.createTempFile("BDRSF_HD_FACE_$ts", ".jpg", activity.cacheDir)
        val options = ImageCapture.OutputFileOptions.Builder(outFile).build()

        capture.takePicture(
            options,
            ContextCompat.getMainExecutor(activity),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    val cb = activeCallback
                    stopScanner(isCancelled = false)
                    finalizeCapturedFace(outFile, cb)
                }

                override fun onError(exception: ImageCaptureException) {
                    isCapturing.set(false)
                    Toast.makeText(activity, "HD ফেস ক্যাপচার ব্যর্থ হয়েছে: ${exception.message}", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun finalizeCapturedFace(file: File, callback: String?) {
        cameraExecutor.execute {
            var bmp: Bitmap? = null
            try {
                KycImageUtils.fixExifOrientationInPlace(file)
                bmp = KycImageUtils.decodeSampledBitmap(file, 1920)
                if (bmp == null || bmp.isRecycled) {
                    dispatchFaceFailure("হাই-রেজোলিউশন ফেস প্রসেস করা যায়নি।", callback)
                    return@execute
                }

                val hdFaceDataUri = KycImageUtils.encodeBitmapToDataUri(bmp, 94)
                val croppedDocFace = referenceCroppedFaceProvider()
                val meta = JSONObject().apply {
                    put("isLiveHuman", true)
                    put("quality", "HIGH_RESOLUTION")
                    put("croppedFaceBase64", croppedDocFace)
                }

                activity.runOnUiThread {
                    if (destroyed) return@runOnUiThread
                    Toast.makeText(activity, "✔ হাই-রেজোলিউশন ফেস ক্যাপচার সম্পন্ন", Toast.LENGTH_SHORT).show()

                    webView.evaluateJavascript(
                        "if(window.handleHdFaceDetected) { window.handleHdFaceDetected(true, ${JSONObject.quote(hdFaceDataUri)}, ${meta}); }",
                        null
                    )
                    webView.evaluateJavascript(
                        "if(window.onFaceLivenessResult) { window.onFaceLivenessResult(true, ${JSONObject.quote(hdFaceDataUri)}, ${meta}); }",
                        null
                    )

                    bridge.sendResponseToWeb(
                        BridgeResponse(Actions.START_KYC, Status.SUCCESS, data = meta, message = "HD Face Captured"),
                        callback
                    )
                }
            } catch (e: Exception) {
                dispatchFaceFailure(e.localizedMessage ?: "ফেস ক্যাপচার ত্রুটি", callback)
            } finally {
                if (bmp != null && !bmp.isRecycled) bmp.recycle()
                try {
                    file.delete()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun dispatchFaceFailure(message: String, callback: String?) {
        activity.runOnUiThread {
            if (destroyed) return@runOnUiThread
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
            webView.evaluateJavascript(
                "if(window.handleHdFaceDetected) { window.handleHdFaceDetected(false, '', null); }",
                null
            )
            bridge.sendResponseToWeb(
                BridgeResponse(Actions.START_KYC, Status.ERROR, message = message),
                callback
            )
        }
    }

    fun stopScanner(isCancelled: Boolean = false) {
        isScanningActive = false
        isCapturing.set(false)
        try {
            cameraProvider?.unbindAll()
        } catch (_: Exception) {
        }
        overlayLayout?.let {
            rootContainer.removeView(it)
            overlayLayout = null
        }
        previewView = null
        hudView = null

        val cb = activeCallback
        activeCallback = null
        if (isCancelled && cb != null) {
            bridge.sendResponseToWeb(
                BridgeResponse(Actions.START_KYC, Status.CANCELLED, message = "Face camera cancelled"),
                cb
            )
        }
    }

    fun destroy() {
        destroyed = true
        stopScanner(isCancelled = false)
        try {
            accurateFaceDetector.close()
            cameraExecutor.shutdownNow()
        } catch (_: Exception) {
        }
    }

    private inner class RealFaceOvalHudView(context: Context) : View(context) {
        private val clearPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR); isAntiAlias = true }
        private val shadePaint = Paint().apply { color = Color.parseColor("#CC000000"); style = Paint.Style.FILL }
        private val ringBgPaint = Paint().apply { color = Color.parseColor("#44FFFFFF"); style = Paint.Style.STROKE; strokeWidth = 8f; isAntiAlias = true }
        private val ringProgressPaint = Paint().apply { color = Color.parseColor("#00E676"); style = Paint.Style.STROKE; strokeWidth = 11f; strokeCap = Paint.Cap.ROUND; isAntiAlias = true }
        private val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 38f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
            setShadowLayer(8f, 0f, 0f, Color.BLACK)
        }

        private val ovalBounds = RectF()
        private var guideMsg = "ওভাল ফ্রেমের ভেতরে আপনার মুখমণ্ডল রাখুন"
        private var progress = 0f

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            setLayerType(LAYER_TYPE_SOFTWARE, null)
            val ovalW = w * 0.74f
            val ovalH = ovalW * 1.34f
            val left = (w - ovalW) / 2f
            val top = (h - ovalH) / 2f - 36f
            ovalBounds.set(left, top, left + ovalW, top + ovalH)
        }

        fun updateState(message: String, color: Int, prog: Float) {
            guideMsg = message
            textPaint.color = color
            progress = prog
            postInvalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            canvas.drawRect(0f, 0f, w, h, shadePaint)
            canvas.drawOval(ovalBounds, clearPaint)
            canvas.drawOval(ovalBounds, ringBgPaint)
            if (progress > 0f) {
                canvas.drawArc(ovalBounds, -90f, progress * 360f, false, ringProgressPaint)
            }
            canvas.drawText(guideMsg, w / 2f, ovalBounds.bottom + 76f, textPaint)
        }
    }
}
