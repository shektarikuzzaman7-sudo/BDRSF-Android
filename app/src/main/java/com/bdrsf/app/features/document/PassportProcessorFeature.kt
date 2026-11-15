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
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
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
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import kotlin.math.max

/**
 * BDRSF Offline Passport Processor.
 *
 * - Offline Google ML Kit Latin OCR
 * - ICAO TD3 MRZ extraction
 * - MRZ check digit validation
 * - English passport field extraction
 * - Face detection and portrait cropping
 * - EXIF orientation correction
 * - PHP WebView bridge integration
 *
 * IMPORTANT:
 * MRZ check digits verify document data consistency only.
 * They do NOT prove government authenticity or that a passport is genuine.
 */
class PassportProcessorFeature(
    private val activity: Activity,
    private val bridge: AndroidBridge,
    private val webView: WebView
) {

    companion object {
        const val RC_PICK_PASSPORT_GALLERY = 6012

        private const val MAX_IMAGE_DIMENSION = 1800
        private const val OCR_IMAGE_QUALITY = 88
        private const val FACE_TIMEOUT_SECONDS = 5L
    }

    private val bgExecutor: ExecutorService =
        Executors.newSingleThreadExecutor()

    @Volatile
    private var activePolicy = JSONObject()

    @Volatile
    private var destroyed = false

    @Volatile
    private var pendingCallback: String? = null

    private val textRecognizerDelegate =
        lazy {
            TextRecognition.getClient(
                TextRecognizerOptions.DEFAULT_OPTIONS
            )
        }

    private val faceDetectorDelegate =
        lazy {
            val options = FaceDetectorOptions.Builder()
                .setPerformanceMode(
                    FaceDetectorOptions.PERFORMANCE_MODE_FAST
                )
                .setLandmarkMode(
                    FaceDetectorOptions.LANDMARK_MODE_NONE
                )
                .setClassificationMode(
                    FaceDetectorOptions.CLASSIFICATION_MODE_NONE
                )
                .build()

            FaceDetection.getClient(options)
        }

    private val textRecognizer: TextRecognizer
        get() = textRecognizerDelegate.value

    private val faceDetector: FaceDetector
        get() = faceDetectorDelegate.value

    // ---------------------------------------------------------
    // POLICY
    // ---------------------------------------------------------

    fun updatePolicy(policyPayload: JSONObject?) {
        activePolicy = copyJson(policyPayload ?: JSONObject())
    }

    private fun copyJson(source: JSONObject): JSONObject {
        return JSONObject(source.toString())
    }

    // ---------------------------------------------------------
    // GALLERY
    // ---------------------------------------------------------

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
            activity.startActivityForResult(
                intent,
                RC_PICK_PASSPORT_GALLERY
            )
        } catch (e: Exception) {
            pendingCallback = null
            notifyError(
                "গ্যালারি ওপেন করা যায়নি: ${e.localizedMessage ?: ""}",
                callback
            )
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
            notifyError(
                "গ্যালারি থেকে ছবি পাওয়া যায়নি",
                callback
            )
            return true
        }

        try {
            val tempFile = File.createTempFile(
                "BDRSF_PASSPORT_",
                ".jpg",
                activity.cacheDir
            )

            val input = activity.contentResolver
                .openInputStream(uri)
                ?: throw IllegalStateException("ছবি পড়া সম্ভব হয়নি")

            input.use { source ->
                FileOutputStream(tempFile).use { output ->
                    source.copyTo(output)
                }
            }

            processPassportFile(
                tempFile,
                copyJson(activePolicy),
                callback
            )

        } catch (e: Exception) {
            notifyError(
                "গ্যালারি ছবি লোড করা যায়নি: ${e.localizedMessage ?: ""}",
                callback
            )
        }

        return true
    }

    // ---------------------------------------------------------
    // MAIN PROCESSOR
    // ---------------------------------------------------------

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
            notifyError(
                "পাসপোর্টের ছবি পাওয়া যায়নি",
                callback
            )
            return
        }

        notifyScanningStarted()

        try {
            bgExecutor.execute {

                var processedBitmap: Bitmap? = null
                var bitmapHandedToOcr = false

                val bitmapRecycled = AtomicBoolean(false)

                fun recycleBitmap() {
                    if (bitmapRecycled.compareAndSet(false, true)) {
                        processedBitmap?.let {
                            if (!it.isRecycled) {
                                it.recycle()
                            }
                        }
                    }
                }

                try {
                    fixExifOrientation(file)

                    val imageResult = optimizeAndCompress(file)

                    processedBitmap = imageResult.first
                    val fullPageBase64 = imageResult.second

                    val bitmap = processedBitmap

                    if (
                        bitmap == null ||
                        bitmap.isRecycled ||
                        fullPageBase64.isEmpty()
                    ) {
                        recycleBitmap()
                        notifyError(
                            "পাসপোর্টের ছবি প্রসেস করা যায়নি",
                            callback
                        )
                        return@execute
                    }

                    val faceBase64 =
                        if (policy.optBoolean("extractFace", true)) {
                            extractPortraitFace(bitmap)
                        } else {
                            ""
                        }

                    if (destroyed) {
                        recycleBitmap()
                        return@execute
                    }

                    val inputImage = InputImage.fromBitmap(
                        bitmap,
                        0
                    )

                    bitmapHandedToOcr = true

                    textRecognizer.process(inputImage)
                        .addOnSuccessListener { visionText ->

                            try {
                                if (destroyed) return@addOnSuccessListener

                                val rawText = visionText.text

                                val validationError =
                                    validateAgainstPhpPolicy(
                                        rawText,
                                        policy
                                    )

                                if (validationError != null) {
                                    notifyError(
                                        validationError,
                                        callback
                                    )
                                    return@addOnSuccessListener
                                }

                                val mrz = extractMrz(visionText)

                                if (mrz == null || !mrz.isValid) {
                                    notifyError(
                                        "পাসপোর্টের MRZ যাচাই করা যায়নি। " +
                                            "পাসপোর্টের নিচের দুইটি MRZ লাইনসহ " +
                                            "পরিষ্কার ও সোজা ছবি দিন।",
                                        callback
                                    )
                                    return@addOnSuccessListener
                                }

                                val fields =
                                    extractAllEnglishPassportFields(
                                        visionText
                                    )

                                dispatchPassportSuccess(
                                    fullPageBase64 = fullPageBase64,
                                    faceBase64 = faceBase64,
                                    fields = fields,
                                    rawText = rawText,
                                    mrz = mrz,
                                    callback = callback
                                )

                            } catch (e: Exception) {
                                notifyError(
                                    "পাসপোর্ট তথ্য প্রসেসিং ত্রুটি: " +
                                        "${e.localizedMessage ?: ""}",
                                    callback
                                )
                            } finally {
                                recycleBitmap()
                            }
                        }
                        .addOnFailureListener { error ->

                            recycleBitmap()

                            notifyError(
                                "পাসপোর্টের লেখা পড়া যায়নি। পরিষ্কার ছবি দিন। " +
                                    "${error.localizedMessage ?: ""}",
                                callback
                            )
                        }

                } catch (e: Exception) {

                    if (!bitmapHandedToOcr) {
                        recycleBitmap()
                    }

                    notifyError(
                        "প্রসেসিং ত্রুটি: ${e.localizedMessage ?: ""}",
                        callback
                    )
                }
            }

        } catch (e: Exception) {
            notifyError(
                "প্রসেসিং শুরু করা যায়নি: ${e.localizedMessage ?: ""}",
                callback
            )
        }
    }

    // ---------------------------------------------------------
    // POLICY VALIDATION
    // ---------------------------------------------------------

    private fun validateAgainstPhpPolicy(
        rawText: String,
        policy: JSONObject
    ): String? {

        val upper = rawText.uppercase(Locale.ROOT)

        val rejectList = jsonArrayToList(
            policy.optJSONArray("rejectIfContains")
        ).ifEmpty {
            listOf(
                "NATIONAL ID",
                "NID NO",
                "জাতীয় পরিচয়"
            )
        }

        for (word in rejectList) {
            if (
                word.isNotBlank() &&
                upper.contains(word.uppercase(Locale.ROOT))
            ) {
                return policy.optString(
                    "rejectMessage",
                    "ভুল ডকুমেন্ট! পাসপোর্টের ছবি দিন।"
                )
            }
        }

        val required = jsonArrayToList(
            policy.optJSONArray("mustContainAny")
        )

        if (required.isNotEmpty()) {
            val matched = required.any { keyword ->
                upper.contains(keyword.uppercase(Locale.ROOT))
            }

            if (!matched) {
                return policy.optString(
                    "invalidMessage",
                    "এটি গ্রহণযোগ্য পাসপোর্টের ছবি নয়।"
                )
            }
        } else {
            val hasPassportIndicator =
                upper.contains("PASSPORT") ||
                    upper.contains("P<") ||
                    upper.contains("SURNAME") ||
                    upper.contains("GIVEN NAME")

            if (!hasPassportIndicator) {
                return policy.optString(
                    "invalidMessage",
                    "পাসপোর্টের পরিচিত তথ্য পাওয়া যায়নি।"
                )
            }
        }

        return null
    }

    private fun jsonArrayToList(
        array: JSONArray?
    ): List<String> {

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

    // ---------------------------------------------------------
    // ICAO MRZ DATA
    // ---------------------------------------------------------

    private data class MrzResult(
        val line1: String,
        val line2: String,
        val passportNo: String,
        val nationality: String,
        val surname: String,
        val givenNames: String,
        val dob: String,
        val gender: String,
        val expiryDate: String,
        val personalNo: String,
        val passportCheck: Boolean,
        val dobCheck: Boolean,
        val expiryCheck: Boolean,
        val personalCheck: Boolean,
        val compositeCheck: Boolean,
        val isValid: Boolean
    )

    private fun normalizeMrzLine(line: String): String {
        return line.uppercase(Locale.ROOT)
            .replace(Regex("[^A-Z0-9<]"), "")
    }

    /**
     * OCR character correction for numeric-only MRZ fields.
     *
     * Do not apply this to the nationality field.
     * Do not apply this to the passport number because it can contain letters.
     */
    private fun normalizeMrzDigits(value: String): String {
        return value.map { char ->
            when (char) {
                'O', 'Q', 'D' -> '0'
                'I', 'L' -> '1'
                'Z' -> '2'
                'S' -> '5'
                'B' -> '8'
                else -> char
            }
        }.joinToString("")
    }

    private fun mrzCharacterValue(char: Char): Int {
        return when {
            char == '<' -> 0
            char in '0'..'9' -> char - '0'
            char in 'A'..'Z' -> char - 'A' + 10
            else -> -1
        }
    }

    /**
     * ICAO 9303 check digit algorithm.
     * Weight sequence: 7, 3, 1.
     */
    private fun calculateMrzCheckDigit(
        value: String
    ): Char? {

        val weights = intArrayOf(7, 3, 1)
        var total = 0

        for (i in value.indices) {
            val charValue = mrzCharacterValue(value[i])

            if (charValue < 0) return null

            total += charValue * weights[i % 3]
        }

        return ('0'.code + total % 10).toChar()
    }

    private fun checkMrzField(
        value: String,
        checkDigit: Char
    ): Boolean {

        val calculated = calculateMrzCheckDigit(value)

        return calculated != null &&
            calculated == checkDigit
    }

    /**
     * Resolve YYMMDD without java.time so this works on minSdk 23.
     *
     * DOB: choose a plausible past date within 120 years.
     * Expiry: choose the candidate closest to the current year.
     */
    private fun resolveMrzDate(
        value: String,
        isExpiry: Boolean
    ): String? {

        if (
            value.length != 6 ||
            !value.all { it in '0'..'9' }
        ) {
            return null
        }

        val yy = value.substring(0, 2).toInt()
        val month = value.substring(2, 4).toInt()
        val day = value.substring(4, 6).toInt()

        if (month !in 1..12 || day !in 1..31) {
            return null
        }

        val currentYear = Calendar.getInstance().get(
            Calendar.YEAR
        )

        fun makeValidDate(year: Int): Calendar? {
            return try {
                Calendar.getInstance(Locale.US).apply {
                    isLenient = false
                    clear()
                    set(year, month - 1, day, 0, 0, 0)
                    timeInMillis
                }
            } catch (_: Exception) {
                null
            }
        }

        val candidates = listOf(
            1900 + yy,
            2000 + yy
        ).distinct().mapNotNull { year ->
            makeValidDate(year)
        }

        if (candidates.isEmpty()) return null

        val selected = if (isExpiry) {
            candidates.minByOrNull { candidate ->
                abs(
                    candidate.get(Calendar.YEAR) - currentYear
                )
            }
        } else {
            candidates
                .filter { candidate ->
                    val year = candidate.get(Calendar.YEAR)

                    year <= currentYear &&
                        year >= currentYear - 120
                }
                .maxByOrNull { candidate ->
                    candidate.get(Calendar.YEAR)
                }
        } ?: return null

        val year = selected.get(Calendar.YEAR)
        val resolvedMonth = selected.get(Calendar.MONTH) + 1
        val resolvedDay = selected.get(Calendar.DAY_OF_MONTH)

        return String.format(
            Locale.US,
            "%04d-%02d-%02d",
            year,
            resolvedMonth,
            resolvedDay
        )
    }

    /**
     * Finds and validates ICAO TD3 passport MRZ.
     * Each TD3 line contains exactly 44 characters.
     */
    private fun extractMrz(
        visionText: Text
    ): MrzResult? {

        val lines = visionText.text.lines()
            .map { normalizeMrzLine(it) }
            .filter { it.isNotEmpty() }

        val firstIndex = lines.indexOfFirst {
            it.startsWith("P<") && it.length == 44
        }

        if (firstIndex < 0) return null

        val first = lines[firstIndex]

        // The second MRZ line is normally immediately below the first.
        // Allow one intervening OCR line, but do not require nationality BGD.
        val second = lines
            .drop(firstIndex + 1)
            .take(3)
            .firstOrNull {
                it.length == 44 && !it.startsWith("P<")
            } ?: return null

        if (first.length != 44 || second.length != 44) {
            return null
        }

        /*
         * TD3 line 2 positions:
         *  0..8   Passport number
         *  9      Passport number check digit
         *  10..12 Nationality
         *  13..18 Date of birth
         *  19     DOB check digit
         *  20     Sex
         *  21..26 Expiry date
         *  27     Expiry check digit
         *  28..41 Optional data
         *  42     Optional data check digit
         *  43     Composite check digit
         */

        // Correct only numeric fields. Nationality remains unchanged.
        val correctedSecond =
            second.substring(0, 13) +
                normalizeMrzDigits(second.substring(13, 20)) +
                second.substring(20, 21) +
                normalizeMrzDigits(second.substring(21, 28)) +
                second.substring(28)

        val passportNo = correctedSecond
            .substring(0, 9)
            .replace("<", "")
            .trim()

        val nationality = correctedSecond.substring(10, 13)

        val dobRaw = correctedSecond.substring(13, 19)
        val genderChar = correctedSecond[20]
        val expiryRaw = correctedSecond.substring(21, 27)

        val optionalData = correctedSecond.substring(28, 42)

        val personalNo = optionalData
            .replace("<", "")
            .trim()

        val passportCheck = checkMrzField(
            correctedSecond.substring(0, 9),
            correctedSecond[9]
        )

        val dobCheck = checkMrzField(
            dobRaw,
            correctedSecond[19]
        )

        val expiryCheck = checkMrzField(
            expiryRaw,
            correctedSecond[27]
        )

        val personalCheck = if (
            optionalData.any { it != '<' }
        ) {
            checkMrzField(
                optionalData,
                correctedSecond[42]
            )
        } else {
            // ICAO filler is acceptable when optional data is empty.
            correctedSecond[42] == '<' ||
                correctedSecond[42].isDigit()
        }

        // TD3 composite check digit:
        // positions 0..9 + 13..19 + 21..42
        val compositeData =
            correctedSecond.substring(0, 10) +
                correctedSecond.substring(13, 20) +
                correctedSecond.substring(21, 43)

        val compositeCheck = checkMrzField(
            compositeData,
            correctedSecond[43]
        )

        val dateOfBirth = resolveMrzDate(
            dobRaw,
            false
        )

        val expiryDate = resolveMrzDate(
            expiryRaw,
            true
        )

        val nameBody = first.substring(5)
        val nameParts = nameBody.split("<<", limit = 2)

        val surname = nameParts
            .getOrNull(0)
            .orEmpty()
            .replace("<", " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        val givenNames = nameParts
            .getOrNull(1)
            .orEmpty()
            .replace("<", " ")
            .replace(Regex("\\s+"), " ")
            .trim()

        val valid =
            passportNo.length >= 7 &&
                passportCheck &&
                dobCheck &&
                expiryCheck &&
                personalCheck &&
                compositeCheck &&
                dateOfBirth != null &&
                expiryDate != null &&
                (
                    genderChar == 'M' ||
                        genderChar == 'F' ||
                        genderChar == '<'
                    ) &&
                nationality.matches(Regex("[A-Z]{3}")) &&
                surname.isNotEmpty()

        return MrzResult(
            line1 = first,
            line2 = correctedSecond,
            passportNo = passportNo,
            nationality = nationality,
            surname = surname,
            givenNames = givenNames,
            dob = dateOfBirth ?: "",
            gender = genderChar.toString(),
            expiryDate = expiryDate ?: "",
            personalNo = personalNo,
            passportCheck = passportCheck,
            dobCheck = dobCheck,
            expiryCheck = expiryCheck,
            personalCheck = personalCheck,
            compositeCheck = compositeCheck,
            isValid = valid
        )
    }

    // ---------------------------------------------------------
    // ENGLISH PASSPORT FIELD EXTRACTION
    // ---------------------------------------------------------

    fun extractAllEnglishPassportFields(
        visionText: Text
    ): Map<String, String> {

        val result = mutableMapOf<String, String>()

        val rawText = visionText.text
        val upperText = rawText.uppercase(Locale.ROOT)

        val lines = upperText.lines()
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        val mrz = extractMrz(visionText)

        if (mrz != null) {
            result["passportNo"] = mrz.passportNo
            result["nationality"] = mrz.nationality
            result["surname"] = mrz.surname
            result["givenNames"] = mrz.givenNames

            result["nameEn"] = listOf(
                mrz.givenNames,
                mrz.surname
            )
                .filter { it.isNotBlank() }
                .joinToString(" ")

            result["dob"] = mrz.dob
            result["gender"] = mrz.gender
            result["expiryDate"] = mrz.expiryDate
            result["personalNo"] = mrz.personalNo

            result["mrzValid"] = mrz.isValid.toString()
            result["mrzLine1"] = mrz.line1
            result["mrzLine2"] = mrz.line2
        }

        for (i in lines.indices) {

            val line = lines[i]
            val next = lines.getOrNull(i + 1).orEmpty()

            fun extractValue(label: String): String {
                return line.substringAfter(label, "")
                    .trim()
                    .trim(':', '-', ' ')
            }

            if (
                result["passportType"].isNullOrEmpty() &&
                line.contains("TYPE OF PASSPORT")
            ) {
                result["passportType"] =
                    extractValue("TYPE OF PASSPORT").ifEmpty { next }
            }

            if (
                result["nationality"].isNullOrEmpty() &&
                line.contains("NATIONALITY")
            ) {
                result["nationality"] =
                    extractValue("NATIONALITY").ifEmpty { next }
            }

            if (
                result["surname"].isNullOrEmpty() &&
                line.contains("SURNAME")
            ) {
                result["surname"] =
                    extractValue("SURNAME").ifEmpty { next }
            }

            if (
                result["givenNames"].isNullOrEmpty() &&
                line.contains("GIVEN NAME")
            ) {
                result["givenNames"] =
                    extractValue("GIVEN NAME").ifEmpty { next }
            }

            if (
                result["nameEn"].isNullOrEmpty() &&
                (line.startsWith("NAME:") || line == "NAME")
            ) {
                result["nameEn"] =
                    extractValue("NAME").ifEmpty { next }
            }

            if (
                result["fatherName"].isNullOrEmpty() &&
                (
                    line.contains("FATHER'S NAME") ||
                        line.contains("FATHER NAME")
                    )
            ) {
                result["fatherName"] =
                    extractValue("FATHER'S NAME")
                        .ifEmpty { extractValue("FATHER NAME") }
                        .ifEmpty { next }
            }

            if (
                result["motherName"].isNullOrEmpty() &&
                (
                    line.contains("MOTHER'S NAME") ||
                        line.contains("MOTHER NAME")
                    )
            ) {
                result["motherName"] =
                    extractValue("MOTHER'S NAME")
                        .ifEmpty { extractValue("MOTHER NAME") }
                        .ifEmpty { next }
            }

            if (
                result["spouseName"].isNullOrEmpty() &&
                line.contains("SPOUSE")
            ) {
                result["spouseName"] =
                    extractValue("SPOUSE").ifEmpty { next }
            }

            if (
                result["birthPlace"].isNullOrEmpty() &&
                line.contains("PLACE OF BIRTH")
            ) {
                result["birthPlace"] =
                    extractValue("PLACE OF BIRTH").ifEmpty { next }
            }

            if (
                result["issueDate"].isNullOrEmpty() &&
                line.contains("DATE OF ISSUE")
            ) {
                result["issueDate"] =
                    extractValue("DATE OF ISSUE").ifEmpty { next }
            }

            if (
                result["expiryDate"].isNullOrEmpty() &&
                line.contains("DATE OF EXPIRY")
            ) {
                result["expiryDate"] =
                    extractValue("DATE OF EXPIRY").ifEmpty { next }
            }

            if (
                result["permanentAddress"].isNullOrEmpty() &&
                line.contains("PERMANENT ADDRESS")
            ) {
                result["permanentAddress"] =
                    extractValue("PERMANENT ADDRESS").ifEmpty { next }
            }

            if (
                result["issuingAuthority"].isNullOrEmpty() &&
                line.contains("AUTHORITY")
            ) {
                result["issuingAuthority"] =
                    extractValue("AUTHORITY").ifEmpty { next }
            }

            if (
                result["placeOfIssue"].isNullOrEmpty() &&
                line.contains("PLACE OF ISSUE")
            ) {
                result["placeOfIssue"] =
                    extractValue("PLACE OF ISSUE").ifEmpty { next }
            }

            if (
                result["emergencyPhone"].isNullOrEmpty() &&
                (
                    line.contains("TELEPHONE") ||
                        line.contains("PHONE") ||
                        line.contains("MOBILE")
                    )
            ) {
                val phone = Regex(
                    "(\\+?8801[3-9][0-9]{8}|01[3-9][0-9]{8})"
                ).find("$line $next")

                if (phone != null) {
                    result["emergencyPhone"] =
                        phone.groupValues[1]
                }
            }
        }

        if (result["issueDate"].isNullOrEmpty()) {
            result["issueDate"] = findDateNearLabel(
                lines,
                "DATE OF ISSUE",
                "ISSUE DATE"
            )
        }

        if (result["dob"].isNullOrEmpty()) {
            result["dob"] = findDateNearLabel(
                lines,
                "DATE OF BIRTH",
                "DOB"
            )
        }

        if (result["expiryDate"].isNullOrEmpty()) {
            result["expiryDate"] = findDateNearLabel(
                lines,
                "DATE OF EXPIRY",
                "EXPIRY DATE"
            )
        }

        result["rawText"] = rawText

        return result
    }

    private fun findDateNearLabel(
        lines: List<String>,
        vararg labels: String
    ): String {

        val dateRegex = Regex(
            "\\b(\\d{2}\\s+(?:JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|OCT|NOV|DEC)\\s+\\d{4}|\\d{2}/\\d{2}/\\d{4}|\\d{4}-\\d{2}-\\d{2})\\b"
        )

        for (i in lines.indices) {
            if (labels.any { lines[i].contains(it) }) {
                val match = dateRegex.find(
                    lines[i] + " " +
                        lines.getOrNull(i + 1).orEmpty()
                )

                if (match != null) {
                    return match.groupValues[1]
                }
            }
        }

        return ""
    }

    // ---------------------------------------------------------
    // EXIF ORIENTATION
    // ---------------------------------------------------------

    private fun fixExifOrientation(file: File) {

        try {
            val exif = ExifInterface(file.absolutePath)

            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )

            val matrix = Matrix()

            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 ->
                    matrix.postRotate(90f)

                ExifInterface.ORIENTATION_ROTATE_180 ->
                    matrix.postRotate(180f)

                ExifInterface.ORIENTATION_ROTATE_270 ->
                    matrix.postRotate(270f)

                ExifInterface.ORIENTATION_FLIP_HORIZONTAL ->
                    matrix.preScale(-1f, 1f)

                ExifInterface.ORIENTATION_FLIP_VERTICAL ->
                    matrix.preScale(1f, -1f)

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

            val original = BitmapFactory.decodeFile(
                file.absolutePath
            ) ?: return

            val rotated = try {
                Bitmap.createBitmap(
                    original,
                    0,
                    0,
                    original.width,
                    original.height,
                    matrix,
                    true
                )
            } catch (e: Exception) {
                if (!original.isRecycled) original.recycle()
                return
            }

            if (rotated !== original && !original.isRecycled) {
                original.recycle()
            }

            try {
                FileOutputStream(file).use { output ->
                    rotated.compress(
                        Bitmap.CompressFormat.JPEG,
                        92,
                        output
                    )
                    output.flush()
                }
            } finally {
                if (!rotated.isRecycled) rotated.recycle()
            }

        } catch (_: Exception) {
            // Continue with original image if EXIF correction fails.
        }
    }

    // ---------------------------------------------------------
    // IMAGE OPTIMIZATION
    // ---------------------------------------------------------

    private fun optimizeAndCompress(
        file: File
    ): Pair<Bitmap?, String> {

        try {
            val options = BitmapFactory.Options().apply {
                inJustDecodeBounds = true
            }

            BitmapFactory.decodeFile(
                file.absolutePath,
                options
            )

            if (
                options.outWidth <= 0 ||
                options.outHeight <= 0
            ) {
                return Pair(null, "")
            }

            val maxDimension = max(
                options.outWidth,
                options.outHeight
            )

            var sampleSize = 1

            while (
                maxDimension / sampleSize >
                MAX_IMAGE_DIMENSION
            ) {
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

                    val compressed = bitmap.compress(
                        Bitmap.CompressFormat.JPEG,
                        OCR_IMAGE_QUALITY,
                        stream
                    )

                    if (!compressed) {
                        return Pair(null, "")
                    }

                    "data:image/jpeg;base64," +
                        Base64.encodeToString(
                            stream.toByteArray(),
                            Base64.NO_WRAP
                        )
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

    // ---------------------------------------------------------
    // FACE DETECTION
    // ---------------------------------------------------------

    private fun extractPortraitFace(
        bitmap: Bitmap
    ): String {

        var faceBitmap: Bitmap? = null

        try {
            val faces = Tasks.await(
                faceDetector.process(
                    InputImage.fromBitmap(bitmap, 0)
                ),
                FACE_TIMEOUT_SECONDS,
                TimeUnit.SECONDS
            )

            val face = faces.firstOrNull() ?: return ""

            val box = face.boundingBox

            val padX = (box.width() * 0.25f).toInt()
            val padY = (box.height() * 0.35f).toInt()

            val left = (box.left - padX)
                .coerceIn(0, bitmap.width - 1)

            val top = (box.top - padY)
                .coerceIn(0, bitmap.height - 1)

            val right = (box.right + padX)
                .coerceIn(left + 1, bitmap.width)

            val bottom = (box.bottom + padY)
                .coerceIn(top + 1, bitmap.height)

            val width = right - left
            val height = bottom - top

            if (width < 30 || height < 30) {
                return ""
            }

            faceBitmap = Bitmap.createBitmap(
                bitmap,
                left,
                top,
                width,
                height
            )

            return ByteArrayOutputStream().use { stream ->

                faceBitmap.compress(
                    Bitmap.CompressFormat.JPEG,
                    90,
                    stream
                )

                "data:image/jpeg;base64," +
                    Base64.encodeToString(
                        stream.toByteArray(),
                        Base64.NO_WRAP
                    )
            }

        } catch (_: Exception) {
            return ""
        } finally {
            faceBitmap?.let {
                if (!it.isRecycled) it.recycle()
            }
        }
    }

    // ---------------------------------------------------------
    // WEBVIEW SCANNING CALLBACKS
    // ---------------------------------------------------------

    private fun notifyScanningStarted() {

        if (destroyed) return

        activity.runOnUiThread {
            if (!destroyed) {
                webView.evaluateJavascript(
                    "if(window.onImageScanningStarted) {" +
                        "window.onImageScanningStarted('passport');" +
                        "}",
                    null
                )
            }
        }
    }

    // ---------------------------------------------------------
    // SUCCESS RESPONSE
    // ---------------------------------------------------------

    private fun dispatchPassportSuccess(
        fullPageBase64: String,
        faceBase64: String,
        fields: Map<String, String>,
        rawText: String,
        mrz: MrzResult,
        callback: String?
    ) {

        val nameEn = fields["nameEn"] ?: ""
        val passportNo = fields["passportNo"] ?: ""
        val dob = fields["dob"] ?: ""

        val extraJson = JSONObject().apply {

            // Compatibility flag: MRZ consistency only, not authenticity.
            put("isPassportVerified", mrz.isValid)
            put("isPassportOcrSuccessful", true)
            put("mrzValid", mrz.isValid)

            put("verificationLevel", "MRZ_CHECK_DIGITS_ONLY")
            put("isGovernmentAuthenticityVerified", false)

            put("passportType", fields["passportType"] ?: "")
            put("nationality", fields["nationality"] ?: "")
            put("countryCode", mrz.nationality)

            put("surname", fields["surname"] ?: "")
            put("givenNames", fields["givenNames"] ?: "")

            put("fatherName", fields["fatherName"] ?: "")
            put("motherName", fields["motherName"] ?: "")
            put("spouseName", fields["spouseName"] ?: "")

            put("birthPlace", fields["birthPlace"] ?: "")
            put("issueDate", fields["issueDate"] ?: "")
            put("expiryDate", fields["expiryDate"] ?: "")

            put("personalNo", fields["personalNo"] ?: "")
            put("gender", fields["gender"] ?: "")

            put("permanentAddress", fields["permanentAddress"] ?: "")
            put("emergencyPhone", fields["emergencyPhone"] ?: "")

            put("issuingAuthority", fields["issuingAuthority"] ?: "")
            put("placeOfIssue", fields["placeOfIssue"] ?: "")

            put("passportBase64", fullPageBase64)
            put("faceBase64", faceBase64)

            put("mrzLine1", mrz.line1)
            put("mrzLine2", mrz.line2)

            put("mrzChecks", JSONObject().apply {
                put("passportNumber", mrz.passportCheck)
                put("dateOfBirth", mrz.dobCheck)
                put("expiryDate", mrz.expiryCheck)
                put("personalNumber", mrz.personalCheck)
                put("composite", mrz.compositeCheck)
            })

            put("rawText", rawText)
        }

        activity.runOnUiThread {

            if (destroyed) return@runOnUiThread

            Toast.makeText(
                activity,
                "✔ পাসপোর্ট OCR ও MRZ যাচাই সম্পন্ন",
                Toast.LENGTH_SHORT
            ).show()

            webView.evaluateJavascript(
                "if(window.onDocumentValidated) {" +
                    "window.onDocumentValidated(" +
                    "'passport', true, " +
                    "${JSONObject.quote(fullPageBase64)}" +
                    ");}",
                null
            )

            webView.evaluateJavascript(
                "if(window.onFullPassportParsed) {" +
                    "window.onFullPassportParsed(" +
                    "${JSONObject.quote(nameEn)}, " +
                    "${JSONObject.quote(passportNo)}, " +
                    "${JSONObject.quote(fullPageBase64)}, " +
                    "${JSONObject.quote(dob)}, " +
                    "${JSONObject.quote(extraJson.toString())}" +
                    ");}",
                null
            )

            val resultPayload = JSONObject(
                extraJson.toString()
            ).apply {
                put("nameEn", nameEn)
                put("passportNo", passportNo)
                put("dob", dob)
            }

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

    // ---------------------------------------------------------
    // ERROR RESPONSE
    // ---------------------------------------------------------

    private fun notifyError(
        message: String,
        callback: String?
    ) {

        if (destroyed) return

        activity.runOnUiThread {

            if (destroyed) return@runOnUiThread

            Toast.makeText(
                activity,
                message,
                Toast.LENGTH_LONG
            ).show()

            webView.evaluateJavascript(
                "if(window.onDocumentValidated) {" +
                    "window.onDocumentValidated('passport', false, '');" +
                    "}",
                null
            )

            webView.evaluateJavascript(
                "if(window.onDocumentValidationFailed) {" +
                    "window.onDocumentValidationFailed(" +
                    "'passport', ${JSONObject.quote(message)}" +
                    ");}",
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

    private fun notifyCancelled(
        callback: String?
    ) {

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

    // ---------------------------------------------------------
    // CLEANUP
    // ---------------------------------------------------------

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

        // Do not initialize lazy ML Kit clients just to close them.
        try {
            if (textRecognizerDelegate.isInitialized()) {
                textRecognizerDelegate.value.close()
            }
        } catch (_: Exception) {
        }

        try {
            if (faceDetectorDelegate.isInitialized()) {
                faceDetectorDelegate.value.close()
            }
        } catch (_: Exception) {
        }
    }
}
