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
 * BDRSF Strict NID ব্যাক পার্ট ডিকোডার (ML Kit + ZXing Hybrid Engine):
 * - শুধুমাত্র আসল PDF417, MicroPDF417 অথবা Digital NID QR Code ডিকোড হলেই ভ্যালিড ধরবে।
 * - ভুয়া বা বানানো ব্যাক পার্ট (যাতে বৈধ বারকোড/QR ডেটা নেই) ১০০% রিজেক্ট করবে।
 * - ডিকোড করা ডেটা থেকে NID নম্বর, নাম, জন্মতারিখ, ইস্যু ডেট ও FZ নম্বর বের করবে।
 */
class NidBarcodeAndQrDecoder {

    data class NidBackDecodedResult(
        val success: Boolean,
        val errorReason: String,
        val codeType: String,           // PDF417 | MICRO_PDF417 | DIGITAL_NID_QR
        val rawBarcodeValue: String,
        val nidNumber: String,
        val fzNumber: String,
        val holderNameEn: String,
        val dob: String,
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
            Barcode.FORMAT_DATA_MATRIX
        )
        .enableAllPotentialBarcodes()
        .build()

    /**
     * রিয়েল-টাইম ক্যামেরা ফ্রেমে আসল PDF417 / QR কোড আছে কি না যাচাই করে
     */
    fun detectBarcodeFastInFrame(inputImage: InputImage, onResult: (hasBarcode: Boolean, rawValue: String) -> Unit) {
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
     * গ্যালারি বা ক্যামেরা থেকে প্রাপ্ত NID ব্যাক পার্ট ইমেজ কঠোরভাবে যাচাই ও ডিকোড করে।
     */
    fun decodeFullNidBack(bitmap: Bitmap): NidBackDecodedResult {
        val scanner = BarcodeScanning.getClient(mlKitOptions)
        val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        var rawCode = ""
        var formatLabel = ""
        var backRawEnglishText = ""

        try {
            val textResult = Tasks.await(textRecognizer.process(InputImage.fromBitmap(bitmap, 0)))
            backRawEnglishText = textResult.text.orEmpty().trim()

            // ধাপ ১: ML Kit দিয়ে ০°, ১৮০°, ৯০°, ২৭০° এবং নিচের বারকোড জোনে স্ক্যান
            val candidates = buildMultiAngleBitmaps(bitmap)
            try {
                for (candidate in candidates) {
                    val found = Tasks.await(scanner.process(InputImage.fromBitmap(candidate, 0)))
                    val validBarcode = found.firstOrNull { !it.rawValue.isNullOrBlank() && it.rawValue!!.trim().length >= 8 }
                    if (validBarcode != null) {
                        rawCode = validBarcode.rawValue!!.trim()
                        formatLabel = when (validBarcode.format) {
                            Barcode.FORMAT_PDF417 -> if (rawCode.length < 70) "MICRO_PDF417" else "PDF417"
                            Barcode.FORMAT_QR_CODE -> "DIGITAL_NID_QR"
                            Barcode.FORMAT_AZTEC -> "AZTEC_2D"
                            Barcode.FORMAT_DATA_MATRIX -> "DATA_MATRIX_2D"
                            else -> "NID_2D_CODE"
                        }
                        break
                    }
                }

                // ধাপ ২: ML Kit ব্যর্থ হলে হাই-কনট্রাস্ট ইমেজে ZXing Core (PDF417 + QR) ব্যাকআপ ডিকোডার চালানো
                if (rawCode.isBlank()) {
                    for (candidate in candidates) {
                        val enhanced = toHighContrastGrayscale(candidate)
                        try {
                            val zxingResult = decodeWithZxing(enhanced)
                            if (zxingResult != null) {
                                rawCode = zxingResult.first
                                formatLabel = zxingResult.second
                                break
                            }
                        } finally {
                            if (enhanced !== candidate && !enhanced.isRecycled) {
                                enhanced.recycle()
                            }
                        }
                    }
                }
            } finally {
                for (candidate in candidates) {
                    if (candidate !== bitmap && !candidate.isRecycled) {
                        candidate.recycle()
                    }
                }
            }
        } catch (_: Exception) {
        } finally {
            try {
                scanner.close()
            } catch (_: Exception) {
            }
            try {
                textRecognizer.close()
            } catch (_: Exception) {
            }
        }

        // কঠোর চেক ১: আসল PDF417 বা QR কোড পাওয়া না গেলে সরাসরি রিজেক্ট
        if (rawCode.isBlank()) {
            return NidBackDecodedResult(
                success = false,
                errorReason = "এনআইডি ব্যাক পার্টের PDF417 বারকোড বা ডিজিটাল QR কোড শনাক্ত করা যায়নি! আসল ও পরিষ্কার ব্যাক পার্ট দিন।",
                codeType = "NONE",
                rawBarcodeValue = "",
                nidNumber = "",
                fzNumber = "",
                holderNameEn = "",
                dob = "",
                issueDate = "",
                qrEnglishData = "",
                backEnglishText = backRawEnglishText,
                fullBackJson = JSONObject()
            )
        }

        val parsed = parseAuthenticNidPayload(rawCode, backRawEnglishText)
        val nidNo = parsed["nidNumber"].orEmpty()
        val fzNo = parsed["fzNumber"].orEmpty()
        val holderName = parsed["nameEn"].orEmpty()
        val dob = parsed["dob"].orEmpty()
        val issueDate = parsed["issueDate"].orEmpty()
        val qrEnglish = parsed["qrEnglish"].orEmpty()

        // কঠোর চেক ২: ডিকোড করা বারকোড/QR-এর ভেতরে বৈধ NID নম্বর বা NID সিগন্যাল থাকতে হবে
        // (যেকোনো সাধারণ প্রোডাক্ট বারকোড বা এলোমেলো QR দিলে আটকে দেবে)
        val hasValidNidDigits = nidNo.length in listOf(10, 13, 17)
        val hasNidPayloadSignature = rawCode.contains("<pin>", ignoreCase = true) ||
            rawCode.contains("NW=", ignoreCase = true) ||
            rawCode.contains("PIN=", ignoreCase = true) ||
            rawCode.contains("NID", ignoreCase = true) ||
            rawCode.contains("RN=", ignoreCase = true) ||
            hasValidNidDigits

        if (!hasNidPayloadSignature) {
            return NidBackDecodedResult(
                success = false,
                errorReason = "এটি বৈধ এনআইডি কার্ডের বারকোড বা QR কোড নয়! সঠিক এনআইডি ব্যাক পার্ট দিন।",
                codeType = formatLabel,
                rawBarcodeValue = rawCode,
                nidNumber = "",
                fzNumber = fzNo,
                holderNameEn = "",
                dob = "",
                issueDate = issueDate,
                qrEnglishData = "",
                backEnglishText = backRawEnglishText,
                fullBackJson = JSONObject()
            )
        }

        val json = JSONObject().apply {
            put("codeType", formatLabel)
            put("backCode", rawCode)
            put("nidNumber", nidNo)
            put("fzNumber", fzNo)
            put("nameEn", holderName)
            put("dob", dob)
            put("issueDate", issueDate)
            put("qrEnglish", qrEnglish)
            put("englishText", backRawEnglishText)
        }

        return NidBackDecodedResult(
            success = true,
            errorReason = "",
            codeType = formatLabel,
            rawBarcodeValue = rawCode,
            nidNumber = nidNo,
            fzNumber = fzNo,
            holderNameEn = holderName,
            dob = dob,
            issueDate = issueDate,
            qrEnglishData = qrEnglish,
            backEnglishText = backRawEnglishText,
            fullBackJson = json
        )
    }

    private fun decodeWithZxing(bitmap: Bitmap): Pair<String, String>? {
        return try {
            val width = bitmap.width
            val height = bitmap.height
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

            val source = RGBLuminanceSource(width, height, pixels)
            val hints = EnumMap<DecodeHintType, Any>(DecodeHintType::class.java).apply {
                put(DecodeHintType.TRY_HARDER, true)
                put(DecodeHintType.POSSIBLE_FORMATS, listOf(BarcodeFormat.PDF_417, BarcodeFormat.QR_CODE))
            }

            val reader = MultiFormatReader()
            val result = try {
                reader.decode(BinaryBitmap(HybridBinarizer(source)), hints)
            } catch (_: Exception) {
                reader.decode(BinaryBitmap(GlobalHistogramBinarizer(source)), hints)
            }

            val text = result?.text?.trim().orEmpty()
            if (text.length < 8) return null
            val type = when (result.barcodeFormat) {
                BarcodeFormat.PDF_417 -> if (text.length < 70) "MICRO_PDF417" else "PDF417"
                BarcodeFormat.QR_CODE -> "DIGITAL_NID_QR"
                else -> "NID_2D_CODE"
            }
            Pair(text, type)
        } catch (_: Exception) {
            null
        }
    }

    private fun buildMultiAngleBitmaps(original: Bitmap): List<Bitmap> {
        val list = mutableListOf<Bitmap>()
        list.add(original)
        list.add(KycImageUtils.rotateBitmap(original, 180f))

        // Smart NID ও Old NID-এর নিচের বারকোড জোন জুম-ক্রপ
        if (original.height > 200 && original.width > 200) {
            val cropTop = (original.height * 0.40f).toInt()
            val cropH = original.height - cropTop
            if (cropH > 80) {
                val bottomCrop = Bitmap.createBitmap(original, 0, cropTop, original.width, cropH)
                val scaled = Bitmap.createScaledBitmap(
                    bottomCrop,
                    (bottomCrop.width * 1.75f).toInt(),
                    (bottomCrop.height * 1.75f).toInt(),
                    true
                )
                if (bottomCrop !== scaled && !bottomCrop.isRecycled) bottomCrop.recycle()
                list.add(scaled)
            }
        }

        list.add(KycImageUtils.rotateBitmap(original, 90f))
        list.add(KycImageUtils.rotateBitmap(original, 270f))
        return list
    }

    private fun toHighContrastGrayscale(src: Bitmap): Bitmap {
        return try {
            val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(out)
            val cm = ColorMatrix().apply {
                setSaturation(0f)
                val contrast = 1.45f
                val translate = (-0.5f * contrast + 0.5f) * 255f
                postConcat(
                    ColorMatrix(
                        floatArrayOf(
                            contrast, 0f, 0f, 0f, translate,
                            0f, contrast, 0f, 0f, translate,
                            0f, 0f, contrast, 0f, translate,
                            0f, 0f, 0f, 1f, 0f
                        )
                    )
                )
            }
            val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(cm) }
            canvas.drawBitmap(src, 0f, 0f, paint)
            out
        } catch (_: Exception) {
            src
        }
    }

    private fun parseAuthenticNidPayload(rawCode: String, backText: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val upperBackText = backText.uppercase(Locale.ROOT)

        // ১. Smart NID / Old NID PDF417 XML ট্যাগ (<pin>...</pin>, <name>...</name>, <dob>...)
        val pinTag = Regex("<pin>\\s*([0-9]{10}|[0-9]{13}|[0-9]{17})\\s*</pin>", RegexOption.IGNORE_CASE)
            .find(rawCode)?.groupValues?.getOrNull(1)
        val nameTag = Regex("<name>\\s*([^<]+?)\\s*</name>", RegexOption.IGNORE_CASE)
            .find(rawCode)?.groupValues?.getOrNull(1)?.trim()
        val dobTag = Regex("<dob>\\s*([^<]+?)\\s*</dob>", RegexOption.IGNORE_CASE)
            .find(rawCode)?.groupValues?.getOrNull(1)?.trim()

        // ২. Digital NID QR / PDF417 Key-Value (NW=..., PIN=..., NID=..., NM=..., DB=...)
        val kvNid = Regex("(?:NW|PIN|NID|ID|RN)[=:\\s]+([0-9]{10}|[0-9]{13}|[0-9]{17})", RegexOption.IGNORE_CASE)
            .find(rawCode)?.groupValues?.getOrNull(1)
        val kvName = Regex("(?:NM|NAME)[=:\\s]+([A-Z .'-]{3,60})", RegexOption.IGNORE_CASE)
            .find(rawCode)?.groupValues?.getOrNull(1)?.trim()
        val kvDob = Regex("(?:DB|DOB)[=:\\s]+([0-9]{2}[/-][0-9]{2}[/-][0-9]{4}|[0-9]{4}-[0-9]{2}-[0-9]{2}|[0-9]{2}\\s+[A-Z]{3}\\s+[0-9]{4})", RegexOption.IGNORE_CASE)
            .find(rawCode)?.groupValues?.getOrNull(1)?.trim()

        // ৩. শুধুমাত্র বারকোড/QR স্ট্রিংয়ের ভেতর থেকে ১০, ১৩ বা ১৭ ডিজিট NID খোঁজা
        val barcodeNidDigits = Regex("\\b([0-9]{17}|[0-9]{13}|[0-9]{10})\\b")
            .find(rawCode)?.groupValues?.getOrNull(1)

        // ৪. ব্যাক পার্টের FZ নম্বর ও Issue Date
        val fzMatch = Regex("\\b(FZ[A-Z0-9\\-]{5,18})\\b").find(upperBackText)?.groupValues?.getOrNull(1)
            ?: Regex("I<BGD([0-9A-Z<]{9,20})").find(upperBackText.replace(" ", ""))?.groupValues?.getOrNull(1)?.replace("<", "")

        val issueMatch = Regex(
            "(?:ISSUE\\s*DATE|DATE\\s*OF\\s*ISSUE)[^0-9A-Z]{0,8}([0-9]{1,2}\\s+[A-Z]{3,9}\\s+[0-9]{4}|[0-9]{2}[/-][0-9]{2}[/-][0-9]{4})"
        ).find(upperBackText)?.groupValues?.getOrNull(1).orEmpty()

        map["nidNumber"] = pinTag ?: kvNid ?: barcodeNidDigits.orEmpty()
        map["fzNumber"] = fzMatch.orEmpty()
        map["nameEn"] = nameTag ?: kvName.orEmpty()
        map["dob"] = dobTag ?: kvDob.orEmpty()
        map["issueDate"] = issueMatch
        map["qrEnglish"] = buildString {
            append(rawCode)
            if (!fzMatch.isNullOrBlank() && !rawCode.contains(fzMatch)) {
                append(" | FZ:").append(fzMatch)
            }
        }
        return map
    }
}
