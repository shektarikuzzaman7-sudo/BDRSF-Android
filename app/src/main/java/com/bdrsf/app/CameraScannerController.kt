package com.bdrsf.app

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.FrameLayout
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraScannerController(
    private val activity: MainActivity
) {
    private var scannerLayout: FrameLayout? = null
    private var previewView: PreviewView? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageCapture: ImageCapture? = null
    private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var isScanningActive = false

    private val barcodeScanner: BarcodeScanner by lazy {
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_PDF417,
                Barcode.FORMAT_QR_CODE,
                Barcode.FORMAT_CODE_128,
                Barcode.FORMAT_CODE_39,
                Barcode.FORMAT_DATA_MATRIX,
                Barcode.FORMAT_AZTEC
            )
            .build()
        BarcodeScanning.getClient(options)
    }

    private val textRecognizer: TextRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    fun startLiveCameraScanner(scanMode: String) {
        if (!hasCameraPermission()) {
            activity.requestAllRequiredPermissions()
            return
        }
        if (isScanningActive) return

        isScanningActive = true

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

        if (scanMode != "qr") {
            scannerLayout?.addView(DocumentOverlayView(activity, scanMode))
        }

        val closeButton = Button(activity).apply {
            text = "✕ বাতিল"
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#AA000000"))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.END
                topMargin = 80
                rightMargin = 40
            }

            setOnClickListener { stopLiveCameraScanner() }
        }

        scannerLayout?.addView(closeButton)
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
            if (!isScanningActive) {
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
                            if (!value.isNullOrBlank()) {
                                stopLiveCameraScanner()
                                activity.webView.evaluateJavascript(
                                    "if(window.processScannedData) { window.processScannedData(${org.json.JSONObject.quote(value)}); }",
                                    null
                                )
                            }
                        }
                        .addOnCompleteListener { imageProxy.close() }
                }

                "nid_front", "nid_back", "passport" -> {
                    textRecognizer.process(inputImage)
                        .addOnSuccessListener { visionText ->
                            val text = visionText.text
                            val normalized = DocumentScanValidator.convertBanglaToEnglishDigits(text)
                            val valid = when (scanMode) {
                                "nid_front" -> DocumentScanValidator.isValidNidFrontText(normalized)
                                "nid_back" -> DocumentScanValidator.isValidNidBackText(normalized)
                                "passport" -> DocumentScanValidator.isValidPassportText(normalized)
                                else -> false
                            }

                            if (valid) {
                                takeAutoCaptureAndCrop(scanMode)
                            }
                        }
                        .addOnCompleteListener { imageProxy.close() }
                }
            }
        }

        try {
            provider.unbindAll()
            provider.bindToLifecycle(
                activity,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageCapture,
                imageAnalysis
            )
        } catch (e: Exception) {
            Log.e("CameraScannerController", "bindToLifecycle failed", e)
            stopLiveCameraScanner()
        }
    }

    fun takeAutoCaptureAndCrop(scanMode: String) {
        val photoFile = try {
            activity.createImageFile()
        } catch (e: Exception) {
            null
        } ?: return

        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()
        imageCapture?.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(activity),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    stopLiveCameraScanner()
                    activity.bgProcessingExecutor.execute {
                        DocumentUtils.cropCenterBoxFromImage(photoFile)
                        activity.processSavedDocumentsHybrid(scanMode)
                    }
                }

                override fun onError(exception: ImageCaptureException) {
                    stopLiveCameraScanner()
                    activity.runOnUiThread {
                        android.widget.Toast.makeText(
                            activity,
                            "ছবি ক্যাপচার ব্যর্থ হয়েছে",
                            android.widget.Toast.LENGTH_SHORT
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
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            activity,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }

    inner class DocumentOverlayView(context: android.content.Context, private val mode: String) : View(context) {
        private val boxPaint = android.graphics.Paint().apply {
            color = Color.parseColor("#00E676")
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 8f
            isAntiAlias = true
        }

        private val shadePaint = android.graphics.Paint().apply {
            color = Color.parseColor("#B3000000")
            style = android.graphics.Paint.Style.FILL
        }

        private val textPaint = android.graphics.Paint().apply {
            color = Color.WHITE
            textSize = 42f
            textAlign = android.graphics.Paint.Align.CENTER
            isAntiAlias = true
            setShadowLayer(6f, 0f, 0f, Color.BLACK)
        }

        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()

            val boxWidth = w * 0.90f
            val boxHeight = boxWidth * 0.63f
            val left = (w - boxWidth) / 2f
            val top = (h - boxHeight) / 2f
            val right = left + boxWidth
            val bottom = top + boxHeight

            canvas.drawRect(0f, 0f, w, top, shadePaint)
            canvas.drawRect(0f, bottom, w, h, shadePaint)
            canvas.drawRect(0f, top, left, bottom, shadePaint)
            canvas.drawRect(right, top, w, bottom, shadePaint)

            canvas.drawRoundRect(android.graphics.RectF(left, top, right, bottom), 22f, 22f, boxPaint)

            val guideText = when (mode) {
                "nid_front" -> "কার্ডের সামনের অংশ ও ছবি বক্সের মধ্যে রাখুন"
                "nid_back" -> "কার্ডের পেছনের অংশ ও বারকোড বক্সের মধ্যে রাখুন"
                "passport" -> "পাসপোর্টের পৃষ্ঠা ও MRZ কোড ফ্রেমের মধ্যে রাখুন"
                else -> "ডকুমেন্টটি ফ্রেমের ভেতরে স্পষ্ট রাখুন"
            }

            canvas.drawText(guideText, w / 2f, top - 45f, textPaint)
        }
    }
}
