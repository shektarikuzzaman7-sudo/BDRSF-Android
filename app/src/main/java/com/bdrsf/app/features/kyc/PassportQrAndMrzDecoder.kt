package com.bdrsf.app.features.kyc

import android.graphics.Bitmap
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * BDRSF ডেডিকেটেড পাসপোর্ট QR কোড, ICAO MRZ এবং ইংরেজি ফিল্ড ডিকোডার:
 * - ই-পাসপোর্ট / MRP পাসপোর্টের QR Code ও 2D বারকোড পড়ে।
 * - ২-লাইনের ICAO 9303 TD3 MRZ (P<BGD...) পার্স করে।
 * - পাসপোর্টের সব ইংরেজি তথ্য বের করে দেয়।
 */
class PassportQrAndMrzDecoder {

    data class PassportDecodedResult(
        val isPassport: Boolean,
        val qrCodeData: String,
        val fields: Map<String, String>,
        val rawText: String
    )

    private val barcodeOptions = BarcodeScannerOptions.Builder()
        .setBarcodeFormats(
            Barcode.FORMAT_QR_CODE,
            Barcode.FORMAT_PDF417,
            Barcode.FORMAT_DATA_MATRIX,
            Barcode.FORMAT_AZTEC
        )
        .enableAllPotentialBarcodes()
        .build()

    fun decodePassport(bitmap: Bitmap): PassportDecodedResult {
        val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val barcodeScanner = BarcodeScanning.getClient(barcodeOptions)

        var visionText: Text? = null
        var qrValue = ""

        try {
            visionText = Tasks.await(textRecognizer.process(InputImage.fromBitmap(bitmap, 0)))
            val barcodes = Tasks.await(barcodeScanner.process(InputImage.fromBitmap(bitmap, 0)))
            qrValue = barcodes.firstOrNull { !it.rawValue.isNullOrBlank() }?.rawValue?.trim().orEmpty()
        } catch (_: Exception) {
        } finally {
            try {
                textRecognizer.close()
            } catch (_: Exception) {
            }
            try {
                barcodeScanner.close()
            } catch (_: Exception) {
            }
        }

        val vt = visionText ?: return PassportDecodedResult(false, qrValue, emptyMap(), "")
        val rawText = vt.text.orEmpty()
        val fields = extractAllEnglishPassportFields(vt, qrValue).toMutableMap()
        fields["qrCodeData"] = qrValue

        val upper = rawText.uppercase(Locale.ROOT)
        val hasPassportWord = upper.contains("PASSPORT") ||
            upper.contains("PEOPLE'S REPUBLIC OF") ||
            upper.contains("P<")

        val indicatorCount = listOf(
            fields["passportNo"],
            fields["nationality"],
            fields["dob"],
            fields["surname"],
            fields["givenNames"],
            fields["countryCode"]
        ).count { !it.isNullOrBlank() }

        return PassportDecodedResult(
            isPassport = hasPassportWord && indicatorCount >= 2,
            qrCodeData = qrValue,
            fields = fields,
            rawText = rawText
        )
    }

    private fun extractAllEnglishPassportFields(visionText: Text, qrValue: String): Map<String, String> {
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

        if (qrValue.isNotBlank() && result["passportNo"].isNullOrBlank()) {
            val qrPass = Regex("\\b([A-Z]{1,2}[0-9]{7,9})\\b").find(qrValue.uppercase(Locale.ROOT))?.value
            if (!qrPass.isNullOrBlank()) result["passportNo"] = qrPass
        }

        if (result["passportNo"].isNullOrBlank()) {
            result["passportNo"] = Regex("\\b[A-Z]{1,2}[0-9]{7,9}\\b")
                .find(rawText.uppercase(Locale.ROOT))?.value.orEmpty()
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

        return result
    }

    private fun getOcrLines(visionText: Text): List<String> {
        val result = mutableListOf<String>()
        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                val cleaned = line.text.replace('\u00A0', ' ').replace(Regex("\\s+"), " ").trim()
                if (cleaned.isNotEmpty()) result.add(cleaned)
            }
        }
        return result
    }

    private fun cleanValue(value: String): String {
        return value.replace('\u00A0', ' ').replace(Regex("\\s+"), " ").trim()
            .replace(Regex("^[/\\\\|]+"), "")
            .trim(':', '-', ' ', '.', ';', '/')
    }

    private fun extractLabelValue(lines: List<String>, labels: List<String>): String {
        val sorted = labels.sortedByDescending { it.length }
        for (i in lines.indices) {
            val line = lines[i]
            val upper = line.uppercase(Locale.ROOT)
            for (label in sorted) {
                val idx = upper.indexOf(label.uppercase(Locale.ROOT))
                if (idx < 0) continue
                val after = cleanValue(line.substring(idx + label.length))
                if (after.isNotBlank() && !isKnownLabelLine(after)) return after
                val next = lines.getOrNull(i + 1).orEmpty()
                if (next.isNotBlank() && !isKnownLabelLine(next)) return cleanValue(next)
            }
        }
        return ""
    }

    private fun extractMultilineValue(lines: List<String>, labels: List<String>, maxNext: Int): String {
        val sorted = labels.sortedByDescending { it.length }
        for (i in lines.indices) {
            val line = lines[i]
            val upper = line.uppercase(Locale.ROOT)
            for (label in sorted) {
                val idx = upper.indexOf(label.uppercase(Locale.ROOT))
                if (idx < 0) continue
                val parts = mutableListOf<String>()
                val first = cleanValue(line.substring(idx + label.length))
                if (first.isNotBlank() && !isKnownLabelLine(first)) parts.add(first)
                for (offset in 1..maxNext) {
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
            "DATE OF EXPIRY", "PERSONAL NO", "PLACE OF BIRTH", "ISSUING AUTHORITY",
            "FATHER'S NAME", "MOTHER'S NAME", "SPOUSE'S NAME", "PERMANENT ADDRESS",
            "EMERGENCY CONTACT", "RELATIONSHIP", "TELEPHONE", "ADDRESS:"
        )
        return labels.any { upper.contains(it) }
    }

    private fun findDateNearLabel(lines: List<String>, labels: List<String>): String {
        val dateRegex = Regex(
            "\\b(\\d{1,2}\\s+(?:JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|OCT|NOV|DEC)[A-Z]*\\s+\\d{4}" +
                "|\\d{1,2}/\\d{1,2}/\\d{4}|\\d{4}-\\d{2}-\\d{2}|\\d{1,2}-\\d{1,2}-\\d{4})\\b",
            RegexOption.IGNORE_CASE
        )
        for (i in lines.indices) {
            val upper = lines[i].uppercase(Locale.ROOT)
            if (labels.any { upper.contains(it.uppercase(Locale.ROOT)) }) {
                val windowText = "${lines[i]} ${lines.getOrNull(i + 1).orEmpty()}"
                val match = dateRegex.find(windowText) ?: continue
                return normalizeDate(match.groupValues[1])
            }
        }
        return ""
    }

    private fun normalizeDate(value: String): String {
        val formats = listOf(
            "dd MMM yyyy", "d MMM yyyy", "dd MMMM yyyy", "d MMMM yyyy",
            "dd/MM/yyyy", "d/M/yyyy", "dd-MM-yyyy", "d-M-yyyy", "yyyy-MM-dd"
        )
        for (fmt in formats) {
            try {
                val parser = SimpleDateFormat(fmt, Locale.US).apply { isLenient = false }
                val date: Date = parser.parse(value.trim()) ?: continue
                return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(date)
            } catch (_: Exception) {
            }
        }
        return value.trim()
    }

    private fun parseMrzFallback(lines: List<String>, result: MutableMap<String, String>) {
        val mrz = lines.map { it.replace(" ", "").uppercase(Locale.ROOT) }
            .filter { it.length >= 30 && it.contains("<") }
        if (mrz.isEmpty()) return

        val line1 = mrz.firstOrNull { it.startsWith("P<") }
        val line2 = mrz.firstOrNull { it != line1 && it.count { ch -> ch.isDigit() } >= 10 }

        if (line1 != null && line1.length >= 6) {
            val country = line1.substring(2, 5).replace("<", "")
            if (result["countryCode"].isNullOrBlank() && country.length == 3) result["countryCode"] = country
            val sections = line1.substring(5).split("<<")
            val surname = sections.getOrNull(0)?.replace("<", " ")?.trim().orEmpty()
            val given = sections.getOrNull(1)?.replace("<", " ")?.trim().orEmpty()
            if (result["surname"].isNullOrBlank() && surname.isNotBlank()) result["surname"] = surname
            if (result["givenNames"].isNullOrBlank() && given.isNotBlank()) result["givenNames"] = given
        }

        if (line2 != null && line2.length >= 28) {
            val pass = line2.substring(0, 9).replace("<", "").trim()
            if (result["passportNo"].isNullOrBlank() && pass.matches(Regex("[A-Z0-9]{7,9}"))) {
                result["passportNo"] = pass
            }
            val dobRaw = line2.substring(13, 19)
            if (result["dob"].isNullOrBlank() && dobRaw.all { it.isDigit() }) {
                val yy = dobRaw.substring(0, 2).toIntOrNull() ?: 0
                val century = if (yy > 30) "19" else "20"
                result["dob"] = "$century${dobRaw.substring(0, 2)}-${dobRaw.substring(2, 4)}-${dobRaw.substring(4, 6)}"
            }
            val expRaw = line2.substring(21, 27)
            if (result["expiryDate"].isNullOrBlank() && expRaw.all { it.isDigit() }) {
                result["expiryDate"] = "20${expRaw.substring(0, 2)}-${expRaw.substring(2, 4)}-${expRaw.substring(4, 6)}"
            }
        }
    }
}
