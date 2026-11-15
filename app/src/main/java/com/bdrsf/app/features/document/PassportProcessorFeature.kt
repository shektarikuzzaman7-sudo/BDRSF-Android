// File: app/src/main/java/com/bdrsf/app/features/document/PassportProcessorFeature.kt
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
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

class PassportProcessorFeature(
    private val activity: Activity,
    private val bridge: AndroidBridge,
    private val webView: WebView
) {

    companion object {
        const val RC_PICK_PASSPORT_GALLERY = 6012
        private const val MAX_IMAGE_DIMENSION = 1800
        private const val OCR_IMAGE_QUALITY = 88
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

    fun updatePolicy(policyPayload: JSONObject?) {
        activePolicy = copyJson(policyPayload ?: JSONObject())
    }

    private fun copyJson(source: JSONObject): JSONObject {
        return JSONObject(source.toString())
    }

    fun openGalleryPicker(
        policyPayload: JSONObject,
        callback: String?
    ) {
        if (destroyed) {
            notifyError("Passport Processor বন্ধ রয়েছে", callback)
            return
        }
        updatePolicy(policyPayload)
        pendingCallback = callback
        val intent = Intent(
            Intent.ACTION_PICK,
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        ).apply {
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

    fun handleActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ): Boolean {
        if (requestCode != RC_PICK_PASSPORT_GALLERY) {
            return false
        }
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
                FileOutputStream(tempFile).use { output ->
                    source.copyTo(output)
                }
            }
            processPassportFile(tempFile, copyJson(activePolicy), callback)
        } catch (e: Exception) {
            notifyError("গ্যালারি ছবি লোড করা যায়নি: ${e.localizedMessage ?: ""}", callback)
        }
        return true
    }

    fun processPassportFile(
        file: File,
        policyPayload: JSONObject? = null,
        callback: String? = null
    ) {
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
                        processedBitmap?.let {
                            if (!it.isRecycled) it.recycle()
                        }
                    }
                }
                try {
                    fixExifOrientation(file)
                    val imageResult = optimizeAndCompress(file)
                    processedBitmap = imageResult.first
                    val fullPageBase64 = imageResult.second
                    val bitmap = processedBitmap
                    if (bitmap == null || bitmap.isRecycled || fullPageBase64.isEmpty()) {
                        recycleBitmap()
                        notifyError("পাসপোর্টের ছবি প্রসেস করা যায়নি", callback)
                        return@execute
                    }
                    if (destroyed) {
                        recycleBitmap()
                        return@execute
                    }
                    val inputImage = InputImage.fromBitmap(bitmap, 0)
                    textRecognizer.process(inputImage)
                        .addOnSuccessListener { visionText ->
                            try {
                                if (destroyed) return@addOnSuccessListener
                                val rawText = visionText.text
                                val validationError = validateAgainstPhpPolicy(rawText, policy)
                                if (validationError != null) {
                                    notifyError(validationError, callback)
                                    return@addOnSuccessListener
                                }
                                val fields = extractAllEnglishPassportFields(visionText)
                                if (!isPassportDocument(rawText, fields)) {
                                    notifyError("পাসপোর্টের প্রয়োজনীয় তথ্য পাওয়া যায়নি। পাসপোর্টের তথ্যের পাতার পরিষ্কার ছবি দিন।", callback)
                                    return@addOnSuccessListener
                                }
                                dispatchPassportSuccess(
                                    fullPageBase64 = fullPageBase64,
                                    fields = fields,
                                    rawText = rawText,
                                    callback = callback
                                )
                            } catch (e: Exception) {
                                notifyError("পাসপোর্ট তথ্য প্রসেসিং ত্রুটি: ${e.localizedMessage ?: ""}", callback)
                            } finally {
                                recycleBitmap()
                            }
                        }
                        .addOnFailureListener { error ->
                            recycleBitmap()
                            notifyError("পাসপোর্টের লেখা পড়া যায়নি। পরিষ্কার ছবি দিন। ${error.localizedMessage ?: ""}", callback)
                        }
                } catch (e: Exception) {
                    recycleBitmap()
                    notifyError("প্রসেসিং ত্রুটি: ${e.localizedMessage ?: ""}", callback)
                }
            }
        } catch (e: Exception) {
            notifyError("প্রসেসিং শুরু করা যায়নি: ${e.localizedMessage ?: ""}", callback)
        }
    }

    private fun isPassportDocument(
        rawText: String,
        fields: Map<String, String>
    ): Boolean {
        val upper = rawText.uppercase(Locale.ROOT)
        val hasPassportWord = upper.contains("PASSPORT") ||
            upper.contains("PEOPLE'S REPUBLIC OF") ||
            upper.contains("P<") ||
            upper.contains("SURNAME")
        val indicators = listOf(
            fields["passportNo"],
            fields["nameEn"],
            fields["nationality"],
            fields["dob"],
            fields["surname"],
            fields["givenNames"],
            fields["fatherName"],
            fields["countryCode"]
        ).count { !it.isNullOrBlank() }
        return hasPassportWord && indicators >= 1
    }

    private fun validateAgainstPhpPolicy(
        rawText: String,
        policy: JSONObject
    ): String? {
        val upper = rawText.uppercase(Locale.ROOT)
        val hasPassportIndicator = upper.contains("PASSPORT") ||
            upper.contains("P<") ||
            upper.contains("SURNAME") ||
            upper.contains("GIVEN NAME")

        if (!hasPassportIndicator) {
            val rejectList = jsonArrayToList(policy.optJSONArray("rejectIfContains")).ifEmpty {
                listOf("NATIONAL ID", "NID NO", "জাতীয় পরিচয়")
            }
            for (word in rejectList) {
                if (word.isNotBlank() && upper.contains(word.uppercase(Locale.ROOT))) {
                    return policy.optString("rejectMessage", "ভুল ডকুমেন্ট! পাসপোর্টের ছবি দিন।")
                }
            }
        }

        val required = jsonArrayToList(policy.optJSONArray("mustContainAny"))
        if (required.isNotEmpty()) {
            val matched = required.any { keyword ->
                upper.contains(keyword.uppercase(Locale.ROOT))
            } || Regex("\\b([A-Z]{1,2})\\s*([0-9]{7,9})\\b").containsMatchIn(upper)
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
            if (value.isNotEmpty()) {
                result.add(value)
            }
        }
        return result
    }

    private fun normalizeLine(value: String): String {
        return value.replace('\u00A0', ' ').replace(Regex("\\s+"), " ").trim()
    }

    private fun cleanValue(value: String): String {
        return normalizeLine(value).trim(':', '-', ' ', '.', ';')
    }

    private fun getOcrLines(visionText: Text): List<String> {
        val result = mutableListOf<String>()
        for (block in visionText.textBlocks) {
            for (line in block.lines) {
                val value = normalizeLine(line.text)
                if (value.isNotEmpty()) {
                    result.add(value)
                }
            }
        }
        if (result.isEmpty()) {
            result.addAll(
                visionText.text.lines().map { normalizeLine(it) }.filter { it.isNotEmpty() }
            )
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

    private fun parseMrzLines(lines: List<String>): Map<String, String> {
        val mrzData = mutableMapOf<String, String>()
        val compactLines = lines.map { it.uppercase(Locale.ROOT).replace(Regex("\\s+"), "") }

        val mrzLine1 = compactLines.firstOrNull { line ->
            line.length >= 18 && (line.contains("P<") || line.contains("<<"))
        }
        if (mrzLine1 != null) {
            val pIndex = mrzLine1.indexOf("P<")
            val startIdx = if (pIndex >= 0 && mrzLine1.length >= pIndex + 5) pIndex + 5 else 0
            val body = mrzLine1.substring(startIdx)
            val nameParts = body.split("<<")
            val surname = nameParts.getOrNull(0)?.replace("<", " ")?.replace(Regex("[^A-Z\\s]"), "")?.trim().orEmpty()
            val givenNames = nameParts.getOrNull(1)?.replace("<", " ")?.replace(Regex("[^A-Z\\s]"), "")?.trim().orEmpty()
            if (surname.isNotBlank()) mrzData["surname"] = surname
            if (givenNames.isNotBlank()) mrzData["givenNames"] = givenNames
            val fullName = listOf(givenNames, surname).filter { it.isNotBlank() }.joinToString(" ").replace(Regex("\\s+"), " ")
            if (fullName.isNotBlank()) mrzData["nameEn"] = fullName
            if (pIndex >= 0 && mrzLine1.length >= pIndex + 5) {
                val cc = mrzLine1.substring(pIndex + 2, pIndex + 5).replace("<", "")
                if (cc.length == 3) mrzData["countryCode"] = cc
            }
        }

        val mrzLine2 = compactLines.firstOrNull { line ->
            line.length >= 20 &&
                !line.contains("P<") &&
                (line.contains("BGD") || line.contains(Regex("[0-9OIZSB]{6}[0-9OIZSB][MF<][0-9OIZSB]{6}")))
        }
        if (mrzLine2 != null) {
            val rawPass = mrzLine2.substring(0, 9).replace("<", "").trim()
            val fixedPass = when {
                rawPass.length >= 3 && rawPass[1].isLetter() && rawPass[2].isDigit() ->
                    rawPass.substring(0, 2) + normalizeMrzDigits(rawPass.substring(2))
                rawPass.length >= 2 ->
                    rawPass.substring(0, 1) + normalizeMrzDigits(rawPass.substring(1))
                else -> rawPass
            }
            if (fixedPass.length >= 7) {
                mrzData["passportNo"] = fixedPass
            }

            if (mrzLine2.length >= 19) {
                val dobDigits = normalizeMrzDigits(mrzLine2.substring(13, 19))
                if (dobDigits.matches(Regex("[0-9]{6}"))) {
                    val yy = dobDigits.substring(0, 2).toIntOrNull() ?: 0
                    val century = if (yy > 30) "19" else "20"
                    mrzData["dob"] = "$century${dobDigits.substring(0, 2)}-${dobDigits.substring(2, 4)}-${dobDigits.substring(4, 6)}"
                }
            }

            if (mrzLine2.length >= 21) {
                val sexChar = mrzLine2[20]
                if (sexChar == 'M' || sexChar == 'F') {
                    mrzData["gender"] = sexChar.toString()
                }
            }

            if (mrzLine2.length >= 27) {
                val expDigits = normalizeMrzDigits(mrzLine2.substring(21, 27))
                if (expDigits.matches(Regex("[0-9]{6}"))) {
                    mrzData["expiryDate"] = "20${expDigits.substring(0, 2)}-${expDigits.substring(2, 4)}-${expDigits.substring(4, 6)}"
                }
            }

            if (mrzLine2.length >= 42) {
                val personalDigits = normalizeMrzDigits(mrzLine2.substring(28, 42)).replace("<", "").trim()
                if (personalDigits.matches(Regex("[0-9]{10,17}"))) {
                    mrzData["personalNo"] = personalDigits
                }
            }
        }

        return mrzData
    }

    private fun extractLabelValue(
        lines: List<String>,
        labels: List<String>,
        nextLines: Int = 2,
        strictPrefix: Boolean = false
    ): String {
        val sortedLabels = labels.sortedByDescending { it.length }
        for (i in lines.indices) {
            val line = lines[i]
            val upper = line.uppercase(Locale.ROOT)
            for (label in sortedLabels) {
                val labelUpper = label.uppercase(Locale.ROOT)
                val index = if (strictPrefix) {
                    if (upper.startsWith(labelUpper) || upper.contains("/$labelUpper")) upper.indexOf(labelUpper) else -1
                } else {
                    upper.indexOf(labelUpper)
                }
                if (index < 0) continue
                val after = cleanValue(line.substring(index + label.length))
                if (after.isNotBlank() && !isKnownLabelLine(after)) {
                    return after
                }
                for (offset in 1..nextLines) {
                    val next = lines.getOrNull(i + offset).orEmpty()
                    if (next.isNotBlank() && !isKnownLabelLine(next)) {
                        return cleanValue(next)
                    }
                }
            }
        }
        return ""
    }

    private fun extractMultilineValue(
        lines: List<String>,
        labels: List<String>,
        maxNextLines: Int = 2
    ): String {
        val sortedLabels = labels.sortedByDescending { it.length }
        for (i in lines.indices) {
            val line = lines[i]
            val upper = line.uppercase(Locale.ROOT)
            for (label in sortedLabels) {
                val index = upper.indexOf(label.uppercase(Locale.ROOT))
                if (index < 0) continue
                val parts = mutableListOf<String>()
                val firstValue = cleanValue(line.substring(index + label.length))
                if (firstValue.isNotBlank() && !isKnownLabelLine(firstValue)) {
                    parts.add(firstValue)
                }
                for (offset in 1..maxNextLines) {
                    val next = lines.getOrNull(i + offset).orEmpty()
                    if (next.isBlank() || isKnownLabelLine(next)) {
                        break
                    }
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
            "ISSUING AUTHORITY", "PREVIOUS PASSPORT", "HOLDER'S SIGNATURE",
            "FATHER'S NAME", "FATHER NAME", "MOTHER'S NAME", "MOTHER NAME",
            "SPOUSE'S NAME", "SPOUSE NAME", "PERMANENT ADDRESS",
            "EMERGENCY CONTACT", "RELATIONSHIP", "TELEPHONE NO", "TELEPHONE",
            "MOBILE", "PHONE", "NAME:", "ADDRESS:", "TYPE", "SEX"
        )
        return labels.any { upper.contains(it) }
    }

    private fun findDateNearLabel(
        lines: List<String>,
        labels: List<String>
    ): String {
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
                val searchText = buildString {
                    append(lines[i])
                    append(" ")
                    append(lines.getOrNull(i + 1).orEmpty())
                    append(" ")
                    append(lines.getOrNull(i + 2).orEmpty())
                }
                val match = dateRegex.find(searchText)
                if (match != null) {
                    return normalizeDate(match.groupValues[1])
                }
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
                val parser = SimpleDateFormat(format, Locale.US).apply {
                    isLenient = false
                }
                val date: Date = parser.parse(value.trim()) ?: continue
                return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(date)
            } catch (_: Exception) {
            }
        }
        return value.trim()
    }

    fun extractAllEnglishPassportFields(
        visionText: Text
    ): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val lines = getOcrLines(visionText)
        val rawText = visionText.text
        val upperText = rawText.uppercase(Locale.ROOT)

        // ১. MRZ লাইন থেকে নির্ভুল প্রাথমিক ডেটা সংগ্রহ
        val mrz = parseMrzLines(lines)
        result.putAll(mrz)

        // ২. ভিজ্যুয়াল জোন থেকে সব ফিল্ড সংগ্রহ (MRZ ফাঁকা থাকলে বা অতিরিক্ত তথ্যের জন্য)
        val passportType = extractLabelValue(lines, listOf("TYPE OF PASSPORT", "TYPE"))
        if (passportType.isNotBlank()) result["passportType"] = passportType

        val countryCode = extractLabelValue(lines, listOf("COUNTRY CODE"))
        if (countryCode.isNotBlank()) result["countryCode"] = countryCode

        val visualPassNo = extractLabelValue(lines, listOf("PASSPORT NUMBER", "PASSPORT NO.", "PASSPORT NO"))
            .replace(Regex("[^A-Z0-9]"), "")
        if (result["passportNo"].isNullOrBlank() && visualPassNo.matches(Regex("^[A-Z]{1,2}[0-9]{6,9}$"))) {
            result["passportNo"] = visualPassNo
        }
        if (result["passportNo"].isNullOrBlank()) {
            val regexMatch = Regex("\\b([A-Z]{1,2})\\s*([0-9]{7,9})\\b").find(upperText)
            if (regexMatch != null) {
                result["passportNo"] = (regexMatch.groupValues[1] + regexMatch.groupValues[2]).take(9)
            }
        }

        val visualSurname = extractLabelValue(lines, listOf("SURNAME"))
        if (visualSurname.isNotBlank()) result["surname"] = visualSurname

        val visualGiven = extractLabelValue(lines, listOf("GIVEN NAME", "GIVEN NAMES"))
        if (visualGiven.isNotBlank()) result["givenNames"] = visualGiven

        val nationality = extractLabelValue(lines, listOf("NATIONALITY"))
        if (nationality.isNotBlank()) result["nationality"] = nationality

        val visualDob = findDateNearLabel(lines, listOf("DATE OF BIRTH", "BIRTH DATE", "DOB"))
        if (visualDob.isNotBlank()) result["dob"] = visualDob

        val visualGender = extractLabelValue(lines, listOf("SEX", "GENDER"))
        if (result["gender"].isNullOrBlank() && visualGender.isNotBlank()) {
            result["gender"] = visualGender
        }

        result["birthPlace"] = extractLabelValue(lines, listOf("PLACE OF BIRTH", "BIRTH PLACE"))
        result["issueDate"] = findDateNearLabel(lines, listOf("DATE OF ISSUE", "ISSUE DATE"))

        val visualExpiry = findDateNearLabel(lines, listOf("DATE OF EXPIRY", "EXPIRY DATE"))
        if (visualExpiry.isNotBlank()) result["expiryDate"] = visualExpiry

        val visualPersonalNo = extractLabelValue(lines, listOf("PERSONAL NO.", "PERSONAL NO", "PERSONAL NUMBER"))
            .replace(Regex("[^0-9]"), "")
        if (visualPersonalNo.isNotBlank()) result["personalNo"] = visualPersonalNo

        result["issuingAuthority"] = extractLabelValue(lines, listOf("ISSUING AUTHORITY", "AUTHORITY"))
        result["placeOfIssue"] = extractLabelValue(lines, listOf("PLACE OF ISSUE"))

        // নাম (strictPrefix = true ব্যবহার করা হয়েছে যাতে FATHER'S NAME বা GIVEN NAME ম্যাচ না করে)
        val topPageName = extractLabelValue(lines, listOf("NAME:"), nextLines = 1, strictPrefix = true)
        if (topPageName.isNotBlank()) {
            result["nameEn"] = topPageName
        } else if (result["nameEn"].isNullOrBlank()) {
            val given = result["givenNames"].orEmpty()
            val surname = result["surname"].orEmpty()
            result["nameEn"] = listOf(given, surname).filter { it.isNotBlank() }.joinToString(" ")
        }

        result["fatherName"] = extractLabelValue(lines, listOf("FATHER'S NAME", "FATHERS NAME", "FATHER NAME"))
        result["motherName"] = extractLabelValue(lines, listOf("MOTHER'S NAME", "MOTHERS NAME", "MOTHER NAME"))
        result["spouseName"] = extractLabelValue(lines, listOf("SPOUSE'S NAME", "SPOUSES NAME", "SPOUSE NAME"))
        result["permanentAddress"] = extractMultilineValue(lines, listOf("PERMANENT ADDRESS"), 2)
        result["emergencyContactName"] = extractLabelValue(lines, listOf("EMERGENCY CONTACT NAME"))
        result["emergencyContactRelation"] = extractLabelValue(lines, listOf("RELATIONSHIP", "RELATION"))
        result["emergencyContactAddress"] = extractMultilineValue(lines, listOf("EMERGENCY CONTACT ADDRESS"), 2)

        val allText = lines.joinToString(" ")
        val phoneRegex = Regex("(\\+?8801[3-9][0-9]{8}|01[3-9][0-9]{8})")
        result["emergencyPhone"] = phoneRegex.find(allText)?.groupValues?.get(1) ?: ""

        if (result["countryCode"].isNullOrBlank() && result["nationality"].orEmpty().contains("BANGLADESH", true)) {
            result["countryCode"] = "BGD"
        }

        result["rawText"] = rawText
        return result
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
        try {
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
                    val compressed = bitmap.compress(Bitmap.CompressFormat.JPEG, OCR_IMAGE_QUALITY, stream)
                    if (!compressed) {
                        if (!bitmap.isRecycled) bitmap.recycle()
                        return Pair(null, "")
                    }
                    "data:image/jpeg;base64," + Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
                }
            } catch (e: Exception) {
                if (!bitmap.isRecycled) bitmap.recycle()
                return Pair(null, "")
            }
            return Pair(bitmap, base64)
        } catch (_: Exception) {
            return Pair(null, "")
        }
    }

    private fun notifyScanningStarted() {
        if (destroyed) return
        activity.runOnUiThread {
            if (!destroyed) {
                webView.evaluateJavascript(
                    "if(window.onImageScanningStarted) {window.onImageScanningStarted('passport');}",
                    null
                )
            }
        }
    }

    private fun dispatchPassportSuccess(
        fullPageBase64: String,
        fields: Map<String, String>,
        rawText: String,
        callback: String?
    ) {
        val nameEn = fields["nameEn"] ?: ""
        val passportNo = fields["passportNo"] ?: ""
        val dob = fields["dob"] ?: ""
        val extraJson = JSONObject().apply {
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
            put("passportBase64", fullPageBase64)
            put("rawText", rawText)
        }
        activity.runOnUiThread {
            if (destroyed) return@runOnUiThread
            Toast.makeText(activity, "✔ পাসপোর্ট OCR সম্পন্ন", Toast.LENGTH_SHORT).show()
            webView.evaluateJavascript(
                "if(window.onDocumentValidated) {window.onDocumentValidated('passport', true, ${JSONObject.quote(fullPageBase64)});}",
                null
            )
            webView.evaluateJavascript(
                "if(window.onFullPassportParsed) {window.onFullPassportParsed(${JSONObject.quote(nameEn)}, ${JSONObject.quote(passportNo)}, ${JSONObject.quote(fullPageBase64)}, ${JSONObject.quote(dob)}, ${JSONObject.quote(extraJson.toString())});}",
                null
            )
            val resultPayload = JSONObject(extraJson.toString())
            bridge.sendResponseToWeb(
                response = BridgeResponse(
                    action = Actions.START_KYC,
                    status = Status.SUCCESS,
                    data = resultPayload
                ),
                callbackFunction = callback
            )
        }
    }

    private fun notifyError(
        message: String,
        callback: String?
    ) {
        if (destroyed) return
        activity.runOnUiThread {
            if (destroyed) return@runOnUiThread
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
            webView.evaluateJavascript(
                "if(window.onDocumentValidated) {window.onDocumentValidated('passport', false, '');}",
                null
            )
            webView.evaluateJavascript(
                "if(window.onDocumentValidationFailed) {window.onDocumentValidationFailed('passport', ${JSONObject.quote(message)});}",
                null
            )
            bridge.sendResponseToWeb(
                response = BridgeResponse(
                    action = Actions.START_KYC,
                    status = Status.ERROR,
                    message = message
                ),
                callbackFunction = callback
            )
        }
    }

    private fun notifyCancelled(callback: String?) {
        if (destroyed) return
        val message = "পাসপোর্ট নির্বাচন বাতিল করা হয়েছে"
        activity.runOnUiThread {
            if (destroyed) return@runOnUiThread
            bridge.sendResponseToWeb(
                response = BridgeResponse(
                    action = Actions.START_KYC,
                    status = Status.CANCELLED,
                    message = message
                ),
                callbackFunction = callback
            )
        }
    }

    fun destroy() {
        if (destroyed) return
        destroyed = true
        pendingCallback = null
        try {
            if (!bgExecutor.isShutdown) {
                bgExecutor.shutdownNow()
            }
        } catch (_: Exception) {
        }
        try {
            if (textRecognizerDelegate.isInitialized()) {
                textRecognizerDelegate.value.close()
            }
        } catch (_: Exception) {
        }
    }
}
