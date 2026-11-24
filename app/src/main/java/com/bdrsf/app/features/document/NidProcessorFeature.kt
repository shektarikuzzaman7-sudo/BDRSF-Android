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

/**
 * BDRSF ডেডিকেটেড NID প্রসেসর ইঞ্জিন (ML Kit First + Gemini Last)।
 * - বানানো/কোডবিহীন বা অমিল এনআইডি কার্ড আটকে দেয়।
 * - ফ্রন্ট ও ব্যাক পার্টের তথ্য/কোড ক্রস-ম্যাচ করে।
 */
class NidProcessorFeature(
    private val activity: Activity,
    private val bridge: AndroidBridge,
    private val webView: WebView,
    private val faceVerificationFeature: FaceVerificationFeature? = null
) {

    private val bgExecutor: ExecutorService = Executors.newFixedThreadPool(2)

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

    fun processNidSlot(file: File, targetSlot: String, callback: String? = null) {
        if (!file.exists() || file.length() == 0L) return

        notifyScanningStarted(targetSlot)
        when (targetSlot.lowercase(Locale.ROOT)) {
            "nid_front", "card_front" -> processNidFront(file, targetSlot, callback)
            "nid_back", "card_back" -> processNidBack(file, targetSlot, callback)
            "face_liveness", "selfie" -> faceVerificationFeature?.verifyCapturedSelfieAgainstDocument(file, cachedFaceBase64, callback)
            else -> processNidFront(file, targetSlot, callback)
        }
    }

    private fun processNidFront(file: File, slot: String, callback: String?) {
        isFrontProcessing.set(true)
        bgExecutor.execute {
            try {
                cropCardBoxFromImage(file)
                val (bitmap, base64) = optimizeAndCompressToBase64(file)
                if (bitmap == null) {
                    notifyValidationFailed(slot, "ছবি লোড করা যায়নি", callback)
                    return@execute
                }

                val faceBase64 = extractFaceFromBitmap(bitmap)
                if (faceBase64.isBlank()) {
                    notifyValidationFailed(slot, "এনআইডি ফ্রন্ট পার্টে স্পষ্ট মুখমণ্ডলের ছবি পাওয়া যায়নি। বানানো বা অস্পষ্ট কার্ড গ্রহণযোগ্য নয়।", callback)
                    cachedFrontBase64 = ""
                    return@execute
                }

                val inputImage = InputImage.fromBitmap(bitmap, 0)
                textRecognizer.process(inputImage)
                    .addOnSuccessListener { visionText ->
                        val allText = visionText.text
                        if (allText.contains("PASSPORT", true) || allText.contains("P<", true)) {
                            notifyValidationFailed(slot, "ভুল ডকুমেন্ট! এনআইডির জায়গায় পাসপোর্টের ছবি দেওয়া হয়েছে।", callback)
                            cachedFrontBase64 = ""
                            return@addOnSuccessListener
                        }

                        val extractedData = extractNidFrontData(allText)
                        val nidNo = extractedData["nid"] ?: ""
                        if (nidNo.isBlank() || nidNo.length !in listOf(10, 13, 17)) {
                            notifyValidationFailed(slot, "সঠিক ১০, ১৩ বা ১৭ ডিজিটের এনআইডি নম্বর পাওয়া যায়নি। বানানো বা অসম্পূর্ণ কার্ড গ্রহণযোগ্য নয়।", callback)
                            cachedFrontBase64 = ""
                            return@addOnSuccessListener
                        }

                        if (!isNidFront(allText)) {
                            notifyValidationFailed(slot, "বৈধ এনআইডি কার্ড সনাক্ত করা যায়নি। পরিষ্কার ছবি দিন।", callback)
                            cachedFrontBase64 = ""
                            return@addOnSuccessListener
                        }

                        cachedFrontData = extractedData
                        cachedFrontBase64 = base64
                        cachedFaceBase64 = faceBase64
                        faceVerificationFeature?.setReferenceDocumentFace(faceBase64)
                        notifyValidationSuccess(slot, base64, extractedData, callback)
                    }
                    .addOnFailureListener {
                        notifyValidationFailed(slot, "লেখা পড়া যায়নি, পুনরায় চেষ্টা করুন।", callback)
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
                if (cachedFrontBase64.isBlank() || cachedFrontData["nid"].isNullOrBlank()) {
                    notifyValidationFailed(slot, "আগে এনআইডি ফ্রন্ট পার্ট সঠিকভাবে ভেরিফাই করুন।", callback)
                    isBackProcessing.set(false)
                    return@execute
                }

                cropCardBoxFromImage(file)
                val (bitmap, base64) = optimizeAndCompressToBase64(file)
                if (bitmap == null) {
                    notifyValidationFailed(slot, "ছবি লোড করা যায়নি", callback)
                    isBackProcessing.set(false)
                    return@execute
                }

                val inputImage = InputImage.fromBitmap(bitmap, 0)
                textRecognizer.process(inputImage).addOnSuccessListener { visionText ->
                    val backText = visionText.text
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
                                rawBarcode = decodeBarcodeWithZxing(bitmap) ?: ""
                            }

                            val cleanCode = rawBarcode.trim()
                            // বানানো এনআইডি বা যেকোনো কার্ডে ব্যাক কোড/বারকোড না থাকলে আটকে দেবে
                            if (cleanCode.isBlank()) {
                                notifyValidationFailed(slot, "এনআইডি ব্যাক পার্টে বারকোড/কিউআর/কোড পাওয়া যায়নি! বানানো বা কোডবিহীন কার্ড গ্রহণযোগ্য নয়।", callback)
                                cachedBackBase64 = ""
                                cachedRawBackCode = ""
                                isBackProcessing.set(false)
                                return@addOnSuccessListener
                            }

                            val parsedBarcode = parseNidBarcodeData(cleanCode, backText)
                            val frontNid = cachedFrontData["nid"].orEmpty()
                            val backNid = parsedBarcode["nid"].orEmpty()

                            if (backNid.isNotBlank() && backNid != frontNid) {
                                notifyValidationFailed(slot, "ফ্রন্ট পার্টের এনআইডি ($frontNid) ও ব্যাক পার্টের কোডের তথ্য ($backNid) মিলছে না! একই কার্ডের উভয় পাশ দিন।", callback)
                                cachedBackBase64 = ""
                                cachedRawBackCode = ""
                                isBackProcessing.set(false)
                                return@addOnSuccessListener
                            }

                            if (!isNidBack(backText) && backNid.isBlank()) {
                                notifyValidationFailed(slot, "এনআইডি ব্যাক পার্টের তথ্য যাচাই করা যায়নি।", callback)
                                cachedBackBase64 = ""
                                cachedRawBackCode = ""
                                isBackProcessing.set(false)
                                return@addOnSuccessListener
                            }

                            cachedBackBase64 = base64
                            cachedRawBackCode = cleanCode
                            cachedBarcodeMap = parsedBarcode
                            val responseData = HashMap(parsedBarcode).apply {
                                put("raw_text", backText)
                                put("back_code", cleanCode)
                                put("matched_with_front", "true")
                            }
                            notifyValidationSuccess(slot, base64, responseData, callback)
                            isBackProcessing.set(false)
                        }
                        .addOnFailureListener {
                            notifyValidationFailed(slot, "ব্যাক পার্টের বারকোড/কোড স্ক্যান করা যায়নি।", callback)
                            cachedBackBase64 = ""
                            isBackProcessing.set(false)
                        }
                }.addOnFailureListener {
                    notifyValidationFailed(slot, "কার্ডের টেক্সট পড়া যায়নি", callback)
                    isBackProcessing.set(false)
                }
            } catch (e: Exception) {
                notifyValidationFailed(slot, "প্রসেসিং ত্রুটি: ${e.localizedMessage}", callback)
                isBackProcessing.set(false)
            }
        }
    }

    fun submitNidHybrid(callback: String? = null) {
        bgExecutor.execute {
            var waitCycles = 0
            while ((isFrontProcessing.get() || isBackProcessing.get()) && waitCycles < 40) {
                Thread.sleep(50)
                waitCycles++
            }

            if (cachedFrontBase64.isEmpty() || cachedBackBase64.isEmpty() || cachedRawBackCode.isBlank()) {
                activity.runOnUiThread {
                    notifyValidationFailed("nid_front", "বৈধ ফ্রন্ট পার্ট এবং ব্যাক পার্টের কোডসহ উভয় পাশ দিন।", callback)
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
            put("backCode", cachedRawBackCode)
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
            put("backCode", cachedRawBackCode)
            put("addressDetails", backObj)
        }

        bridge.sendResponseToWeb(
            response = BridgeResponse(action = Actions.START_KYC, status = Status.SUCCESS, data = resultData),
            callbackFunction = callback
        )
    }

    private fun cropCardBoxFromImage(file: File) {
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

            // নিচের বারকোড/MRZ যেন না কাটে সেজন্য একটু বড় মার্জিন রাখা হয়েছে
            val cropW = (bitmap.width * 0.94f).toInt()
            val cropH = (cropW / 1.48f).toInt().coerceAtMost(bitmap.height)
            val x = ((bitmap.width - cropW) / 2).coerceAtLeast(0)
            val y = ((bitmap.height - cropH) / 2).coerceAtLeast(0)
            val croppedBitmap = Bitmap.createBitmap(bitmap, x, y, cropW.coerceAtMost(bitmap.width - x), cropH.coerceAtMost(bitmap.height - y))
            bitmap.recycle()

            FileOutputStream(file).use {
                croppedBitmap.compress(Bitmap.CompressFormat.JPEG, 92, it)
            }
            croppedBitmap.recycle()
        } catch (_: Exception) {}
    }

    private fun optimizeAndCompressToBase64(file: File): Pair<Bitmap?, String> {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, options)
            val maxDim = max(options.outWidth, options.outHeight)
            var sampleSize = 1
            while (maxDim / sampleSize > 1500) sampleSize *= 2

            val bitmap = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sampleSize })
                ?: return Pair(null, "")

            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, 88, stream)
            val base64 = "data:image/jpeg;base64," + Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
            Pair(bitmap, base64)
        } catch (_: Exception) {
            Pair(null, "")
        }
    }

    private fun extractFaceFromBitmap(bitmap: Bitmap): String {
        return try {
            val faces = Tasks.await(faceDetector.process(InputImage.fromBitmap(bitmap, 0)), 3, TimeUnit.SECONDS)
            if (!faces.isNullOrEmpty()) {
                val box = faces[0].boundingBox
                val padX = (box.width() * 0.25f).toInt()
                val padY = (box.height() * 0.35f).toInt()
                val x = (box.left - padX).coerceIn(0, bitmap.width - 1)
                val y = (box.top - padY).coerceIn(0, bitmap.height - 1)
                val w = (box.width() + padX * 2).coerceAtMost(bitmap.width - x)
                val h = (box.height() + padY * 2).coerceAtMost(bitmap.height - y)
                if (w > 30 && h > 30) {
                    val stream = ByteArrayOutputStream()
                    val faceBmp = Bitmap.createBitmap(bitmap, x, y, w, h)
                    faceBmp.compress(Bitmap.CompressFormat.JPEG, 92, stream)
                    faceBmp.recycle()
                    return "data:image/jpeg;base64," + Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
                }
            }
            ""
        } catch (_: Exception) {
            ""
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
        for (i in bn.indices) out = out.replace(bn[i], en[i])
        return out
    }

    private fun extractNidFrontData(rawText: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val normalized = convertBanglaToEnglishDigits(rawText)
        Regex("([0-9]{10}|[0-9]{13}|[0-9]{17})").find(normalized.replace("\\s+".toRegex(), ""))
            ?.groupValues?.getOrNull(1)?.let { result["nid"] = it }

        Regex("([0-9]{2}[\\s/\\-.A-Za-z]{3,5}[0-9]{4})").find(rawText)?.value?.let { result["dob"] = it.trim() }

        val lines = rawText.lines().map { it.trim() }
        for (i in lines.indices) {
            if (lines[i].startsWith("Name", ignoreCase = true)) {
                val part = lines[i].replace(Regex("(?i)^Name[:\\s]*"), "").trim()
                if (part.isNotEmpty()) {
                    result["name"] = part
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
        val combined = convertBanglaToEnglishDigits("$barcodeData\n$fallbackBackText")

        Regex("<pin>\\s*([0-9]{10,17})\\s*</pin>", RegexOption.IGNORE_CASE).find(combined)
            ?.groupValues?.getOrNull(1)?.let { result["nid"] = it }
            ?: Regex("([0-9]{17}|[0-9]{13}|[0-9]{10})").find(combined)?.groupValues?.getOrNull(1)?.let { result["nid"] = it }

        Regex("<name>\\s*([^<]+)\\s*</name>", RegexOption.IGNORE_CASE).find(barcodeData)?.groupValues?.getOrNull(1)?.let { result["name"] = it.trim() }
        Regex("<(nameBn|name_bn)>\\s*([^<]+)\\s*<", RegexOption.IGNORE_CASE).find(barcodeData)?.groupValues?.getOrNull(2)?.let { result["name_bn"] = it.trim() }
        Regex("<DOB>\\s*([^<]+)\\s*</DOB>", RegexOption.IGNORE_CASE).find(barcodeData)?.groupValues?.getOrNull(1)?.let { result["dob"] = it.trim() }
        Regex("<(father|fname)>\\s*([^<]+)\\s*<", RegexOption.IGNORE_CASE).find(barcodeData)?.groupValues?.getOrNull(2)?.let { result["father"] = it.trim() }
        Regex("<(mother|mname)>\\s*([^<]+)\\s*<", RegexOption.IGNORE_CASE).find(barcodeData)?.groupValues?.getOrNull(2)?.let { result["mother"] = it.trim() }
        return result
    }

    private fun isNidFront(rawText: String): Boolean {
        return rawText.contains("National ID", true) || rawText.contains("জাতীয় পরিচয়", true) || rawText.contains("Government", true)
    }

    private fun isNidBack(rawText: String): Boolean {
        return rawText.contains("ঠিকানা") || rawText.contains("ডাকঘর") || rawText.contains("Address", true) || rawText.contains("Blood", true)
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
            bridge.sendResponseToWeb(BridgeResponse(action = Actions.START_KYC, status = Status.SUCCESS, data = json), callback)
        }
    }

    private fun notifyValidationFailed(slot: String, message: String, callback: String?) {
        activity.runOnUiThread {
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
            webView.evaluateJavascript("if(window.onDocumentValidated) { window.onDocumentValidated(${JSONObject.quote(slot)}, false, ''); }", null)
            webView.evaluateJavascript("if(window.onDocumentValidationFailed) { window.onDocumentValidationFailed(${JSONObject.quote(slot)}, ${JSONObject.quote(message)}); }", null)
            bridge.sendResponseToWeb(BridgeResponse(action = Actions.START_KYC, status = Status.ERROR, message = message), callback)
        }
    }

    fun clearCache() {
        cachedFrontBase64 = ""
        cachedBackBase64 = ""
        cachedFaceBase64 = ""
        cachedRawBackCode = ""
        cachedBarcodeMap = emptyMap()
        cachedFrontData = emptyMap()
        faceVerificationFeature?.setReferenceDocumentFace("")
    }

    fun destroy() {
        clearCache()
        try {
            textRecognizer.close()
            faceDetector.close()
            barcodeScanner.close()
            bgExecutor.shutdownNow()
        } catch (_: Exception) {}
    }
}
