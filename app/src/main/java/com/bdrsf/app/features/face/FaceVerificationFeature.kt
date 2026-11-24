package com.bdrsf.app.features.face

import android.Manifest
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.webkit.CookieManager
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
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.LifecycleOwner
import com.bdrsf.app.bridge.AndroidBridge
import com.bdrsf.app.bridge.WebBridgeContract.Actions
import com.bdrsf.app.bridge.WebBridgeContract.BridgeResponse
import com.bdrsf.app.bridge.WebBridgeContract.Status
import com.bdrsf.app.config.WebAppConfig
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import org.json.JSONObject
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * BDRSF ডেডিকেটেড ফেস লাইভনেস ও ডকুমেন্ট ফেস ম্যাচিং ইঞ্জিন।
 * ১. কাস্টম ফ্রন্ট ক্যামেরা + 3D ওয়্যারফ্রেম অ্যানিমেশন দিয়ে মুখ বামে-ডানে-উপর-নিচে ও চোখ মিটমিট করে লাইভনেস যাচাই করে।
 * ২. ক্লিয়ার সেলফি ক্যাপচার করে হাই-রেজুলেশন (720x860) JPEG-এ কনভার্ট করে।
 * ৩. ডকুমেন্টের (NID/Passport) কাটা ছবির সাথে তুলনা করে ফলাফল WebView ও Bridge-এ পাঠায়।
 */
class FaceVerificationFeature(
    private val activity: Activity,
    private val bridge: AndroidBridge,
    private val webView: WebView,
    private val overlayRoot: ViewGroup? = null
) {

    companion object {
        private const val RC_CAMERA_PERMISSION = 2002
        private const val HD_FACE_WIDTH = 720
        private const val HD_FACE_HEIGHT = 860
    }

    private enum class LivenessPose(val code: String, val bnPrompt: String) {
        ALIGN_CENTER("CENTER", "১. ক্যামেরার দিকে সোজা তাকান"),
        TURN_LEFT("LEFT", "২. ধীরে ধীরে মাথা বামে ঘোরান ⬅️"),
        TURN_RIGHT("RIGHT", "৩. ধীরে ধীরে মাথা ডানে ঘোরান ➡️"),
        TILT_UP_DOWN("TILT", "৪. মাথা সামান্য উপর-নিচে করুন ↕️"),
        BLINK_EYES("BLINK", "৫. চোখ মিটমিট করুন 👁️"),
        CAPTURING("CAPTURE", "ছবি ক্যাপচার ও হাই-রেজুলেশন ম্যাচিং হচ্ছে...")
    }

    private val bgExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var referenceDocFaceBase64: String = ""

    @Volatile
    private var destroyed: Boolean = false

    @Volatile
    var isScanningActive: Boolean = false
        private set

    private var cameraProvider: ProcessCameraProvider? = null
    private var previewView: PreviewView? = null
    private var imageCapture: ImageCapture? = null
    private var scannerOverlayLayout: FrameLayout? = null
    private var hud3dView: Face3dLivenessHudView? = null
    private var activeCallback: String? = null
    private var activeDocFaceOverride: String? = null

    private val isCapturing = AtomicBoolean(false)
    private var currentPose: LivenessPose = LivenessPose.ALIGN_CENTER
    private var sawLeftTurn = false
    private var sawRightTurn = false
    private var sawVerticalTilt = false
    private var sawEyeClosed = false
    private var sawBlinkConfirmed = false
    private var centerHoldFrames = 0

    private val faceDetector: FaceDetector by lazy {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setMinFaceSize(0.14f)
            .build()
        FaceDetection.getClient(options)
    }

    fun setReferenceDocumentFace(docFaceBase64: String) {
        referenceDocFaceBase64 = docFaceBase64.trim()
    }

    /**
     * কাস্টম 3D অ্যানিমেশনসহ ফ্রন্ট ক্যামেরা চালু করে লাইভনেস ও ফেস ভেরিফিকেশন শুরু করে।
     */
    fun startFaceVerificationScanner(docFaceBase64Override: String? = null, callback: String? = null) {
        if (destroyed) return
        val docFace = (docFaceBase64Override ?: referenceDocFaceBase64).trim()
        if (docFace.isEmpty()) {
            sendFailure("ডকুমেন্টের কাটা ছবি পাওয়া যায়নি। প্রথমে ডকুমেন্ট স্ক্যান করুন।", "", 0.0, callback)
            return
        }

        if (!hasCameraPermission()) {
            ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.CAMERA), RC_CAMERA_PERMISSION)
            return
        }

        if (isScanningActive) return

        activeDocFaceOverride = docFace
        activeCallback = callback

        activity.runOnUiThread {
            if (destroyed || isScanningActive) return@runOnUiThread
            resetLivenessState()
            isScanningActive = true
            buildScannerOverlay()
            bindFrontCamera()
        }
    }

    private fun resetLivenessState() {
        isCapturing.set(false)
        currentPose = LivenessPose.ALIGN_CENTER
        sawLeftTurn = false
        sawRightTurn = false
        sawVerticalTilt = false
        sawEyeClosed = false
        sawBlinkConfirmed = false
        centerHoldFrames = 0
    }

    private fun buildScannerOverlay() {
        val container = overlayRoot ?: (activity.findViewById<View>(android.R.id.content) as? ViewGroup) ?: return
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

        hud3dView = Face3dLivenessHudView(activity)
        scannerOverlayLayout?.addView(hud3dView)

        val closeButton = TextView(activity).apply {
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
                topMargin = (36 * density).toInt()
                leftMargin = (20 * density).toInt()
            }
            setOnClickListener { stopScanner(isCancelled = true) }
        }
        scannerOverlayLayout?.addView(closeButton)

        val manualCaptureButton = TextView(activity).apply {
            text = "● এখনই ক্যাপচার"
            setTextColor(Color.WHITE)
            textSize = 15f
            setPadding((22 * density).toInt(), (10 * density).toInt(), (22 * density).toInt(), (10 * density).toInt())
            setBackgroundColor(Color.parseColor("#B300E676"))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                bottomMargin = (32 * density).toInt()
            }
            setOnClickListener {
                if (isScanningActive && isCapturing.compareAndSet(false, true)) {
                    currentPose = LivenessPose.CAPTURING
                    hud3dView?.updateState(LivenessPose.CAPTURING, Color.parseColor("#00E676"), 1f)
                    captureLivePhoto()
                }
            }
        }
        scannerOverlayLayout?.addView(manualCaptureButton)

        container.addView(scannerOverlayLayout)
    }

    private fun bindFrontCamera() {
        val future = ProcessCameraProvider.getInstance(activity)
        future.addListener({
            if (destroyed || !isScanningActive) return@addListener
            try {
                cameraProvider = future.get()
                setupFrontCameraPipelines()
            } catch (e: Exception) {
                Log.e("FaceVerification", "Front camera bind error", e)
                stopScanner(isCancelled = true)
            }
        }, ContextCompat.getMainExecutor(activity))
    }

    @SuppressLint("UnsafeOptInUsageError")
    private fun setupFrontCameraPipelines() {
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

        imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
            if (!isScanningActive || isCapturing.get() || destroyed) {
                imageProxy.close()
                return@setAnalyzer
            }
            val mediaImage = imageProxy.image
            if (mediaImage == null) {
                imageProxy.close()
                return@setAnalyzer
            }

            val inputImage = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)
            faceDetector.process(inputImage)
                .addOnSuccessListener { faces ->
                    if (!isScanningActive || isCapturing.get()) return@addOnSuccessListener
                    evaluateLivenessSequence(faces)
                }
                .addOnFailureListener {
                    hud3dView?.updateState(LivenessPose.ALIGN_CENTER, Color.WHITE, 0.05f)
                }
                .addOnCompleteListener {
                    imageProxy.close()
                }
        }

        try {
            provider.unbindAll()
            provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_FRONT_CAMERA, preview, imageCapture, imageAnalysis)
        } catch (e: Exception) {
            Log.e("FaceVerification", "Lifecycle bind error", e)
            stopScanner(isCancelled = true)
        }
    }

    private fun evaluateLivenessSequence(faces: List<com.google.mlkit.vision.face.Face>) {
        if (faces.isEmpty()) {
            centerHoldFrames = 0
            hud3dView?.updateState(LivenessPose.ALIGN_CENTER, Color.WHITE, 0.1f)
            return
        }
        if (faces.size > 1) {
            centerHoldFrames = 0
            hud3dView?.updateState(LivenessPose.ALIGN_CENTER, Color.parseColor("#FF5252"), 0.1f)
            return
        }

        val face = faces[0]
        val yaw = face.headEulerAngleY
        val pitch = face.headEulerAngleX
        val leftEye = face.leftEyeOpenProbability ?: 1f
        val rightEye = face.rightEyeOpenProbability ?: 1f

        // ধাপ ১: সোজা মুখ
        if (currentPose == LivenessPose.ALIGN_CENTER) {
            if (kotlin.math.abs(yaw) <= 10f && kotlin.math.abs(pitch) <= 10f) {
                centerHoldFrames++
                hud3dView?.updateState(LivenessPose.ALIGN_CENTER, Color.parseColor("#00E676"), 0.2f)
                if (centerHoldFrames >= 4) {
                    currentPose = LivenessPose.TURN_LEFT
                    centerHoldFrames = 0
                }
            } else {
                centerHoldFrames = 0
                hud3dView?.updateState(LivenessPose.ALIGN_CENTER, Color.YELLOW, 0.15f)
            }
            return
        }

        // ধাপ ২: বামে ঘোরানো
        if (currentPose == LivenessPose.TURN_LEFT) {
            if (yaw <= -13f) {
                sawLeftTurn = true
                currentPose = LivenessPose.TURN_RIGHT
                hud3dView?.updateState(LivenessPose.TURN_RIGHT, Color.parseColor("#00E676"), 0.4f)
            } else {
                hud3dView?.updateState(LivenessPose.TURN_LEFT, Color.YELLOW, 0.25f)
            }
            return
        }

        // ধাপ ৩: ডানে ঘোরানো
        if (currentPose == LivenessPose.TURN_RIGHT) {
            if (yaw >= 13f) {
                sawRightTurn = true
                currentPose = LivenessPose.TILT_UP_DOWN
                hud3dView?.updateState(LivenessPose.TILT_UP_DOWN, Color.parseColor("#00E676"), 0.6f)
            } else {
                hud3dView?.updateState(LivenessPose.TURN_RIGHT, Color.YELLOW, 0.45f)
            }
            return
        }

        // ধাপ ৪: উপর-নিচে ঘোরানো
        if (currentPose == LivenessPose.TILT_UP_DOWN) {
            if (pitch <= -10f || pitch >= 11f) {
                sawVerticalTilt = true
                currentPose = LivenessPose.BLINK_EYES
                hud3dView?.updateState(LivenessPose.BLINK_EYES, Color.parseColor("#00E676"), 0.8f)
            } else {
                hud3dView?.updateState(LivenessPose.TILT_UP_DOWN, Color.YELLOW, 0.65f)
            }
            return
        }

        // ধাপ ৫: চোখ মিটমিট করা ও ফাইনাল ক্যাপচার
        if (currentPose == LivenessPose.BLINK_EYES) {
            if (leftEye < 0.35f || rightEye < 0.35f) {
                sawEyeClosed = true
                hud3dView?.updateState(LivenessPose.BLINK_EYES, Color.parseColor("#00E5FF"), 0.9f)
            } else if (sawEyeClosed && leftEye >= 0.65f && rightEye >= 0.65f) {
                sawBlinkConfirmed = true
                currentPose = LivenessPose.CAPTURING
                hud3dView?.updateState(LivenessPose.CAPTURING, Color.parseColor("#00E676"), 1.0f)
                if (isCapturing.compareAndSet(false, true)) {
                    captureLivePhoto()
                }
            } else {
                hud3dView?.updateState(LivenessPose.BLINK_EYES, Color.YELLOW, 0.85f)
            }
        }
    }

    private fun captureLivePhoto() {
        val captureUseCase = imageCapture
        if (captureUseCase == null) {
            isCapturing.set(false)
            return
        }

        val file = try {
            createTempSelfieFile()
        } catch (_: Exception) {
            isCapturing.set(false)
            return
        }

        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        captureUseCase.takePicture(
            options,
            ContextCompat.getMainExecutor(activity),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    val cb = activeCallback
                    val docOverride = activeDocFaceOverride
                    stopScanner(isCancelled = false)
                    verifyCapturedSelfieAgainstDocument(file, docOverride, cb)
                }

                override fun onError(exception: ImageCaptureException) {
                    isCapturing.set(false)
                    Toast.makeText(activity, "লাইভ ছবি তোলা যায়নি: ${exception.message}", Toast.LENGTH_SHORT).show()
                }
            }
        )
    }

    fun stopScanner(isCancelled: Boolean = false) {
        isScanningActive = false
        isCapturing.set(false)
        hud3dView?.stopAnimation()

        try {
            cameraProvider?.unbindAll()
        } catch (_: Exception) {}

        scannerOverlayLayout?.let { parent ->
            (parent.parent as? ViewGroup)?.removeView(parent)
            scannerOverlayLayout = null
        }
        previewView = null
        hud3dView = null
        imageCapture = null

        val cb = activeCallback
        activeCallback = null
        if (isCancelled && cb != null) {
            bridge.sendResponseToWeb(
                BridgeResponse(
                    action = Actions.START_KYC,
                    status = Status.CANCELLED,
                    message = "Face verification cancelled"
                ),
                cb
            )
        }
    }

    fun verifyCapturedSelfieAgainstDocument(
        livePhotoFile: File,
        docFaceBase64Override: String? = null,
        callback: String? = null
    ) {
        if (destroyed) return
        val docFace = (docFaceBase64Override ?: referenceDocFaceBase64).trim()
        if (docFace.isEmpty()) {
            sendFailure("ডকুমেন্টের কাটা ছবি পাওয়া যায়নি। প্রথমে ডকুমেন্ট স্ক্যান করুন।", "", 0.0, callback)
            return
        }

        if (!livePhotoFile.exists() || livePhotoFile.length() == 0L) {
            sendFailure("লাইভ ক্যামেরার ছবি পাওয়া যায়নি।", docFace, 0.0, callback)
            return
        }

        bgExecutor.execute {
            var fullBitmap: Bitmap? = null
            var croppedLiveBitmap: Bitmap? = null
            var hdLiveBitmap: Bitmap? = null
            try {
                val orientedFile = fixSelfieExifAndMirror(livePhotoFile)
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(orientedFile.absolutePath, options)
                val maxDim = max(options.outWidth, options.outHeight)
                var sampleSize = 1
                while (maxDim / sampleSize > 1600) sampleSize *= 2

                fullBitmap = BitmapFactory.decodeFile(
                    orientedFile.absolutePath,
                    BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                )
                orientedFile.delete()
                livePhotoFile.delete()

                if (fullBitmap == null || fullBitmap.isRecycled) {
                    sendFailure("লাইভ ছবি লোড করা যায়নি।", docFace, 0.0, callback)
                    return@execute
                }

                val faces = Tasks.await(
                    faceDetector.process(InputImage.fromBitmap(fullBitmap, 0)),
                    6,
                    TimeUnit.SECONDS
                )

                if (faces.isNullOrEmpty()) {
                    sendFailure("ক্যাপচার করা ছবিতে কোনো স্পষ্ট মুখমণ্ডল পাওয়া যায়নি।", docFace, 0.0, callback)
                    return@execute
                }

                if (faces.size > 1) {
                    sendFailure("ফ্রেমে একাধিক মুখমণ্ডল পাওয়া গেছে। শুধুমাত্র একজনের মুখ রাখুন।", docFace, 0.0, callback)
                    return@execute
                }

                val face = faces[0]
                val leftEyeOpen = face.leftEyeOpenProbability ?: 1f
                val rightEyeOpen = face.rightEyeOpenProbability ?: 1f
                if (leftEyeOpen < 0.25f && rightEyeOpen < 0.25f) {
                    sendFailure("চোখ বন্ধ রয়েছে। চোখ খোলা রেখে পুনরায় স্ক্যান করুন।", docFace, 0.0, callback)
                    return@execute
                }

                val box = face.boundingBox
                val padX = (box.width() * 0.28f).toInt()
                val padY = (box.height() * 0.35f).toInt()
                val x = max(0, box.left - padX)
                val y = max(0, box.top - padY)
                val w = min(fullBitmap.width - x, box.width() + padX * 2)
                val h = min(fullBitmap.height - y, box.height() + padY * 2)

                if (w < 48 || h < 48) {
                    sendFailure("মুখমণ্ডল খুব ছোট বা অস্পষ্ট। ক্যামেরার কাছে আসুন।", docFace, 0.0, callback)
                    return@execute
                }

                // হাই-রেজুলেশন (720x860) ক্লিয়ার ফেস ইমেজ তৈরি
                croppedLiveBitmap = Bitmap.createBitmap(fullBitmap, x, y, w, h)
                hdLiveBitmap = Bitmap.createScaledBitmap(croppedLiveBitmap, HD_FACE_WIDTH, HD_FACE_HEIGHT, true)
                val liveFaceBase64 = bitmapToBase64(hdLiveBitmap, 95)

                val geminiResult = matchFacesViaGemini(docFace, liveFaceBase64)
                val isMatched = geminiResult.optBoolean("is_match", false)
                val confidence = geminiResult.optDouble("confidence", 0.0)
                val reason = geminiResult.optString(
                    "reason",
                    if (isMatched) "ডকুমেন্টের কাটা ছবির সাথে লাইভ সেলফি মিলেছে।" else "ডকুমেন্টের কাটা ছবির সাথে লাইভ মুখমণ্ডল মিলছে না।"
                )

                if (isMatched && confidence >= 60.0) {
                    sendSuccess(
                        isMatched = true,
                        score = confidence,
                        liveFaceBase64 = liveFaceBase64,
                        docFaceBase64 = docFace,
                        message = reason,
                        callback = callback
                    )
                } else {
                    sendFailure(reason, docFace, confidence, callback, liveFaceBase64)
                }
            } catch (e: Exception) {
                sendFailure("ফেস ভেরিফিকেশন ত্রুটি: ${e.localizedMessage ?: ""}", docFace, 0.0, callback)
            } finally {
                if (hdLiveBitmap != null && !hdLiveBitmap.isRecycled) hdLiveBitmap.recycle()
                if (croppedLiveBitmap != null && !croppedLiveBitmap.isRecycled) croppedLiveBitmap.recycle()
                if (fullBitmap != null && !fullBitmap.isRecycled) fullBitmap.recycle()
            }
        }
    }

    private fun fixSelfieExifAndMirror(sourceFile: File): File {
        val temp = File.createTempFile("BDRSF_SELFIE_HD_", ".jpg", activity.cacheDir)
        var original: Bitmap? = null
        var transformed: Bitmap? = null
        try {
            val exif = ExifInterface(sourceFile.absolutePath)
            val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            original = BitmapFactory.decodeFile(sourceFile.absolutePath) ?: run {
                sourceFile.copyTo(temp, overwrite = true)
                return temp
            }

            val matrix = Matrix().apply {
                when (orientation) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> postRotate(90f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> postRotate(180f)
                    ExifInterface.ORIENTATION_ROTATE_270 -> postRotate(270f)
                }
                // ফ্রন্ট ক্যামেরার মিরর স্বাভাবিক করা
                preScale(-1f, 1f)
            }

            transformed = Bitmap.createBitmap(original, 0, 0, original.width, original.height, matrix, true)
            FileOutputStream(temp).use { out ->
                transformed.compress(Bitmap.CompressFormat.JPEG, 95, out)
                out.flush()
            }
        } catch (_: Exception) {
            sourceFile.copyTo(temp, overwrite = true)
        } finally {
            if (transformed !== original) transformed?.let { if (!it.isRecycled) it.recycle() }
            original?.let { if (!it.isRecycled) it.recycle() }
        }
        return temp
    }

    private fun matchFacesViaGemini(docFaceBase64: String, liveFaceBase64: String): JSONObject {
        val cleanDoc = if (docFaceBase64.startsWith("data:image")) docFaceBase64 else "data:image/jpeg;base64,$docFaceBase64"
        val cleanLive = if (liveFaceBase64.startsWith("data:image")) liveFaceBase64 else "data:image/jpeg;base64,$liveFaceBase64"

        val url = URL("${WebAppConfig.BASE_URL}/api/ocr")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 25000
        conn.readTimeout = 25000
        conn.doInput = true
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("User-Agent", WebAppConfig.APP_USER_AGENT_TAG)

        try {
            val cookieHeader = CookieManager.getInstance().getCookie(WebAppConfig.BASE_URL)
            if (!cookieHeader.isNullOrBlank()) {
                conn.setRequestProperty("Cookie", cookieHeader)
            }
        } catch (_: Exception) {}

        val body = JSONObject().apply {
            put("mode", "face_match_gemini")
            put("doc_type", "FACE_MATCH")
            put("document_face_image", cleanDoc)
            put("live_face_image", cleanLive)
            put("face_image", cleanLive)
            put("liveness_checks", JSONObject().apply {
                put("left_turn", sawLeftTurn)
                put("right_turn", sawRightTurn)
                put("vertical_tilt", sawVerticalTilt)
                put("eye_blink", sawBlinkConfirmed)
            })
        }

        OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use {
            it.write(body.toString())
            it.flush()
        }

        val code = conn.responseCode
        val input = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).use { it.readText() }
        conn.disconnect()

        if (code !in 200..299) {
            throw IllegalStateException("Gemini ফেস ম্যাচ সার্ভার ত্রুটি (HTTP $code)")
        }

        val root = JSONObject(text)
        val data = root.optJSONObject("data") ?: root
        val matched = data.optBoolean("is_match", data.optBoolean("matched", data.optBoolean("verified", false)))
        val score = data.optDouble("confidence", data.optDouble("match_score", data.optDouble("score", if (matched) 90.0 else 15.0)))
        val reason = data.optString("reason", data.optString("message", ""))

        return JSONObject().apply {
            put("is_match", matched)
            put("confidence", score)
            put("reason", reason)
        }
    }

    private fun bitmapToBase64(bitmap: Bitmap, quality: Int): String {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(activity, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    @Throws(java.io.IOException::class)
    private fun createTempSelfieFile(): File {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        return File.createTempFile("FACE_LIVE_$stamp", ".jpg", activity.cacheDir)
    }

    private fun sendSuccess(
        isMatched: Boolean,
        score: Double,
        liveFaceBase64: String,
        docFaceBase64: String,
        message: String,
        callback: String?
    ) {
        if (destroyed) return
        mainHandler.post {
            if (destroyed) return@post
            val script = "if(window.onLivenessCompleted) { window.onLivenessCompleted($isMatched, ${JSONObject.quote(liveFaceBase64)}, $score, ${JSONObject.quote(docFaceBase64)}, ${JSONObject.quote(message)}); }"
            webView.evaluateJavascript(script, null)

            val payload = JSONObject().apply {
                put("matched", isMatched)
                put("score", score)
                put("liveFaceBase64", liveFaceBase64)
                put("docFaceBase64", docFaceBase64)
                put("message", message)
            }
            bridge.sendResponseToWeb(
                BridgeResponse(action = Actions.START_KYC, status = Status.SUCCESS, data = payload, message = message),
                callback
            )
        }
    }

    private fun sendFailure(
        message: String,
        docFaceBase64: String,
        score: Double,
        callback: String?,
        liveFaceBase64: String = ""
    ) {
        if (destroyed) return
        mainHandler.post {
            if (destroyed) return@post
            val script = "if(window.onLivenessCompleted) { window.onLivenessCompleted(false, ${JSONObject.quote(liveFaceBase64)}, $score, ${JSONObject.quote(docFaceBase64)}, ${JSONObject.quote(message)}); }"
            webView.evaluateJavascript(script, null)

            bridge.sendResponseToWeb(
                BridgeResponse(action = Actions.START_KYC, status = Status.ERROR, message = message),
                callback
            )
        }
    }

    fun destroy() {
        destroyed = true
        stopScanner(isCancelled = false)
        try {
            faceDetector.close()
            cameraExecutor.shutdownNow()
            bgExecutor.shutdownNow()
        } catch (_: Exception) {}
    }

    /**
     * কাস্টম 3D ওয়্যারফ্রেম ও গাইড HUD ভিউ।
     */
    private inner class Face3dLivenessHudView(context: Context) : View(context) {
        private val clearPaint = Paint().apply {
            xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
            isAntiAlias = true
        }
        private val backdropPaint = Paint().apply {
            color = Color.parseColor("#CC000000")
            style = Paint.Style.FILL
        }
        private val ovalRingPaint = Paint().apply {
            color = Color.parseColor("#00E676")
            style = Paint.Style.STROKE
            strokeWidth = 8f
            isAntiAlias = true
        }
        private val meshPaint = Paint().apply {
            color = Color.parseColor("#9900E5FF")
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
            isAntiAlias = true
        }
        private val textPaint = Paint().apply {
            color = Color.WHITE
            textSize = 38f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
            setShadowLayer(8f, 0f, 0f, Color.BLACK)
        }

        private val ovalBounds = RectF()
        private var currentPose: LivenessPose = LivenessPose.ALIGN_CENTER
        private var progressRatio: Float = 0.1f
        private var phaseAngle: Float = 0f

        private val spinAnimator: ValueAnimator = ValueAnimator.ofFloat(0f, (Math.PI * 2).toFloat()).apply {
            duration = 2400L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                phaseAngle = it.animatedValue as Float
                invalidate()
            }
            start()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            setLayerType(LAYER_TYPE_SOFTWARE, null)
            val ovalW = w * 0.72f
            val ovalH = ovalW * 1.32f
            val left = (w - ovalW) / 2f
            val top = (h - ovalH) / 2f - 45f
            ovalBounds.set(left, top, left + ovalW, top + ovalH)
        }

        fun updateState(pose: LivenessPose, textColor: Int, progress: Float) {
            currentPose = pose
            textPaint.color = textColor
            progressRatio = progress.coerceIn(0f, 1f)
            postInvalidate()
        }

        fun stopAnimation() {
            spinAnimator.cancel()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()

            canvas.drawRect(0f, 0f, w, h, backdropPaint)
            canvas.drawOval(ovalBounds, clearPaint)
            canvas.drawOval(ovalBounds, ovalRingPaint)

            // 3D Wireframe Sphere/Head Mesh অ্যানিমেশন
            val cx = ovalBounds.centerX()
            val cy = ovalBounds.centerY()
            val rx = ovalBounds.width() * 0.28f
            val ry = ovalBounds.height() * 0.30f

            val yawOffset = when (currentPose) {
                LivenessPose.TURN_LEFT -> -rx * 0.42f
                LivenessPose.TURN_RIGHT -> rx * 0.42f
                else -> sin(phaseAngle) * (rx * 0.18f)
            }
            val pitchOffset = when (currentPose) {
                LivenessPose.TILT_UP_DOWN -> cos(phaseAngle) * (ry * 0.22f)
                else -> 0f
            }

            val headCenterX = cx + yawOffset
            val headCenterY = cy + pitchOffset

            // 3D ল্যাটিটিউড ও লঙ্গিটিউড লাইন
            canvas.drawOval(
                RectF(headCenterX - rx, headCenterY - ry, headCenterX + rx, headCenterY + ry),
                meshPaint
            )
            val meridianRx = max(6f, abs(cos(phaseAngle)) * rx)
            canvas.drawOval(
                RectF(headCenterX - meridianRx, headCenterY - ry, headCenterX + meridianRx, headCenterY + ry),
                meshPaint
            )

            val eyeLineY = headCenterY - ry * 0.18f
            val eyeSpread = rx * 0.42f
            val eyeRadiusY = if (currentPose == LivenessPose.BLINK_EYES && sin(phaseAngle * 2f) > 0.3f) 1.5f else 6f
            canvas.drawOval(RectF(headCenterX - eyeSpread - 12f, eyeLineY - eyeRadiusY, headCenterX - eyeSpread + 12f, eyeLineY + eyeRadiusY), meshPaint)
            canvas.drawOval(RectF(headCenterX + eyeSpread - 12f, eyeLineY - eyeRadiusY, headCenterX + eyeSpread + 12f, eyeLineY + eyeRadiusY), meshPaint)

            val nosePath = Path().apply {
                moveTo(headCenterX, eyeLineY + 6f)
                lineTo(headCenterX - 9f, headCenterY + ry * 0.18f)
                lineTo(headCenterX + 9f, headCenterY + ry * 0.18f)
            }
            canvas.drawPath(nosePath, meshPaint)

            val pct = (progressRatio * 100).toInt()
            canvas.drawText("${currentPose.bnPrompt} ($pct%)", w / 2f, ovalBounds.bottom + 76f, textPaint)
        }
    }
}
