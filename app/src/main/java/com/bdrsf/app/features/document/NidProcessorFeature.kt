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
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * Bangladesh NID document-side detector and front/back cross-matcher.
 *
 * Scope:
 * - Identifies likely Bangladesh NID front vs passport/other document.
 * - Reads Latin/English text from both sides using ML Kit Latin OCR.
 * - Reads supported 2D barcodes (especially PDF417, plus QR/Aztec/Data Matrix).
 * - Requires a decodable barcode payload on the back.
 * - Accepts a front/back pair when NID identifiers match exactly; if either side
 *   has no readable NID, both English name AND date of birth must match exactly.
 *
 * This class does NOT call /api/ocr or any backend OCR endpoint.
 * Website/PHP/Gemini OCR remains the responsibility of the web application.
 *
 * Important: OCR/barcode matching is not proof of government-issued authenticity.
 * It is a local document-type and pair-consistency check only.
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
        private const val MAX_CARD_DIMENSION = 2000
        private const val HD_FACE_WIDTH = 720
        private const val HD_FACE_HEIGHT = 860
        private const val BANGLADESH_NID_ASPECT_RATIO = 1.586f
        private const val OCR_TIMEOUT_SECONDS = 15L
        private const val BARCODE_TIMEOUT_SECONDS = 12L

        private val VALID_BARCODE_FORMATS = intArrayOf(
            Barcode.FORMAT_PDF417,
            Barcode.FORMAT_QR_CODE,
            Barcode.FORMAT_AZTEC,
            Barcode.FORMAT_DATA_MATRIX,
            Barcode.FORMAT_CODE_128,
            Barcode.FORMAT_CODE_39
        )
    }

    private data class NidTextData(
        val nid: String = "",
        val name: String = "",
        val dob: String = "",
        val englishText: String = "",
        val allText: String = ""
    )

    private data class BarcodeData(
        val raw: String = "",
        val format: String = "",
        val nid: String = "",
        val name: String = "",
        val dob: String = "",
        val fields: Map<String, String> = emptyMap()
    )

    private val bgExecutor: ExecutorService = Executors.newFixedThreadPool(2)

    @Volatile private var destroyed = false
    @Volatile private var pendingGallerySlot = "nid_front"
    @Volatile private var pendingGalleryCallback: String? = null

    @Volatile private var cachedFrontBase64 = ""
    @Volatile private var cachedBackBase64 = ""
    @Volatile private var cachedFaceBase64 = ""
    @Volatile private var cachedRawBackCode = ""
    @Volatile private var cachedBarcodeMap: Map<String, String> = emptyMap()
    @Volatile private var cachedFrontData: Map<String, String> = emptyMap()
    @Volatile private var cachedBackData: Map<String, String> = emptyMap()
    @Volatile private var cachedBackMatched = false

    private val isFrontProcessing = AtomicBoolean(false)
    private val isBackProcessing = AtomicBoolean(false)

    // Latin recognizer intentionally reads English text. Bengali OCR is handled by the website.
    private val textRecognizer: TextRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private val faceDetector: FaceDetector by lazy {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(0.08f)
            .build()
        FaceDetection.getClient(options)
    }

    private val barcodeScanner: BarcodeScanner by lazy {
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_PDF417,
                Barcode.FORMAT_QR_CODE,
                Barcode.FORMAT_AZTEC,
                Barcode.FORMAT_DATA_MATRIX,
                Barcode.FORMAT_CODE_128,
                Barcode.FORMAT_CODE_39
            )
            .build()
        BarcodeScanning.getClient(options)
    }

    fun openNidCameraScanner(slot: String = "nid_front", callback: String? = null) {
        if (destroyed) return
        val normalizedSlot = normalizeNidSlot(slot)
        val guideText = if (normalizedSlot == "nid_back") {
            "এনআইডি কার্ডের পেছনের অংশ ও 2D কোড/বারকোড ফ্রেমে সোজা রাখুন"
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
            bridge.executeFeature(
                Actions.START_KYC,
                payload.toString(),
                callback ?: "onDocumentValidated"
            )
        }
    }

    fun openGalleryPicker(slot: String = "nid_front", callback: String? = null) {
        if (destroyed) return
        pendingGallerySlot = normalizeNidSlot(slot)
        pendingGalleryCallback = callback

        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply {
            type = "image/*"
        }

        try {
            @Suppress("DEPRECATION")
            activity.startActivityForResult(intent, RC_PICK_NID_GALLERY)
        } catch (e: Exception) {
            pendingGalleryCallback = null
            notifyValidationFailed(
                pendingGallerySlot,
                "ফোনের গ্যালারি চালু করা যায়নি: ${e.localizedMessage ?: "অজানা সমস্যা"}",
                callback
            )
        }
    }

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
                val tempFile = File.createTempFile(
                    "BDRSF_NID_${targetSlot.uppercase(Locale.ROOT)}_",
                    ".jpg",
                    activity.cacheDir
                )
                val input = activity.contentResolver.openInputStream(uri)
                    ?: throw IllegalStateException("গ্যালারির ছবি পড়া সম্ভব হয়নি।")
                input.use { source ->
                    FileOutputStream(tempFile).use { output ->
                        source.copyTo(output)
                        output.flush()
                    }
                }
                processNidSlot(tempFile, targetSlot, callback)
            } catch (e: Exception) {
                notifyValidationFailed(
                    targetSlot,
                    "গ্যালারির ছবি প্রসেস করা যায়নি: ${e.localizedMessage ?: "অজানা সমস্যা"}",
                    callback
                )
            }
        }
        return true
    }

    fun processNidSlot(file: File, targetSlot: String, callback: String? = null) {
        if (destroyed) return
        val slot = normalizeNidSlot(targetSlot)

        if (!file.exists() || file.length() <= 0L) {
            notifyValidationFailed(slot, "ডকুমেন্টের ছবি পাওয়া যায়নি।", callback)
            return
        }

        notifyScanningStarted(slot)

        when (slot) {
            "nid_back" -> processNidBack(file, slot, callback)
            "face_liveness" -> {
                val reference = cachedFaceBase64
                if (reference.isBlank()) {
                    notifyValidationFailed(
                        slot,
                        "আগে বৈধ NID Front থেকে মুখের ছবি সংগ্রহ করুন।",
                        callback
                    )
                } else {
                    faceVerificationFeature?.verifyCapturedSelfieAgainstDocument(
                        file,
                        reference,
                        callback
                    ) ?: notifyValidationFailed(
                        slot,
                        "Face Verification Feature পাওয়া যায়নি।",
                        callback
                    )
                }
            }
            else -> processNidFront(file, "nid_front", callback)
        }
    }

    private fun processNidFront(file: File, slot: String, callback: String?) {
        if (!isFrontProcessing.compareAndSet(false, true)) {
            notifyValidationFailed(slot, "NID Front ইতিমধ্যে প্রসেস হচ্ছে।", callback)
            return
        }

        bgExecutor.execute {
            var bitmap: Bitmap? = null
            try {
                fixExifAndNormalizeFile(file)
                val decoded = decodeOptimizedBitmap(file)
                bitmap = decoded ?: throw IllegalStateException("ছবিটি ডিকোড করা যায়নি।")

                val input = InputImage.fromBitmap(bitmap, 0)
                val visionText = Tasks.await(
                    textRecognizer.process(input),
                    OCR_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
                )
                val rawText = visionText.text.orEmpty()
                val upper = rawText.uppercase(Locale.ROOT)

                if (isPassportText(upper)) {
                    clearFrontCache()
                    notifyValidationFailed(
                        slot,
                        "এটি পাসপোর্টের ছবি। NID Front-এর ছবি দিন।",
                        callback
                    )
                    return@execute
                }

                val front = extractEnglishNidData(rawText)
                if (!isBangladeshNidFront(rawText, front)) {
                    clearFrontCache()
                    notifyValidationFailed(
                        slot,
                        "বাংলাদেশি NID Front শনাক্ত করা যায়নি। কার্ডটি সোজা রেখে পরিষ্কার ছবি দিন।",
                        callback
                    )
                    return@execute
                }

                val base64 = bitmapToBase64(bitmap, 92)
                if (base64.isBlank()) {
                    clearFrontCache()
                    notifyValidationFailed(slot, "NID Front ছবি সংরক্ষণ করা যায়নি।", callback)
                    return@execute
                }

                val face = extractHighResFaceFromBitmap(bitmap)
                    .ifBlank { cropFallbackPortraitFromCard(bitmap) }

                cachedFrontData = linkedMapOf(
                    "nid" to front.nid,
                    "name" to front.name,
                    "dob" to front.dob,
                    "english_text" to front.englishText,
                    "raw_text" to front.allText,
                    "document_type" to "BANGLADESH_NID"
                )
                cachedFrontBase64 = base64
                cachedFaceBase64 = face
                clearBackCacheOnly()
                faceVerificationFeature?.setReferenceDocumentFace(face)

                val response = HashMap(cachedFrontData).apply {
                    put("face_cropped_hd", if (face.isNotBlank()) "true" else "false")
                    put("faceBase64", face)
                    put("image", base64)
                    put("slot", slot)
                }
                notifyValidationSuccess(slot, base64, response, callback)
            } catch (e: Exception) {
                clearFrontCache()
                notifyValidationFailed(
                    slot,
                    "NID Front যাচাই করা যায়নি: ${e.localizedMessage ?: "OCR বা ছবি প্রসেসিং সমস্যা"}",
                    callback
                )
            } finally {
                recycleQuietly(bitmap)
                isFrontProcessing.set(false)
            }
        }
    }

    private fun processNidBack(file: File, slot: String, callback: String?) {
        if (!isBackProcessing.compareAndSet(false, true)) {
            notifyValidationFailed(slot, "NID Back ইতিমধ্যে প্রসেস হচ্ছে।", callback)
            return
        }

        bgExecutor.execute {
            var bitmap: Bitmap? = null
            try {
                if (cachedFrontBase64.isBlank() || cachedFrontData.isEmpty()) {
                    notifyValidationFailed(
                        slot,
                        "আগে NID Front শনাক্ত করুন, তারপর Back Part দিন।",
                        callback
                    )
                    return@execute
                }

                fixExifAndNormalizeFile(file)
                bitmap = decodeOptimizedBitmap(file)
                    ?: throw IllegalStateException("Back ছবিটি ডিকোড করা যায়নি।")

                val input = InputImage.fromBitmap(bitmap, 0)
                val backText = try {
                    Tasks.await(
                        textRecognizer.process(input),
                        OCR_TIMEOUT_SECONDS,
                        TimeUnit.SECONDS
                    ).text.orEmpty()
                } catch (_: Exception) {
                    ""
                }

                // A readable barcode payload is mandatory by the user's validation rule.
                val barcode = readBarcode(input, bitmap)
                if (barcode.raw.isBlank()) {
                    clearBackCacheOnly()
                    notifyValidationFailed(
                        slot,
                        "NID Back-এর QR/PDF417 Barcode পড়া যায়নি। কোডটি সম্পূর্ণ দৃশ্যমান রেখে পরিষ্কার ছবি দিন।",
                        callback
                    )
                    return@execute
                }

                val parsed = parseBarcodePayload(barcode.raw).copy(format = barcode.format)
                if (parsed.fields.isEmpty() &&
                    parsed.nid.isBlank() &&
                    parsed.name.isBlank() &&
                    parsed.dob.isBlank()
                ) {
                    clearBackCacheOnly()
                    notifyValidationFailed(
                        slot,
                        "Back-এর Barcode পাওয়া গেছে, কিন্তু এর ভিতর থেকে যাচাইযোগ্য তথ্য পড়া যায়নি।",
                        callback
                    )
                    return@execute
                }

                val frontNid = cachedFrontData["nid"].orEmpty()
                val frontName = cachedFrontData["name"].orEmpty()
                val frontDob = cachedFrontData["dob"].orEmpty()

                val match = findAnyExactMatch(
                    frontNid = frontNid,
                    frontName = frontName,
                    frontDob = frontDob,
                    back = parsed
                )

                if (!match.first) {
                    clearBackCacheOnly()
                    notifyValidationFailed(
                        slot,
                        "NID Front ও Back-এর নির্ভরযোগ্য মিল পাওয়া যায়নি। NID নম্বর দুই পাশে থাকলে হুবহু মিলতে হবে; কোনো পাশে NID নম্বর না পড়লে English Name ও Date of Birth—দুটিই মিলতে হবে।",
                        callback
                    )
                    return@execute
                }

                val backBase64 = bitmapToBase64(bitmap, 92)
                if (backBase64.isBlank()) {
                    clearBackCacheOnly()
                    notifyValidationFailed(slot, "NID Back ছবি সংরক্ষণ করা যায়নি।", callback)
                    return@execute
                }

                val backEnglish = extractEnglishNidData(backText)
                cachedBackBase64 = backBase64
                cachedRawBackCode = barcode.raw
                cachedBarcodeMap = parsed.fields + mapOf(
                    "nid" to parsed.nid,
                    "name" to parsed.name,
                    "dob" to parsed.dob,
                    "barcode_format" to barcode.format
                )
                cachedBackData = linkedMapOf(
                    "nid" to parsed.nid,
                    "name" to parsed.name,
                    "dob" to parsed.dob,
                    "english_text" to backEnglish.englishText,
                    "raw_text" to backText,
                    "barcode_payload" to barcode.raw,
                    "barcode_format" to barcode.format,
                    "matched_field" to match.second,
                    "matched_with_front" to "true"
                )
                cachedBackMatched = true

                val response = HashMap(cachedBackData).apply {
                    put("image", backBase64)
                    put("slot", slot)
                    put("front_nid", frontNid)
                }
                notifyValidationSuccess(slot, backBase64, response, callback)
            } catch (e: Exception) {
                clearBackCacheOnly()
                notifyValidationFailed(
                    slot,
                    "NID Back যাচাই করা যায়নি: ${e.localizedMessage ?: "Barcode/OCR সমস্যা"}",
                    callback
                )
            } finally {
                recycleQuietly(bitmap)
                isBackProcessing.set(false)
            }
        }
    }

    /**
     * No network request is made here. The website owns Gemini/PHP OCR.
     * This method only reports the locally verified front/back pair to the WebView.
     */
    fun submitNidHybrid(callback: String? = null) {
        if (destroyed) return
        bgExecutor.execute {
            while ((isFrontProcessing.get() || isBackProcessing.get()) && !destroyed) {
                try {
                    Thread.sleep(50)
                } catch (_: InterruptedException) {
                    return@execute
                }
            }
            if (destroyed) return@execute

            if (cachedFrontBase64.isBlank() || cachedBackBase64.isBlank() || !cachedBackMatched) {
                notifyValidationFailed(
                    "nid_back",
                    "সাবমিট করার আগে NID Front ও QR/PDF417-ভিত্তিক Back Matching সম্পন্ন করুন।",
                    callback
                )
                return@execute
            }

            val result = JSONObject().apply {
                put("documentType", "BANGLADESH_NID")
                put("front", JSONObject(cachedFrontData))
                put("back", JSONObject(cachedBackData))
                put("front_image", cachedFrontBase64)
                put("back_image", cachedBackBase64)
                put("faceBase64", cachedFaceBase64)
                put("back_code", cachedRawBackCode)
                put("matched", true)
                put("matched_field", cachedBackData["matched_field"].orEmpty())
                put("verification_scope", "LOCAL_DOCUMENT_TYPE_AND_FRONT_BACK_MATCH")
                put("government_authenticity_verified", false)
            }

            activity.runOnUiThread {
                if (destroyed) return@runOnUiThread
                webView.evaluateJavascript(
                    "if(window.onNidLocalPairVerified){window.onNidLocalPairVerified(${JSONObject.quote(result.toString())});}",
                    null
                )
                bridge.sendResponseToWeb(
                    BridgeResponse(
                        action = Actions.START_KYC,
                        status = Status.SUCCESS,
                        data = result
                    ),
                    callback
                )
            }
        }
    }

    private fun readBarcode(input: InputImage, bitmap: Bitmap): BarcodeData {
        // Do not stop at the first decoded symbol: it may contain unrelated or
        // incomplete data. Prefer the first payload that yields useful NID fields.
        var firstDecoded: BarcodeData? = null
        try {
            val results = Tasks.await(
                barcodeScanner.process(input),
                BARCODE_TIMEOUT_SECONDS,
                TimeUnit.SECONDS
            )
            for (item in results) {
                val payload = readableBarcodePayload(item)
                if (payload.isBlank()) continue

                val candidate = parseBarcodePayload(payload).copy(
                    raw = payload,
                    format = barcodeFormatName(item.format)
                )
                if (firstDecoded == null) firstDecoded = candidate
                if (hasUsefulNidFields(candidate)) return candidate
            }
        } catch (_: Exception) {
            // Continue with ZXing fallback. Do not log decoded payloads (PII).
        }

        val zxingText = decodeBarcodeWithZxing(bitmap)
        if (!zxingText.isNullOrBlank()) {
            val zxingCandidate = parseBarcodePayload(zxingText).copy(
                raw = zxingText,
                format = "ZXING_DECODED"
            )
            if (hasUsefulNidFields(zxingCandidate)) return zxingCandidate
            if (firstDecoded == null) firstDecoded = zxingCandidate
        }

        return firstDecoded ?: BarcodeData()
    }

    private fun hasUsefulNidFields(data: BarcodeData): Boolean {
        return data.nid.isNotBlank() || data.name.isNotBlank() || data.dob.isNotBlank()
    }

    private fun readableBarcodePayload(barcode: Barcode): String {
        val rawValue = barcode.rawValue.orEmpty().trim()
        if (isReadablePayload(rawValue)) return rawValue

        val bytes = barcode.rawBytes ?: return ""
        val utf8 = try {
            String(bytes, StandardCharsets.UTF_8).trim()
        } catch (_: Exception) {
            ""
        }
        if (isReadablePayload(utf8)) return utf8

        val latin = try {
            String(bytes, StandardCharsets.ISO_8859_1).trim()
        } catch (_: Exception) {
            ""
        }
        return if (isReadablePayload(latin)) latin else ""
    }

    private fun isReadablePayload(value: String): Boolean {
        if (value.isBlank() || value.length < 4) return false
        val printable = value.count { it == '\n' || it == '\r' || it == '\t' || it.isLetterOrDigit() || it in "<>{}[]:=|_-/.,; " }
        return printable.toFloat() / value.length.toFloat() >= 0.75f
    }

    private fun barcodeFormatName(format: Int): String = when (format) {
        Barcode.FORMAT_PDF417 -> "PDF417"
        Barcode.FORMAT_QR_CODE -> "QR_CODE"
        Barcode.FORMAT_AZTEC -> "AZTEC"
        Barcode.FORMAT_DATA_MATRIX -> "DATA_MATRIX"
        Barcode.FORMAT_CODE_128 -> "CODE_128"
        Barcode.FORMAT_CODE_39 -> "CODE_39"
        else -> "UNKNOWN"
    }

    private fun parseBarcodePayload(raw: String): BarcodeData {
        val fields = linkedMapOf<String, String>()
        val text = raw.trim()

        // JSON payloads.
        try {
            val json = JSONObject(text)
            collectJsonFields(json, fields)
        } catch (_: Exception) {
            // Not JSON; try XML/key-value/plain text.
        }

        // XML-like payloads, including <PIN>...</PIN>, <DOB>...</DOB>, <NAME>...</NAME>.
        val tagRegex = Regex(
            "<([A-Za-z][A-Za-z0-9_ -]{0,30})>\\s*([^<>]{1,500})\\s*</\\1>",
            RegexOption.IGNORE_CASE
        )
        tagRegex.findAll(text).forEach { match ->
            putNormalizedField(fields, match.groupValues[1], match.groupValues[2])
        }

        // Key-value formats such as PIN: 123..., DOB=1990-01-01, NAME: JOHN DOE.
        val keyValueRegex = Regex(
            "(?im)(?:^|[\\r\\n|;,])\\s*(PIN|NID(?:\\s*NO)?|ID(?:\\s*NO)?|DOB|DATE\\s*OF\\s*BIRTH|NAME(?:\\s*EN)?|ENGLISH\\s*NAME|FULL\\s*NAME|VOTER\\s*NAME)\\s*[:=| -]+\\s*([^\\r\\n|;,<>]{2,100})"
        )
        keyValueRegex.findAll(text).forEach { match ->
            putNormalizedField(fields, match.groupValues[1], match.groupValues[2])
        }

        val nid = findField(fields, setOf("nid", "nidno", "pin", "id", "idno", "nationalid", "nationalidno"))
            .ifBlank { findNidLikeToken(text) }
        val name = findField(fields, setOf("name", "nameen", "englishname", "fullname", "votername"))
        val dob = findField(fields, setOf("dob", "dateofbirth", "birthdate"))

        return BarcodeData(
            raw = text,
            format = "DECODED",
            nid = cleanIdentifier(nid),
            name = normalizeEnglishName(name),
            dob = normalizeDateForMatch(dob),
            fields = fields
        )
    }

    private fun collectJsonFields(json: JSONObject, out: MutableMap<String, String>) {
        val keys = json.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = json.opt(key)
            when (value) {
                is JSONObject -> collectJsonFields(value, out)
                is JSONArray -> {
                    val strings = buildString {
                        for (i in 0 until value.length()) {
                            if (i > 0) append(' ')
                            append(value.opt(i)?.toString().orEmpty())
                        }
                    }
                    putNormalizedField(out, key, strings)
                }
                null, JSONObject.NULL -> Unit
                else -> putNormalizedField(out, key, value.toString())
            }
        }
    }

    private fun putNormalizedField(out: MutableMap<String, String>, rawKey: String, rawValue: String) {
        val key = normalizeKey(rawKey)
        val value = rawValue.trim().trim('"', '\'')
        if (key.isNotBlank() && value.isNotBlank()) out[key] = value
    }

    private fun findField(fields: Map<String, String>, aliases: Set<String>): String {
        for ((key, value) in fields) {
            if (normalizeKey(key) in aliases && value.isNotBlank()) return value
        }
        return ""
    }

    private fun extractEnglishNidData(rawText: String): NidTextData {
        val englishLines = rawText.lines()
            .map { it.trim() }
            .filter { line -> line.any { it in 'A'..'Z' || it in 'a'..'z' || it.isDigit() } }

        val englishText = englishLines.joinToString("\n")
        val upper = englishText.uppercase(Locale.ROOT)

        val nid = extractLabeledNid(englishText).ifBlank { findNidLikeToken(englishText) }
        val dobRaw = extractDob(englishText)
        val name = extractName(englishLines)

        return NidTextData(
            nid = cleanIdentifier(nid),
            name = normalizeEnglishName(name),
            dob = normalizeDateForMatch(dobRaw),
            englishText = englishText,
            allText = rawText
        )
    }

    private fun extractLabeledNid(text: String): String {
        val patterns = listOf(
            Regex(
                "(?i)(?:NID\\s*(?:NO|NUMBER)?|NATIONAL\\s*ID(?:ENTIFICATION)?\\s*(?:NO|NUMBER)?|ID\\s*NO|PIN)\\s*[:#.-]*\\s*([A-Z0-9][A-Z0-9\\s-]{6,25})"
            ),
            Regex(
                "(?i)(?:NID|PIN)\\s*[:#.-]*\\s*([A-Z0-9][A-Z0-9\\s-]{6,25})"
            )
        )
        for (pattern in patterns) {
            val candidate = pattern.find(text)?.groupValues?.getOrNull(1).orEmpty()
                .trim()
                .split(Regex("\\s{2,}|\\n"))
                .firstOrNull().orEmpty()
            val cleaned = cleanIdentifier(candidate)
            if (isPlausibleNidIdentifier(cleaned)) return candidate
        }
        return ""
    }

    private fun findNidLikeToken(text: String): String {
        val candidates = Regex("(?i)(?<![A-Z0-9])[A-Z0-9][A-Z0-9 -]{7,23}(?![A-Z0-9])")
            .findAll(text)
            .map { it.value.trim() }
            .filter { candidate ->
                val cleaned = cleanIdentifier(candidate)
                isPlausibleNidIdentifier(cleaned) &&
                    cleaned.any { it.isDigit() } &&
                    !cleaned.matches(Regex("[0-9]{8}")) // avoid common short dates/year fragments
            }
            .sortedByDescending { cleanIdentifier(it).length }
            .toList()
        return candidates.firstOrNull().orEmpty()
    }

    private fun isPlausibleNidIdentifier(value: String): Boolean {
        if (value.length !in 8..24) return false
        if (value.count { it.isDigit() } < 6) return false
        return value.all { it.isLetterOrDigit() }
    }

    private fun extractDob(text: String): String {
        val patterns = listOf(
            Regex("\\b(?:0?[1-9]|[12][0-9]|3[01])[-/. ](?:0?[1-9]|1[0-2])[-/. ](?:19|20)\\d{2}\\b"),
            Regex("\\b(?:19|20)\\d{2}[-/. ](?:0?[1-9]|1[0-2])[-/. ](?:0?[1-9]|[12][0-9]|3[01])\\b"),
            Regex("(?i)\\b(?:0?[1-9]|[12][0-9]|3[01])\\s+[A-Z]{3,9}\\s+(?:19|20)\\d{2}\\b")
        )
        for (pattern in patterns) {
            val found = pattern.find(text)?.value
            if (!found.isNullOrBlank()) return found
        }
        return ""
    }

    private fun extractName(lines: List<String>): String {
        val labels = listOf("NAME", "NAME EN", "ENGLISH NAME", "FULL NAME")
        for (i in lines.indices) {
            val line = lines[i]
            val upper = line.uppercase(Locale.ROOT)
            val label = labels.firstOrNull { upper.startsWith(it) } ?: continue
            val inline = line.substringAfter(label, "")
                .trim()
                .trim(':', '-', ' ')
            if (inline.length >= 3 && inline.any { it.isLetter() }) return inline
            if (i + 1 < lines.size) {
                val next = lines[i + 1].trim()
                if (next.length >= 3 && next.any { it.isLetter() }) return next
            }
        }
        return ""
    }

    private fun isBangladeshNidFront(rawText: String, data: NidTextData): Boolean {
        val upper = rawText.uppercase(Locale.ROOT)
        if (isPassportText(upper)) return false

        val hasBangladeshNidMarker =
            upper.contains("NATIONAL ID") ||
            upper.contains("NATIONAL IDENTITY") ||
            upper.contains("NID NO") ||
            upper.contains("NID NUMBER") ||
            upper.contains("GOVERNMENT OF THE PEOPLE'S REPUBLIC OF BANGLADESH") ||
            upper.contains("PEOPLE'S REPUBLIC OF BANGLADESH") ||
            rawText.contains("জাতীয় পরিচয়", ignoreCase = true) ||
            rawText.contains("জাতীয় পরিচয়", ignoreCase = true) ||
            rawText.contains("গণপ্রজাতন্ত্রী বাংলাদেশ", ignoreCase = true)

        // Do not require a 10/13/17-digit numeric-only string: Smart NID OCR may
        // split the identifier or read an X-like character inside it.
        return hasBangladeshNidMarker || data.nid.isNotBlank()
    }

    private fun isPassportText(upperText: String): Boolean {
        return upperText.contains("PASSPORT") ||
            upperText.contains("P<BGD") ||
            upperText.contains("PASSPORT NO") ||
            upperText.contains("TYPE P") ||
            Regex("(?m)^P<[A-Z]{3}").containsMatchIn(upperText)
    }

    private fun findAnyExactMatch(
        frontNid: String,
        frontName: String,
        frontDob: String,
        back: BarcodeData
    ): Pair<Boolean, String> {
        val fNid = cleanIdentifier(frontNid)
        val bNid = cleanIdentifier(back.nid)

        // If both sides contain an identifier, it must match exactly. A DOB or
        // name match must never override a conflicting NID number.
        if (fNid.isNotBlank() && bNid.isNotBlank()) {
            return if (fNid == bNid) true to "nid" else false to ""
        }

        // When one side has no readable NID, require BOTH independent fields.
        // A single name-only or DOB-only match is too weak for pair acceptance.
        val fDob = normalizeDateForMatch(frontDob)
        val bDob = normalizeDateForMatch(back.dob)
        val dobMatches = fDob.isNotBlank() && bDob.isNotBlank() && fDob == bDob

        val fName = normalizeEnglishName(frontName)
        val bName = normalizeEnglishName(back.name)
        val nameMatches = fName.length >= 4 && bName.length >= 4 && fName == bName

        return if (dobMatches && nameMatches) true to "name_and_dob" else false to ""
    }

    private fun cleanIdentifier(value: String): String {
        return value.uppercase(Locale.ROOT)
            .replace('Ｏ', '0')
            .replace('О', '0')
            .replace('Ｘ', 'X')
            .replace(Regex("[^A-Z0-9]"), "")
    }

    private fun normalizeEnglishName(value: String): String {
        return value.uppercase(Locale.ROOT)
            .replace(Regex("[^A-Z ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun normalizeDateForMatch(value: String): String {
        val raw = value.trim().uppercase(Locale.ROOT)
        if (raw.isBlank()) return ""

        val numeric = Regex("(\\d{1,4})[-/. ](\\d{1,2})[-/. ](\\d{2,4})").find(raw)
        if (numeric != null) {
            val a = numeric.groupValues[1].toIntOrNull() ?: return ""
            val b = numeric.groupValues[2].toIntOrNull() ?: return ""
            val c = numeric.groupValues[3].toIntOrNull() ?: return ""
            val year: Int
            val month: Int
            val day: Int
            if (numeric.groupValues[1].length == 4) {
                year = a; month = b; day = c
            } else {
                day = a; month = b; year = if (c < 100) 2000 + c else c
            }
            if (year !in 1900..2099 || month !in 1..12 || day !in 1..31) return ""
            return "%04d%02d%02d".format(Locale.ROOT, year, month, day)
        }

        val monthNames = mapOf(
            "JAN" to 1, "JANUARY" to 1, "FEB" to 2, "FEBRUARY" to 2,
            "MAR" to 3, "MARCH" to 3, "APR" to 4, "APRIL" to 4,
            "MAY" to 5, "JUN" to 6, "JUNE" to 6, "JUL" to 7, "JULY" to 7,
            "AUG" to 8, "AUGUST" to 8, "SEP" to 9, "SEPT" to 9, "SEPTEMBER" to 9,
            "OCT" to 10, "OCTOBER" to 10, "NOV" to 11, "NOVEMBER" to 11,
            "DEC" to 12, "DECEMBER" to 12
        )
        val named = Regex("(?i)(\\d{1,2})\\s+([A-Z]{3,9})\\s+(\\d{4})").find(raw)
        if (named != null) {
            val day = named.groupValues[1].toIntOrNull() ?: return ""
            val month = monthNames[named.groupValues[2].uppercase(Locale.ROOT)] ?: return ""
            val year = named.groupValues[3].toIntOrNull() ?: return ""
            if (year !in 1900..2099 || day !in 1..31) return ""
            return "%04d%02d%02d".format(Locale.ROOT, year, month, day)
        }
        return ""
    }

    private fun normalizeKey(key: String): String {
        return key.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]"), "")
    }

    private fun decodeBarcodeWithZxing(bitmap: Bitmap): String? {
        return try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(
                pixels, 0, bitmap.width,
                0, 0, bitmap.width, bitmap.height
            )
            val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
            val hints = mapOf(
                DecodeHintType.TRY_HARDER to true,
                DecodeHintType.CHARACTER_SET to "UTF-8"
            )
            MultiFormatReader().decode(
                BinaryBitmap(HybridBinarizer(source)),
                hints
            ).text
        } catch (_: Exception) {
            null
        }
    }

    private fun decodeOptimizedBitmap(file: File): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        val maxDimension = max(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (maxDimension / sample > MAX_CARD_DIMENSION) sample *= 2

        return BitmapFactory.decodeFile(
            file.absolutePath,
            BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
        )
    }

    private fun bitmapToBase64(bitmap: Bitmap, quality: Int): String {
        return try {
            val output = ByteArrayOutputStream()
            if (!bitmap.compress(Bitmap.CompressFormat.JPEG, quality, output)) return ""
            "data:image/jpeg;base64," +
                Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
        } catch (_: Exception) {
            ""
        }
    }

    private fun fixExifAndNormalizeFile(file: File) {
        var original: Bitmap? = null
        var rotated: Bitmap? = null
        try {
            val exif = ExifInterface(file.absolutePath)
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
            val degrees = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (degrees == 0f) return

            original = BitmapFactory.decodeFile(file.absolutePath) ?: return
            val matrix = Matrix().apply { postRotate(degrees) }
            val normalized = Bitmap.createBitmap(
                original, 0, 0, original.width, original.height, matrix, true
            )
            rotated = normalized
            FileOutputStream(file).use { output ->
                normalized.compress(Bitmap.CompressFormat.JPEG, 95, output)
                output.flush()
            }
        } catch (_: Exception) {
            // Continue with the original image; downstream decoding will decide validity.
        } finally {
            if (rotated !== original) recycleQuietly(rotated)
            recycleQuietly(original)
        }
    }

    private fun extractHighResFaceFromBitmap(bitmap: Bitmap): String {
        var crop: Bitmap? = null
        var scaled: Bitmap? = null
        return try {
            val faces = Tasks.await(
                faceDetector.process(InputImage.fromBitmap(bitmap, 0)),
                6,
                TimeUnit.SECONDS
            )
            val box = faces.firstOrNull()?.boundingBox ?: return ""
            val padX = (box.width() * 0.28f).toInt()
            val padY = (box.height() * 0.36f).toInt()
            val x = (box.left - padX).coerceIn(0, bitmap.width - 1)
            val y = (box.top - padY).coerceIn(0, bitmap.height - 1)
            val width = (box.width() + padX * 2).coerceAtMost(bitmap.width - x)
            val height = (box.height() + padY * 2).coerceAtMost(bitmap.height - y)
            if (width < 24 || height < 24) return ""

            crop = Bitmap.createBitmap(bitmap, x, y, width, height)
            val hd = Bitmap.createScaledBitmap(crop, HD_FACE_WIDTH, HD_FACE_HEIGHT, true)
            scaled = hd
            bitmapToBase64(hd, 95)
        } catch (_: Exception) {
            ""
        } finally {
            recycleQuietly(scaled)
            recycleQuietly(crop)
        }
    }

    private fun cropFallbackPortraitFromCard(bitmap: Bitmap): String {
        var crop: Bitmap? = null
        var scaled: Bitmap? = null
        return try {
            val width = bitmap.width
            val height = bitmap.height
            if (width < 80 || height < 60) return ""

            // Approximate portrait zone for common horizontal Bangladesh NID layouts.
            val left = (width * 0.04f).toInt().coerceIn(0, width - 1)
            val top = (height * 0.20f).toInt().coerceIn(0, height - 1)
            val cropWidth = (width * 0.30f).toInt().coerceAtMost(width - left).coerceAtLeast(1)
            val cropHeight = (height * 0.60f).toInt().coerceAtMost(height - top).coerceAtLeast(1)
            crop = Bitmap.createBitmap(bitmap, left, top, cropWidth, cropHeight)
            scaled = Bitmap.createScaledBitmap(crop, HD_FACE_WIDTH, HD_FACE_HEIGHT, true)
            bitmapToBase64(scaled, 92)
        } catch (_: Exception) {
            ""
        } finally {
            recycleQuietly(scaled)
            recycleQuietly(crop)
        }
    }

    private fun normalizeNidSlot(rawSlot: String?): String {
        val value = rawSlot.orEmpty().trim().lowercase(Locale.ROOT)
        return when {
            value.contains("back") -> "nid_back"
            value.contains("face") || value.contains("selfie") || value.contains("live") -> "face_liveness"
            else -> "nid_front"
        }
    }

    private fun notifyScanningStarted(slot: String) {
        if (destroyed) return
        activity.runOnUiThread {
            if (!destroyed) {
                webView.evaluateJavascript(
                    "if(window.onImageScanningStarted){window.onImageScanningStarted(${JSONObject.quote(slot)});}",
                    null
                )
            }
        }
    }

    private fun notifyValidationSuccess(
        slot: String,
        base64: String,
        data: Map<String, String>,
        callback: String?
    ) {
        if (destroyed) return
        activity.runOnUiThread {
            if (destroyed) return@runOnUiThread
            Toast.makeText(activity, "✔ $slot যাচাই সম্পন্ন", Toast.LENGTH_SHORT).show()

            webView.evaluateJavascript(
                "if(window.onDocumentValidated){window.onDocumentValidated(${JSONObject.quote(slot)},true,${JSONObject.quote(base64)});}",
                null
            )

            val json = JSONObject().apply {
                put("slot", slot)
                put("image", base64)
                data.forEach { (key, value) -> put(key, value) }
            }
            bridge.sendResponseToWeb(
                BridgeResponse(
                    action = Actions.START_KYC,
                    status = Status.SUCCESS,
                    data = json
                ),
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
                "if(window.onDocumentValidated){window.onDocumentValidated(${JSONObject.quote(slot)},false,'');}",
                null
            )
            webView.evaluateJavascript(
                "if(window.onDocumentValidationFailed){window.onDocumentValidationFailed(${JSONObject.quote(slot)},${JSONObject.quote(message)});}",
                null
            )
            bridge.sendResponseToWeb(
                BridgeResponse(
                    action = Actions.START_KYC,
                    status = Status.ERROR,
                    message = message
                ),
                callback
            )
        }
    }

    private fun clearBackCacheOnly() {
        cachedBackBase64 = ""
        cachedRawBackCode = ""
        cachedBarcodeMap = emptyMap()
        cachedBackData = emptyMap()
        cachedBackMatched = false
    }

    private fun clearFrontCache() {
        cachedFrontBase64 = ""
        cachedFaceBase64 = ""
        cachedFrontData = emptyMap()
        clearBackCacheOnly()
        faceVerificationFeature?.setReferenceDocumentFace("")
    }

    fun clearCache() {
        clearFrontCache()
    }

    private fun recycleQuietly(bitmap: Bitmap?) {
        try {
            if (bitmap != null && !bitmap.isRecycled) bitmap.recycle()
        } catch (_: Exception) {
            // Ignore cleanup failures.
        }
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
        } catch (_: Exception) {
            // Ignore shutdown failures.
        }
    }
}
