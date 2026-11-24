package com.bdrsf.app.features.kyc

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import org.json.JSONObject
import java.util.EnumMap
import java.util.Locale

/**
 * BDRSF NID ব্যাক পার্ট ডিকোডার (পুরাতন ল্যামিনেটেড, স্মার্ট NID ও ডিজিটাল/অনলাইন NID):
 * - PDF417 XML (<pin>, <name>, <DOB>, <ds>), স্মার্ট কার্ডের 3-লাইন MRZ (I<BGD...) এবং QR কোড ডিকোড করে।
 * - ল্যামিনেশন গ্লেয়ারের কারণে বারকোড আংশিক অস্পষ্ট হলে বাংলা-যুক্ত পুরাতন ব্যাক পার্টকে ব্লক না করে
 *   চূড়ান্ত ফরেনসিক ক্রস-চেকের জন্য Gemini AI (OcrForensicEngine)-এ পাঠায়।
 */
class NidBarcodeAndQrDecoder {

    data class NidBackDecodedResult(
        val success: Boolean,
        val errorReason: String,
        val cardVariant: String,          // LEGACY_PDF417_XML | SMART_NID_MRZ_PDF417 | DIGITAL_NID_QR | LAMINATED_BACK_OCR
        val hasDecodedBarcodeOrMrz: Boolean,
        val rawBarcodeValue: String,
        val nidNumber: String,
        val holderNameEn: String,
        val dob: String,
        val bloodGroup: String,
        val issueDate: String,
        val qrEnglishData: String,
        val backEnglishText: String,
        val fullBackJson: JSONObject
    )

    private val mlKitOptions = BarcodeScannerOptions.Builder()
        .setBarcodeFormats(
            Barcode.FORMAT_PDF417,
            Barcode.FORMAT_QR_CODE,
            Barcode.FORMAT_AZTEC,
            Barcode.FORMAT_DATA_MATRIX,
            Barcode.FORMAT_CODE_128
        )
        .enableAllPotentialBarcodes()
        .build()

    fun detectBarcodeFastInFrame(inputImage: InputImage, onResult: (hasSignal: Boolean, rawValue: String) -> Unit) {
        val scanner = BarcodeScanning.getClient(mlKitOptions)
        scanner.process(inputImage)
            .addOnSuccessListener { barcodes ->
                val valid = barcodes.firstOrNull { !it.rawValue.isNullOrBlank() && it.rawValue!!.trim().length >= 6 }
                onResult(valid != null, valid?.rawValue.orEmpty())
            }
            .addOnFailureListener {
                onResult(false, "")
            }
            .addOnCompleteListener {
                try {
                    scanner.close()
                } catch (_: Exception) {
                }
            }
    }

    fun decodeFullNidBack(bitmap: Bitmap): NidBackDecodedResult {
        val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val backRawText = try {
            Tasks.await(textRecognizer.process(InputImage.fromBitmap(bitmap, 0))).text.orEmpty().trim()
        } catch (_: Exception) {
            ""
        } finally {
            try {
                textRecognizer.close()
            } catch (_: Exception) {
            }
        }

        val upperText = backRawText.uppercase(Locale.ROOT)

        // ১. ভুল করে ফ্রন্ট পার্ট বা পাসপোর্ট দিলে আটকে দেওয়া
        val looksLikeFrontCard = (upperText.contains("FATHER") || upperText.contains("MOTHER") ||
            upperText.contains("NID NO") || upperText.contains("ID NO")) &&
            !upperText.contains("BLOOD GROUP") && !upperText.contains("ISSUE DATE") && !upperText.contains("BGD")

        if (looksLikeFrontCard) {
            return NidBackDecodedResult(
                success = false,
                errorReason = "এটি এনআইডি কার্ডের সামনের অংশ! অনুগ্রহ করে পেছনের অংশ (Back Part) দিন।",
                cardVariant = "WRONG_SIDE",
                hasDecodedBarcodeOrMrz = false,
                rawBarcodeValue = "",
                nidNumber = "",
                holderNameEn = "",
                dob = "",
                bloodGroup = "",
                issueDate = "",
                qrEnglishData = "",
                backEnglishText = backRawText,
                fullBackJson = JSONObject()
            )
        }

        if (upperText.contains("PASSPORT") && upperText.contains("P<")) {
            return NidBackDecodedResult(
                success = false,
                errorReason = "ভুল ডকুমেন্ট! পাসপোর্টের জায়গায় এনআইডি কার্ডের ব্যাক পার্ট দিন।",
                cardVariant = "WRONG_DOC",
                hasDecodedBarcodeOrMrz = false,
                rawBarcodeValue = "",
                nidNumber = "",
                holderNameEn = "",
                dob = "",
                bloodGroup = "",
                issueDate = "",
                qrEnglishData = "",
                backEnglishText = backRawText,
                fullBackJson = JSONObject()
            )
        }

        // ২. যদি কোনো সেলফি বা মানুষের বড় মুখমণ্ডল দেওয়া হয় তবে আটকে দেওয়া
        if (containsLargeSelfieFace(bitmap)) {
            return NidBackDecodedResult(
                success = false,
                errorReason = "এটি এনআইডি কার্ডের ব্যাক পার্ট নয়! সঠিক ব্যাক পার্ট দিন।",
                cardVariant = "INVALID_IMAGE",
                hasDecodedBarcodeOrMrz = false,
                rawBarcodeValue = "",
                nidNumber = "",
                holderNameEn = "",
                dob = "",
                bloodGroup = "",
                issueDate = "",
                qrEnglishData = "",
                backEnglishText = backRawText,
                fullBackJson = JSONObject()
            )
        }

        // ৩. PDF417 / QR কোড স্ক্যান (পুরাতন ল্যামিনেটেড ও স্মার্ট কার্ড উভয় জোনে)
        val rawBarcode = scanPdf417AndQrAcrossZones(bitmap)

        // ৪. পুরাতন ল্যামিনেটেড NID ও অনলাইন e-NID XML (<pin>...</pin><name>...</name><DOB>...</DOB>)
        if (rawBarcode.contains("<pin>", ignoreCase = true) || rawBarcode.contains("<name>", ignoreCase = true)) {
            return parseLegacyXmlPdf417(rawBarcode, backRawText)
        }

        // ৫. স্মার্ট NID / ডিজিটাল NID-এর MRZ (I<BGD...) ও PDF417/QR
        val smartMrz = parseSmartOrDigitalMrz(rawBarcode, backRawText)
        if (smartMrz != null) {
            return smartMrz
        }

        // ৬. অন্যান্য QR / Key-Value বারকোড
        if (rawBarcode.isNotBlank()) {
            return parseGenericBarcodePayload(rawBarcode, backRawText)
        }

        // ৭. পুরাতন ল্যামিনেটেড কার্ডের ব্যাক পার্টে সব লেখা বাংলায় থাকে এবং প্লাস্টিক গ্লেয়ারে
        // বারকোড আংশিক অস্পষ্ট হতে পারে — তাই আসল ব্যাক পার্ট হিসেবে গ্রহণ করে Step 3-এ Gemini AI দ্বারা ভেরিফাই করা হবে
        val blood = extractBloodGroup(backRawText)
        val issue = extractIssueDate(backRawText)
        val fallbackCode = "NID-BACK-LAMINATED-" + (issue.ifBlank { blood.ifBlank { "OK" } })

        val json = JSONObject().apply {
            put("codeType", "LAMINATED_BACK_OCR")
            put("backCode", fallbackCode)
            put("nidNumber", "")
            put("nameEn", "")
            put("dob", "")
            put("bloodGroup", blood)
            put("issueDate", issue)
            put("qrEnglish", "")
            put("englishText", backRawText)
        }

        return NidBackDecodedResult(
            success = true,
            errorReason = "",
            cardVariant = "LAMINATED_BACK_OCR",
            hasDecodedBarcodeOrMrz = false,
            rawBarcodeValue = fallbackCode,
            nidNumber = "",
            holderNameEn = "",
            dob = "",
            bloodGroup = blood,
            issueDate = issue,
            qrEnglishData = "",
            backEnglishText = backRawText,
            fullBackJson = json
        )
    }

    private fun containsLargeSelfieFace(bitmap: Bitmap): Boolean {
        val detector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setMinFaceSize(0.25f)
                .build()
        )
        return try {
            val faces = Tasks.await(detector.process(InputImage.fromBitmap(bitmap, 0)))
            val imgArea = (bitmap.width * bitmap.height).coerceAtLeast(1).toFloat()
            faces.any { (it.boundingBox.width() * it.boundingBox.height()) / imgArea > 0.14f }
        } catch (_: Exception) {
            false
        } finally {
            try {
                detector.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun parseLegacyXmlPdf417(rawXml: String, backText: String): NidBackDecodedResult {
        val pin = Regex("<pin>\\s*([0-9\\s]{10,22})\\s*</pin>", RegexOption.IGNORE_CASE)
            .find(rawXml)?.groupValues?.getOrNull(1)?.replace("\\s+".toRegex(), "").orEmpty()
        val name = Regex("<name>\\s*([^<]+?)\\s*</name>", RegexOption.IGNORE_CASE)
            .find(rawXml)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        val dob = Regex("<dob>\\s*([^<]+?)\\s*</dob>", RegexOption.IGNORE_CASE)
            .find(rawXml)?.groupValues?.getOrNull(1)?.trim().orEmpty()

        val blood = extractBloodGroup(backText)
        val issue = extractIssueDate(backText)

        val json = JSONObject().apply {
            put("codeType", "LEGACY_PDF417_XML")
            put("backCode", rawXml)
            put("nidNumber", pin)
            put("nameEn", name)
            put("dob", dob)
            put("bloodGroup", blood)
            put("issueDate", issue)
            put("qrEnglish", rawXml)
            put("englishText", backText)
        }

        return NidBackDecodedResult(
            success = true,
            errorReason = "",
            cardVariant = "LEGACY_PDF417_XML",
            hasDecodedBarcodeOrMrz = pin.length in listOf(10, 13, 17),
            rawBarcodeValue = rawXml,
            nidNumber = pin,
            holderNameEn = name,
            dob = dob,
            bloodGroup = blood,
            issueDate = issue,
            qrEnglishData = rawXml,
            backEnglishText = backText,
            fullBackJson = json
        )
    }

    private fun parseSmartOrDigitalMrz(rawBarcode: String, backText: String): NidBackDecodedResult? {
        val normalizedUpper = backText.uppercase(Locale.ROOT)
        val compactAll = normalizedUpper.replace(" ", "")

        val mrzLine1Regex = Regex("[I1L|!]<[B8][G6][D0]([0-9O]{9})[<C(]?([0-9O])")
        val m1 = mrzLine1Regex.find(compactAll)

        var mrzNid10 = ""
        if (m1 != null) {
            val part9 = m1.groupValues[1].replace('O', '0')
            val part1 = m1.groupValues[2].replace('O', '0')
            if (part9.all { it.isDigit() } && part1.all { it.isDigit() }) {
                mrzNid10 = part9 + part1
            }
        }

        if (mrzNid10.length != 10) {
            val alt = Regex("(?:BGD|8GD|B6D)([0-9<]{10,13})").find(compactAll)?.groupValues?.getOrNull(1)
            val digits = alt?.filter { it.isDigit() }.orEmpty()
            if (digits.length >= 10) {
                mrzNid10 = digits.substring(0, 10)
            }
        }

        var mrzDob = ""
        val mrzDobMatch = Regex("\\b([0-9]{6})[0-9][MF<][0-9]{6,7}(?:BGD|8GD|B6D)").find(compactAll)
        if (mrzDobMatch != null) {
            mrzDob = formatMrzDob(mrzDobMatch.groupValues[1])
        }

        var mrzName = ""
        val nameLine = normalizedUpper.lines()
            .map { it.replace(" ", "").trim() }
            .firstOrNull { it.contains("<<") && !it.contains("BGD") && it.count { ch -> ch.isLetter() } >= 5 }
        if (nameLine != null) {
            val parts = nameLine.split("<<")
            val surname = parts.getOrNull(0)?.replace("<", " ")?.trim().orEmpty()
            val given = parts.getOrNull(1)?.replace("<", " ")?.trim().orEmpty()
            mrzName = listOf(given, surname).filter { it.isNotBlank() }.joinToString(" ").trim()
        }

        if (mrzNid10.length != 10 && !compactAll.contains("I<BGD") && !compactAll.contains("1<BGD")) {
            return null
        }

        val blood = extractBloodGroup(backText)
        val issue = extractIssueDate(backText)
        val combinedCode = buildString {
            if (rawBarcode.isNotBlank()) append(rawBarcode).append(" | ")
            if (mrzNid10.isNotBlank()) append("MRZ_NID:").append(mrzNid10)
            else append("SMART_NID_MRZ")
        }

        val json = JSONObject().apply {
            put("codeType", "SMART_NID_MRZ_PDF417")
            put("backCode", combinedCode)
            put("nidNumber", mrzNid10)
            put("nameEn", mrzName)
            put("dob", mrzDob)
            put("bloodGroup", blood)
            put("issueDate", issue)
            put("qrEnglish", combinedCode)
            put("englishText", backText)
        }

        return NidBackDecodedResult(
            success = true,
            errorReason = "",
            cardVariant = "SMART_NID_MRZ_PDF417",
            hasDecodedBarcodeOrMrz = (mrzNid10.length == 10),
            rawBarcodeValue = combinedCode,
            nidNumber = mrzNid10,
            holderNameEn = mrzName,
            dob = mrzDob,
            bloodGroup = blood,
            issueDate = issue,
            qrEnglishData = combinedCode,
            backEnglishText = backText,
            fullBackJson = json
        )
    }

    private fun parseGenericBarcodePayload(rawCode: String, backText: String): NidBackDecodedResult {
        val compactCode = rawCode.replace("\\s+".toRegex(), " ")
        val nid = Regex("(?:NW|PIN|NID|ID|RN)[=:\\s]+([0-9\\s]{10,22})", RegexOption.IGNORE_CASE)
            .find(compactCode)?.groupValues?.getOrNull(1)?.replace("\\s+".toRegex(), "")
            ?: Regex("\\b([0-9]{17}|[0-9]{13}|[0-9]{10}|[0-9]{3}\\s+[0-9]{3}\\s+[0-9]{4})\\b")
                .find(compactCode)?.groupValues?.getOrNull(1)?.replace("\\s+".toRegex(), "").orEmpty()

        val name = Regex("(?:NM|NAME)[=:\\s]+([A-Z .'-]{3,60})", RegexOption.IGNORE_CASE)
            .find(compactCode)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        val dob = Regex("(?:DB|DOB)[=:\\s]+([0-9]{2}[/-][0-9]{2}[/-][0-9]{4}|[0-9]{4}-[0-9]{2}-[0-9]{2}|[0-9]{2}\\s+[A-Z]{3}\\s+[0-9]{4})", RegexOption.IGNORE_CASE)
            .find(compactCode)?.groupValues?.getOrNull(1)?.trim().orEmpty()

        val blood = extractBloodGroup(backText)
        val issue = extractIssueDate(backText)

        val json = JSONObject().apply {
            put("codeType", "DIGITAL_NID_QR")
            put("backCode", rawCode)
            put("nidNumber", nid)
            put("nameEn", name)
            put("dob", dob)
            put("bloodGroup", blood)
            put("issueDate", issue)
            put("qrEnglish", rawCode)
            put("englishText", backText)
        }

        return NidBackDecodedResult(
            success = true,
            errorReason = "",
            cardVariant = "DIGITAL_NID_QR",
            hasDecodedBarcodeOrMrz = nid.length in listOf(10, 13, 17),
            rawBarcodeValue = rawCode,
            nidNumber = nid,
            holderNameEn = name,
            dob = dob,
            bloodGroup = blood,
            issueDate = issue,
            qrEnglishData = rawCode,
            backEnglishText = backText,
            fullBackJson = json
        )
    }

    private fun scanPdf417AndQrAcrossZones(bitmap: Bitmap): String {
        val scanner = BarcodeScanning.getClient(mlKitOptions)
        val zones = buildTopAndBottomZones(bitmap)
        try {
            for (zone in zones) {
                try {
                    val found = Tasks.await(scanner.process(InputImage.fromBitmap(zone, 0)))
                    val hit = found.firstOrNull { !it.rawValue.isNullOrBlank() && it.rawValue!!.trim().length >= 6 }
                    if (hit != null) return hit.rawValue!!.trim()
                } catch (_: Exception) {
                }
            }
            for (zone in zones) {
                val enhanced = toHighContrastGrayscale(zone)
                try {
                    val zxingText = decodeWithZxing(enhanced)
                    if (!zxingText.isNullOrBlank()) return zxingText
                } finally {
                    if (enhanced !== zone && !enhanced.isRecycled) enhanced.recycle()
                }
            }
        } finally {
            for (z in zones) {
                if (z !== bitmap && !z.isRecycled) z.recycle()
            }
            try {
                scanner.close()
            } catch (_: Exception) {
            }
        }
        return ""
    }

    private fun buildTopAndBottomZones(original: Bitmap): List<Bitmap> {
        val list = mutableListOf<Bitmap>()
        list.add(original)

        if (original.height > 160 && original.width > 160) {
            val topH = (original.height * 0.45f).toInt()
            if (topH > 50) {
                val topCrop = Bitmap.createBitmap(original, 0, 0, original.width, topH)
                val topScaled = Bitmap.createScaledBitmap(topCrop, (topCrop.width * 1.8f).toInt(), (topCrop.height * 1.8f).toInt(), true)
                if (topCrop !== topScaled && !topCrop.isRecycled) topCrop.recycle()
                list.add(topScaled)
            }

            val botTop = (original.height * 0.48f).toInt()
            val botH = original.height - botTop
            if (botH > 50) {
                val botCrop = Bitmap.createBitmap(original, 0, botTop, original.width, botH)
                val botScaled = Bitmap.createScaledBitmap(botCrop, (botCrop.width * 1.8f).toInt(), (botCrop.height * 1.8f).toInt(), true)
                if (botCrop !== botScaled && !botCrop.isRecycled) botCrop.recycle()
                list.add(botScaled)
            }
        }

        list.add(KycImageUtils.rotateBitmap(original, 180f))
        list.add(KycImageUtils.rotateBitmap(original, 90f))
        list.add(KycImageUtils.rotateBitmap(original, 270f))
        return list
    }

    private fun decodeWithZxing(bitmap: Bitmap): String? {
        return try {
            val w = bitmap.width
            val h = bitmap.height
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            val source = RGBLuminanceSource(w, h, pixels)
            val hints = EnumMap<DecodeHintType, Any>(DecodeHintType::class.java).apply {
                put(DecodeHintType.TRY_HARDER, true)
                put(DecodeHintType.POSSIBLE_FORMATS, listOf(BarcodeFormat.PDF_417, BarcodeFormat.QR_CODE))
            }
            val reader = MultiFormatReader()
            val res = try {
                reader.decode(BinaryBitmap(HybridBinarizer(source)), hints)
            } catch (_: Exception) {
                reader.decode(BinaryBitmap(GlobalHistogramBinarizer(source)), hints)
            }
            res?.text?.trim()?.takeIf { it.length >= 6 }
        } catch (_: Exception) {
            null
        }
    }

    private fun toHighContrastGrayscale(src: Bitmap): Bitmap {
        return try {
            val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            val cm = ColorMatrix().apply {
                setSaturation(0f)
                val c = 1.45f
                val t = (-0.5f * c + 0.5f) * 255f
                postConcat(ColorMatrix(floatArrayOf(c, 0f, 0f, 0f, t, 0f, c, 0f, 0f, t, 0f, 0f, c, 0f, t, 0f, 0f, 0f, 1f, 0f)))
            }
            val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(cm) }
            canvas.drawBitmap(src, 0f, 0f, paint)
            out
        } catch (_: Exception) {
            src
        }
    }

    private fun formatMrzDob(yyMMdd: String): String {
        val yy = yyMMdd.substring(0, 2).toIntOrNull() ?: return ""
        val mm = yyMMdd.substring(2, 4).toIntOrNull() ?: return ""
        val dd = yyMMdd.substring(4, 6)
        val months = listOf("", "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec")
        val monthStr = months.getOrNull(mm) ?: return ""
        val year = if (yy > 30) "19${yyMMdd.substring(0, 2)}" else "20${yyMMdd.substring(0, 2)}"
        return "$dd $monthStr $year"
    }

    private fun extractBloodGroup(text: String): String {
        return Regex("\\b(A\\+|A-|B\\+|B-|AB\\+|AB-|O\\+|O-)\\b")
            .find(text.uppercase(Locale.ROOT))?.groupValues?.getOrNull(1).orEmpty()
    }

    private fun extractIssueDate(text: String): String {
        return Regex("(?:ISSUE\\s*DATE|প্রদানের\\s*তারিখ)[^0-9A-Za-z০-৯]{0,8}([0-9]{1,2}\\s+[A-Za-z]{3,9}\\s+[0-9]{4}|[0-9০-৯]{2}[/-][0-9০-৯]{2}[/-][0-9০-৯]{4})", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.getOrNull(1).orEmpty()
    }
}
