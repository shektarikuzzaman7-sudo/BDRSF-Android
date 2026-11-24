package com.bdrsf.app.features.document

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.provider.MediaStore
import android.util.Base64
import android.webkit.WebView
import android.widget.Toast
import androidx.exifinterface.media.ExifInterface
import com.bdrsf.app.bridge.AndroidBridge
import com.bdrsf.app.bridge.WebBridgeContract.Actions
import com.bdrsf.app.bridge.WebBridgeContract.BridgeResponse
import com.bdrsf.app.bridge.WebBridgeContract.Status
import com.bdrsf.app.config.WebAppConfig
import com.bdrsf.app.features.face.FaceVerificationFeature
import com.google.android.gms.tasks.Tasks
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
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
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
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

/**
 * BDRSF ডেডিকেটেড বাংলাদেশি NID প্রসেসর ইঞ্জিন।
 * - ডাইরেক্ট ফোন গ্যালারি ও NID সাইজ কাস্টম ক্যামেরা সাপোর্ট করে।
 * - বাংলাদেশি NID ফ্রন্ট যাচাই করে ছবি ক্রপ ও হাই-রেজুলেশনে কনভার্ট করে।
 * - ব্যাক পার্টের QR/বারকোড/কোড পড়ে ফ্রন্ট পার্টের তথ্যের সাথে ক্রস-ম্যাচ করে।
 */
class NidProcessorFeature(
    private val activity: Activity,
    private val bridge: AndroidBridge,
    private val webView: WebView,
    private val faceVerificationFeature: FaceVerificationFeature? = null,
    private val documentScannerFeature: DocumentScannerFeature? = null
) {

    companion object {
        const val RC_PICK_NID_GALLERY = 6021
        private const val BANGLADESH_NID_ASPECT_RATIO = 1.586f
        private const val MAX_CARD_DIMENSION = 1600
        private const val HD_FACE_WIDTH = 720
        private const val HD_FACE_HEIGHT = 860
    }

    private val bgExecutor: ExecutorService = Executors.newFixedThreadPool(2)

    @Volatile private var destroyed: Boolean = false
    @Volatile private var pendingGallerySlot: String = "nid_front"
    @Volatile private var pendingGalleryCallback: String? = null

    @Volatile private var cachedFrontBase64: String = ""
    @Volatile private var cachedBackBase64: String = ""
    @Volatile private var cachedFaceBase64: String = ""
    @Volatile private var cachedRawBackCode: String = ""
    @Volatile private var cachedBarcodeMap: Map<String, String> = emptyMap()
    @Volatile private var cachedFrontData: Map<String, String> = emptyMap()

    private val isFrontProcessing = AtomicBoolean(false)
    private val isBackProcessing = AtomicBoolean(false)

    private val textRecognizer: TextRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private val faceDetector: FaceDetector by lazy {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(0.10f)
            .build()
        FaceDetection.getClient(options)
    }

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

    /**
     * NID কার্ডের আসল অনুপাত (1.586:1) অনুযায়ী কাস্টম ক্যামেরা চালু করে।
     */
    fun openNidCameraScanner(slot: String = "nid_front", callback: String? = null) {
        if (destroyed) return
        val normalizedSlot = normalizeNidSlot(slot)
        val guideText = if (normalizedSlot == "nid_back") {
            "এনআইডি কার্ডের পেছনের অংশ ও বারকোড ফ্রেমে সোজা রাখুন"
        } else {
            "বাংলাদেশি এনআইডি কার্ডের সামনের অংশ ফ্রেমে সোজা রাখুন"
        }

        val payload = JSONObject().apply {
            put("mode", normalizedSlot)
            put("scanMode", normalizedSlot)
            put("documentType", normalizedSlot.uppercase(Locale.ROOT))
            put("docType", "NID")
            put("frameRatio", BANGLADESH_NID_ASPECT_RATIO.toDouble())
            put("guideText", guideText)
            put("autoCapture", true)
        }

        if (documentScannerFeature != null) {
            documentScannerFeature.startScanner(payload, callback)
        } else {
            bridge.executeFeature(Actions.START_KYC, payload.toString(), callback ?: "onDocumentValidated")
        }
    }

    /**
     * সরাসরি ফোনের ফটো গ্যালারি চালু করে এবং সিলেক্ট করা ছবি NID স্লট অনুযায়ী প্রসেস করে।
     */
    fun openGalleryPicker(slot: String = "nid_front", callback: String? = null) {
        if (destroyed) return
        pendingGallerySlot = normalizeNidSlot(slot)
        pendingGalleryCallback = callback

        val galleryIntent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply {
            type = "image/*"
        }

        try {
            @Suppress("DEPRECATION")
            activity.startActivityForResult(galleryIntent, RC_PICK_NID_GALLERY)
        } catch (e: Exception) {
            pendingGalleryCallback = null
            notifyValidationFailed(
                pendingGallerySlot,
                "ফোনের গ্যালারি চালু করা যায়নি: ${e.localizedMessage ?: ""}",
                callback
            )
        }
    }

    /**
     * MainActivity.onActivityResult থেকে গ্যালারির ফলাফল গ্রহণ করে।
     */
    fun handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != RC_PICK_NID_GALLERY) return false

        val targetSlot = pendingGallerySlot
        val callback = pendingGalleryCallback
        pendingGalleryCallback = null

        if (destroyed) return true

        if (resultCode != Activity.RESULT_OK) {
            bridge.sendResponseToWeb(
                BridgeResponse(
                    action = Actions.START_KYC,
                    status = Status.CANCELLED,
                    message = "গ্যালারি থেকে ছবি নির্বাচন বাতিল করা হয়েছে"
                ),
                callback
            )
            return true
        }

        val uri = data?.data
        if (uri == null) {
            notifyValidationFailed(targetSlot, "গ্যালারি থেকে কোনো ছবি পাওয়া যায়নি।", callback)
            return true
        }

        bgExecutor.execute {
            try {
                val tempFile = File.createTempFile("BDRSF_NID_${targetSlot.uppercase(Locale.ROOT)}_", ".jpg", activity.cacheDir)
                val inputStream = activity.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("গ্যালারির ছবি পড়া সম্ভব হয়নি।")
                inputStream.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                        output.flush()
                    }
                }
                processNidSlot(tempFile, targetSlot, callback)
            } catch (e: Exception) {
                notifyValidationFailed(targetSlot, "গ্যালারি ছবি প্রসেস করা যায়নি: ${e.localizedMessage ?: ""}", callback)
            }
        }

        return true
    }

    fun processNidSlot(file: File, targetSlot: String, callback: String? = null) {
        if (destroyed) return
        val normalizedSlot = normalizeNidSlot(targetSlot)
        if (!file.exists() || file.length() == 0L) {
            notifyValidationFailed(normalizedSlot, "ডকুমেন্টের ছবি পাওয়া যায়নি।", callback)
            return
        }

        notifyScanningStarted(normalizedSlot)
        when (normalizedSlot) {
            "nid_back" -> processNidBack(file, normalizedSlot, callback)
            "face_liveness" -> faceVerificationFeature?.verifyCapturedSelfieAgainstDocument(file, cachedFaceBase64, callback)
            else -> processNidFront(file, "nid_front", callback)
        }
    }

    private fun processNidFront(file: File, slot: String, callback: String?) {
        if (!isFrontProcessing.compareAndSet(false, true)) return

        bgExecutor.execute {
            var cardBitmap: Bitmap? = null
            try {
                cropAndNormalizeCardFile(file)
                val (bitmap, base64) = optimizeAndCompressToBase64(file)
                cardBitmap = bitmap
                if (bitmap == null || bitmap.isRecycled || base64.isBlank()) {
                    notifyValidationFailed(slot, "এনআইডি ফ্রন্টের ছবি লোড করা যায়নি।", callback)
                    return@execute
                }

                val inputImage = InputImage.fromBitmap(bitmap, 0)
                val visionText = Tasks.await(textRecognizer.process(inputImage), 8, TimeUnit.SECONDS)
                val allText = visionText.text.orEmpty()

                if (isPassportText(allText)) {
                    clearFrontCache()
                    notifyValidationFailed(slot, "ভুল ডকুমেন্ট! এনআইডির জায়গায় পাসপোর্টের ছবি দেওয়া হয়েছে।", callback)
                    return@execute
                }

                val extractedData = extractNidFrontData(allText)
                val nidNo = extractedData["nid"].orEmpty()
                if (nidNo.length !in listOf(10, 13, 17)) {
                    clearFrontCache()
                    notifyValidationFailed(
                        slot,
                        "বৈধ ১০, ১৩ বা ১৭ ডিজিটের বাংলাদেশি এনআইডি নম্বর পাওয়া যায়নি। স্পষ্ট ছবি দিন।",
                        callback
                    )
                    return@execute
                }

                if (!isGenuineBangladeshNidFront(allText, nidNo)) {
                    clearFrontCache()
                    notifyValidationFailed(
                        slot,
                        "এটি বৈধ বাংলাদেশি জাতীয় পরিচয়পত্র (NID) হিসেবে সনাক্ত হয়নি। পরিষ্কার ছবি দিন।",
                        callback
                    )
                    return@execute
                }

                val hdFaceBase64 = extractHighResFaceFromBitmap(bitmap)
                if (hdFaceBase64.isBlank()) {
                    clearFrontCache()
                    notifyValidationFailed(
                        slot,
                        "এনআইডি কার্ডের ভেতরে স্পষ্ট মুখমণ্ডলের ছবি পাওয়া যায়নি। বৈধ কার্ড দিন।",
                        callback
                    )
                    return@execute
                }

                cachedFrontData = extractedData
                cachedFrontBase64 = base64
                cachedFaceBase64 = hdFaceBase64
                // ব্যাক পার্ট নতুনভাবে যাচাই হবে
                cachedBackBase64 = ""
                cachedRawBackCode = ""
                cachedBarcodeMap = emptyMap()

                faceVerificationFeature?.setReferenceDocumentFace(hdFaceBase64)

                val responseData = HashMap(extractedData).apply {
                    put("face_cropped_hd", "true")
                    put("faceBase64", hdFaceBase64)
                    put("raw_text", allText)
                }
                notifyValidationSuccess(slot, base64, responseData, callback)
            } catch (e: Exception) {
                clearFrontCache()
                notifyValidationFailed(slot, "এনআইডি ফ্রন্ট যাচাই ত্রুটি: ${e.localizedMessage ?: ""}", callback)
            } finally {
                recycleQuietly(cardBitmap)
                isFrontProcessing.set(false)
            }
        }
    }

    private fun processNidBack(file: File, slot: String, callback: String?) {
        if (!isBackProcessing.compareAndSet(false, true)) return

        bgExecutor.execute {
            var backBitmap: Bitmap? = null
            try {
                val frontNid = cachedFrontData["nid"].orEmpty()
                if (cachedFrontBase64.isBlank() || frontNid.length !in listOf(10, 13, 17)) {
                    notifyValidationFailed(slot, "আগে বৈধ বাংলাদেশি এনআইডি ফ্রন্ট পার্ট ভেরিফাই করুন।", callback)
                    return@execute
                }

                cropAndNormalizeCardFile(file)
                val (bitmap, base64) = optimizeAndCompressToBase64(file)
                backBitmap = bitmap
                if (bitmap == null || bitmap.isRecycled || base64.isBlank()) {
                    notifyValidationFailed(slot, "এনআইডি ব্যাক পার্টের ছবি লোড করা যায়নি।", callback)
                    return@execute
                }

                val inputImage = InputImage.fromBitmap(bitmap, 0)
                val visionText = Tasks.await(textRecognizer.process(inputImage), 8, TimeUnit.SECONDS)
                val backText = visionText.text.orEmpty()

                val barcodes = Tasks.await(barcodeScanner.process(inputImage), 8, TimeUnit.SECONDS)
                var rawBarcode = ""
                for (b in barcodes) {
                    val decoded = b.rawBytes?.let {
                        try {
                            String(it, StandardCharsets.UTF_8)
                        } catch (_: Exception) {
                            b.rawValue ?: ""
                        }
                    } ?: (b.rawValue ?: "")
                    if (decoded.isNotBlank()) {
                        rawBarcode += decoded.trim() + "\n"
                    }
                }

                if (rawBarcode.isBlank()) {
                    rawBarcode = decodeBarcodeWithZxing(bitmap).orEmpty()
                }

                val cleanCode = rawBarcode.trim()
                if (cleanCode.isBlank()) {
                    clearBackCache()
                    notifyValidationFailed(
                        slot,
                        "এনআইডি ব্যাক পার্টে কোনো QR কোড বা বারকোড পাওয়া যায়নি! কোডবিহীন বা বানানো কার্ড গ্রহণযোগ্য নয়।",
                        callback
                    )
                    return@execute
                }

                val parsedBarcode = parseNidBarcodeData(cleanCode, backText)
                val backNid = parsedBarcode["nid"].orEmpty()
                val backDob = parsedBarcode["dob"].orEmpty()
                val frontDob = cachedFrontData["dob"].orEmpty()

                if (backNid.isBlank()) {
                    clearBackCache()
                    notifyValidationFailed(
                        slot,
                        "ব্যাক পার্টের কোড/বারকোড থেকে এনআইডি নম্বর শনাক্ত করা যায়নি।",
                        callback
                    )
                    return@execute
                }

                if (!isNidNumbersMatching(frontNid, backNid)) {
                    clearBackCache()
                    notifyValidationFailed(
                        slot,
                        "তথ্য মিলছে না! ফ্রন্ট পার্টের এনআইডি ($frontNid) ও ব্যাক পার্টের কোডের এনআইডি ($backNid) এক নয়।",
                        callback
                    )
                    return@execute
                }

                if (backDob.isNotBlank() && frontDob.isNotBlank() && !isDobMatching(frontDob, backDob)) {
                    clearBackCache()
                    notifyValidationFailed(
                        slot,
                        "তথ্য মিলছে না! ফ্রন্ট পার্ট ও ব্যাক পার্টের জন্মতারিখ অমিল পাওয়া গেছে।",
                        callback
                    )
                    return@execute
                }

                if (!isNidBackContentValid(backText, cleanCode, backNid)) {
                    clearBackCache()
                    notifyValidationFailed(
                        slot,
                        "ব্যাক পার্টের তথ্য বাংলাদেশি এনআইডি ব্যাকের সাথে মিলছে না।",
                        callback
                    )
                    return@execute
                }

                cachedBackBase64 = base64
                cachedRawBackCode = cleanCode
                cachedBarcodeMap = parsedBarcode

                val responseData = HashMap(parsedBarcode).apply {
                    put("raw_text", backText)
                    put("back_code", cleanCode)
                    put("front_nid", frontNid)
                    put("back_nid", backNid)
                    put("matched_with_front", "true")
                }
                notifyValidationSuccess(slot, base64, responseData, callback)
            } catch (e: Exception) {
                clearBackCache()
                notifyValidationFailed(slot, "এনআইডি ব্যাক যাচাই ত্রুটি: ${e.localizedMessage ?: ""}", callback)
            } finally {
                recycleQuietly(backBitmap)
                isBackProcessing.set(false)
            }
        }
    }

    fun submitNidHybrid(callback: String? = null) {
        bgExecutor.execute {
            var waitCycles = 0
            while ((isFrontProcessing.get() || isBackProcessing.get()) && waitCycles < 60) {
                Thread.sleep(50)
                waitCycles++
            }

            if (cachedFrontBase64.isEmpty() || cachedBackBase64.isEmpty() || cachedRawBackCode.isBlank()) {
                activity.runOnUiThread {
                    notifyValidationFailed(
                        "nid_front",
                        "সাবমিট করার আগে বৈধ ফ্রন্ট পার্ট এবং মিলে যাওয়া ব্যাক পার্টের কোড সম্পন্ন করুন।",
                        callback
                    )
                }
                return@execute
            }

            try {
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

                val payload = JSONObject().apply {
                    put("doc_type", "NID")
                    put("front_image", cachedFrontBase64)
                    put("back_image", cachedBackBase64)
                    put("back_code", cachedRawBackCode)
                    put("face_image", cachedFaceBase64)
                }

                OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use {
                    it.write(payload.toString())
                    it.flush()
                }

                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val respText = BufferedReader(InputStreamReader(stream, StandardCharsets.UTF_8)).use { it.readText() }
                conn.disconnect()

                val json = if (code in 200..299) JSONObject(respText) else JSONObject()
                val data = json.optJSONObject("data") ?: JSONObject()
                val rawBack = json.optString("raw_text", "")
                activity.runOnUiThread {
                    dispatchNidSuccess(data, rawBack, cachedBarcodeMap, cachedFaceBase64, callback)
                }
            } catch (_: Exception) {
                activity.runOnUiThread {
                    dispatchNidSuccess(JSONObject(cachedFrontData), "", cachedBarcodeMap, cachedFaceBase64, callback)
                }
            }
        }
    }

    private fun dispatchNidSuccess(
        ocrData: JSONObject,
        rawBackText: String,
        barcodeMap: Map<String, String>,
        faceBase64: String,
        callback: String?
    ) {
        val ocrNid = ocrData.optString("nidNo", "").replace(Regex("\\D"), "")
        val barcodeNid = barcodeMap["nid"]?.replace(Regex("\\D"), "") ?: ""
        val finalNid = when {
            barcodeNid.isNotEmpty() -> barcodeNid
            !cachedFrontData["nid"].isNullOrBlank() -> cachedFrontData["nid"].orEmpty()
            else -> ocrNid
        }

        val displayName = barcodeMap["name_bn"]?.takeIf { it.isNotEmpty() }
            ?: barcodeMap["name"]?.takeIf { it.isNotEmpty() }
            ?: cachedFrontData["name"]?.takeIf { it.isNotEmpty() }
            ?: ocrData.optString("nameBn", "")

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
            put("backCode", cachedRawBackCode)
        }

        val fullAddress = if (rawBackText.isNotBlank()) rawBackText else {
            listOfNotNull(
                backObj.optString("holding").takeIf { it.isNotBlank() }?.let { "হোল্ডিং: $it" },
                backObj.optString("village").takeIf { it.isNotBlank() }?.let { "গ্রাম: $it" },
                backObj.optString("postOffice").takeIf { it.isNotBlank() }?.let { "ডাকঘর: $it" },
                backObj.optString("upazila").takeIf { it.isNotBlank() }?.let { "উপজেলা: $it" },
                backObj.optString("district").takeIf { it.isNotBlank() }?.let { "জেলা: $it" }
            ).joinToString(", ")
        }

        val script = "if(window.onFullNIDParsed) { window.onFullNIDParsed(" +
            "${JSONObject.quote(displayName)}, " +
            "${JSONObject.quote(barcodeMap["father"] ?: ocrData.optString("fatherName", ""))}, " +
            "${JSONObject.quote(barcodeMap["mother"] ?: ocrData.optString("motherName", ""))}, " +
            "${JSONObject.quote(barcodeMap["dob"]?.takeIf { it.isNotEmpty() } ?: cachedFrontData["dob"] ?: ocrData.optString("dob", ""))}, " +
            "${JSONObject.quote(finalNid)}, " +
            "${JSONObject.quote(fullAddress)}, " +
            "${JSONObject.quote(faceBase64)}, " +
            "${JSONObject.quote(backObj.toString())}" +
            "); }"
        webView.evaluateJavascript(script, null)

        val resultData = JSONObject().apply {
            put("name", displayName)
            put("nid", finalNid)
            put("dob", barcodeMap["dob"] ?: cachedFrontData["dob"] ?: "")
            put("father", barcodeMap["father"] ?: "")
            put("mother", barcodeMap["mother"] ?: "")
            put("address", fullAddress)
            put("faceBase64", faceBase64)
            put("backCode", cachedRawBackCode)
            put("addressDetails", backObj)
        }

        bridge.sendResponseToWeb(
            response = BridgeResponse(action = Actions.START_KYC, status = Status.SUCCESS, data = resultData),
            callbackFunction = callback
        )
    }

    private fun cropAndNormalizeCardFile(file: File) {
        var original: Bitmap? = null
        var rotated: Bitmap? = null
        var cropped: Bitmap? = null
        try {
            val exif = ExifInterface(file.absolutePath)
            val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            original = BitmapFactory.decodeFile(file.absolutePath) ?: return

            val rotationDegrees = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }

            rotated = if (rotationDegrees != 0f) {
                val matrix = Matrix().apply { postRotate(rotationDegrees) }
                Bitmap.createBitmap(original, 0, 0, original.width, original.height, matrix, true)
            } else {
                original
            }

            // বাংলাদেশি NID কার্ডের অনুপাত (1.586:1) অনুযায়ী কার্ড ফ্রেম ক্রপ
            val imgRatio = rotated.width.toFloat() / max(1, rotated.height).toFloat()
            val cropW: Int
            val cropH: Int
            if (imgRatio >= BANGLADESH_NID_ASPECT_RATIO) {
                cropH = (rotated.height * 0.90f).toInt().coerceAtLeast(1)
                cropW = (cropH * BANGLADESH_NID_ASPECT_RATIO).toInt().coerceAtMost(rotated.width)
            } else {
                cropW = (rotated.width * 0.94f).toInt().coerceAtLeast(1)
                cropH = (cropW / BANGLADESH_NID_ASPECT_RATIO).toInt().coerceAtMost(rotated.height)
            }

            val x = ((rotated.width - cropW) / 2).coerceIn(0, max(0, rotated.width - 1))
            val y = ((rotated.height - cropH) / 2).coerceIn(0, max(0, rotated.height - 1))
            val safeW = cropW.coerceAtMost(rotated.width - x).coerceAtLeast(1)
            val safeH = cropH.coerceAtMost(rotated.height - y).coerceAtLeast(1)

            cropped = Bitmap.createBitmap(rotated, x, y, safeW, safeH)
            FileOutputStream(file).use { out ->
                cropped.compress(Bitmap.CompressFormat.JPEG, 94, out)
                out.flush()
            }
        } catch (_: Exception) {
        } finally {
            if (cropped !== rotated) recycleQuietly(cropped)
            if (rotated !== original) recycleQuietly(rotated)
            recycleQuietly(original)
        }
    }

    private fun optimizeAndCompressToBase64(file: File): Pair<Bitmap?, String> {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return Pair(null, "")

            val maxDim = max(bounds.outWidth, bounds.outHeight)
            var sampleSize = 1
            while (maxDim / sampleSize > MAX_CARD_DIMENSION) {
                sampleSize *= 2
            }

            val bitmap = BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
            ) ?: return Pair(null, "")

            val stream = ByteArrayOutputStream()
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 92, stream)) {
                recycleQuietly(bitmap)
                return Pair(null, "")
            }
            val base64 = "data:image/jpeg;base64," + Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
            Pair(bitmap, base64)
        } catch (_: Exception) {
            Pair(null, "")
        }
    }

    /**
     * NID কার্ডের ভেতরের মুখমণ্ডল ক্রপ করে হাই-রেজুলেশন (720x860) JPEG Base64-এ কনভার্ট করে।
     */
    private fun extractHighResFaceFromBitmap(bitmap: Bitmap): String {
        var rawCrop: Bitmap? = null
        var hdBitmap: Bitmap? = null
        return try {
            val faces = Tasks.await(faceDetector.process(InputImage.fromBitmap(bitmap, 0)), 5, TimeUnit.SECONDS)
            val box = faces?.firstOrNull()?.boundingBox ?: return ""

            val padX = (box.width() * 0.28f).toInt()
            val padY = (box.height() * 0.36f).toInt()
            val x = (box.left - padX).coerceIn(0, max(0, bitmap.width - 1))
            val y = (box.top - padY).coerceIn(0, max(0, bitmap.height - 1))
            val w = (box.width() + padX * 2).coerceAtMost(bitmap.width - x)
            val h = (box.height() + padY * 2).coerceAtMost(bitmap.height - y)

            if (w < 32 || h < 32) return ""

            rawCrop = Bitmap.createBitmap(bitmap, x, y, w, h)
            hdBitmap = Bitmap.createScaledBitmap(rawCrop, HD_FACE_WIDTH, HD_FACE_HEIGHT, true)

            val out = ByteArrayOutputStream()
            hdBitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
        } catch (_: Exception) {
            ""
        } finally {
            recycleQuietly(hdBitmap)
            recycleQuietly(rawCrop)
        }
    }

    private fun decodeBarcodeWithZxing(bitmap: Bitmap): String? {
        return try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
            val hints = mapOf(DecodeHintType.TRY_HARDER to true)
            MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)), hints).text
        } catch (_: Exception) {
            null
        }
    }

    private fun convertBanglaToEnglishDigits(input: String): String {
        val bn = charArrayOf('০', '১', '২', '৩', '৪', '৫', '৬', '৭', '৮', '৯')
        val en = charArrayOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9')
        var out = input
        for (i in bn.indices) {
            out = out.replace(bn[i], en[i])
        }
        return out
    }

    private fun isPassportText(rawText: String): Boolean {
        val upper = rawText.uppercase(Locale.ROOT)
        return upper.contains("PASSPORT") || upper.contains("P<BGD") || upper.contains("P<")
    }

    private fun isGenuineBangladeshNidFront(rawText: String, nidNo: String): Boolean {
        if (nidNo.length !in listOf(10, 13, 17)) return false
        val upper = rawText.uppercase(Locale.ROOT)
        val hasIdentityMarker =
            rawText.contains("জাতীয় পরিচয়", ignoreCase = true) ||
            rawText.contains("গণপ্রজাতন্ত্রী বাংলাদেশ", ignoreCase = true) ||
            rawText.contains("NID No", ignoreCase = true) ||
            rawText.contains("PIN", ignoreCase = true) ||
            upper.contains("NATIONAL ID") ||
            upper.contains("GOVERNMENT OF THE PEOPLE") ||
            upper.contains("BANGLADESH")
        return hasIdentityMarker
    }

    private fun isNidBackContentValid(backText: String, rawCode: String, backNid: String): Boolean {
        if (backNid.length !in listOf(10, 13, 17) || rawCode.isBlank()) return false
        val hasBackTextMarker =
            backText.contains("ঠিকানা", ignoreCase = true) ||
            backText.contains("ডাকঘর", ignoreCase = true) ||
            backText.contains("মুদ্রণের তারিখ", ignoreCase = true) ||
            backText.contains("Election Commission", ignoreCase = true) ||
            backText.uppercase(Locale.ROOT).contains("ADDRESS") ||
            backText.uppercase(Locale.ROOT).contains("BLOOD")
        return hasBackTextMarker || rawCode.length >= 10
    }

    private fun isNidNumbersMatching(frontNid: String, backNid: String): Boolean {
        val f = frontNid.replace(Regex("\\D"), "")
        val b = backNid.replace(Regex("\\D"), "")
        if (f.isEmpty() || b.isEmpty()) return false
        return f == b || f.contains(b) || b.contains(f)
    }

    private fun isDobMatching(frontDob: String, backDob: String): Boolean {
        val fDigits = convertBanglaToEnglishDigits(frontDob).replace(Regex("[^0-9A-Za-z]"), "").lowercase(Locale.ROOT)
        val bDigits = convertBanglaToEnglishDigits(backDob).replace(Regex("[^0-9A-Za-z]"), "").lowercase(Locale.ROOT)
        if (fDigits.isEmpty() || bDigits.isEmpty()) return true
        return fDigits == bDigits || fDigits.contains(bDigits) || bDigits.contains(fDigits)
    }

    private fun extractNidFrontData(rawText: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val normalized = convertBanglaToEnglishDigits(rawText)

        // আগে NID No / PIN লেবেলের কাছাকাছি নম্বর খুঁজি
        val labeledNidMatch = Regex("(?:NID\\s*No\\.?|PIN|জাতীয়\\s*পরিচয়\\s*পত্র\\s*নং?)[^0-9]{0,12}([0-9]{10}|[0-9]{13}|[0-9]{17})", RegexOption.IGNORE_CASE)
            .find(normalized)
        if (labeledNidMatch != null) {
            result["nid"] = labeledNidMatch.groupValues[1]
        } else {
            Regex("\\b([0-9]{17}|[0-9]{13}|[0-9]{10})\\b").find(normalized)?.groupValues?.getOrNull(1)?.let {
                result["nid"] = it
            }
        }

        Regex("\\b([0-9]{2}[\\s/\\-.A-Za-z]{3,6}[0-9]{4})\\b").find(normalized)?.value?.let {
            result["dob"] = it.trim()
        }

        val lines = rawText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        for (i in lines.indices) {
            if (lines[i].startsWith("Name", ignoreCase = true)) {
                val inlineVal = lines[i].replace(Regex("(?i)^Name[:\\s]*"), "").trim()
                if (inlineVal.isNotEmpty()) {
                    result["name"] = inlineVal
                    break
                } else if (i + 1 < lines.size) {
                    result["name"] = lines[i + 1]
                    break
                }
            }
        }
        return result
    }

    private fun parseNidBarcodeData(barcodeData: String, fallbackBackText: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val normalizedBarcode = convertBanglaToEnglishDigits(barcodeData)
        val normalizedFallback = convertBanglaToEnglishDigits(fallbackBackText)

        // ১. XML/EMV/SmartCard ট্যাগ বা কি-ভ্যালু থেকে NID/PIN বের করা
        Regex("<pin>\\s*([0-9]{10,17})\\s*</pin>", RegexOption.IGNORE_CASE).find(normalizedBarcode)?.groupValues?.getOrNull(1)?.let {
            result["nid"] = it
        }
        if (result["nid"].isNullOrBlank()) {
            Regex("(?:PIN|NID)[:\\s=_-]*([0-9]{17}|[0-9]{13}|[0-9]{10})", RegexOption.IGNORE_CASE)
                .find(normalizedBarcode)?.groupValues?.getOrNull(1)?.let {
                    result["nid"] = it
                }
        }
        if (result["nid"].isNullOrBlank()) {
            Regex("\\b([0-9]{17}|[0-9]{13}|[0-9]{10})\\b").find(normalizedBarcode)?.groupValues?.getOrNull(1)?.let {
                result["nid"] = it
            }
        }
        if (result["nid"].isNullOrBlank()) {
            Regex("\\b([0-9]{17}|[0-9]{13}|[0-9]{10})\\b").find(normalizedFallback)?.groupValues?.getOrNull(1)?.let {
                result["nid"] = it
            }
        }

        // ২. অতিরিক্ত তথ্য (নাম, জন্মতারিখ, পিতা/মাতা) বারকোড/QR থেকে থাকলে বের করা
        Regex("<name>\\s*([^<]+)\\s*</name>", RegexOption.IGNORE_CASE).find(barcodeData)?.groupValues?.getOrNull(1)?.let {
            result["name"] = it.trim()
        }
        Regex("<(nameBn|name_bn)>\\s*([^<]+)\\s*<", RegexOption.IGNORE_CASE).find(barcodeData)?.groupValues?.getOrNull(2)?.let {
            result["name_bn"] = it.trim()
        }
        Regex("<DOB>\\s*([^<]+)\\s*</DOB>", RegexOption.IGNORE_CASE).find(normalizedBarcode)?.groupValues?.getOrNull(1)?.let {
            result["dob"] = it.trim()
        } ?: Regex("(?:DOB|BIRTH)[:\\s=_-]*([0-9]{2}[/\\-][0-9]{2}[/\\-][0-9]{4}|[0-9]{4}[/\\-][0-9]{2}[/\\-][0-9]{2})", RegexOption.IGNORE_CASE)
            .find(normalizedBarcode)?.groupValues?.getOrNull(1)?.let {
                result["dob"] = it.trim()
            }

        Regex("<(father|fname)>\\s*([^<]+)\\s*<", RegexOption.IGNORE_CASE).find(barcodeData)?.groupValues?.getOrNull(2)?.let {
            result["father"] = it.trim()
        }
        Regex("<(mother|mname)>\\s*([^<]+)\\s*<", RegexOption.IGNORE_CASE).find(barcodeData)?.groupValues?.getOrNull(2)?.let {
            result["mother"] = it.trim()
        }

        return result
    }

    private fun normalizeNidSlot(rawSlot: String?): String {
        val v = rawSlot.orEmpty().trim().lowercase(Locale.ROOT)
        return when {
            v.contains("back") -> "nid_back"
            v.contains("face") || v.contains("selfie") || v.contains("live") -> "face_liveness"
            else -> "nid_front"
        }
    }

    private fun notifyScanningStarted(slot: String) {
        if (destroyed) return
        activity.runOnUiThread {
            if (!destroyed) {
                webView.evaluateJavascript(
                    "if(window.onImageScanningStarted) { window.onImageScanningStarted(${JSONObject.quote(slot)}); }",
                    null
                )
            }
        }
    }

    private fun notifyValidationSuccess(slot: String, base64: String, data: Map<String, String>, callback: String?) {
        if (destroyed) return
        activity.runOnUiThread {
            if (destroyed) return@runOnUiThread
            Toast.makeText(activity, "✔ $slot যাচাই সম্পন্ন", Toast.LENGTH_SHORT).show()
            webView.evaluateJavascript(
                "if(window.onDocumentValidated) { window.onDocumentValidated(${JSONObject.quote(slot)}, true, ${JSONObject.quote(base64)}); }",
                null
            )
            val json = JSONObject().apply {
                put("slot", slot)
                put("image", base64)
                data.forEach { (k, v) -> put(k, v) }
            }
            bridge.sendResponseToWeb(
                BridgeResponse(action = Actions.START_KYC, status = Status.SUCCESS, data = json),
                callback
            )
        }
    }

    private fun notifyValidationFailed(slot: String, message: String, callback: String?) {
        if (destroyed) return
        activity.runOnUiThread {
            if (destroyed) return@runOnUiThread
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
            webView.evaluateJavascript(
                "if(window.onDocumentValidated) { window.onDocumentValidated(${JSONObject.quote(slot)}, false, ''); }",
                null
            )
            webView.evaluateJavascript(
                "if(window.onDocumentValidationFailed) { window.onDocumentValidationFailed(${JSONObject.quote(slot)}, ${JSONObject.quote(message)}); }",
                null
            )
            bridge.sendResponseToWeb(
                BridgeResponse(action = Actions.START_KYC, status = Status.ERROR, message = message),
                callback
            )
        }
    }

    private fun clearFrontCache() {
        cachedFrontBase64 = ""
        cachedFaceBase64 = ""
        cachedFrontData = emptyMap()
        faceVerificationFeature?.setReferenceDocumentFace("")
    }

    private fun clearBackCache() {
        cachedBackBase64 = ""
        cachedRawBackCode = ""
        cachedBarcodeMap = emptyMap()
    }

    private fun recycleQuietly(bitmap: Bitmap?) {
        try {
            if (bitmap != null && !bitmap.isRecycled) {
                bitmap.recycle()
            }
        } catch (_: Exception) {}
    }

    fun clearCache() {
        clearFrontCache()
        clearBackCache()
    }

    fun destroy() {
        destroyed = true
        pendingGalleryCallback = null
        clearCache()
        try {
            textRecognizer.close()
            faceDetector.close()
            barcodeScanner.close()
            bgExecutor.shutdownNow()
        } catch (_: Exception) {}
    }
}
