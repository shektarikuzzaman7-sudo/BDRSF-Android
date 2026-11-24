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
 * BDRSF দ্বৈত-ইঞ্জিন NID ব্যাক পার্ট ডিকোডার:
 * ১) Legacy & Online NID PDF417 Feature:
 *    <pin>...</pin><name>...</name><DOB>...</DOB>...<ds>...</ds> XML ডিকোড করে।
 * ২) Smart / Digital NID MRZ + PDF417/QR Feature:
 *    স্মার্ট কার্ডের ৩-লাইনের ICAO TD1 MRZ (I<BGD...) এবং উপরের PDF417/QR ডিকোড করে।
 */
class NidBarcodeAndQrDecoder {

    data class NidBackDecodedResult(
        val success: Boolean,
        val errorReason: String,
        val cardVariant: String,        // LEGACY_PDF417_XML | SMART_NID_MRZ_PDF417 | DIGITAL_QR
        val rawBarcodeValue: String,
        val nidNumber: String,
        val pinNumber: String,
        val holderNameEn: String,
        val dob: String,
        val bloodGroup: String,
        val issueDate: String,
        val hasDigitalSignature: Boolean,
        val qrEnglishData: String,
        val backEnglishText: String,
        val fullBackJson: JSONObject
    )

    private val mlKitOptions = BarcodeScannerOptions.Builder()
        .setBarcodeFormats(
            Barcode.FORMAT_PDF417,
            Barcode.FORMAT_QR_CODE,
            Barcode.FORMAT_AZTEC,
            Barcode.FORMAT_DATA_MATRIX
        )
        .enableAllPotentialBarcodes()
        .build()

    /**
     * রিয়েল-টাইম ক্যামেরা ফ্রেমে PDF417 বা স্মার্ট NID MRZ আছে কি না যাচাই করে
     */
    fun detectBarcodeFastInFrame(inputImage: InputImage, onResult: (hasSignal: Boolean, rawValue: String) -> Unit) {
        val scanner = BarcodeScanning.getClient(mlKitOptions)
        scanner.process(inputImage)
            .addOnSuccessListener { barcodes ->
                val valid = barcodes.firstOrNull { !it.rawValue.isNullOrBlank() && it.rawValue!!.trim().length >= 8 }
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

    /**
     * মূল ডিকোডার এন্ট্রি পয়েন্ট — পুরনো NID (PDF417 XML) এবং নতুন স্মার্ট NID (MRZ + PDF417/QR)
     * উভয় ফরম্যাট যাচাই করে।
     */
    fun decodeFullNidBack(bitmap: Bitmap): NidBackDecodedResult {
        val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val backRawEnglishText = try {
            Tasks.await(textRecognizer.process(InputImage.fromBitmap(bitmap, 0))).text.orEmpty().trim()
        } catch (_: Exception) {
            ""
        } finally {
            try {
                textRecognizer.close()
            } catch (_: Exception) {
            }
        }

        // ধাপ ১: ব্যাক পার্টের উপরে ও নিচে উভয় জোনে ML Kit + ZXing চালিয়ে PDF417 / QR বের করা
        val rawBarcode = scanPdf417AndQrAcrossZones(bitmap)

        // ধাপ ২: যদি XML ট্যাগ (<pin>, <name>, <DOB>, <ds>) থাকে -> পুরাতন NID / অনলাইন NID ফিচার
        if (rawBarcode.contains("<pin>", ignoreCase = true) || rawBarcode.contains("<name>", ignoreCase = true)) {
            return decodeLegacyPdf417Feature(rawBarcode, backRawEnglishText)
        }

        // ধাপ ৩: স্মার্ট NID কার্ডের MRZ (I<BGD...) অথবা QR/PDF417 ফিচার
        val smartResult = decodeSmartMrzAndQrFeature(rawBarcode, backRawEnglishText)
        if (smartResult.success) {
            return smartResult
        }

        // ধাপ ৪: সাধারণ Key-Value QR / PDF417 (NW=..., PIN=...) থাকলে সেটি প্রসেস করা
        if (rawBarcode.isNotBlank()) {
            val kvRes = decodeKeyValueQrFeature(rawBarcode, backRawEnglishText)
            if (kvRes.success) return kvRes
        }

        return NidBackDecodedResult(
            success = false,
            errorReason = "এনআইডি ব্যাক পার্টের PDF417 বারকোড অথবা স্মার্ট কার্ডের MRZ/QR কোড শনাক্ত করা যায়নি! পরিষ্কার ব্যাক পার্ট দিন।",
            cardVariant = "NONE",
            rawBarcodeValue = rawBarcode,
            nidNumber = "",
            pinNumber = "",
            holderNameEn = "",
            dob = "",
            bloodGroup = "",
            issueDate = "",
            hasDigitalSignature = false,
            qrEnglishData = "",
            backEnglishText = backRawEnglishText,
            fullBackJson = JSONObject()
        )
    }

    /**
     * ফিচার ১: পুরাতন লেমিনেটেড NID ও অনলাইন ডাউনলোড NID-এর PDF417 XML ডিকোডার
     * ফরম্যাট: <pin>19945611076000418</pin><name>SHEK TARIKUZZAMAN UZZAL</name><DOB>02 Feb 1994</DOB>...<ds>...</ds>
     */
    private fun decodeLegacyPdf417Feature(rawXml: String, backText: String): NidBackDecodedResult {
        val pin = Regex("<pin>\\s*([0-9]{10,17})\\s*</pin>", RegexOption.IGNORE_CASE)
            .find(rawXml)?.groupValues?.getOrNull(1).orEmpty()
        val name = Regex("<name>\\s*([^<]+?)\\s*</name>", RegexOption.IGNORE_CASE)
            .find(rawXml)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        val dob = Regex("<dob>\\s*([^<]+?)\\s*</dob>", RegexOption.IGNORE_CASE)
            .find(rawXml)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        val ds = Regex("<ds>\\s*([0-9a-fA-F]{20,})\\s*</ds>", RegexOption.IGNORE_CASE)
            .find(rawXml)?.groupValues?.getOrNull(1).orEmpty()

        val issueDate = extractIssueDateFromBackText(backText)
        val bloodGroup = extractBloodGroupFromBackText(backText)

        if (pin.length !in listOf(10, 13, 17) || name.length < 3) {
            return NidBackDecodedResult(
                success = false,
                errorReason = "পুরাতন/অনলাইন NID-এর PDF417 ডেটা অসম্পূর্ণ বা বিকৃত। পরিষ্কার ছবি দিন।",
                cardVariant = "LEGACY_PDF417_XML",
                rawBarcodeValue = rawXml,
                nidNumber = pin,
                pinNumber = pin,
                holderNameEn = name,
                dob = dob,
                bloodGroup = bloodGroup,
                issueDate = issueDate,
                hasDigitalSignature = ds.isNotBlank(),
                qrEnglishData = rawXml,
                backEnglishText = backText,
                fullBackJson = JSONObject()
            )
        }

        val json = JSONObject().apply {
            put("codeType", "LEGACY_PDF417_XML")
            put("backCode", rawXml)
            put("nidNumber", pin)
            put("pinNumber", pin)
            put("nameEn", name)
            put("dob", dob)
            put("bloodGroup", bloodGroup)
            put("issueDate", issueDate)
            put("hasDigitalSignature", ds.isNotBlank())
            put("qrEnglish", rawXml)
            put("englishText", backText)
        }

        return NidBackDecodedResult(
            success = true,
            errorReason = "",
            cardVariant = "LEGACY_PDF417_XML",
            rawBarcodeValue = rawXml,
            nidNumber = pin,
            pinNumber = pin,
            holderNameEn = name,
            dob = dob,
            bloodGroup = bloodGroup,
            issueDate = issueDate,
            hasDigitalSignature = ds.isNotBlank(),
            qrEnglishData = rawXml,
            backEnglishText = backText,
            fullBackJson = json
        )
    }

    /**
     * ফিচার ২: স্মার্ট NID কার্ডের ৩-লাইনের ICAO TD1 MRZ + PDF417/QR ডিকোডার
     * উদাহরণ:
     * I<BGD736198413<62<<<<<<<<<<<<<
     * 0101217F3506086BGD<<<<<<<<<<<8
     * MEEM<<MST<KHADIJA<BEGUM<<<<<<<
     */
    private fun decodeSmartMrzAndQrFeature(rawBarcode: String, backText: String): NidBackDecodedResult {
        val compactLines = backText.lines()
            .map { it.uppercase(Locale.ROOT).replace(" ", "").trim() }
            .filter { it.length >= 20 && (it.contains("<") || it.startsWith("I<BGD") || it.startsWith("1<BGD")) }

        val line1 = compactLines.firstOrNull {
            it.startsWith("I<BGD") || it.startsWith("1<BGD") || it.startsWith("I<8GD")
        }

        var mrzNid10 = ""
        var mrzDob = ""
        var mrzName = ""

        if (line1 != null && line1.length >= 16) {
            // I<BGD এর পরের অংশ থেকে প্রথম ১০টি ডিজিট নেওয়া (যেমন 736198413<62 -> 7361984136)
            val afterCountry = line1.substring(5)
            val digitsOnly = afterCountry.filter { it.isDigit() }
            if (digitsOnly.length >= 10) {
                mrzNid10 = digitsOnly.substring(0, 10)
            }

            val line1Idx = compactLines.indexOf(line1)
            val line2 = compactLines.getOrNull(line1Idx + 1).orEmpty()
            val line3 = compactLines.getOrNull(line1Idx + 2).orEmpty()

            // লাইন ২ থেকে জন্মতারিখ YYMMDD -> dd MMM yyyy (যেমন 010121 -> 21 Jan 2001)
            if (line2.length >= 6 && line2.substring(0, 6).all { it.isDigit() }) {
                mrzDob = formatMrzDob(line2.substring(0, 6))
            }

            // লাইন ৩ থেকে নাম (MEEM<<MST<KHADIJA<BEGUM<<<<<<< -> MST KHADIJA BEGUM MEEM)
            if (line3.contains("<<")) {
                val parts = line3.split("<<")
                val surname = parts.getOrNull(0)?.replace("<", " ")?.trim().orEmpty()
                val given = parts.getOrNull(1)?.replace("<", " ")?.trim().orEmpty()
                mrzName = listOf(given, surname).filter { it.isNotBlank() }.joinToString(" ").trim()
            }
        }

        // যদি স্মার্ট কার্ডের MRZ থেকে ১০ ডিজিট NID পাওয়া যায় (অথবা উপরে PDF417 ও ডিটেক্ট হয়)
        if (mrzNid10.length == 10) {
            val bloodGroup = extractBloodGroupFromBackText(backText)
            val issueDate = extractIssueDateFromBackText(backText)
            val combinedCode = buildString {
                if (rawBarcode.isNotBlank()) append(rawBarcode).append(" | ")
                append("MRZ_NID:").append(mrzNid10)
                if (mrzName.isNotBlank()) append(" | NAME:").append(mrzName)
                if (mrzDob.isNotBlank()) append(" | DOB:").append(mrzDob)
            }

            val json = JSONObject().apply {
                put("codeType", "SMART_NID_MRZ_PDF417")
                put("backCode", combinedCode)
                put("nidNumber", mrzNid10)
                put("pinNumber", mrzNid10)
                put("nameEn", mrzName)
                put("dob", mrzDob)
                put("bloodGroup", bloodGroup)
                put("issueDate", issueDate)
                put("hasDigitalSignature", rawBarcode.isNotBlank())
                put("qrEnglish", combinedCode)
                put("englishText", backText)
            }

            return NidBackDecodedResult(
                success = true,
                errorReason = "",
                cardVariant = "SMART_NID_MRZ_PDF417",
                rawBarcodeValue = combinedCode,
                nidNumber = mrzNid10,
                pinNumber = mrzNid10,
                holderNameEn = mrzName,
                dob = mrzDob,
                bloodGroup = bloodGroup,
                issueDate = issueDate,
                hasDigitalSignature = rawBarcode.isNotBlank(),
                qrEnglishData = combinedCode,
                backEnglishText = backText,
                fullBackJson = json
            )
        }

        return NidBackDecodedResult(
            success = false,
            errorReason = "",
            cardVariant = "NONE",
            rawBarcodeValue = rawBarcode,
            nidNumber = "",
            pinNumber = "",
            holderNameEn = "",
            dob = "",
            bloodGroup = "",
            issueDate = "",
            hasDigitalSignature = false,
            qrEnglishData = "",
            backEnglishText = backText,
            fullBackJson = JSONObject()
        )
    }

    private fun decodeKeyValueQrFeature(rawCode: String, backText: String): NidBackDecodedResult {
        val nid = Regex("(?:NW|PIN|NID|ID|RN)[=:\\s]+([0-9]{10}|[0-9]{13}|[0-9]{17})", RegexOption.IGNORE_CASE)
            .find(rawCode)?.groupValues?.getOrNull(1)
            ?: Regex("\\b([0-9]{17}|[0-9]{13}|[0-9]{10})\\b").find(rawCode)?.groupValues?.getOrNull(1).orEmpty()

        val name = Regex("(?:NM|NAME)[=:\\s]+([A-Z .'-]{3,60})", RegexOption.IGNORE_CASE)
            .find(rawCode)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        val dob = Regex("(?:DB|DOB)[=:\\s]+([0-9]{2}[/-][0-9]{2}[/-][0-9]{4}|[0-9]{4}-[0-9]{2}-[0-9]{2}|[0-9]{2}\\s+[A-Z]{3}\\s+[0-9]{4})", RegexOption.IGNORE_CASE)
            .find(rawCode)?.groupValues?.getOrNull(1)?.trim().orEmpty()

        if (nid.length !in listOf(10, 13, 17)) {
            return NidBackDecodedResult(false, "", "NONE", rawCode, "", "", "", "", "", "", false, "", backText, JSONObject())
        }

        val json = JSONObject().apply {
            put("codeType", "DIGITAL_QR")
            put("backCode", rawCode)
            put("nidNumber", nid)
            put("pinNumber", nid)
            put("nameEn", name)
            put("dob", dob)
            put("bloodGroup", extractBloodGroupFromBackText(backText))
            put("issueDate", extractIssueDateFromBackText(backText))
            put("qrEnglish", rawCode)
            put("englishText", backText)
        }

        return NidBackDecodedResult(
            success = true,
            errorReason = "",
            cardVariant = "DIGITAL_QR",
            rawBarcodeValue = rawCode,
            nidNumber = nid,
            pinNumber = nid,
            holderNameEn = name,
            dob = dob,
            bloodGroup = extractBloodGroupFromBackText(backText),
            issueDate = extractIssueDateFromBackText(backText),
            hasDigitalSignature = true,
            qrEnglishData = rawCode,
            backEnglishText = backText,
            fullBackJson = json
        )
    }

    /**
     * স্মার্ট কার্ডের উপরের অংশ (Top 45%) এবং পুরাতন/অনলাইন কার্ডের নিচের অংশ (Bottom 50%)
     * উভয় জায়গায় ML Kit + ZXing হাই-কনট্রাস্ট স্ক্যান চালায়।
     */
    private fun scanPdf417AndQrAcrossZones(bitmap: Bitmap): String {
        val scanner = BarcodeScanning.getClient(mlKitOptions)
        val zones = buildTopAndBottomZones(bitmap)
        try {
            // ১. ML Kit পাস
            for (zone in zones) {
                try {
                    val found = Tasks.await(scanner.process(InputImage.fromBitmap(zone, 0)))
                    val hit = found.firstOrNull { !it.rawValue.isNullOrBlank() && it.rawValue!!.trim().length >= 8 }
                    if (hit != null) return hit.rawValue!!.trim()
                } catch (_: Exception) {
                }
            }
            // ২. ZXing হাই-কনট্রাস্ট পাস (PDF417 & QR)
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

        if (original.height > 200 && original.width > 200) {
            // স্মার্ট কার্ডের উপরের PDF417 জোন (Top 42%)
            val topH = (original.height * 0.42f).toInt()
            if (topH > 70) {
                val topCrop = Bitmap.createBitmap(original, 0, 0, original.width, topH)
                val topScaled = Bitmap.createScaledBitmap(topCrop, (topCrop.width * 1.7f).toInt(), (topCrop.height * 1.7f).toInt(), true)
                if (topCrop !== topScaled && !topCrop.isRecycled) topCrop.recycle()
                list.add(topScaled)
            }

            // পুরাতন ও অনলাইন কার্ডের নিচের PDF417 জোন (Bottom 45%)
            val botTop = (original.height * 0.52f).toInt()
            val botH = original.height - botTop
            if (botH > 70) {
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
            res?.text?.trim()?.takeIf { it.length >= 8 }
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
                val c = 1.5f
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

    private fun extractBloodGroupFromBackText(text: String): String {
        return Regex("\\b(A\\+|A-|B\\+|B-|AB\\+|AB-|O\\+|O-)\\b")
            .find(text.uppercase(Locale.ROOT))?.groupValues?.getOrNull(1).orEmpty()
    }

    private fun extractIssueDateFromBackText(text: String): String {
        return Regex("(?:ISSUE\\s*DATE|প্রদানের\\s*তারিখ)[^0-9A-Za-z০-৯]{0,8}([0-9]{1,2}\\s+[A-Za-z]{3,9}\\s+[0-9]{4}|[0-9০-৯]{2}[/-][0-9০-৯]{2}[/-][0-9০-৯]{4})", RegexOption.IGNORE_CASE)
            .find(text)?.groupValues?.getOrNull(1).orEmpty()
    }
}
