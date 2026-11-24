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
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min

/**
 * BDRSF ডেডিকেটেড পাসপোর্ট প্রসেসর ইঞ্জিন।
 * - পাসপোর্টের আলাদা ব্যাক পার্ট নেই; দুই পাতা একসাথে A4 সাইজ কাস্টম ক্যামেরায় স্ক্যান হয়।
 * - বাঁকা/তেরা ছবি সোজা (deskew) করে এবং A4 ফ্রেমে ক্রপ করে।
 * - পাসপোর্টের ছবি ক্রপ করে হাই-রেজুলেশনে (720x860) কনভার্ট করে ফেস ম্যাচিংয়ের জন্য প্রস্তুত রাখে।
 * - ভেতরের তথ্য এবং QR/বারকোড/MRZ ক্রস-যাচাই করে সঠিকতা নিশ্চিত করে।
 */
class PassportProcessorFeature(
    private val activity: Activity,
    private val bridge: AndroidBridge,
    private val webView: WebView,
    private val faceVerificationFeature: FaceVerificationFeature? = null,
    private val documentScannerFeature: DocumentScannerFeature? = null
) {

    companion object {
        const val RC_PICK_PASSPORT_GALLERY = 6012
        private const val A4_SPREAD_ASPECT_RATIO = 0.707f // A4 portrait spread (width / height)
        private const val MAX_IMAGE_DIMENSION = 1600
        private const val OCR_IMAGE_QUALITY = 90
        private const val HD_FACE_WIDTH = 720
        private const val HD_FACE_HEIGHT = 860
    }

    private val bgExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    @Volatile
    private var activePolicy = JSONObject()

    @Volatile
    private var destroyed = false

    @Volatile
    private var pendingCallback: String? = null

    private val textRecognizerDelegate = lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private val textRecognizer: TextRecognizer
        get() = textRecognizerDelegate.value

    private val faceDetector by lazy {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(0.08f)
            .build()
        FaceDetection.getClient(options)
    }

    private val barcodeScanner by lazy {
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_QR_CODE,
                Barcode.FORMAT_PDF417,
                Barcode.FORMAT_DATA_MATRIX,
                Barcode.FORMAT_AZTEC,
                Barcode.FORMAT_CODE_128
            )
            .build()
        BarcodeScanning.getClient(options)
    }

    fun updatePolicy(policyPayload: JSONObject?) {
        activePolicy = copyJson(policyPayload ?: JSONObject())
    }

    private fun copyJson(source: JSONObject): JSONObject {
        return JSONObject(source.toString())
    }

    /**
     * পাসপোর্টের দুই পাতা একসাথে ধরার জন্য A4 সাইজের কাস্টম ক্যামেরা চালু করে।
     */
    fun openPassportCameraScanner(policyPayload: JSONObject? = null, callback: String? = null) {
        if (destroyed) {
            notifyError("Passport Processor বন্ধ রয়েছে", callback)
            return
        }
        val policy = copyJson(policyPayload ?: activePolicy)
        updatePolicy(policy)
        pendingCallback = callback

        val payload = JSONObject(policy.toString()).apply {
            put("mode", "passport")
            put("scanMode", "passport")
            put("documentType", "PASSPORT")
            put("docType", "PASSPORT")
            put("docCategory", "passport")
            put("frameRatio", A4_SPREAD_ASPECT_RATIO.toDouble())
            put("guideText", "পাসপোর্টের উভয় পাতা একসাথে A4 ফ্রেমে সোজা রাখুন")
            put("autoCapture", true)
        }

        if (documentScannerFeature != null) {
            documentScannerFeature.startScanner(payload, callback)
        } else {
            bridge.executeFeature(Actions.START_KYC, payload.toString(), callback ?: "onDocumentValidated")
        }
    }

    /**
     * সরাসরি ফোনের গ্যালারি খুলে পাসপোর্টের ছবি নির্বাচন করে।
     */
    fun openGalleryPicker(policyPayload: JSONObject, callback: String?) {
        if (destroyed) {
            notifyError("Passport Processor বন্ধ রয়েছে", callback)
            return
        }
        updatePolicy(policyPayload)
        pendingCallback = callback
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply {
            type = "image/*"
        }
        try {
            @Suppress("DEPRECATION")
            activity.startActivityForResult(intent, RC_PICK_PASSPORT_GALLERY)
        } catch (e: Exception) {
            pendingCallback = null
            notifyError("গ্যালারি ওপেন করা যায়নি: ${e.localizedMessage ?: ""}", callback)
        }
    }

    fun handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != RC_PICK_PASSPORT_GALLERY) return false
        val callback = pendingCallback
        pendingCallback = null
        if (destroyed) return true
        if (resultCode != Activity.RESULT_OK) {
            notifyCancelled(callback)
            return true
        }
        val uri = data?.data
        if (uri == null) {
            notifyError("গ্যালারি থেকে ছবি পাওয়া যায়নি", callback)
            return true
        }
        try {
            val tempFile = File.createTempFile("BDRSF_PASSPORT_", ".jpg", activity.cacheDir)
            val input = activity.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("ছবি পড়া সম্ভব হয়নি")
            input.use { source ->
                FileOutputStream(tempFile).use { output -> source.copyTo(output) }
            }
            processPassportFile(tempFile, copyJson(activePolicy), callback)
        } catch (e: Exception) {
            notifyError("গ্যালারি ছবি লোড করা যায়নি: ${e.localizedMessage ?: ""}", callback)
        }
        return true
    }

    fun processPassportFile(file: File, policyPayload: JSONObject? = null, callback: String? = null) {
        if (destroyed) {
            notifyError("Passport Processor বন্ধ রয়েছে", callback)
            return
        }
        val policy = copyJson(policyPayload ?: activePolicy)
        if (!file.exists() || file.length() <= 0L) {
            notifyError("পাসপোর্টের ছবি পাওয়া যায়নি", callback)
            return
        }
        notifyScanningStarted()
        try {
            bgExecutor.execute {
                var processedBitmap: Bitmap? = null
                val bitmapRecycled = AtomicBoolean(false)
                fun recycleBitmap() {
                    if (bitmapRecycled.compareAndSet(false, true)) {
                        processedBitmap?.let { if (!it.isRecycled) it.recycle() }
                    }
                }
                try {
                    fixExifOrientation(file)
                    cropToA4SpreadFile(file)

                    val imageResult = optimizeAndCompress(file)
                    processedBitmap = imageResult.first
                    var fullPageBase64 = imageResult.second
                    var bitmap = processedBitmap
                    if (bitmap == null || bitmap.isRecycled || fullPageBase64.isEmpty()) {
                        recycleBitmap()
                        notifyError("পাসপোর্টের ছবি প্রসেস করা যায়নি", callback)
                        return@execute
                    }

                    if (destroyed) {
                        recycleBitmap()
                        return@execute
                    }

                    var inputImage = InputImage.fromBitmap(bitmap, 0)
                    var visionText = Tasks.await(textRecognizer.process(inputImage), 8, TimeUnit.SECONDS)

                    // বাঁকা/তেরা থাকলে টেক্সট লাইনের অ্যাঙ্গেল অনুযায়ী সোজা (deskew) করা
                    val deskewAngle = estimateSkewAngle(visionText)
                    if (abs(deskewAngle) in 1.2f..22.0f) {
                        val straightened = rotateBitmap(bitmap, -deskewAngle)
                        if (straightened != null && straightened !== bitmap) {
                            recycleBitmap()
                            bitmapRecycled.set(false)
                            processedBitmap = straightened
                            bitmap = straightened
                            fullPageBase64 = bitmapToBase64(bitmap, OCR_IMAGE_QUALITY)
                            saveBitmapToFile(bitmap, file)
                            inputImage = InputImage.fromBitmap(bitmap, 0)
                            visionText = Tasks.await(textRecognizer.process(inputImage), 8, TimeUnit.SECONDS)
                        }
                    }

                    val rawText = visionText.text.orEmpty()
                    val validationError = validateAgainstPhpPolicy(rawText, policy)
                    if (validationError != null) {
                        recycleBitmap()
                        notifyError(validationError, callback)
                        return@execute
                    }

                    val docFaceBase64 = extractHighResFaceFromBitmap(bitmap)
                    val decodedCodes = scanPassportCodes(inputImage)
                    val fields = extractAllEnglishPassportFields(visionText).toMutableMap()

                    if (decodedCodes.first.isNotBlank()) {
                        fields["qrCodeData"] = decodedCodes.first
                    }
                    if (decodedCodes.second.isNotBlank()) {
                        fields["barcodeData"] = decodedCodes.second
                    }

                    if (!isPassportDocument(rawText, fields)) {
                        recycleBitmap()
                        notifyError(
                            "এটি বৈধ পাসপোর্ট হিসেবে সনাক্ত হয়নি। পাসপোর্টের তথ্য ও ছবির পাতা স্পষ্টভাবে দিন।",
                            callback
                        )
                        return@execute
                    }

                    val internalCheck = verifyPassportInternalConsistency(fields, decodedCodes.first, decodedCodes.second)
                    if (!internalCheck.first) {
                        recycleBitmap()
                        notifyError(internalCheck.second, callback)
                        return@execute
                    }

                    fields["consistencyVerified"] = "true"
                    fields["consistencyNote"] = internalCheck.second

                    if (docFaceBase64.isNotBlank()) {
                        faceVerificationFeature?.setReferenceDocumentFace(docFaceBase64)
                    }

                    dispatchPassportSuccess(
                        fullPageBase64 = fullPageBase64,
                        docFaceBase64 = docFaceBase64,
                        fields = fields,
                        qrProcessed = decodedCodes.first.isNotBlank(),
                        barcodeProcessed = decodedCodes.second.isNotBlank(),
                        callback = callback
                    )
                    recycleBitmap()
                } catch (e: Exception) {
                    recycleBitmap()
                    notifyError("পাসপোর্ট প্রসেসিং ত্রুটি: ${e.localizedMessage ?: ""}", callback)
                }
            }
        } catch (e: Exception) {
            notifyError("প্রসেসিং শুরু করা যায়নি: ${e.localizedMessage ?: ""}", callback)
        }
    }

    private fun scanPassportCodes(inputImage: InputImage): Pair<String, String> {
        return try {
            val barcodes = Tasks.await(barcodeScanner.process(inputImage), 4, TimeUnit.SECONDS)
            val qrList = mutableListOf<String>()
            val barList = mutableListOf<String>()
            for (b in barcodes) {
                val raw = b.rawBytes?.let {
                    try { String(it, StandardCharsets.UTF_8) } catch (_: Exception) { b.rawValue ?: "" }
                } ?: (b.rawValue ?: "")
                if (raw.isBlank()) continue
                if (b.format == Barcode.FORMAT_QR_CODE) qrList.add(raw.trim()) else barList.add(raw.trim())
            }
            Pair(qrList.joinToString(" | "), barList.joinToString(" | "))
        } catch (_: Exception) {
            Pair("", "")
        }
    }

    /**
     * পাসপোর্টের ছবি ক্রপ করে হাই-রেজুলেশন (720x860) JPEG Base64-এ কনভার্ট করে।
     */
    private fun extractHighResFaceFromBitmap(bitmap: Bitmap): String {
        var rawFace: Bitmap? = null
        var hdFace: Bitmap? = null
        return try {
            val faces = Tasks.await(faceDetector.process(InputImage.fromBitmap(bitmap, 0)), 4, TimeUnit.SECONDS)
            val box = faces?.firstOrNull()?.boundingBox ?: return ""

            val padX = (box.width() * 0.28f).toInt()
            val padY = (box.height() * 0.36f).toInt()
            val x = (box.left - padX).coerceIn(0, max(0, bitmap.width - 1))
            val y = (box.top - padY).coerceIn(0, max(0, bitmap.height - 1))
            val w = (box.width() + padX * 2).coerceAtMost(bitmap.width - x)
            val h = (box.height() + padY * 2).coerceAtMost(bitmap.height - y)

            if (w >= 32 && h >= 32) {
                rawFace = Bitmap.createBitmap(bitmap, x, y, w, h)
                hdFace = Bitmap.createScaledBitmap(rawFace, HD_FACE_WIDTH, HD_FACE_HEIGHT, true)
                return bitmapToBase64(hdFace, 95)
            }
            ""
        } catch (_: Exception) {
            ""
        } finally {
            if (hdFace !== rawFace) hdFace?.let { if (!it.isRecycled) it.recycle() }
            rawFace?.let { if (!it.isRecycled) it.recycle() }
        }
    }

    private fun estimateSkewAngle(visionText: Text): Float {
        val angles = mutableListOf<Float>()
        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                val box = line.boundingBox ?: continue
                if (box.width() > box.height() * 2.5f && box.width() > 80) {
                    val angle = line.angle
                    if (!angle.isNaN() && abs(angle) <= 25f) {
                        angles.add(angle)
                    }
                }
            }
        }
        if (angles.isEmpty()) return 0f
        angles.sort()
        return angles[angles.size / 2]
    }

    private fun rotateBitmap(source: Bitmap, degrees: Float): Bitmap? {
        return try {
            val matrix = Matrix().apply { postRotate(degrees) }
            Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
        } catch (_: Exception) {
            null
        }
    }

    private fun cropToA4SpreadFile(file: File) {
        var original: Bitmap? = null
        var cropped: Bitmap? = null
        try {
            original = BitmapFactory.decodeFile(file.absolutePath) ?: return
            val currentRatio = original.width.toFloat() / max(1, original.height).toFloat()
            val targetW: Int
            val targetH: Int

            if (currentRatio > A4_SPREAD_ASPECT_RATIO) {
                targetH = (original.height * 0.94f).toInt().coerceAtLeast(1)
                targetW = (targetH * A4_SPREAD_ASPECT_RATIO).toInt().coerceAtMost(original.width)
            } else {
                targetW = (original.width * 0.94f).toInt().coerceAtLeast(1)
                targetH = (targetW / A4_SPREAD_ASPECT_RATIO).toInt().coerceAtMost(original.height)
            }

            val x = ((original.width - targetW) / 2).coerceIn(0, max(0, original.width - 1))
            val y = ((original.height - targetH) / 2).coerceIn(0, max(0, original.height - 1))
            val safeW = targetW.coerceAtMost(original.width - x).coerceAtLeast(1)
            val safeH = targetH.coerceAtMost(original.height - y).coerceAtLeast(1)

            cropped = Bitmap.createBitmap(original, x, y, safeW, safeH)
            saveBitmapToFile(cropped, file)
        } catch (_: Exception) {
        } finally {
            if (cropped !== original) cropped?.let { if (!it.isRecycled) it.recycle() }
            original?.let { if (!it.isRecycled) it.recycle() }
        }
    }

    private fun saveBitmapToFile(bitmap: Bitmap, file: File) {
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 92, out)
            out.flush()
        }
    }

    private fun bitmapToBase64(bitmap: Bitmap, quality: Int): String {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, quality, out)
        return "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    /**
     * পাসপোর্টের ভেতরে থাকা তথ্য ও QR/বারকোড/MRZ ক্রস-যাচাই করে।
     */
    private fun verifyPassportInternalConsistency(
        fields: Map<String, String>,
        qrData: String,
        barcodeData: String
    ): Pair<Boolean, String> {
        val passportNo = fields["passportNo"].orEmpty().trim().uppercase(Locale.ROOT)
        val combinedCodeText = "$qrData $barcodeData".trim().uppercase(Locale.ROOT)

        if (passportNo.isBlank()) {
            return Pair(false, "পাসপোর্ট নম্বর পাওয়া যায়নি। স্পষ্ট ছবি দিন।")
        }

        if (combinedCodeText.isNotBlank()) {
            val codeDigitsOrAlnum = combinedCodeText.replace(Regex("[^A-Z0-9]"), "")
            val cleanPassNo = passportNo.replace(Regex("[^A-Z0-9]"), "")
            if (cleanPassNo.length >= 6 && !codeDigitsOrAlnum.contains(cleanPassNo)) {
                return Pair(
                    false,
                    "পাসপোর্টের ভেতরের QR/বারকোড তথ্যের সাথে পাসপোর্ট নম্বর ($passportNo) মিলছে না!"
                )
            }
            return Pair(true, "পাসপোর্টের তথ্য এবং QR/বারকোড সফলভাবে মিলেছে।")
        }

        return Pair(true, "পাসপোর্টের তথ্য ও MRZ সফলভাবে যাচাই হয়েছে।")
    }

    private fun isPassportDocument(rawText: String, fields: Map<String, String>): Boolean {
        val upper = rawText.uppercase(Locale.ROOT)
        val hasPassportWord = upper.contains("PASSPORT") ||
            upper.contains("PEOPLE'S REPUBLIC OF") ||
            upper.contains("P<")
        val indicators = listOf(
            fields["passportNo"],
            fields["nationality"],
            fields["dob"],
            fields["surname"],
            fields["givenNames"],
            fields["passportType"],
            fields["countryCode"]
        ).count { !it.isNullOrBlank() }
        return hasPassportWord && indicators >= 2
    }

    private fun validateAgainstPhpPolicy(rawText: String, policy: JSONObject): String? {
        val upper = rawText.uppercase(Locale.ROOT)
        val rejectList = jsonArrayToList(policy.optJSONArray("rejectIfContains")).ifEmpty {
            listOf("NATIONAL ID", "NID NO", "জাতীয় পরিচয়")
        }
        for (word in rejectList) {
            if (word.isNotBlank() && upper.contains(word.uppercase(Locale.ROOT))) {
                return policy.optString("rejectMessage", "ভুল ডকুমেন্ট! পাসপোর্টের ছবি দিন।")
            }
        }
        val required = jsonArrayToList(policy.optJSONArray("mustContainAny"))
        if (required.isNotEmpty()) {
            val matched = required.any { keyword -> upper.contains(keyword.uppercase(Locale.ROOT)) }
            if (!matched) {
                return policy.optString("invalidMessage", "এটি গ্রহণযোগ্য পাসপোর্টের ছবি নয়।")
            }
        }
        return null
    }

    private fun jsonArrayToList(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        val result = mutableListOf<String>()
        for (i in 0 until array.length()) {
            val value = array.optString(i, "").trim()
            if (value.isNotEmpty()) result.add(value)
        }
        return result
    }

    private fun normalizeLine(value: String): String = value.replace('\u00A0', ' ').replace(Regex("\\s+"), " ").trim()

    private fun cleanValue(value: String): String = normalizeLine(value).replace(Regex("^[/\\\\|]+"), "").trim(':', '-', ' ', '.', ';', '/')

    private fun getOcrLines(visionText: Text): List<String> {
        val result = mutableListOf<String>()
        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                val value = normalizeLine(line.text)
                if (value.isNotEmpty()) result.add(value)
            }
        }
        if (result.isEmpty()) {
            result.addAll(visionText.text.lines().map { normalizeLine(it) }.filter { it.isNotEmpty() })
        }
        return result
    }

    private fun extractLabelValue(lines: List<String>, labels: List<String>, nextLines: Int = 1): String {
        val sortedLabels = labels.sortedByDescending { it.length }
        for (i in lines.indices) {
            val line = lines[i]
            val upper = line.uppercase(Locale.ROOT)
            for (label in sortedLabels) {
                val labelUpper = label.uppercase(Locale.ROOT)
                val index = upper.indexOf(labelUpper)
                if (index < 0) continue
                val after = cleanValue(line.substring(index + label.length))
                if (after.isNotBlank() && !isKnownLabelLine(after)) return after
                for (offset in 1..nextLines) {
                    val next = lines.getOrNull(i + offset).orEmpty()
                    if (next.isNotBlank() && !isKnownLabelLine(next)) return cleanValue(next)
                }
            }
        }
        return ""
    }

    private fun extractMultilineValue(lines: List<String>, labels: List<String>, maxNextLines: Int = 2): String {
        val sortedLabels = labels.sortedByDescending { it.length }
        for (i in lines.indices) {
            val line = lines[i]
            val upper = line.uppercase(Locale.ROOT)
            for (label in sortedLabels) {
                val index = upper.indexOf(label.uppercase(Locale.ROOT))
                if (index < 0) continue
                val parts = mutableListOf<String>()
                val firstValue = cleanValue(line.substring(index + label.length))
                if (firstValue.isNotBlank() && !isKnownLabelLine(firstValue)) parts.add(firstValue)
                for (offset in 1..maxNextLines) {
                    val next = lines.getOrNull(i + offset).orEmpty()
                    if (next.isBlank() || isKnownLabelLine(next)) break
                    parts.add(cleanValue(next))
                }
                return parts.joinToString(" ").trim()
            }
        }
        return ""
    }

    private fun isKnownLabelLine(value: String): Boolean {
        val upper = value.uppercase(Locale.ROOT)
        val labels = listOf(
            "PASSPORT NUMBER", "PASSPORT NO", "COUNTRY CODE", "NATIONALITY",
            "SURNAME", "GIVEN NAME", "DATE OF BIRTH", "DATE OF ISSUE",
            "DATE OF EXPIRY", "PERSONAL NO", "PERSONAL NUMBER", "PLACE OF BIRTH",
            "ISSUING AUTHORITY", "FATHER'S NAME", "FATHER NAME", "MOTHER'S NAME",
            "MOTHER NAME", "SPOUSE'S NAME", "SPOUSE NAME", "PERMANENT ADDRESS",
            "EMERGENCY CONTACT", "RELATIONSHIP", "TELEPHONE NO", "TELEPHONE",
            "MOBILE", "PHONE", "NAME:", "ADDRESS:"
        )
        return labels.any { upper.contains(it) }
    }

    private fun findDateNearLabel(lines: List<String>, labels: List<String>): String {
        val dateRegex = Regex(
            "\\b(\\d{1,2}\\s+(?:JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|OCT|NOV|DEC)[A-Z]*\\s+\\d{4}" +
                "|\\d{1,2}/\\d{1,2}/\\d{4}" +
                "|\\d{4}-\\d{2}-\\d{2}" +
                "|\\d{1,2}-\\d{1,2}-\\d{4})\\b",
            RegexOption.IGNORE_CASE
        )
        for (i in lines.indices) {
            val upper = lines[i].uppercase(Locale.ROOT)
            if (labels.any { upper.contains(it.uppercase(Locale.ROOT)) }) {
                val searchText = "${lines[i]} ${lines.getOrNull(i + 1).orEmpty()}"
                val match = dateRegex.find(searchText)
                if (match != null) return normalizeDate(match.groupValues[1])
            }
        }
        return ""
    }

    private fun normalizeDate(value: String): String {
        val formats = listOf(
            "dd MMM yyyy", "d MMM yyyy", "dd MMMM yyyy", "d MMMM yyyy",
            "dd/MM/yyyy", "d/M/yyyy", "dd-MM-yyyy", "d-M-yyyy", "yyyy-MM-dd"
        )
        for (format in formats) {
            try {
                val parser = SimpleDateFormat(format, Locale.US).apply { isLenient = false }
                val date: Date = parser.parse(value.trim()) ?: continue
                return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(date)
            } catch (_: Exception) {}
        }
        return value.trim()
    }

    private fun parseMrzFallback(lines: List<String>, result: MutableMap<String, String>) {
        val mrzCandidates = lines.map { it.replace(" ", "").uppercase(Locale.ROOT) }
            .filter { it.length >= 30 && it.contains("<") }
        if (mrzCandidates.isEmpty()) return

        val line1 = mrzCandidates.firstOrNull { it.startsWith("P<") }
        val line2 = mrzCandidates.firstOrNull { it != line1 && it.count { ch -> ch.isDigit() } >= 10 }

        if (line1 != null && line1.length >= 6) {
            val country = line1.substring(2, 5).replace("<", "")
            if (result["countryCode"].isNullOrBlank() && country.length == 3) result["countryCode"] = country
            val namesPart = line1.substring(5)
            val nameSections = namesPart.split("<<")
            val mrzSurname = nameSections.getOrNull(0)?.replace("<", " ")?.trim().orEmpty()
            val mrzGiven = nameSections.getOrNull(1)?.replace("<", " ")?.trim().orEmpty()
            if (result["surname"].isNullOrBlank() && mrzSurname.isNotBlank()) result["surname"] = mrzSurname
            if (result["givenNames"].isNullOrBlank() && mrzGiven.isNotBlank()) result["givenNames"] = mrzGiven
        }

        if (line2 != null && line2.length >= 28) {
            val rawPass = line2.substring(0, 9).replace("<", "").trim()
            if (result["passportNo"].isNullOrBlank() && rawPass.matches(Regex("[A-Z0-9]{7,9}"))) result["passportNo"] = rawPass
            val nat = line2.substring(10, 13).replace("<", "").trim()
            if (result["nationality"].isNullOrBlank() && nat == "BGD") result["nationality"] = "BANGLADESHI"
            val dobRaw = line2.substring(13, 19)
            if (result["dob"].isNullOrBlank() && dobRaw.all { it.isDigit() }) result["dob"] = parseMrzDate(dobRaw, isExpiry = false)
            val sex = line2.substring(20, 21)
            if (result["gender"].isNullOrBlank() && (sex == "M" || sex == "F")) result["gender"] = sex
            val expRaw = line2.substring(21, 27)
            if (result["expiryDate"].isNullOrBlank() && expRaw.all { it.isDigit() }) result["expiryDate"] = parseMrzDate(expRaw, isExpiry = true)
        }
    }

    private fun parseMrzDate(yyMMdd: String, isExpiry: Boolean): String {
        if (yyMMdd.length != 6) return ""
        val yy = yyMMdd.substring(0, 2).toIntOrNull() ?: return ""
        val mm = yyMMdd.substring(2, 4)
        val dd = yyMMdd.substring(4, 6)
        val century = if (isExpiry) "20" else if (yy > 30) "19" else "20"
        return "$century${yyMMdd.substring(0, 2)}-$mm-$dd"
    }

    fun extractAllEnglishPassportFields(visionText: Text): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val lines = getOcrLines(visionText)
        val rawText = visionText.text

        result["passportType"] = extractLabelValue(lines, listOf("TYPE OF PASSPORT", "TYPE"))
        result["countryCode"] = extractLabelValue(lines, listOf("COUNTRY CODE"))
        result["passportNo"] = extractLabelValue(lines, listOf("PASSPORT NUMBER", "PASSPORT NO.", "PASSPORT NO"))
        result["surname"] = extractLabelValue(lines, listOf("SURNAME"))
        result["givenNames"] = extractLabelValue(lines, listOf("GIVEN NAME", "GIVEN NAMES"))
        result["nationality"] = extractLabelValue(lines, listOf("NATIONALITY"))
        result["dob"] = findDateNearLabel(lines, listOf("DATE OF BIRTH", "BIRTH DATE", "DOB"))
        result["gender"] = extractLabelValue(lines, listOf("SEX", "GENDER"))
        result["birthPlace"] = extractLabelValue(lines, listOf("PLACE OF BIRTH", "BIRTH PLACE"))
        result["issueDate"] = findDateNearLabel(lines, listOf("DATE OF ISSUE", "ISSUE DATE"))
        result["expiryDate"] = findDateNearLabel(lines, listOf("DATE OF EXPIRY", "EXPIRY DATE"))
        result["personalNo"] = extractLabelValue(lines, listOf("PERSONAL NO.", "PERSONAL NO", "PERSONAL NUMBER"))
        result["issuingAuthority"] = extractLabelValue(lines, listOf("ISSUING AUTHORITY", "AUTHORITY"))
        result["placeOfIssue"] = extractLabelValue(lines, listOf("PLACE OF ISSUE"))
        result["nameEn"] = extractLabelValue(lines, listOf("NAME"))
        result["fatherName"] = extractLabelValue(lines, listOf("FATHER'S NAME", "FATHERS NAME", "FATHER NAME"))
        result["motherName"] = extractLabelValue(lines, listOf("MOTHER'S NAME", "MOTHERS NAME", "MOTHER NAME"))
        result["spouseName"] = extractLabelValue(lines, listOf("SPOUSE'S NAME", "SPOUSES NAME", "SPOUSE NAME"))
        result["permanentAddress"] = extractMultilineValue(lines, listOf("PERMANENT ADDRESS"), 2)
        result["emergencyContactName"] = extractLabelValue(lines, listOf("EMERGENCY CONTACT NAME"))
        result["emergencyContactRelation"] = extractLabelValue(lines, listOf("RELATIONSHIP", "RELATION"))
        result["emergencyContactAddress"] = extractMultilineValue(lines, listOf("EMERGENCY CONTACT ADDRESS", "ADDRESS"), 2)

        parseMrzFallback(lines, result)

        val allText = lines.joinToString(" ")
        val phoneRegex = Regex("(\\+?8801[3-9][0-9]{8}|01[3-9][0-9]{8})")
        result["emergencyPhone"] = phoneRegex.find(allText)?.groupValues?.get(1) ?: ""

        if (result["passportNo"].isNullOrBlank()) {
            val passportNumberRegex = Regex("\\b[A-Z]{1,2}[0-9]{7,9}\\b")
            result["passportNo"] = passportNumberRegex.find(rawText.uppercase(Locale.ROOT))?.value ?: ""
        }

        if (result["countryCode"].isNullOrBlank() && result["nationality"].orEmpty().contains("BANGLADESH", true)) {
            result["countryCode"] = "BGD"
        }

        if (result["nameEn"].isNullOrBlank()) {
            val given = result["givenNames"].orEmpty()
            val surname = result["surname"].orEmpty()
            result["nameEn"] = listOf(given, surname).filter { it.isNotBlank() }.joinToString(" ")
        }

        if (result["nameEn"].isNullOrBlank() && result["surname"].orEmpty().isNotBlank()) {
            result["nameEn"] = result["surname"].orEmpty()
        }

        result["rawText"] = rawText
        return result
    }

    private fun fixExifOrientation(file: File) {
        try {
            val exif = ExifInterface(file.absolutePath)
            val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.preScale(-1f, 1f) }
                ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.preScale(-1f, 1f) }
                else -> return
            }
            val original = BitmapFactory.decodeFile(file.absolutePath) ?: return
            val rotated = Bitmap.createBitmap(original, 0, 0, original.width, original.height, matrix, true)
            if (rotated !== original && !original.isRecycled) original.recycle()
            try {
                saveBitmapToFile(rotated, file)
            } finally {
                if (!rotated.isRecycled) rotated.recycle()
            }
        } catch (_: Exception) {}
    }

    private fun optimizeAndCompress(file: File): Pair<Bitmap?, String> {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, options)
            if (options.outWidth <= 0 || options.outHeight <= 0) return Pair(null, "")
            val maxDimension = max(options.outWidth, options.outHeight)
            var sampleSize = 1
            while (maxDimension / sampleSize > MAX_IMAGE_DIMENSION) sampleSize *= 2
            val bitmap = BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
            ) ?: return Pair(null, "")
            val base64 = bitmapToBase64(bitmap, OCR_IMAGE_QUALITY)
            if (base64.isEmpty()) {
                if (!bitmap.isRecycled) bitmap.recycle()
                return Pair(null, "")
            }
            Pair(bitmap, base64)
        } catch (_: Exception) {
            Pair(null, "")
        }
    }

    private fun notifyScanningStarted() {
        if (destroyed) return
        activity.runOnUiThread {
            if (!destroyed) {
                webView.evaluateJavascript("if(window.onImageScanningStarted) {window.onImageScanningStarted('passport');}", null)
            }
        }
    }

    private fun dispatchPassportSuccess(
        fullPageBase64: String,
        docFaceBase64: String,
        fields: Map<String, String>,
        qrProcessed: Boolean,
        barcodeProcessed: Boolean,
        callback: String?
    ) {
        val nameEn = fields["nameEn"] ?: ""
        val passportNo = fields["passportNo"] ?: ""
        val dob = fields["dob"] ?: ""

        val metaJson = JSONObject().apply {
            put("isPassportVerified", true)
            put("isPassportOcrSuccessful", true)
            put("documentTypeRecognized", "passport")
            put("passportType", fields["passportType"] ?: "")
            put("countryCode", fields["countryCode"] ?: "")
            put("nationality", fields["nationality"] ?: "")
            put("passportNo", passportNo)
            put("nameEn", nameEn)
            put("surname", fields["surname"] ?: "")
            put("givenNames", fields["givenNames"] ?: "")
            put("dob", dob)
            put("gender", fields["gender"] ?: "")
            put("birthPlace", fields["birthPlace"] ?: "")
            put("issueDate", fields["issueDate"] ?: "")
            put("expiryDate", fields["expiryDate"] ?: "")
            put("personalNo", fields["personalNo"] ?: "")
            put("fatherName", fields["fatherName"] ?: "")
            put("motherName", fields["motherName"] ?: "")
            put("spouseName", fields["spouseName"] ?: "")
            put("permanentAddress", fields["permanentAddress"] ?: "")
            put("emergencyContactName", fields["emergencyContactName"] ?: "")
            put("emergencyContactRelation", fields["emergencyContactRelation"] ?: "")
            put("emergencyContactAddress", fields["emergencyContactAddress"] ?: "")
            put("emergencyPhone", fields["emergencyPhone"] ?: "")
            put("issuingAuthority", fields["issuingAuthority"] ?: "")
            put("placeOfIssue", fields["placeOfIssue"] ?: "")
            put("qrCodeData", fields["qrCodeData"] ?: "")
            put("barcodeData", fields["barcodeData"] ?: "")
            put("consistencyVerified", fields["consistencyVerified"] ?: "true")
            put("consistencyNote", fields["consistencyNote"] ?: "")
            put("mrzProcessed", true)
            put("qrProcessed", qrProcessed)
            put("barcodeProcessed", barcodeProcessed)
        }

        activity.runOnUiThread {
            if (destroyed) return@runOnUiThread
            Toast.makeText(activity, "✔ পাসপোর্ট OCR সম্পন্ন", Toast.LENGTH_SHORT).show()

            webView.evaluateJavascript(
                "if(window.onDocumentValidated) {window.onDocumentValidated('passport', true, ${JSONObject.quote(fullPageBase64)});}",
                null
            )

            webView.evaluateJavascript(
                "if(window.onFullPassportParsed) {window.onFullPassportParsed(${JSONObject.quote(nameEn)}, ${JSONObject.quote(passportNo)}, ${JSONObject.quote(docFaceBase64)}, ${JSONObject.quote(dob)}, ${JSONObject.quote(metaJson.toString())});}",
                null
            )

            val safeCallback = if (callback == "onFullPassportParsed" || callback == "onDocumentValidated") null else callback
            bridge.sendResponseToWeb(
                response = BridgeResponse(action = Actions.START_KYC, status = Status.SUCCESS, data = JSONObject(metaJson.toString())),
                callbackFunction = safeCallback
            )
        }
    }

    private fun notifyError(message: String, callback: String?) {
        if (destroyed) return
        activity.runOnUiThread {
            if (destroyed) return@runOnUiThread
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
            webView.evaluateJavascript("if(window.onDocumentValidated) {window.onDocumentValidated('passport', false, '');}", null)
            webView.evaluateJavascript("if(window.onDocumentValidationFailed) {window.onDocumentValidationFailed('passport', ${JSONObject.quote(message)});}", null)
            val safeCallback = if (callback == "onFullPassportParsed" || callback == "onDocumentValidated") null else callback
            bridge.sendResponseToWeb(BridgeResponse(action = Actions.START_KYC, status = Status.ERROR, message = message), callbackFunction = safeCallback)
        }
    }

    private fun notifyCancelled(callback: String?) {
        if (destroyed) return
        val message = "পাসপোর্ট নির্বাচন বাতিল করা হয়েছে"
        activity.runOnUiThread {
            if (destroyed) return@runOnUiThread
            val safeCallback = if (callback == "onFullPassportParsed" || callback == "onDocumentValidated") null else callback
            bridge.sendResponseToWeb(BridgeResponse(action = Actions.START_KYC, status = Status.CANCELLED, message = message), callbackFunction = safeCallback)
        }
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        pendingCallback = null
        try { if (!bgExecutor.isShutdown) bgExecutor.shutdownNow() } catch (_: Exception) {}
        try { if (textRecognizerDelegate.isInitialized()) textRecognizerDelegate.value.close() } catch (_: Exception) {}
        try { faceDetector.close() } catch (_: Exception) {}
        try { barcodeScanner.close() } catch (_: Exception) {}
    }
}
