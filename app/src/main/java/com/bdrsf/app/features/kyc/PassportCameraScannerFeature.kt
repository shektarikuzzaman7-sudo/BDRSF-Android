package com.bdrsf.app.features.kyc

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
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
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * BDRSF ডেডিকেটেড পাসপোর্ট কাস্টম CameraX স্ক্যানার:
 * - পাসপোর্টের সম্পূর্ণ বায়ো-ডাটা পেজ ও MRZ জোন পরিষ্কারভাবে ফ্রেম করার জন্য আলাদা HUD।
 * - রিয়েল-টাইম MRZ (`P<`) + পাসপোর্ট টেক্সট + পোর্ট্রেট ফেস ডিটেক্ট হলেই সর্বোচ্চ রেজোলিউশনে ক্যাপচার।
 */
class PassportCameraScannerFeature(
    private val activity: Activity,
    private val rootContainer: ViewGroup,
    private val bridge: AndroidBridge,
    private val onCaptured: (file: File, callback: String?) -> Unit
) {

    companion object {
        private const val RC_CAMERA = 2102
        private const val PASSPORT_SPREAD_RATIO = 0.72f
        private const val REQUIRED_STABLE_FRAMES = 5
    }

    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var previewView: PreviewView? = null
    private var imageCapture: ImageCapture? = null
    private var overlayLayout: FrameLayout? = null
    private var hudView: PassportHudView? = null

    private var activeCallback: String? = null
    private var isTorchOn: Boolean = false
    private val isCapturing = AtomicBoolean(false)
    private var stableFrames = 0

    @Volatile
    private var destroyed = false

    @Volatile
    var isScanningActive: Boolean = false
        private set

    private val textRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private val faceDetector by lazy {
        FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setMinFaceSize(0.07f)
                .build()
        )
    }

    fun startScanner(callback: String?) {
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
            stableFrames = 0
            isTorchOn = false
            activeCallback = callback

            buildUi()
            bindCamera()
        }
    }

    private fun buildUi() {
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

        hudView = PassportHudView(activity)
        overlayLayout?.addView(hudView)

        val topBar = FrameLayout(activity).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                (60 * density).toInt()
            ).apply {
                topMargin = (34 * density).toInt()
                leftMargin = (18 * density).toInt()
                rightMargin = (18 * density).toInt()
            }
        }

        val closeBtn = TextView(activity).apply {
            text = "✕ বাতিল"
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding((14 * density).toInt(), (8 * density).toInt(), (14 * density).toInt(), (8 * density).toInt())
            setBackgroundColor(Color.parseColor("#88000000"))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.START or Gravity.CENTER_VERTICAL }
            setOnClickListener { stopScanner(isCancelled = true) }
        }
        topBar.addView(closeBtn)

        val torchBtn = TextView(activity).apply {
            text = "⚡ টর্চ"
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding((14 * density).toInt(), (8 * density).toInt(), (14 * density).toInt(), (8 * density).toInt())
            setBackgroundColor(Color.parseColor("#88000000"))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
            setOnClickListener { toggleTorch(this) }
        }
        topBar.addView(torchBtn)
        overlayLayout?.addView(topBar)

        val captureBtn = TextView(activity).apply {
            text = "● পাসপোর্ট ক্যাপচার"
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding((24 * density).toInt(), (11 * density).toInt(), (24 * density).toInt(), (11 * density).toInt())
            setBackgroundColor(Color.parseColor("#CC00C853"))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = (30 * density).toInt()
            }
            setOnClickListener {
                if (isScanningActive && isCapturing.compareAndSet(false, true)) {
                    takePassportPhoto()
                }
            }
        }
        overlayLayout?.addView(captureBtn)

        rootContainer.addView(overlayLayout)
    }

    private fun bindCamera() {
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
            val textTask = textRecognizer.process(input)
            val faceTask = faceDetector.process(input)

            Tasks.whenAllComplete(textTask, faceTask)
                .addOnCompleteListener {
                    if (isScanningActive && !isCapturing.get()) {
                        val raw = if (textTask.isSuccessful) textTask.result.text.uppercase(Locale.ROOT) else ""
                        val hasPassportSignal = raw.contains("PASSPORT") || raw.contains("P<")
                        val hasFace = faceTask.isSuccessful && !faceTask.result.isNullOrEmpty()

                        if (hasPassportSignal && hasFace) {
                            stableFrames++
                            hudView?.setReady(true, "পাসপোর্ট MRZ ও ছবি শনাক্ত হয়েছে — স্থির থাকুন")
                            if (stableFrames >= REQUIRED_STABLE_FRAMES && isCapturing.compareAndSet(false, true)) {
                                takePassportPhoto()
                            }
                        } else {
                            stableFrames = 0
                            hudView?.setReady(false, "পাসপোর্টের তথ্যের পাতা ফ্রেমের ভেতরে সোজা রাখুন")
                        }
                    }
                    proxy.close()
                }
        }

        try {
            provider.unbindAll()
            camera = provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture, analysis)
        } catch (_: Exception) {
            stopScanner(isCancelled = true)
        }
    }

    private fun takePassportPhoto() {
        val capture = imageCapture ?: run {
            isCapturing.set(false)
            return
        }
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outFile = File.createTempFile("BDRSF_PASSPORT_$ts", ".jpg", activity.cacheDir)
        val options = ImageCapture.OutputFileOptions.Builder(outFile).build()

        capture.takePicture(
            options,
            ContextCompat.getMainExecutor(activity),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    val cb = activeCallback
                    stopScanner(isCancelled = false)
                    onCaptured(outFile, cb)
                }

                override fun onError(exception: ImageCaptureException) {
                    isCapturing.set(false)
                    Toast.makeText(activity, "পাসপোর্ট ক্যাপচার ব্যর্থ হয়েছে: ${exception.message}", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    private fun toggleTorch(btn: TextView) {
        val cam = camera ?: return
        if (cam.cameraInfo.hasFlashUnit()) {
            isTorchOn = !isTorchOn
            cam.cameraControl.enableTorch(isTorchOn)
            btn.text = if (isTorchOn) "☀ বন্ধ" else "⚡ টর্চ"
        }
    }

    fun stopScanner(isCancelled: Boolean = false) {
        isScanningActive = false
        isCapturing.set(false)
        hudView?.stopLaser()
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
        camera = null
        imageCapture = null

        val cb = activeCallback
        activeCallback = null
        if (isCancelled && cb != null) {
            bridge.sendResponseToWeb(
                BridgeResponse(Actions.START_KYC, Status.CANCELLED, message = "Passport scan cancelled"),
                cb
            )
        }
    }

    fun destroy() {
        destroyed = true
        stopScanner(isCancelled = false)
        try {
            textRecognizer.close()
            faceDetector.close()
            cameraExecutor.shutdownNow()
        } catch (_: Exception) {
        }
    }

    private inner class PassportHudView(context: Context) : View(context) {
        private val shadePaint = Paint().apply { color = Color.parseColor("#B8000000"); style = Paint.Style.FILL }
        private val borderPaint = Paint().apply { color = Color.parseColor("#38BDF8"); style = Paint.Style.STROKE; strokeWidth = 6f; isAntiAlias = true }
        private val cornerPaint = Paint().apply { color = Color.parseColor("#00E676"); style = Paint.Style.STROKE; strokeWidth = 12f; strokeCap = Paint.Cap.ROUND; isAntiAlias = true }
        private val laserPaint = Paint().apply { strokeWidth = 5f; isAntiAlias = true }
        private val guidePaint = Paint().apply {
            color = Color.WHITE
            textSize = 35f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
            setShadowLayer(8f, 0f, 0f, Color.BLACK)
        }

        private val frameRect = RectF()
        private var laserY = 0f
        private var animator: ValueAnimator? = null
        private var guideText = "পাসপোর্টের তথ্যের পাতা ফ্রেমের ভেতরে সোজা রাখুন"

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            val boxW = w * 0.84f
            val boxH = (boxW / PASSPORT_SPREAD_RATIO).coerceAtMost(h * 0.66f)
            val left = (w - boxW) / 2f
            val top = (h - boxH) / 2f - 25f
            frameRect.set(left, top, left + boxW, top + boxH)

            animator?.cancel()
            animator = ValueAnimator.ofFloat(frameRect.top, frameRect.bottom).apply {
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

        fun setReady(ready: Boolean, message: String) {
            val c = if (ready) Color.parseColor("#00E676") else Color.parseColor("#38BDF8")
            borderPaint.color = c
            cornerPaint.color = c
            guideText = message
            postInvalidate()
        }

        fun stopLaser() {
            animator?.cancel()
            animator = null
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            canvas.drawRect(0f, 0f, w, frameRect.top, shadePaint)
            canvas.drawRect(0f, frameRect.bottom, w, h, shadePaint)
            canvas.drawRect(0f, frameRect.top, frameRect.left, frameRect.bottom, shadePaint)
            canvas.drawRect(frameRect.right, frameRect.top, w, frameRect.bottom, shadePaint)
            canvas.drawRoundRect(frameRect, 20f, 20f, borderPaint)

            val cl = 52f
            canvas.drawLine(frameRect.left, frameRect.top, frameRect.left + cl, frameRect.top, cornerPaint)
            canvas.drawLine(frameRect.left, frameRect.top, frameRect.left, frameRect.top + cl, cornerPaint)
            canvas.drawLine(frameRect.right, frameRect.top, frameRect.right - cl, frameRect.top, cornerPaint)
            canvas.drawLine(frameRect.right, frameRect.top, frameRect.right, frameRect.top + cl, cornerPaint)
            canvas.drawLine(frameRect.left, frameRect.bottom, frameRect.left + cl, frameRect.bottom, cornerPaint)
            canvas.drawLine(frameRect.left, frameRect.bottom, frameRect.left, frameRect.bottom - cl, cornerPaint)
            canvas.drawLine(frameRect.right, frameRect.bottom, frameRect.right - cl, frameRect.bottom, cornerPaint)
            canvas.drawLine(frameRect.right, frameRect.bottom, frameRect.right, frameRect.bottom - cl, cornerPaint)

            if (laserY in frameRect.top..frameRect.bottom) {
                laserPaint.shader = LinearGradient(
                    frameRect.left, laserY, frameRect.right, laserY,
                    intArrayOf(Color.TRANSPARENT, Color.parseColor("#00E676"), Color.TRANSPARENT),
                    null,
                    Shader.TileMode.CLAMP
                )
                canvas.drawLine(frameRect.left + 14f, laserY, frameRect.right - 14f, laserY, laserPaint)
            }
            canvas.drawText(guideText, w / 2f, frameRect.bottom + 64f, guidePaint)
        }
    }
}
