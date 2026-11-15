// File: app/src/main/java/com/bdrsf/app/features/document/OcrProcessorFeature.kt
package com.bdrsf.app.features.document

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Base64
import android.webkit.WebView
import android.widget.Toast
import androidx.exifinterface.media.ExifInterface
import com.bdrsf.app.bridge.AndroidBridge
import com.bdrsf.app.bridge.WebBridgeContract.Actions
import com.bdrsf.app.bridge.WebBridgeContract.BridgeResponse
import com.bdrsf.app.bridge.WebBridgeContract.Status
import com.bdrsf.app.config.WebAppConfig
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

/**
 * BDRSF সার্বজনীন OCR, ফেস ডিটেকশন, বারকোড ডিকোডিং ও সার্ভার ভেরিফিকেশন ইঞ্জিন।
 * এটি সম্পূর্ণ স্বাধীনভাবে কাজ করে এবং যেকোনো ডকুমেন্টের টেক্সট ও ইমেজ প্রসেস করতে সক্ষম।
 */
class OcrProcessorFeature(
    private val activity: Activity,
    private val bridge: AndroidBridge,
    private val webView: WebView
) {

    private val bgExecutor: ExecutorService = Executors.newFixedThreadPool(2)

    @Volatile private var cachedFrontBase64: String = ""
    @Volatile private var cachedBackBase64: String = ""
    @Volatile private var cachedFaceBase64: String = ""
    @Volatile private var cachedBarcodeMap: Map<String, String> = emptyMap()
    @Volatile private var cachedFrontData: Map<String, String> = emptyMap()

    private val isFrontProcessing = AtomicBoolean(false)
    private val isBackProcessing = AtomicBoolean(false)

    private val textRecognizer: TextRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private val faceDetector: FaceDetector by lazy {
        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_NONE)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
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

    fun processDocumentFile(file: File, targetSlot: String, callback: String? = null) {
        if (!file.exists() || file.length() == 0L) return

        notifyScanningStarted(targetSlot)

        when (targetSlot.lowercase(Locale.ROOT)) {
            "nid_front", "card_front" -> processNidFront(file, targetSlot, callback)
            "nid_back", "card_back" -> processNidBack(file, targetSlot, callback)
            "passport" -> processPassport(file, targetSlot, callback, shouldCropBox = true)
            "face_liveness", "selfie" -> processFaceSelfie(file, targetSlot, callback)
            else -> processGenericDocument(file, targetSlot, callback)
        }
    }

    fun processPassportBase64Image(rawBase64: String, callback: String? = null) {
        val cleanB64 = rawBase64.substringAfter("base64,").trim()
        if (cleanB64.isEmpty()) {
            notifyValidationFailed("passport", "গ্যালারি থেকে পাসপোর্টের ছবি লোড করা যায়নি", callback)
            return
        }

        notifyScanningStarted("passport")
        bgExecutor.execute {
            try {
                val bytes = Base64.decode(cleanB64, Base64.DEFAULT)
                val tempFile = File.createTempFile("GAL_PASSPORT_", ".jpg", activity.cacheDir)
                FileOutputStream(tempFile).use { it.write(bytes) }
                processPassport(tempFile, "passport", callback, shouldCropBox = false)
            } catch (e: Exception) {
                notifyValidationFailed("passport", "গ্যালারি ছবি প্রসেসিং ত্রুটি: ${e.localizedMessage}", callback)
            }
        }
    }

    private fun processNidFront(file: File, slot: String, callback: String?) {
        isFrontProcessing.set(true)
        bgExecutor.execute {
            try {
                cropCenterBoxFromImage(file, isCard = true)
                val (bitmap, base64) = optimizeAndCompressToBase64(file)
                if (bitmap == null) {
                    notifyValidationFailed(slot, "ছবি লোড করা যায়নি", callback)
                    return@execute
                }

                val faceBase64 = extractFaceFromBitmap(bitmap)
                val inputImage = InputImage.fromBitmap(bitmap, 0)

                textRecognizer.process(inputImage)
                    .addOnSuccessListener { visionText ->
                        val allText = visionText.text

                        if (isPassport(allText)) {
                            notifyValidationFailed(slot, "ভুল ডকুমেন্ট! আপনি এনআইডির জায়গায় পাসপোর্টের ছবি দিয়েছেন।", callback)
                            cachedFrontBase64 = ""
                            return@addOnSuccessListener
                        }

                        val extractedData = extractNidFrontData(allText)
                        val nidNo = extractedData["nid"] ?: ""

                        if (nidNo.isEmpty() && !isNidFront(allText)) {
                            notifyValidationFailed(slot, "সঠিক এনআইডি কার্ড সনাক্ত করা যায়নি। পরিষ্কার ছবি দিন।", callback)
                            cachedFrontBase64 = ""
                            return@addOnSuccessListener
                        }

                        cachedFrontData = extractedData
                        cachedFrontBase64 = base64
                        cachedFaceBase64 = faceBase64

                        notifyValidationSuccess(slot, base64, extractedData, callback)
                    }
                    .addOnFailureListener {
                        notifyValidationFailed(slot, "লেখা পড়া যায়নি, পুনরায় চেষ্টা করুন।", callback)
                        cachedFrontBase64 = ""
                    }
            } catch (e: Exception) {
                notifyValidationFailed(slot, "প্রসেসিং ত্রুটি: ${e.localizedMessage}", callback)
            } finally {
                isFrontProcessing.set(false)
            }
        }
    }

    private fun processNidBack(file: File, slot: String, callback: String?) {
        isBackProcessing.set(true)
        bgExecutor.execute {
            try {
                cropCenterBoxFromImage(file, isCard = true)
                val (bitmap, base64) = optimizeAndCompressToBase64(file)
                if (bitmap == null) {
                    notifyValidationFailed(slot, "ছবি লোড করা যায়নি", callback)
                    return@execute
                }

                val inputImage = InputImage.fromBitmap(bitmap, 0)

                textRecognizer.process(inputImage).addOnSuccessListener { visionText ->
                    val backText = visionText.text

                    if (isPassport(backText)) {
                        notifyValidationFailed(slot, "ভুল ডকুমেন্ট! এনআইডির পেছনে পাসপোর্টের ছবি দেওয়া হয়েছে।", callback)
                        cachedBackBase64 = ""
                        isBackProcessing.set(false)
                        return@addOnSuccessListener
                    }

                    barcodeScanner.process(inputImage)
                        .addOnSuccessListener { barcodes ->
                            var rawBarcode = ""
                            for (b in barcodes) {
                                val decoded = b.rawBytes?.let {
                                    try { String(it, StandardCharsets.UTF_8) } catch (_: Exception) { b.rawValue ?: "" }
                                } ?: (b.rawValue ?: "")
                                if (decoded.isNotEmpty()) rawBarcode += "$decoded\n"
                            }

                            if (rawBarcode.isBlank()) {
                                val zxingResult = decodeBarcodeWithZxing(bitmap)
                                if (!zxingResult.isNullOrBlank()) {
                                    rawBarcode = zxingResult
                                }
                            }

                            val parsedBarcode = parseNidBarcodeData(rawBarcode)

                            if (rawBarcode.isBlank() && !isNidBack(backText, false)) {
                                notifyValidationFailed(slot, "পেছনের অংশে কোনো বৈধ বারকোড বা তথ্য পাওয়া যায়নি!", callback)
                                cachedBackBase64 = ""
                                isBackProcessing.set(false)
                                return@addOnSuccessListener
                            }

                            if (cachedFrontData.isNotEmpty() && rawBarcode.isNotBlank()) {
                                val isMatch = validateNidCrossMatch(cachedFrontData, parsedBarcode, rawBarcode)
                                if (!isMatch) {
                                    notifyValidationFailed(slot, "কার্ডের সামনের এবং পেছনের অংশ দুটি ভিন্ন কার্ডের!", callback)
                                    cachedBackBase64 = ""
                                    isBackProcessing.set(false)
                                    return@addOnSuccessListener
                                }
                            }

                            cachedBackBase64 = base64
                            cachedBarcodeMap = parsedBarcode

                            val responseData = HashMap(parsedBarcode)
                            responseData["raw_text"] = backText
                            notifyValidationSuccess(slot, base64, responseData, callback)
                            isBackProcessing.set(false)
                        }
                        .addOnFailureListener {
                            if (isNidBack(backText, false)) {
                                cachedBackBase64 = base64
                                val fallbackData = mapOf("raw_text" to backText)
                                notifyValidationSuccess(slot, base64, fallbackData, callback)
                            } else {
                                notifyValidationFailed(slot, "পেছনের অংশ পড়া সম্ভব হয়নি।", callback)
                                cachedBackBase64 = ""
                            }
                            isBackProcessing.set(false)
                        }
                }.addOnFailureListener {
                    notifyValidationFailed(slot, "কার্ডের টেক্সট পড়া যায়নি", callback)
                    isBackProcessing.set(false)
                }
            } catch (e: Exception) {
                notifyValidationFailed(slot, "প্রসেসিং ত্রুটি: ${e.localizedMessage}", callback)
                isBackProcessing.set(false)
            }
        }
    }

    private fun processPassport(file: File, slot: String, callback: String?, shouldCropBox: Boolean = true) {
        isFrontProcessing.set(true)
        bgExecutor.execute {
            try {
                if (shouldCropBox) {
                    cropCenterBoxFromImage(file, isCard = false)
                }
                val (bitmap, base64) = optimizeAndCompressToBase64(file)
                if (bitmap == null) {
                    notifyValidationFailed(slot, "পাসপোর্টের ছবি লোড করা যায়নি", callback)
                    return@execute
                }

                val faceBase64 = extractFaceFromBitmap(bitmap)
                val inputImage = InputImage.fromBitmap(bitmap, 0)

                textRecognizer.process(inputImage)
                    .addOnSuccessListener { visionText ->
                        val allText = visionText.text

                        if (isNidFront(allText)) {
                            notifyValidationFailed(slot, "ভুল ডকুমেন্ট! আপনি পাসপোর্টের জায়গায় এনআইডি কার্ড দিয়েছেন।", callback)
                            cachedFrontBase64 = ""
                            return@addOnSuccessListener
                        }

                        val passData = extractPassportData(allText)
                        val passNo = passData["passportNo"] ?: ""

                        if (!isPassport(allText) && passNo.isEmpty()) {
                            notifyValidationFailed(slot, "এটি সঠিক পাসপোর্ট নয়! পাসপোর্টের সামনের পৃষ্ঠাটি স্পষ্ট রাখুন।", callback)
                            cachedFrontBase64 = ""
                            return@addOnSuccessListener
                        }

                        if (!validatePassportCrossMatch(passData, allText)) {
                            notifyValidationFailed(slot, "পাসপোর্টের নম্বর বা MRZ কোড পড়া যায়নি! পরিষ্কার ছবি দিন।", callback)
                            cachedFrontBase64 = ""
                            return@addOnSuccessListener
                        }

                        cachedFrontBase64 = base64
                        cachedFaceBase64 = faceBase64
                        cachedFrontData = passData

                        notifyValidationSuccess(slot, base64, passData, callback)

                        activity.runOnUiThread {
                            val ocrJson = JSONObject().apply {
                                put("passportNo", passData["passportNo"] ?: "")
                                put("nameEn", passData["name"] ?: "")
                                put("dob", passData["dob"] ?: "")
                                put("fatherName", passData["fatherName"] ?: "")
                                put("motherName", passData["motherName"] ?: "")
                                put("expiryDate", passData["expiryDate"] ?: "")
                                put("personalNo", passData["personalNo"] ?: "")
                                put("gender", passData["gender"] ?: "")
                                put("rawText", allText)
                            }
                            dispatchPassportSuccess(ocrJson, base64, faceBase64, callback)
                        }
                    }
                    .addOnFailureListener {
                        notifyValidationFailed(slot, "পাসপোর্টের পৃষ্ঠা পড়া সম্ভব হয়নি।", callback)
                        cachedFrontBase64 = ""
                    }
            } catch (e: Exception) {
                notifyValidationFailed(slot, "প্রসেসিং ত্রুটি: ${e.localizedMessage}", callback)
            } finally {
                isFrontProcessing.set(false)
            }
        }
    }

    private fun processFaceSelfie(file: File, slot: String, callback: String?) {
        bgExecutor.execute {
            try {
                val (bitmap, base64) = optimizeAndCompressToBase64(file)
                if (bitmap == null) {
                    notifyValidationFailed(slot, "মুখমণ্ডলের ছবি লোড করা যায়নি", callback)
                    return@execute
                }

                val extractedFace = extractFaceFromBitmap(bitmap)
                val finalFace = if (extractedFace.isNotBlank()) extractedFace else base64
                cachedFaceBase64 = finalFace

                val dataMap = mapOf("faceImage" to finalFace)
                notifyValidationSuccess(slot, finalFace, dataMap, callback)
            } catch (e: Exception) {
                notifyValidationFailed(slot, "ফেস প্রসেসিং ব্যর্থ: ${e.localizedMessage}", callback)
            }
        }
    }

    private fun processGenericDocument(file: File, slot: String, callback: String?) {
        bgExecutor.execute {
            try {
                val (bitmap, base64) = optimizeAndCompressToBase64(file)
                if (bitmap == null) {
                    notifyValidationFailed(slot, "ডকুমেন্ট ছবি লোড করা যায়নি", callback)
                    return@execute
                }

                val inputImage = InputImage.fromBitmap(bitmap, 0)
                textRecognizer.process(inputImage)
                    .addOnSuccessListener { visionText ->
                        val dataMap = mapOf(
                            "text" to visionText.text,
                            "lineCount" to visionText.textBlocks.sumOf { it.lines.size }.toString()
                        )
                        notifyValidationSuccess(slot, base64, dataMap, callback)
                    }
                    .addOnFailureListener {
                        notifyValidationFailed(slot, "ডকুমেন্টের টেক্সট পড়া যায়নি", callback)
                    }
            } catch (e: Exception) {
                notifyValidationFailed(slot, "প্রসেসিং ত্রুটি: ${e.localizedMessage}", callback)
            }
        }
    }

    fun processSavedDocumentsHybrid(rawDocType: String, callback: String? = null) {
        val isPassport = rawDocType.contains("PASS", ignoreCase = true)
        val normalizedDocType = if (isPassport) "PASSPORT" else "NID"

        bgExecutor.execute {
            var waitCycles = 0
            while ((isFrontProcessing.get() || (!isPassport && isBackProcessing.get())) && waitCycles < 40) {
                Thread.sleep(50)
                waitCycles++
            }

            if (cachedFrontBase64.isEmpty()) {
                activity.runOnUiThread {
                    notifyScanFailed("সামনের অংশের ছবি পাওয়া যায়নি বা সঠিক নয়!", callback)
                }
                return@execute
            }

            if (!isPassport && cachedBackBase64.isEmpty()) {
                activity.runOnUiThread {
                    notifyScanFailed("পেছনের অংশের ছবি সঠিক নয়!", callback)
                }
                return@execute
            }

            sendOcrServerRequest(
                documentType = normalizedDocType,
                frontBase64 = cachedFrontBase64,
                backBase64 = if (isPassport) null else cachedBackBase64,
                faceBase64 = cachedFaceBase64,
                onSuccess = { json ->
                    activity.runOnUiThread {
                        val data = json.optJSONObject("data") ?: JSONObject()
                        val rawBackText = json.optString("raw_text", "")
                        if (normalizedDocType == "PASSPORT") {
                            dispatchPassportSuccess(data, cachedFrontBase64, cachedFaceBase64, callback)
                        } else {
                            dispatchNidSuccess(data, rawBackText, cachedBarcodeMap, cachedFaceBase64, callback)
                        }
                    }
                },
                onFailure = { _ ->
                    activity.runOnUiThread {
                        if (normalizedDocType == "PASSPORT") {
                            dispatchPassportSuccess(JSONObject(cachedFrontData), cachedFrontBase64, cachedFaceBase64, callback)
                        } else {
                            dispatchNidSuccess(JSONObject(cachedFrontData), "", cachedBarcodeMap, cachedFaceBase64, callback)
                        }
                    }
                }
            )
        }
    }

    private fun sendOcrServerRequest(
        documentType: String,
        frontBase64: String,
        backBase64: String?,
        faceBase64: String,
        onSuccess: (JSONObject) -> Unit,
        onFailure: (String) -> Unit
    ) {
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
                put("doc_type", documentType)
                put("front_image", frontBase64)
                if (backBase64 != null) put("back_image", backBase64)
                put("face_image", faceBase64)
            }

            OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { writer ->
                writer.write(payload.toString())
                writer.flush()
            }

            val responseCode = conn.responseCode
            val inputStream = if (responseCode in 200..299) conn.inputStream else conn.errorStream
            val responseText = BufferedReader(InputStreamReader(inputStream, StandardCharsets.UTF_8)).use { it.readText() }

            if (responseCode in 200..299) {
                val json = JSONObject(responseText)
                onSuccess(json)
            } else {
                onFailure("সার্ভার রেসপন্স কোড: $responseCode")
            }
            conn.disconnect()
        } catch (e: Exception) {
            onFailure(e.localizedMessage ?: "নেটওয়ার্ক সংযোগে ত্রুটি")
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
        val finalNid = if (barcodeNid.isNotEmpty()) barcodeNid else (cachedFrontData["nid"] ?: ocrNid)

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
        }

        val fullAddress = if (rawBackText.isNotBlank()) rawBackText else {
            "বাসা/হোল্ডিং: ${backObj.optString("holding")}, গ্রাম: ${backObj.optString("village")}, ডাকঘর: ${backObj.optString("postOffice")}, উপজেলা: ${backObj.optString("upazila")}, জেলা: ${backObj.optString("district")}"
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
            put("addressDetails", backObj)
        }

        bridge.sendResponseToWeb(
            response = BridgeResponse(action = Actions.START_KYC, status = Status.SUCCESS, data = resultData),
            callbackFunction = callback
        )
    }

    private fun dispatchPassportSuccess(
        ocrData: JSONObject,
        fullPageBase64: String,
        faceBase64: String,
        callback: String?
    ) {
        val passportNo = ocrData.optString("passportNo", "").trim().takeIf { it.isNotEmpty() }
            ?: (cachedFrontData["passportNo"] ?: "")
        val nameEn = ocrData.optString("nameEn", "").trim().takeIf { it.isNotEmpty() }
            ?: (cachedFrontData["name"] ?: "")
        val dob = ocrData.optString("dob", "").trim().takeIf { it.isNotEmpty() }
            ?: (cachedFrontData["dob"] ?: "")

        val extraObj = JSONObject().apply {
            put("fatherName", ocrData.optString("fatherName", cachedFrontData["fatherName"] ?: ""))
            put("motherName", ocrData.optString("motherName", cachedFrontData["motherName"] ?: ""))
            put("expiryDate", ocrData.optString("expiryDate", cachedFrontData["expiryDate"] ?: ""))
            put("personalNo", ocrData.optString("personalNo", cachedFrontData["personalNo"] ?: ""))
            put("gender", ocrData.optString("gender", cachedFrontData["gender"] ?: ""))
            put("faceBase64", faceBase64)
            put("rawText", ocrData.optString("rawText", ""))
        }

        val previewToPass = if (fullPageBase64.isNotBlank()) fullPageBase64 else faceBase64

        val script = "if(window.onFullPassportParsed) { window.onFullPassportParsed(" +
                "${JSONObject.quote(nameEn)}, " +
                "${JSONObject.quote(passportNo)}, " +
                "${JSONObject.quote(previewToPass)}, " +
                "${JSONObject.quote(dob)}, " +
                "${JSONObject.quote(extraObj.toString())}" +
                "); }"
        webView.evaluateJavascript(script, null)

        val resultData = JSONObject().apply {
            put("nameEn", nameEn)
            put("passportNo", passportNo)
            put("dob", dob)
            put("fatherName", extraObj.optString("fatherName"))
            put("motherName", extraObj.optString("motherName"))
            put("expiryDate", extraObj.optString("expiryDate"))
            put("personalNo", extraObj.optString("personalNo"))
            put("gender", extraObj.optString("gender"))
            put("passportBase64", fullPageBase64)
            put("faceBase64", faceBase64)
        }

        bridge.sendResponseToWeb(
            response = BridgeResponse(action = Actions.START_KYC, status = Status.SUCCESS, data = resultData),
            callbackFunction = callback
        )
    }

    fun cropCenterBoxFromImage(file: File, isCard: Boolean) {
        try {
            val exif = ExifInterface(file.absolutePath)
            val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            var bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return

            val rotationDegrees = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }

            if (rotationDegrees != 0) {
                val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                bitmap.recycle()
                bitmap = rotated
            }

            val cropW = (bitmap.width * 0.94f).toInt()
            val ratio = if (isCard) 1.58f else 1.38f
            val cropH = (cropW / ratio).toInt().coerceAtMost(bitmap.height)

            val x = ((bitmap.width - cropW) / 2).coerceAtLeast(0)
            val y = ((bitmap.height - cropH) / 2).coerceAtLeast(0)
            val safeW = cropW.coerceAtMost(bitmap.width - x)
            val safeH = cropH.coerceAtMost(bitmap.height - y)

            val croppedBitmap = Bitmap.createBitmap(bitmap, x, y, safeW, safeH)
            bitmap.recycle()

            FileOutputStream(file).use {
                croppedBitmap.compress(Bitmap.CompressFormat.JPEG, 92, it)
            }
            croppedBitmap.recycle()
        } catch (_: Exception) {}
    }

    fun optimizeAndCompressToBase64(file: File): Pair<Bitmap?, String> {
        try {
            if (!file.exists() || file.length() == 0L) return Pair(null, "")
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, options)

            val maxDim = max(options.outWidth, options.outHeight)
            var sampleSize = 1
            while (maxDim / sampleSize > 1600) {
                sampleSize *= 2
            }

            val bitmap = BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sampleSize }
            ) ?: return Pair(null, "")

            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 88, stream)
            val bytes = stream.toByteArray()
            val base64 = "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)

            return Pair(bitmap, base64)
        } catch (_: Exception) {
            return Pair(null, "")
        }
    }

    fun extractFaceFromBitmap(bitmap: Bitmap?): String {
        if (bitmap == null) return ""
        return try {
            val facesTask = faceDetector.process(InputImage.fromBitmap(bitmap, 0))
            val faces = Tasks.await(facesTask, 3, TimeUnit.SECONDS)

            if (!faces.isNullOrEmpty()) {
                val box = faces[0].boundingBox
                val padX = (box.width() * 0.25f).toInt()
                val padY = (box.height() * 0.35f).toInt()

                val x = (box.left - padX).coerceIn(0, bitmap.width - 1)
                val y = (box.top - padY).coerceIn(0, bitmap.height - 1)
                val width = (box.width() + padX * 2).coerceAtMost(bitmap.width - x)
                val height = (box.height() + padY * 2).coerceAtMost(bitmap.height - y)

                if (width > 30 && height > 30) {
                    val stream = ByteArrayOutputStream()
                    val faceBmp = Bitmap.createBitmap(bitmap, x, y, width, height)
                    faceBmp.compress(Bitmap.CompressFormat.JPEG, 90, stream)
                    faceBmp.recycle()
                    "data:image/jpeg;base64," + Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
                } else fallbackFaceCrop(bitmap)
            } else fallbackFaceCrop(bitmap)
        } catch (_: Exception) {
            fallbackFaceCrop(bitmap)
        }
    }

    private fun fallbackFaceCrop(bitmap: Bitmap): String {
        return try {
            val x = (bitmap.width * 0.04).toInt().coerceIn(0, bitmap.width - 1)
            val y = (bitmap.height * 0.16).toInt().coerceIn(0, bitmap.height - 1)
            val width = (bitmap.width * 0.36).toInt().coerceAtMost(bitmap.width - x)
            val height = (bitmap.height * 0.65).toInt().coerceAtMost(bitmap.height - y)

            val stream = ByteArrayOutputStream()
            val faceBmp = Bitmap.createBitmap(bitmap, x, y, width, height)
            faceBmp.compress(Bitmap.CompressFormat.JPEG, 90, stream)
            faceBmp.recycle()
            "data:image/jpeg;base64," + Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
        } catch (_: Exception) {
            ""
        }
    }

    private fun decodeBarcodeWithZxing(bitmap: Bitmap): String? {
        return try {
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
            val source = RGBLuminanceSource(width, height, pixels)
            val binaryBitmap = BinaryBitmap(HybridBinarizer(source))

            val hints = mapOf(
                DecodeHintType.TRY_HARDER to true,
                DecodeHintType.POSSIBLE_FORMATS to listOf(
                    com.google.zxing.BarcodeFormat.PDF_417,
                    com.google.zxing.BarcodeFormat.QR_CODE,
                    com.google.zxing.BarcodeFormat.CODE_128,
                    com.google.zxing.BarcodeFormat.CODE_39
                )
            )
            MultiFormatReader().decode(binaryBitmap, hints).text
        } catch (_: Exception) {
            null
        }
    }

    fun convertBanglaToEnglishDigits(input: String): String {
        if (input.isEmpty()) return ""
        val banglaDigits = charArrayOf('০', '১', '২', '৩', '৪', '৫', '৬', '৭', '৮', '৯')
        val englishDigits = charArrayOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9')
        var result = input
        for (i in banglaDigits.indices) {
            result = result.replace(banglaDigits[i], englishDigits[i])
        }
        return result
    }

    fun extractNidFrontData(rawText: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val normalized = convertBanglaToEnglishDigits(rawText)
        val cleanDigitsOnly = normalized.replace("\\s+".toRegex(), "")

        val nidMatch = Regex("([0-9]{10}|[0-9]{13}|[0-9]{17})").find(cleanDigitsOnly)
        nidMatch?.groupValues?.getOrNull(1)?.let { result["nid"] = it }

        val dobMatch = Regex("(Date of Birth|Birth|জন্ম তারিখ)[:\\s]*([0-9]{1,2}[\\s/\\-.A-Za-z]{3,5}[0-9]{4})", RegexOption.IGNORE_CASE).find(rawText)
            ?: Regex("([0-9]{2}[\\s/\\-.A-Za-z]{3,5}[0-9]{4})").find(rawText)
            ?: Regex("([0-9]{2}[/-][0-9]{2}[/-][0-9]{4})").find(normalized)

        val foundDob = dobMatch?.groupValues?.getOrNull(2) ?: dobMatch?.value
        if (!foundDob.isNullOrBlank()) result["dob"] = foundDob.trim()

        val lines = rawText.lines().map { it.trim() }
        for (i in lines.indices) {
            val line = lines[i]
            if (line.startsWith("Name", ignoreCase = true)) {
                val namePart = line.replace(Regex("(?i)^Name[:\\s]*"), "").trim()
                if (namePart.isNotEmpty()) {
                    result["name"] = namePart
                    break
                } else if (i + 1 < lines.size) {
                    result["name"] = lines[i + 1]
                    break
                }
            }
        }
        return result
    }

    private fun normalizeMrzDigits(segment: String): String {
        return segment
            .replace('O', '0')
            .replace('Q', '0')
            .replace('D', '0')
            .replace('I', '1')
            .replace('L', '1')
            .replace('Z', '2')
            .replace('S', '5')
            .replace('B', '8')
    }

    fun extractPassportData(rawText: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val upperText = rawText.uppercase(Locale.ROOT)
        val rawLines = upperText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val compactLines = rawLines.map { it.replace(Regex("\\s+"), "") }

        val mrzLine1 = compactLines.firstOrNull { line ->
            line.length >= 25 && (line.startsWith("P<") || line.startsWith("PO") || line.startsWith("P0"))
        }

        if (mrzLine1 != null) {
            val body = if (mrzLine1.length > 5) mrzLine1.substring(5) else ""
            val nameParts = body.split("<<")
            val surname = nameParts.getOrNull(0)?.replace("<", " ")?.replace(Regex("[^A-Z\\s]"), "")?.trim() ?: ""
            val givenNames = nameParts.getOrNull(1)?.replace("<", " ")?.replace(Regex("[^A-Z\\s]"), "")?.trim() ?: ""
            val fullName = listOf(givenNames, surname).filter { it.isNotEmpty() }.joinToString(" ").replace(Regex("\\s+"), " ")
            if (fullName.isNotEmpty()) {
                result["name"] = fullName
            }
        }

        val mrzLine2 = compactLines.firstOrNull { line ->
            line.length >= 28 &&
                !line.startsWith("P<") &&
                (line.contains("BGD") || line.contains(Regex("[0-9OIZSB]{6}[0-9OIZSB][MF<][0-9OIZSB]{6}")))
        }

        if (mrzLine2 != null) {
            val rawPass = mrzLine2.substring(0, 9).replace("<", "").trim()
            val fixedPass = if (rawPass.length >= 2) {
                rawPass.substring(0, 1) + normalizeMrzDigits(rawPass.substring(1))
            } else {
                rawPass
            }
            if (fixedPass.isNotEmpty()) {
                result["passportNo"] = fixedPass
            }

            if (mrzLine2.length >= 19) {
                val dobDigits = normalizeMrzDigits(mrzLine2.substring(13, 19))
                if (dobDigits.matches(Regex("[0-9]{6}"))) {
                    val yy = dobDigits.substring(0, 2).toIntOrNull() ?: 0
                    val century = if (yy > 30) "19" else "20"
                    result["dob"] = "$century${dobDigits.substring(0, 2)}-${dobDigits.substring(2, 4)}-${dobDigits.substring(4, 6)}"
                }
            }

            if (mrzLine2.length >= 21) {
                val sexChar = mrzLine2[20]
                if (sexChar == 'M' || sexChar == 'F') {
                    result["gender"] = sexChar.toString()
                }
            }

            if (mrzLine2.length >= 27) {
                val expDigits = normalizeMrzDigits(mrzLine2.substring(21, 27))
                if (expDigits.matches(Regex("[0-9]{6}"))) {
                    result["expiryDate"] = "20${expDigits.substring(0, 2)}-${expDigits.substring(2, 4)}-${expDigits.substring(4, 6)}"
                }
            }

            if (mrzLine2.length >= 42) {
                val personalDigits = normalizeMrzDigits(mrzLine2.substring(28, 42)).replace("<", "").trim()
                if (personalDigits.matches(Regex("[0-9]{10,17}"))) {
                    result["personalNo"] = personalDigits
                }
            }

            result["isMrzValid"] = "true"
        }

        if (result["passportNo"].isNullOrEmpty()) {
            val passMatch = Regex("\\b([AE][0-9]{8}|[A-Z]{2}[0-9]{7})\\b").find(upperText)
            passMatch?.groupValues?.getOrNull(1)?.let { result["passportNo"] = it }
        }

        if (result["name"].isNullOrEmpty()) {
            var surname = ""
            var givenName = ""
            for (i in rawLines.indices) {
                val line = rawLines[i]
                if (line.contains("SURNAME") && i + 1 < rawLines.size) {
                    surname = rawLines[i + 1].replace(Regex("[^A-Z\\s.]"), "").trim()
                }
                if (line.contains("GIVEN NAME") && i + 1 < rawLines.size) {
                    givenName = rawLines[i + 1].replace(Regex("[^A-Z\\s.]"), "").trim()
                }
            }
            val combined = listOf(givenName, surname).filter { it.isNotEmpty() }.joinToString(" ").trim()
            if (combined.isNotEmpty()) {
                result["name"] = combined
            }
        }

        for (i in rawLines.indices) {
            val line = rawLines[i]
            if (result["fatherName"].isNullOrEmpty() && line.contains("FATHER") && i + 1 < rawLines.size) {
                val candidate = rawLines[i + 1].replace(Regex("[^A-Z\\s.]"), "").trim()
                if (candidate.length >= 3 && !candidate.contains("MOTHER")) {
                    result["fatherName"] = candidate
                }
            }
            if (result["motherName"].isNullOrEmpty() && line.contains("MOTHER") && i + 1 < rawLines.size) {
                val candidate = rawLines[i + 1].replace(Regex("[^A-Z\\s.]"), "").trim()
                if (candidate.length >= 3 && !candidate.contains("SPOUSE")) {
                    result["motherName"] = candidate
                }
            }
        }

        return result
    }

    fun parseNidBarcodeData(barcodeData: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        if (barcodeData.isBlank()) return result
        val normalized = convertBanglaToEnglishDigits(barcodeData)

        val pinMatch = Regex("<pin>\\s*([0-9]{10,17})\\s*</pin>", RegexOption.IGNORE_CASE).find(normalized)
            ?: Regex("pin[:=]?\\s*([0-9]{10,17})", RegexOption.IGNORE_CASE).find(normalized)
            ?: Regex("([0-9]{10}|[0-9]{13}|[0-9]{17})").find(normalized)

        pinMatch?.groupValues?.getOrNull(1)?.let { result["nid"] = it }

        Regex("<name>\\s*([^<]+)\\s*</name>", RegexOption.IGNORE_CASE)
            .find(barcodeData)?.groupValues?.getOrNull(1)?.let { result["name"] = it.trim() }

        Regex("<(nameBn|name_bn|banglaName)>\\s*([^<]+)\\s*<", RegexOption.IGNORE_CASE)
            .find(barcodeData)?.groupValues?.getOrNull(2)?.let { result["name_bn"] = it.trim() }

        Regex("<DOB>\\s*([^<]+)\\s*</DOB>", RegexOption.IGNORE_CASE)
            .find(barcodeData)?.groupValues?.getOrNull(1)?.let { result["dob"] = it.trim() }

        Regex("<(father|fname|fatherName)>\\s*([^<]+)\\s*<", RegexOption.IGNORE_CASE)
            .find(barcodeData)?.groupValues?.getOrNull(2)?.let { result["father"] = it.trim() }

        Regex("<(mother|mname|motherName)>\\s*([^<]+)\\s*<", RegexOption.IGNORE_CASE)
            .find(barcodeData)?.groupValues?.getOrNull(2)?.let { result["mother"] = it.trim() }

        return result
    }

    fun validateNidCrossMatch(frontData: Map<String, String>, backData: Map<String, String>, rawBarcode: String): Boolean {
        val frontNid = frontData["nid"]?.replace("\\D".toRegex(), "") ?: ""
        val backNid = backData["nid"]?.replace("\\D".toRegex(), "") ?: ""

        if (frontNid.isNotEmpty() && backNid.isNotEmpty()) {
            val isNidMatched = frontNid == backNid ||
                    frontNid.endsWith(backNid) ||
                    backNid.endsWith(frontNid) ||
                    (frontNid.length == 17 && frontNid.substring(4) == backNid) ||
                    (backNid.length == 17 && backNid.substring(4) == frontNid)

            if (isNidMatched) return true
        }

        if (frontNid.isNotEmpty() && rawBarcode.contains(frontNid)) return true

        val frontName = frontData["name"]?.lowercase() ?: ""
        val backName = backData["name"]?.lowercase() ?: ""
        if (frontName.isNotEmpty() && backName.isNotEmpty()) {
            if (frontName.contains(backName) || backName.contains(frontName)) return true
        }
        return false
    }

    fun validatePassportCrossMatch(passData: Map<String, String>, rawText: String): Boolean {
        val passNo = (passData["passportNo"] ?: "").replace(Regex("[^A-Z0-9]"), "")
        val hasValidFormat = passNo.matches(Regex("^[A-Z]{1,2}[0-9]{6,8}$"))
        val hasMrzOrKeyword = rawText.contains("P<", ignoreCase = true) ||
            rawText.contains("PASSPORT", ignoreCase = true) ||
            passData["isMrzValid"] == "true"
        return hasValidFormat && hasMrzOrKeyword
    }

    fun isNidFront(rawText: String): Boolean {
        if (rawText.contains("PASSPORT", true) || rawText.contains("P<", true)) return false
        return rawText.contains("Government", true) ||
                rawText.contains("Republic", true) ||
                rawText.contains("গণপ্রজাতন্ত্রী", true) ||
                rawText.contains("National ID", true) ||
                rawText.contains("জাতীয় পরিচয়", true)
    }

    fun isNidBack(rawText: String, hasBarcode: Boolean): Boolean {
        if (rawText.contains("PASSPORT", true) || rawText.contains("P<", true)) return false
        if (hasBarcode) return true
        return rawText.contains("ঠিকানা") || rawText.contains("ডাকঘর") || rawText.contains("গ্রাম") ||
                rawText.contains("Address", true) || rawText.contains("Blood", true)
    }

    fun isPassport(rawText: String): Boolean {
        return rawText.contains("PASSPORT", true) ||
                rawText.contains("P<BGD", true) ||
                (rawText.contains("P<", true) && rawText.contains("BANGLADESH", true))
    }

    private fun notifyScanningStarted(slot: String) {
        activity.runOnUiThread {
            webView.evaluateJavascript("if(window.onImageScanningStarted) { window.onImageScanningStarted(${JSONObject.quote(slot)}); }", null)
        }
    }

    private fun notifyValidationSuccess(slot: String, base64: String, data: Map<String, String>, callback: String?) {
        activity.runOnUiThread {
            Toast.makeText(activity, "✔ $slot যাচাই সম্পন্ন", Toast.LENGTH_SHORT).show()
            webView.evaluateJavascript("if(window.onDocumentValidated) { window.onDocumentValidated(${JSONObject.quote(slot)}, true, ${JSONObject.quote(base64)}); }", null)

            val json = JSONObject().apply {
                put("slot", slot)
                put("image", base64)
                data.forEach { (k, v) -> put(k, v) }
            }
            bridge.sendResponseToWeb(
                response = BridgeResponse(action = Actions.START_KYC, status = Status.SUCCESS, data = json),
                callbackFunction = callback
            )
        }
    }

    private fun notifyValidationFailed(slot: String, message: String, callback: String?) {
        activity.runOnUiThread {
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
            webView.evaluateJavascript("if(window.onDocumentValidated) { window.onDocumentValidated(${JSONObject.quote(slot)}, false, ''); }", null)
            webView.evaluateJavascript("if(window.onDocumentValidationFailed) { window.onDocumentValidationFailed(${JSONObject.quote(slot)}, ${JSONObject.quote(message)}); }", null)

            bridge.sendResponseToWeb(
                response = BridgeResponse(action = Actions.START_KYC, status = Status.ERROR, message = message),
                callbackFunction = callback
            )
        }
    }

    private fun notifyScanFailed(message: String, callback: String?) {
        webView.evaluateJavascript("if(window.onScanFailed) { window.onScanFailed(${JSONObject.quote(message)}); }", null)
        bridge.sendResponseToWeb(
            response = BridgeResponse(action = Actions.START_KYC, status = Status.ERROR, message = message),
            callbackFunction = callback
        )
    }

    fun clearCache() {
        cachedFrontBase64 = ""
        cachedBackBase64 = ""
        cachedFaceBase64 = ""
        cachedBarcodeMap = emptyMap()
        cachedFrontData = emptyMap()
    }

    fun destroy() {
        clearCache()
        try {
            textRecognizer.close()
            faceDetector.close()
            barcodeScanner.close()
            bgExecutor.shutdown()
        } catch (_: Exception) {}
    }
}
