package com.bdrsf.app.features.kyc

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONObject
import java.util.Locale

/**
 * BDRSF ডেডিকেটেড NID ব্যাক পার্ট ডিকোডার:
 * - Smart NID & Old NID-এর PDF417 এবং MicroPDF417 বারকোড ডিকোড করে।
 * - Digital NID / e-NID-এর QR Code ডিকোড করে।
 * - ব্যাক পার্টের FZ নম্বর, MRZ লাইন এবং সকল ইংরেজি তথ্য আলাদা JSON আকারে বের করে।
 * - ক্যামেরা ও গ্যালারি উভয় ছবির ক্ষেত্রে ০°, ৯০°, ১৮০°, ২৭০° রোটেশন ও নিচের বারকোড জোন জুম করে স্ক্যান করে।
 */
class NidBarcodeAndQrDecoder {

    data class NidBackDecodedResult(
        val success: Boolean,
        val codeType: String,           // PDF417 | MICRO_PDF417 | DIGITAL_NID_QR | BACK_FZ_MRZ | NONE
        val rawBarcodeValue: String,
        val nidNumber: String,
        val fzNumber: String,
        val holderNameEn: String,
        val dob: String,
        val qrEnglishData: String,
        val backEnglishText: String,
        val fullBackJson: JSONObject
    )

    private val scannerOptions = BarcodeScannerOptions.Builder()
        .setBarcodeFormats(
            Barcode.FORMAT_PDF417,
            Barcode.FORMAT_QR_CODE,
            Barcode.FORMAT_AZTEC,
            Barcode.FORMAT_DATA_MATRIX,
            Barcode.FORMAT_CODE_128
        )
        .enableAllPotentialBarcodes()
        .build()

    /**
     * রিয়েল-টাইম ক্যামেরা ফ্রেমে PDF417 / MicroPDF417 / QR কোড আছে কি না দ্রুত যাচাই করে
     */
    fun detectBarcodeFastInFrame(inputImage: InputImage, onResult: (hasBarcode: Boolean, rawValue: String) -> Unit) {
        val scanner = BarcodeScanning.getClient(scannerOptions)
        scanner.process(inputImage)
            .addOnSuccessListener { barcodes ->
                val valid = barcodes.firstOrNull { !it.rawValue.isNullOrBlank() }
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
     * গ্যালারি বা ক্যাপচার করা NID ব্যাক পার্ট ইমেজ থেকে সর্বোচ্চ নির্ভুলতায়
     * PDF417, MicroPDF417, Digital NID QR, FZ নম্বর এবং ইংরেজি তথ্য ডিকোড করে।
     */
    fun decodeFullNidBack(bitmap: Bitmap): NidBackDecodedResult {
        val scanner = BarcodeScanning.getClient(scannerOptions)
        val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

        var detectedBarcodes: List<Barcode> = emptyList()
        var backRawEnglishText = ""

        try {
            // ১. ইংরেজি টেক্সট ও FZ / MRZ নম্বর পড়া
            val textResult = Tasks.await(textRecognizer.process(InputImage.fromBitmap(bitmap, 0)))
            backRawEnglishText = textResult.text.orEmpty().trim()

            // ২. পূর্ণ ছবিতে ও নিচের বারকোড অংশে (PDF417/QR জোন) মাল্টি-অ্যাঙ্গেল স্ক্যান
            val candidates = buildScanBitmaps(bitmap)
            try {
                for (candidate in candidates) {
                    val found = Tasks.await(scanner.process(InputImage.fromBitmap(candidate, 0)))
                    val nonEmpty = found.filter { !it.rawValue.isNullOrBlank() }
                    if (nonEmpty.isNotEmpty()) {
                        detectedBarcodes = nonEmpty
                        break
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

        val primaryBarcode = detectedBarcodes.firstOrNull {
            it.format == Barcode.FORMAT_PDF417 || it.format == Barcode.FORMAT_QR_CODE
        } ?: detectedBarcodes.firstOrNull()

        val rawCode = primaryBarcode?.rawValue?.trim().orEmpty()
        val formatLabel = when (primaryBarcode?.format) {
            Barcode.FORMAT_PDF417 -> if (rawCode.length < 70) "MICRO_PDF417" else "PDF417"
            Barcode.FORMAT_QR_CODE -> "DIGITAL_NID_QR"
            Barcode.FORMAT_AZTEC -> "AZTEC_2D"
            Barcode.FORMAT_DATA_MATRIX -> "DATA_MATRIX_2D"
            else -> if (backRawEnglishText.isNotBlank()) "BACK_FZ_MRZ" else "NONE"
        }

        val parsedFields = parsePdf417AndQrPayload(rawCode, backRawEnglishText)
        val nidNo = parsedFields["nidNumber"].orEmpty()
        val fzNo = parsedFields["fzNumber"].orEmpty()
        val holderName = parsedFields["nameEn"].orEmpty()
        val dob = parsedFields["dob"].orEmpty()
        val qrEnglish = parsedFields["qrEnglish"].orEmpty()

        val hasAnyValidSignal = rawCode.isNotBlank() ||
            nidNo.isNotBlank() ||
            fzNo.isNotBlank() ||
            hasBackCardEnglishKeywords(backRawEnglishText)

        val json = JSONObject().apply {
            put("codeType", formatLabel)
            put("backCode", rawCode.ifBlank { fzNo.ifBlank { nidNo } })
            put("nidNumber", nidNo)
            put("fzNumber", fzNo)
            put("nameEn", holderName)
            put("dob", dob)
            put("qrEnglish", qrEnglish)
            put("englishText", backRawEnglishText)
            put("barcodeFormatInt", primaryBarcode?.format ?: -1)
        }

        return NidBackDecodedResult(
            success = hasAnyValidSignal,
            codeType = formatLabel,
            rawBarcodeValue = rawCode,
            nidNumber = nidNo,
            fzNumber = fzNo,
            holderNameEn = holderName,
            dob = dob,
            qrEnglishData = qrEnglish,
            backEnglishText = backRawEnglishText,
            fullBackJson = json
        )
    }

    private fun buildScanBitmaps(original: Bitmap): List<Bitmap> {
        val list = mutableListOf<Bitmap>()
        // পূর্ণ ছবি (0 এবং 180 ডিগ্রি)
        list.add(original)
        list.add(KycImageUtils.rotateBitmap(original, 180f))

        // Smart NID ও Old NID-এর নিচের অর্ধাংশ (যেখানে PDF417 / MicroPDF417 বা QR থাকে) 2x স্কেল করে স্ক্যান
        if (original.height > 200 && original.width > 200) {
            val cropTop = (original.height * 0.42f).toInt()
            val cropHeight = original.height - cropTop
            if (cropHeight > 80) {
                val bottomCrop = Bitmap.createBitmap(original, 0, cropTop, original.width, cropHeight)
                val scaled = Bitmap.createScaledBitmap(
                    bottomCrop,
                    (bottomCrop.width * 1.6f).toInt(),
                    (bottomCrop.height * 1.6f).toInt(),
                    true
                )
                if (bottomCrop !== scaled && !bottomCrop.isRecycled) {
                    bottomCrop.recycle()
                }
                list.add(scaled)
            }
        }

        // উল্লম্ব (Portrait) ছবি হলে 90 এবং 270 ডিগ্রি
        list.add(KycImageUtils.rotateBitmap(original, 90f))
        list.add(KycImageUtils.rotateBitmap(original, 270f))
        return list
    }

    private fun parsePdf417AndQrPayload(rawCode: String, backText: String): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val combined = "$rawCode\n$backText"
        val upperCombined = combined.uppercase(Locale.ROOT)

        // ১. Smart NID / Old NID PDF417 XML বা Tag পার্সিং (<pin>...</pin>, <name>...</name>, <dob>...)
        val pinTag = Regex("<pin>([0-9]{10,17})</pin>", RegexOption.IGNORE_CASE).find(rawCode)?.groupValues?.getOrNull(1)
        val nameTag = Regex("<name>([^<]+)</name>", RegexOption.IGNORE_CASE).find(rawCode)?.groupValues?.getOrNull(1)?.trim()
        val dobTag = Regex("<dob>([^<]+)</dob>", RegexOption.IGNORE_CASE).find(rawCode)?.groupValues?.getOrNull(1)?.trim()

        // ২. Key-Value স্টাইল PDF417 / Digital NID QR (NW=..., RN=..., NID=..., DOB=...)
        val kvNid = Regex("(?:NW|PIN|NID|ID)[=:\\s]+([0-9]{10}|[0-9]{13}|[0-9]{17})", RegexOption.IGNORE_CASE)
            .find(combined)?.groupValues?.getOrNull(1)
        val kvName = Regex("(?:NM|NAME)[=:\\s]+([A-Z .'-]{3,60})", RegexOption.IGNORE_CASE)
            .find(rawCode)?.groupValues?.getOrNull(1)?.trim()
        val kvDob = Regex("(?:DB|DOB)[=:\\s]+([0-9]{2}[/-][0-9]{2}[/-][0-9]{4}|[0-9]{4}-[0-9]{2}-[0-9]{2}|[0-9]{2}\\s+[A-Z]{3}\\s+[0-9]{4})", RegexOption.IGNORE_CASE)
            .find(combined)?.groupValues?.getOrNull(1)?.trim()

        // ৩. যেকোনো ১০, ১৩ বা ১৭ ডিজিটের NID নম্বর ফলব্যাক
        val fallbackNid = Regex("\\b([0-9]{17}|[0-9]{13}|[0-9]{10})\\b")
            .find(combined)?.groupValues?.getOrNull(1)

        // ৪. ব্যাক পার্টের FZ নম্বর (যেমন: FZ1234567 বা FZ-...) এবং MRZ লাইন
        val fzMatch = Regex("\\b(FZ[A-Z0-9\\-]{5,18})\\b").find(upperCombined)?.groupValues?.getOrNull(1)
            ?: Regex("I<BGD([0-9A-Z<]{9,20})").find(upperCombined.replace(" ", ""))?.groupValues?.getOrNull(1)?.replace("<", "")

        map["nidNumber"] = pinTag ?: kvNid ?: fallbackNid.orEmpty()
        map["fzNumber"] = fzMatch.orEmpty()
        map["nameEn"] = nameTag ?: kvName.orEmpty()
        map["dob"] = dobTag ?: kvDob.orEmpty()
        map["qrEnglish"] = buildString {
            if (rawCode.isNotBlank()) append(rawCode)
            if (!fzMatch.isNullOrBlank() && !rawCode.contains(fzMatch)) {
                if (isNotEmpty()) append(" | ")
                append("FZ:").append(fzMatch)
            }
        }
        return map
    }

    private fun hasBackCardEnglishKeywords(text: String): Boolean {
        val upper = text.uppercase(Locale.ROOT)
        val keywords = listOf(
            "BLOOD GROUP", "ISSUE DATE", "PLACE OF BIRTH",
            "GOVERNMENT OF THE PEOPLE", "ELECTION COMMISSION",
            "I<BGD", "FZ", "ADDRESS", "POST OFFICE"
        )
        return keywords.any { upper.contains(it) }
    }
}
