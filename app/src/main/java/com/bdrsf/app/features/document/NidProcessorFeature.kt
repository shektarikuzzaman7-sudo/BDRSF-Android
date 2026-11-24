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
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScanner
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer
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
 * Clean & Fast Bangladesh NID Processor (Front -> Back Barcode/MRZ Cross-Match).
 * Straightens image orientation via EXIF and converts to high-resolution JPEG/Base64
 * without cutting card text or barcodes.
 * Keeps full Front NID data in local state for matching against Back barcode/MRZ.
 * Reads Back barcode (ML Kit + ZXing fallback) and Smart NID MRZ, then matches with Front.
 */
class NidProcessorFeature(
    private val activity: Activity,
    private val bridge: AndroidBridge,
    private val webView: WebView
) {

    companion object {
        const val RC_PICK_NID_GALLERY = 6021
        private const val MAX_IMAGE_DIMENSION = 1600
        private const val IMAGE_QUALITY = 88
        private const val OCR_TIMEOUT_SECONDS = 15L
        private const val BARCODE_TIMEOUT_SECONDS = 12L
    }

    private data class NidTextData(
        val nid: String = "",
        val name: String = "",
        val dob: String = "",
        val englishText: String = "",
        val allText: String = ""
    )

    private data class BackPayloadData(
        val raw: String = "",
        val format: String = "",
        val nid: String = "",
        val name: String = "",
        val dob: String = ""
    )

    private val bgExecutor: ExecutorService = Executors.newFixedThreadPool(2)

    @Volatile private var destroyed = false
    @Volatile private var pendingGallerySlot = "nid_front"
    @Volatile private var pendingGalleryCallback: String? = null

    @Volatile private var cachedFrontBase64 = ""
    @Volatile private var cachedBackBase64 = ""
    @Volatile private var cachedRawBackCode = ""
    @Volatile private var cachedFrontData: Map<String, String> = emptyMap()
    @Volatile private var cachedBackData: Map<String, String> = emptyMap()
    @Volatile private var cachedBackMatched = false

    private val isFrontProcessing = AtomicBoolean(false)
    private val isBackProcessing = AtomicBoolean(false)

    private val textRecognizer: TextRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    private val barcodeScanner: BarcodeScanner by lazy {
        val options = BarcodeScannerOptions.Builder()
            .setBarcodeFormats(
                Barcode.FORMAT_PDF417,
                Barcode.FORMAT_QR_CODE,
                Barcode.FORMAT_AZTEC,
                Barcode.FORMAT_DATA_MATRIX
            )
            .build()
        BarcodeScanning.getClient(options)
    }

    fun openGalleryPicker(slot: String = "nid_front", callback: String? = null) {
        if (destroyed) {
            notifyValidationFailed(slot, "NID Processor বন্ধ রয়েছে", callback)
            return
        }
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
                "গ্যালারি ওপেন করা যায়নি: ${e.localizedMessage ?: ""}",
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
            notifyCancelled(targetSlot, callback)
            return true
        }

        val uri = data?.data
        if (uri == null) {
            notifyValidationFailed(targetSlot, "গ্যালারি থেকে ছবি পাওয়া যায়নি", callback)
            return true
        }

        try {
            val tempFile = File.createTempFile(
                "BDRSF_NID_${targetSlot.uppercase(Locale.ROOT)}_",
                ".jpg",
                activity.cacheDir
            )
            val input = activity.contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("ছবি পড়া সম্ভব হয়নি")
            input.use { source ->
                FileOutputStream(tempFile).use { output ->
                    source.copyTo(output)
                }
            }
            processNidSlot(tempFile, targetSlot, callback)
        } catch (e: Exception) {
            notifyValidationFailed(
                targetSlot,
                "গ্যালারি ছবি লোড করা যায়নি: ${e.localizedMessage ?: ""}",
                callback
            )
        }
        return true
    }

    fun processNidSlot(file: File, targetSlot: String, callback: String? = null) {
        if (destroyed) {
            notifyValidationFailed(targetSlot, "NID Processor বন্ধ রয়েছে", callback)
            return
        }
        val slot = normalizeNidSlot(targetSlot)
        if (!file.exists() || file.length() <= 0L) {
            notifyValidationFailed(slot, "এনআইডি কার্ডের ছবি পাওয়া যায়নি", callback)
            return
        }

        notifyScanningStarted(slot)
        if (slot == "nid_back") {
            processNidBack(file, slot, callback)
        } else {
            processNidFront(file, "nid_front", callback)
        }
    }

    private fun processNidFront(file: File, slot: String, callback: String?) {
        if (!isFrontProcessing.compareAndSet(false, true)) return

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
                val imageResult = optimizeAndCompress(file)
                processedBitmap = imageResult.first
                val fullCardBase64 = imageResult.second
                val bitmap = processedBitmap

                if (bitmap == null || bitmap.isRecycled || fullCardBase64.isEmpty()) {
                    recycleBitmap()
                    notifyValidationFailed(slot, "এনআইডি ফ্রন্টের ছবি প্রসেস করা যায়নি", callback)
                    return@execute
                }
                if (destroyed) {
                    recycleBitmap()
                    return@execute
                }

                val inputImage = InputImage.fromBitmap(bitmap, 0)
                val visionText = Tasks.await(
                    textRecognizer.process(inputImage),
                    OCR_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
                )
                val rawText = visionText.text.orEmpty()
                val upper = rawText.uppercase(Locale.ROOT)

                if (isPassportText(upper)) {
                    clearFrontCache()
                    recycleBitmap()
                    notifyValidationFailed(slot, "ভুল ডকুমেন্ট! এটি পাসপোর্ট, বাংলাদেশি NID Front দিন।", callback)
                    return@execute
                }

                val front = extractEnglishNidData(rawText)
                if (!isBangladeshNidFront(rawText, front)) {
                    clearFrontCache()
                    recycleBitmap()
                    notifyValidationFailed(slot, "বৈধ বাংলাদেশি NID Front শনাক্ত করা যায়নি। পরিষ্কার ছবি দিন।", callback)
                    return@execute
                }

                cachedFrontData = linkedMapOf(
                    "nid" to front.nid,
                    "name" to front.name,
                    "dob" to front.dob,
                    "english_text" to front.englishText,
                    "raw_text" to front.allText,
                    "document_type" to "BANGLADESH_NID"
                )
                cachedFrontBase64 = fullCardBase64
                clearBackCacheOnly()

                val response = HashMap(cachedFrontData).apply {
                    put("image", fullCardBase64)
                    put("slot", slot)
                }
                recycleBitmap()
                notifyValidationSuccess(slot, fullCardBase64, response, callback)
            } catch (e: Exception) {
                clearFrontCache()
                recycleBitmap()
                notifyValidationFailed(slot, "NID Front প্রসেসিং ত্রুটি: ${e.localizedMessage ?: ""}", callback)
            } finally {
                isFrontProcessing.set(false)
            }
        }
    }

    private fun processNidBack(file: File, slot: String, callback: String?) {
        if (!isBackProcessing.compareAndSet(false, true)) return

        bgExecutor.execute {
            var processedBitmap: Bitmap? = null
            val bitmapRecycled = AtomicBoolean(false)
            fun recycleBitmap() {
                if (bitmapRecycled.compareAndSet(false, true)) {
                    processedBitmap?.let { if (!it.isRecycled) it.recycle() }
                }
            }

            try {
                if (cachedFrontBase64.isBlank() || cachedFrontData.isEmpty()) {
                    notifyValidationFailed(slot, "আগে NID Front ভেরিফাই করুন, তারপর NID Back দিন।", callback)
                    return@execute
                }

                fixExifOrientation(file)
                val imageResult = optimizeAndCompress(file)
                processedBitmap = imageResult.first
                val fullBackBase64 = imageResult.second
                val bitmap = processedBitmap

                if (bitmap == null || bitmap.isRecycled || fullBackBase64.isEmpty()) {
                    recycleBitmap()
                    notifyValidationFailed(slot, "এনআইডি ব্যাক পার্টের ছবি প্রসেস করা যায়নি", callback)
                    return@execute
                }
                if (destroyed) {
                    recycleBitmap()
                    return@execute
                }

                val inputImage = InputImage.fromBitmap(bitmap, 0)
                val backText = try {
                    Tasks.await(
                        textRecognizer.process(inputImage),
                        OCR_TIMEOUT_SECONDS,
                        TimeUnit.SECONDS
                    ).text.orEmpty()
                } catch (_: Exception) {
                    ""
                }

                val barcode = readBarcodeFromBack(inputImage, bitmap)
                val mrz = parseSmartCardMrz(backText)

                if (barcode.raw.isBlank() && mrz.raw.isBlank()) {
                    clearBackCacheOnly()
                    recycleBitmap()
                    notifyValidationFailed(slot, "ব্যাক পার্টে কোনো বারকোড বা কোড পাওয়া যায়নি।", callback)
                    return@execute
                }

                val parsedBarcode = if (barcode.raw.isNotBlank()) {
                    parseBarcodePayload(barcode.raw).copy(format = barcode.format)
                } else {
                    BackPayloadData()
                }

                val backInfo = BackPayloadData(
                    raw = parsedBarcode.raw.ifBlank { mrz.raw },
                    format = listOf(parsedBarcode.format, mrz.format)
                        .filter { it.isNotBlank() }
                        .joinToString("+"),
                    nid = parsedBarcode.nid.ifBlank { mrz.nid },
                    name = parsedBarcode.name.ifBlank { mrz.name },
                    dob = parsedBarcode.dob.ifBlank { mrz.dob }
                )

                if (backInfo.nid.isBlank() && backInfo.name.isBlank() && backInfo.dob.isBlank()) {
                    clearBackCacheOnly()
                    recycleBitmap()
                    notifyValidationFailed(slot, "ব্যাক বারকোডে কোনো যাচাইযোগ্য ডাটা পাওয়া যায়নি।", callback)
                    return@execute
                }

                val frontNid = cachedFrontData["nid"].orEmpty()
                val frontName = cachedFrontData["name"].orEmpty()
                val frontDob = cachedFrontData["dob"].orEmpty()

                val match = matchWithFrontEnglishData(frontNid, frontName, frontDob, backInfo)
                if (!match.first) {
                    clearBackCacheOnly()
                    recycleBitmap()
                    notifyValidationFailed(
                        slot,
                        "নট ম্যাচিং! ব্যাক বারকোডের ডাটা ফ্রন্ট এনআইডি কার্ডের ইংরেজি তথ্যের সাথে মিলছে না।",
                        callback
                    )
                    return@execute
                }

                cachedBackBase64 = fullBackBase64
                cachedRawBackCode = backInfo.raw
                cachedBackData = linkedMapOf(
                    "nid" to backInfo.nid,
                    "name" to backInfo.name,
                    "dob" to backInfo.dob,
                    "raw_text" to backText,
                    "barcode_payload" to backInfo.raw,
                    "barcode_format" to backInfo.format,
                    "matched_field" to match.second,
                    "matched_with_front" to "true"
                )
                cachedBackMatched = true

                val response = HashMap(cachedBackData).apply {
                    put("image", fullBackBase64)
                    put("slot", slot)
                    put("front_nid", frontNid)
                }
                recycleBitmap()
                notifyValidationSuccess(slot, fullBackBase64, response, callback)
            } catch (e: Exception) {
                clearBackCacheOnly()
                recycleBitmap()
                notifyValidationFailed(slot, "NID Back প্রসেসিং ত্রুটি: ${e.localizedMessage ?: ""}", callback)
            } finally {
                isBackProcessing.set(false)
            }
        }
    }

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
                    "সাবমিট করার আগে NID Front ও Back Barcode ম্যাচিং সম্পন্ন করুন।",
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
                put("back_code", cachedRawBackCode)
                put("matched", true)
                put("matched_field", cachedBackData["matched_field"].orEmpty())
            }

            activity.runOnUiThread {
                if (destroyed) return@runOnUiThread
                webView.evaluateJavascript(
                    "if(window.onNidLocalPairVerified){window.onNidLocalPairVerified(${JSONObject.quote(result.toString())});}",
                    null
                )
                val safeCallback = if (callback == "onNidLocalPairVerified" || callback == "onDocumentValidated") {
                    null
                } else {
                    callback
                }
                bridge.sendResponseToWeb(
                    BridgeResponse(
                        action = Actions.START_KYC,
                        status = Status.SUCCESS,
                        data = result
                    ),
                    safeCallback
                )
            }
        }
    }

    private fun readBarcodeFromBack(input: InputImage, bitmap: Bitmap): BackPayloadData {
        try {
            val barcodes = Tasks.await(
                barcodeScanner.process(input),
                BARCODE_TIMEOUT_SECONDS,
                TimeUnit.SECONDS
            )
            for (b in barcodes) {
                val decoded = b.rawValue?.trim().orEmpty().ifBlank {
                    b.rawBytes?.let { bytes ->
                        try {
                            String(bytes, StandardCharsets.UTF_8).trim()
                        } catch (_: Exception) {
                            ""
                        }
                    }.orEmpty()
                }
                if (decoded.isNotBlank()) {
                    val fmt = when (b.format) {
                        Barcode.FORMAT_PDF417 -> "PDF417"
                        Barcode.FORMAT_QR_CODE -> "QR_CODE"
                        Barcode.FORMAT_AZTEC -> "AZTEC"
                        Barcode.FORMAT_DATA_MATRIX -> "DATA_MATRIX"
                        else -> "2D_BARCODE"
                    }
                    return BackPayloadData(raw = decoded, format = fmt)
                }
            }
        } catch (_: Exception) {
        }

        val zxingRaw = decodeBarcodeWithZxing(bitmap)
        return if (!zxingRaw.isNullOrBlank()) {
            BackPayloadData(raw = zxingRaw.trim(), format = "ZXING_2D")
        } else {
            BackPayloadData()
        }
    }

    private fun decodeBarcodeWithZxing(bitmap: Bitmap): String? {
        return try {
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val source = RGBLuminanceSource(bitmap.width, bitmap.height, pixels)
            val hints = mapOf(
                DecodeHintType.TRY_HARDER to true,
                DecodeHintType.CHARACTER_SET to "UTF-8",
                DecodeHintType.POSSIBLE_FORMATS to listOf(
                    BarcodeFormat.PDF_417,
                    BarcodeFormat.QR_CODE,
                    BarcodeFormat.AZTEC,
                    BarcodeFormat.DATA_MATRIX
                )
            )
            MultiFormatReader().decode(BinaryBitmap(HybridBinarizer(source)), hints).text
        } catch (_: Exception) {
            null
        }
    }

    private fun parseBarcodePayload(raw: String): BackPayloadData {
        val text = raw.trim()
        val fields = linkedMapOf<String, String>()

        try {
            val json = JSONObject(text)
            val keys = json.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = json.opt(k)?.toString()?.trim().orEmpty()
                if (v.isNotBlank()) fields[normalizeKey(k)] = v
            }
        } catch (_: Exception) {
        }

        val tagRegex = Regex(
            "<([A-Za-z][A-Za-z0-9_ -]{0,30})>\\s*([^<>]{1,200}?)\\s*</\\1>",
            RegexOption.IGNORE_CASE
        )
        tagRegex.findAll(text).forEach { match ->
            val k = normalizeKey(match.groupValues[1])
            val v = match.groupValues[2].trim()
            if (k.isNotBlank() && v.isNotBlank()) fields[k] = v
        }

        var parsedNid = fields["nid"] ?: fields["nidno"] ?: fields["pin"] ?: fields["id"] ?: ""
        var parsedName = fields["name"] ?: fields["nameen"] ?: fields["englishname"] ?: fields["fullname"] ?: ""
        var parsedDob = fields["dob"] ?: fields["dateofbirth"] ?: fields["birthdate"] ?: ""

        if (parsedNid.isBlank() || parsedName.isBlank() || parsedDob.isBlank()) {
            val leadingNidMatch = Regex("^(\\d{17}|\\d{13}|\\d{10})").find(text)
            if (parsedNid.isBlank() && leadingNidMatch != null) {
                parsedNid = leadingNidMatch.groupValues[1]
            }

            val namedDateRegex = Regex(
                "\\b(\\d{1,2}\\s+(?:JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|SEPT|OCT|NOV|DEC)[A-Z]*\\s+\\d{4})\\b",
                RegexOption.IGNORE_CASE
            )
            val numericDateRegex = Regex("\\b(\\d{1,4}[-/.]\\d{1,2}[-/.]\\d{2,4})\\b")

            val dateMatch = namedDateRegex.find(text) ?: numericDateRegex.find(text)
            if (dateMatch != null) {
                if (parsedDob.isBlank()) {
                    parsedDob = dateMatch.groupValues[1].trim()
                }

                if (parsedName.isBlank()) {
                    val startIndex = leadingNidMatch?.range?.last?.plus(1) ?: 0
                    val endIndex = dateMatch.range.first
                    if (endIndex > startIndex) {
                        val candidateName = text.substring(startIndex, endIndex)
                            .replace(Regex("[^A-Za-z .'-]"), " ")
                            .replace(Regex("\\s+"), " ")
                            .trim()
                        if (candidateName.length >= 3) {
                            parsedName = candidateName
                        }
                    }
                }
            }

            if (parsedNid.isBlank()) {
                parsedNid = findNidLikeToken(text)
            }
        }

        return BackPayloadData(
            raw = text,
            format = "DECODED",
            nid = cleanIdentifier(parsedNid),
            name = normalizeEnglishName(parsedName),
            dob = normalizeDateForMatch(parsedDob)
        )
    }

    private fun parseSmartCardMrz(rawText: String): BackPayloadData {
        val lines = rawText.lines()
            .map { it.replace(" ", "").uppercase(Locale.ROOT).trim() }
            .filter { it.length >= 24 && it.contains("<") }
        if (lines.isEmpty()) return BackPayloadData()

        val line1 = lines.firstOrNull { it.startsWith("I<BGD") || it.startsWith("1<BGD") || it.startsWith("I<") }
        val line2 = lines.firstOrNull { it != line1 && it.take(6).all { ch -> ch.isDigit() } && it.contains("BGD") }
        val line3 = lines.firstOrNull { it != line1 && it != line2 && it.contains("<<") }

        var nid = ""
        if (line1 != null) {
            val afterBgd = line1.substringAfter("BGD", "")
            val match = Regex("^([0-9]{9})<([0-9])").find(afterBgd)
            nid = if (match != null) {
                match.groupValues[1] + match.groupValues[2]
            } else {
                afterBgd.replace(Regex("[^0-9]"), "").take(10)
            }
        }

        var dob = ""
        if (line2 != null && line2.length >= 6) {
            val yyMMdd = line2.substring(0, 6)
            if (yyMMdd.all { it.isDigit() }) {
                val yy = yyMMdd.substring(0, 2).toIntOrNull() ?: -1
                val mm = yyMMdd.substring(2, 4).toIntOrNull() ?: -1
                val dd = yyMMdd.substring(4, 6).toIntOrNull() ?: -1
                if (yy in 0..99 && mm in 1..12 && dd in 1..31) {
                    val century = if (yy > 30) 1900 else 2000
                    dob = "%04d%02d%02d".format(Locale.ROOT, century + yy, mm, dd)
                }
            }
        }

        var name = ""
        if (line3 != null) {
            val parts = line3.split("<<")
            val surname = parts.getOrNull(0)?.replace("<", " ")?.trim().orEmpty()
            val given = parts.getOrNull(1)?.replace("<", " ")?.trim().orEmpty()
            name = normalizeEnglishName(listOf(given, surname).filter { it.isNotBlank() }.joinToString(" "))
        }

        val combined = listOfNotNull(line1, line2, line3).joinToString("\n")
        return BackPayloadData(
            raw = combined,
            format = "SMART_NID_MRZ",
            nid = cleanIdentifier(nid),
            name = name,
            dob = dob
        )
    }

    private fun extractEnglishNidData(rawText: String): NidTextData {
        val englishLines = rawText.lines()
            .map { it.trim() }
            .filter { line -> line.any { it in 'A'..'Z' || it in 'a'..'z' || it.isDigit() } }

        val englishText = englishLines.joinToString("\n")
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
            Regex("(?i)(?:NID\\s*(?:NO|NUMBER)?|NATIONAL\\sID(?:ENTIFICATION)?\\s(?:NO|NUMBER)?|ID\\sNO|PIN)\\s*[:#.-]?\\s*([0-9]{3}\\s+[0-9]{3}\\s+[0-9]{4})\\b"),
            Regex("(?i)(?:NID\\s*(?:NO|NUMBER)?|NATIONAL\\sID(?:ENTIFICATION)?\\s(?:NO|NUMBER)?|ID\\sNO|PIN)\\s*[:#.-]?\\s*([0-9][0-9\\s-]{8,23}[0-9])"),
            Regex("(?i)(?:NID|PIN)\\s*[:#.-]?\\s*([A-Z0-9][A-Z0-9\\s-]{7,25})")
        )
        for (pattern in patterns) {
            val candidate = pattern.find(text)?.groupValues?.getOrNull(1).orEmpty()
                .trim()
                .split(Regex("\\s{2,}|\n"))
                .firstOrNull().orEmpty()
            val cleaned = cleanIdentifier(candidate)
            if (isPlausibleNidIdentifier(cleaned)) return cleaned
        }
        return ""
    }

    private fun findNidLikeToken(text: String): String {
        val spacedSmartNid = Regex("\\b([0-9]{3}\\s+[0-9]{3}\\s+[0-9]{4})\\b")
            .find(text)?.groupValues?.getOrNull(1).orEmpty()
        if (spacedSmartNid.isNotBlank()) {
            val cleaned = cleanIdentifier(spacedSmartNid)
            if (cleaned.length == 10) return cleaned
        }

        val exactDigit = Regex("(?<!\\d)([0-9]{17}|[0-9]{13}|[0-9]{10})(?!\\d)")
            .find(text)?.groupValues?.getOrNull(1).orEmpty()
        if (exactDigit.isNotBlank()) return exactDigit

        return Regex("(?i)(?<![A-Z0-9])[0-9][0-9 -]{8,22}[0-9](?![A-Z0-9])")
            .findAll(text)
            .map { cleanIdentifier(it.value) }
            .firstOrNull { it.length in listOf(10, 13, 17) && it.all { ch -> ch.isDigit() } }
            .orEmpty()
    }

    private fun isPlausibleNidIdentifier(value: String): Boolean {
        val digits = value.filter { it.isDigit() }
        if (digits.length in listOf(10, 13, 17)) return true
        return value.length in 9..20 && digits.length >= 9 && value.all { it.isLetterOrDigit() }
    }

    private fun extractDob(text: String): String {
        val patterns = listOf(
            Regex("\\b(?:0?[1-9]|[12][0-9]|3[01])[-/.](?:0?[1-9]|1[0-2])[-/.](?:19|20)\\d{2}\\b"),
            Regex("\\b(?:19|20)\\d{2}[-/.](?:0?[1-9]|1[0-2])[-/.](?:0?[1-9]|[12][0-9]|3[01])\\b"),
            Regex("(?i)\\b(?:0?[1-9]|[12][0-9]|3[01])\\s+(?:JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|SEPT|OCT|NOV|DEC)[A-Z]*\\s+(?:19|20)\\d{2}\\b")
        )
        for (pattern in patterns) {
            val found = pattern.find(text)?.value
            if (!found.isNullOrBlank()) return found
        }
        return ""
    }

    private fun extractName(lines: List<String>): String {
        val labels = listOf("NAME", "NAME EN", "ENGLISH NAME", "FULL NAME")
        val disallowed = setOf(
            "FATHER", "FATHERS", "FATHER'S", "MOTHER", "MOTHERS", "MOTHER'S",
            "DATE OF BIRTH", "DOB", "NATIONAL ID", "NID", "GOVERNMENT", "BANGLADESH"
        )
        for (i in lines.indices) {
            val line = lines[i]
            val upper = line.uppercase(Locale.ROOT)
            if (disallowed.any { upper.startsWith(it) }) continue
            val label = labels.firstOrNull { upper.startsWith(it) } ?: continue
            val inline = line.substringAfter(label, "").trim().trim(':', '-', ' ')
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
        val hasMarker = upper.contains("NATIONAL ID") ||
            upper.contains("NATIONAL IDENTITY") ||
            upper.contains("NID NO") ||
            upper.contains("ID NO") ||
            upper.contains("GOVERNMENT OF THE PEOPLE'S REPUBLIC OF BANGLADESH") ||
            upper.contains("PEOPLE'S REPUBLIC OF BANGLADESH") ||
            rawText.contains("জাতীয় পরিচয়", ignoreCase = true) ||
            rawText.contains("জাতীয় পরিচয়", ignoreCase = true) ||
            rawText.contains("গণপ্রজাতন্ত্রী বাংলাদেশ", ignoreCase = true)
        return hasMarker && data.nid.isNotBlank()
    }

    private fun isPassportText(upperText: String): Boolean {
        return upperText.contains("PASSPORT") ||
            upperText.contains("P<BGD") ||
            upperText.contains("PASSPORT NO") ||
            upperText.contains("TYPE P") ||
            Regex("(?m)^P<[A-Z]{3}").containsMatchIn(upperText)
    }

    private fun matchWithFrontEnglishData(
        frontNid: String,
        frontName: String,
        frontDob: String,
        back: BackPayloadData
    ): Pair<Boolean, String> {
        val fNid = cleanIdentifier(frontNid)
        val bNid = cleanIdentifier(back.nid)

        val fName = normalizeEnglishName(frontName)
        val bName = normalizeEnglishName(back.name)

        val fDob = normalizeDateForMatch(frontDob)
        val bDob = normalizeDateForMatch(back.dob)

        val nidExactMatch = fNid.isNotBlank() && bNid.isNotBlank() && fNid == bNid
        val nidContainedMatch = fNid.isNotBlank() && bNid.isNotBlank() &&
            minOf(fNid.length, bNid.length) >= 10 &&
            (fNid.contains(bNid) || bNid.contains(fNid))
        val nidMatched = nidExactMatch || nidContainedMatch

        val dobMatched = fDob.isNotBlank() && bDob.isNotBlank() && fDob == bDob
        val nameMatched = isEnglishNameSimilar(fName, bName)

        if (!nidMatched && !dobMatched && !nameMatched) {
            return false to ""
        }

        if (nidMatched && (nameMatched || dobMatched)) {
            val matchedFields = buildList {
                add("nid")
                if (nameMatched) add("name")
                if (dobMatched) add("dob")
            }.joinToString("+")
            return true to matchedFields
        }

        if (bNid.isBlank() && nameMatched && dobMatched) {
            return true to "name+dob"
        }

        if (nidExactMatch && bName.isBlank() && bDob.isBlank()) {
            return true to "nid_only"
        }

        return false to ""
    }

    private fun isEnglishNameSimilar(frontName: String, backName: String): Boolean {
        if (frontName.length < 3 || backName.length < 3) return false
        if (frontName == backName) return true

        val frontTokens = frontName.split(" ").filter { it.length >= 2 }.toSet()
        val backTokens = backName.split(" ").filter { it.length >= 2 }.toSet()
        if (frontTokens.isEmpty() || backTokens.isEmpty()) return false

        val commonTokens = frontTokens.intersect(backTokens).size
        val minSize = minOf(frontTokens.size, backTokens.size)

        return if (minSize >= 2) commonTokens >= 2 else commonTokens >= 1
    }

    private fun cleanIdentifier(value: String): String {
        val bn = charArrayOf('০', '১', '২', '৩', '৪', '৫', '৬', '৭', '৮', '৯')
        val en = charArrayOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9')
        var out = value.uppercase(Locale.ROOT)
        for (i in bn.indices) out = out.replace(bn[i], en[i])
        return out.replace(Regex("[^A-Z0-9]"), "")
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
                year = a
                month = b
                day = c
            } else {
                day = a
                month = b
                year = if (c < 100) 2000 + c else c
            }
            if (year !in 1900..2099 || month !in 1..12 || day !in 1..31) return ""
            return "%04d%02d%02d".format(Locale.ROOT, year, month, day)
        }

        val monthNames = mapOf(
            "JAN" to 1, "JANUARY" to 1,
            "FEB" to 2, "FEBRUARY" to 2,
            "MAR" to 3, "MARCH" to 3,
            "APR" to 4, "APRIL" to 4,
            "MAY" to 5,
            "JUN" to 6, "JUNE" to 6,
            "JUL" to 7, "JULY" to 7,
            "AUG" to 8, "AUGUST" to 8,
            "SEP" to 9, "SEPT" to 9, "SEPTEMBER" to 9,
            "OCT" to 10, "OCTOBER" to 10,
            "NOV" to 11, "NOVEMBER" to 11,
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

    private fun fixExifOrientation(file: File) {
        try {
            val exif = ExifInterface(file.absolutePath)
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )
            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> {
                    matrix.postRotate(90f)
                    matrix.preScale(-1f, 1f)
                }
                ExifInterface.ORIENTATION_TRANSVERSE -> {
                    matrix.postRotate(270f)
                    matrix.preScale(-1f, 1f)
                }
                else -> return
            }
            val original = BitmapFactory.decodeFile(file.absolutePath) ?: return
            val rotated = Bitmap.createBitmap(original, 0, 0, original.width, original.height, matrix, true)
            if (rotated !== original && !original.isRecycled) {
                original.recycle()
            }
            try {
                FileOutputStream(file).use { output ->
                    rotated.compress(Bitmap.CompressFormat.JPEG, 92, output)
                    output.flush()
                }
            } finally {
                if (!rotated.isRecycled) rotated.recycle()
            }
        } catch (_: Exception) {
        }
    }

    private fun optimizeAndCompress(file: File): Pair<Bitmap?, String> {
        return try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }
            BitmapFactory.decodeFile(file.absolutePath, options)
            if (options.outWidth <= 0 || options.outHeight <= 0) {
                return Pair(null, "")
            }
            val maxDimension = max(options.outWidth, options.outHeight)
            var sampleSize = 1
            while (maxDimension / sampleSize > MAX_IMAGE_DIMENSION) {
                sampleSize *= 2
            }
            val bitmap = BitmapFactory.decodeFile(
                file.absolutePath,
                BitmapFactory.Options().apply {
                    inSampleSize = sampleSize
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
            ) ?: return Pair(null, "")

            val base64 = try {
                ByteArrayOutputStream().use { stream ->
                    val compressed = bitmap.compress(Bitmap.CompressFormat.JPEG, IMAGE_QUALITY, stream)
                    if (!compressed) {
                        if (!bitmap.isRecycled) bitmap.recycle()
                        return Pair(null, "")
                    }
                    "data:image/jpeg;base64," + Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
                }
            } catch (_: Exception) {
                if (!bitmap.isRecycled) bitmap.recycle()
                return Pair(null, "")
            }
            Pair(bitmap, base64)
        } catch (_: Exception) {
            Pair(null, "")
        }
    }

    private fun normalizeNidSlot(rawSlot: String?): String {
        val value = rawSlot.orEmpty().trim().lowercase(Locale.ROOT)
        return if (value.contains("back")) "nid_back" else "nid_front"
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
                data.forEach { (k, v) -> put(k, v) }
            }
            val safeCallback = if (callback == "onDocumentValidated") null else callback
            bridge.sendResponseToWeb(
                BridgeResponse(
                    action = Actions.START_KYC,
                    status = Status.SUCCESS,
                    data = json
                ),
                safeCallback
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
            val safeCallback = if (callback == "onDocumentValidated") null else callback
            bridge.sendResponseToWeb(
                BridgeResponse(
                    action = Actions.START_KYC,
                    status = Status.ERROR,
                    message = message
                ),
                safeCallback
            )
        }
    }

    private fun notifyCancelled(slot: String, callback: String?) {
        if (destroyed) return
        val message = "এনআইডি নির্বাচন বাতিল করা হয়েছে"
        activity.runOnUiThread {
            if (destroyed) return@runOnUiThread
            val safeCallback = if (callback == "onDocumentValidated") null else callback
            bridge.sendResponseToWeb(
                BridgeResponse(
                    action = Actions.START_KYC,
                    status = Status.CANCELLED,
                    message = message
                ),
                safeCallback
            )
        }
    }

    private fun clearBackCacheOnly() {
        cachedBackBase64 = ""
        cachedRawBackCode = ""
        cachedBackData = emptyMap()
        cachedBackMatched = false
    }

    private fun clearFrontCache() {
        cachedFrontBase64 = ""
        cachedFrontData = emptyMap()
        clearBackCacheOnly()
    }

    fun clearCache() {
        clearFrontCache()
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        pendingGalleryCallback = null
        clearCache()
        try {
            if (!bgExecutor.isShutdown) {
                bgExecutor.shutdownNow()
            }
        } catch (_: Exception) {
        }
        try {
            textRecognizer.close()
            barcodeScanner.close()
        } catch (_: Exception) {
        }
    }
}
