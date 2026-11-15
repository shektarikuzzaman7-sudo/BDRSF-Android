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
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.max

/**
 * BDRSF ডেডিকেটেড পাসপোর্ট প্রসেসর ইঞ্জিন।
 * এটি শুধুমাত্র পাসপোর্ট প্রসেস করে এবং PHP পেজ থেকে পাঠানো পলিসি অনুসরণ করে।
 */
class PassportProcessorFeature(
    private val activity: Activity,
    private val bridge: AndroidBridge,
    private val webView: WebView
) {

    companion object {
        const val RC_PICK_PASSPORT_GALLERY = 6012
    }

    private val bgExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private var pendingCallback: String? = null
    private var activePolicy: JSONObject = JSONObject()

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

    fun updatePolicy(policyPayload: JSONObject?) {
        activePolicy = policyPayload ?: JSONObject()
    }

    fun openGalleryPicker(policyPayload: JSONObject, callback: String?) {
        updatePolicy(policyPayload)
        pendingCallback = callback
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply {
            type = "image/*"
        }
        try {
            @Suppress("DEPRECATION")
            activity.startActivityForResult(intent, RC_PICK_PASSPORT_GALLERY)
        } catch (_: Exception) {
            notifyError("গ্যালারি ওপেন করা যায়নি", callback)
        }
    }

    fun handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != RC_PICK_PASSPORT_GALLERY) return false
        val cb = pendingCallback
        pendingCallback = null

        if (resultCode == Activity.RESULT_OK && data?.data != null) {
            try {
                val tempFile = File.createTempFile("PASS_GAL_", ".jpg", activity.cacheDir)
                activity.contentResolver.openInputStream(data.data!!)?.use { input ->
                    FileOutputStream(tempFile).use { output ->
                        input.copyTo(output)
                    }
                }
                processPassportFile(tempFile, activePolicy, cb)
            } catch (e: Exception) {
                notifyError("গ্যালারি ছবি লোড করা যায়নি: ${e.localizedMessage}", cb)
            }
        }
        return true
    }

    fun processPassportFile(file: File, policyPayload: JSONObject? = null, callback: String? = null) {
        if (policyPayload != null && policyPayload.length() > 0) {
            activePolicy = policyPayload
        }
        if (!file.exists() || file.length() == 0L) {
            notifyError("পাসপোর্টের ছবি পাওয়া যায়নি", callback)
            return
        }

        notifyScanningStarted()

        bgExecutor.execute {
            try {
                fixExifOrientation(file)
                val (bitmap, fullPageBase64) = optimizeAndCompress(file)
                if (bitmap == null) {
                    notifyError("পাসপোর্টের ছবি পড়া সম্ভব হয়নি", callback)
                    return@execute
                }

                val extractFaceFlag = activePolicy.optBoolean("extractFace", true)
                val faceBase64 = if (extractFaceFlag) extractPortraitFace(bitmap) else ""

                val inputImage = InputImage.fromBitmap(bitmap, 0)
                textRecognizer.process(inputImage)
                    .addOnSuccessListener { visionText ->
                        val rawText = visionText.text
                        val validationError = validateAgainstPhpPolicy(rawText, activePolicy)
                        if (validationError != null) {
                            notifyError(validationError, callback)
                            return@addOnSuccessListener
                        }

                        val fields = extractAllEnglishPassportFields(visionText)
                        dispatchPassportSuccess(
                            fullPageBase64 = fullPageBase64,
                            faceBase64 = faceBase64,
                            fields = fields,
                            rawText = rawText,
                            callback = callback
                        )
                    }
                    .addOnFailureListener {
                        notifyError("পাসপোর্টের লেখা পড়া যায়নি, পরিষ্কার ছবি দিন।", callback)
                    }
            } catch (e: Exception) {
                notifyError("প্রসেসিং ত্রুটি: ${e.localizedMessage}", callback)
            }
        }
    }

    private fun validateAgainstPhpPolicy(rawText: String, policy: JSONObject): String? {
        val upper = rawText.uppercase(Locale.ROOT)

        val rejectList = jsonArrayToList(policy.optJSONArray("rejectIfContains"))
        val effectiveRejects = if (rejectList.isNotEmpty()) {
            rejectList
        } else {
            listOf("NATIONAL ID", "NID NO", "জাতীয় পরিচয়")
        }

        val hasPassportIndicator = upper.contains("PASSPORT") ||
            upper.contains("P<") ||
            upper.contains("SURNAME") ||
            upper.contains("GIVEN NAME")

        if (!hasPassportIndicator) {
            for (badWord in effectiveRejects) {
                if (badWord.isNotBlank() && upper.contains(badWord.uppercase(Locale.ROOT))) {
                    return policy.optString(
                        "rejectMessage",
                        "ভুল ডকুমেন্ট! আপনি পাসপোর্টের জায়গায় অন্য ডকুমেন্ট দিয়েছেন।"
                    )
                }
            }
        }

        val mustContainAny = jsonArrayToList(policy.optJSONArray("mustContainAny"))
        val effectiveRequired = if (mustContainAny.isNotEmpty()) {
            mustContainAny
        } else {
            listOf(
                "PASSPORT",
                "P<",
                "SURNAME",
                "GIVEN NAME",
                "NATIONALITY",
                "PERSONAL DATA AND EMERGENCY CONTACT",
                "BGD"
            )
        }

        val matchedAny = effectiveRequired.any { kw ->
            kw.isNotBlank() && upper.contains(kw.uppercase(Locale.ROOT))
        } || Regex("\\b([A-Z]{1,2})\\s*([0-9]{7,9})\\b").containsMatchIn(upper)

        if (!matchedAny) {
            return policy.optString(
                "invalidMessage",
                "এটি পাসপোর্ট নয়! অনুগ্রহ করে পাসপোর্টের পরিষ্কার ছবি দিন।"
            )
        }

        return null
    }

    private fun jsonArrayToList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val list = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val item = arr.optString(i, "").trim()
            if (item.isNotEmpty()) list.add(item)
        }
        return list
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

    /**
     * ডেমো পাসপোর্টের আদলে তৈরি অত্যন্ত শক্তিশালী ও নিখুঁত ডিপ টেক্সট এক্সট্রাকশন ইঞ্জিন।
     * এটি পাসপোর্টের উভয় পৃষ্ঠা (বা যেকোনো এক পৃষ্ঠা) থেকে লেবেল ও মান মিলিয়ে তথ্য তুলে আনবে।
     */
    fun extractAllEnglishPassportFields(visionText: com.google.mlkit.vision.text.Text): Map<String, String> {
        val result = mutableMapOf<String, String>()
        val rawText = visionText.text
        val upperText = rawText.uppercase(Locale.ROOT)
        
        val rawLines = upperText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val compactLines = rawLines.map { it.replace(Regex("\\s+"), "") }

        // ১. MRZ লাইন-১ (যেমন: P<BGDHOSAIN<<ROBIN<<<<) থেকে নাম
        val mrzLine1 = compactLines.firstOrNull { line ->
            line.length >= 18 && (line.contains("P<") || line.contains("<<"))
        }
        if (mrzLine1 != null) {
            val startIdx = mrzLine1.indexOf("P<").let { if (it >= 0) it + 5 else 0 }
            val body = if (mrzLine1.length > startIdx) mrzLine1.substring(startIdx) else ""
            val nameParts = body.split("<<")
            val surname = nameParts.getOrNull(0)?.replace("<", " ")?.replace(Regex("[^A-Z\\s]"), "")?.trim() ?: ""
            val givenNames = nameParts.getOrNull(1)?.replace("<", " ")?.replace(Regex("[^A-Z\\s]"), "")?.trim() ?: ""
            val fullName = listOf(givenNames, surname).filter { it.isNotEmpty() }.joinToString(" ").replace(Regex("\\s+"), " ")
            if (fullName.isNotEmpty()) {
                result["nameEn"] = fullName
            }
        }

        // ২. MRZ লাইন-২ থেকে পাসপোর্ট নম্বর, জন্মতারিখ, লিঙ্গ, মেয়াদ ও পার্সোনাল নম্বর
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
        }

        // ৩. ভিজ্যুয়াল জোন থেকে পাসপোর্ট নম্বর ব্যাকআপ
        if (result["passportNo"].isNullOrEmpty()) {
            val passMatch = Regex("\\b([A-Z]{1,2})\\s*([0-9]{7,9})\\b").find(upperText)
            if (passMatch != null) {
                result["passportNo"] = (passMatch.groupValues[1] + passMatch.groupValues[2]).take(9)
            }
        }

        // ৪. ডেমো পাসপোর্টের লেবেল ভিত্তিক সুনির্দিষ্ট স্ক্যানিং (Name, Father, Mother, Birth Place, Dates, Phone)
        var surnameVis = ""
        var givenNameVis = ""

        for (i in rawLines.indices) {
            val line = rawLines[i]
            val nextLine = if (i + 1 < rawLines.size) rawLines[i + 1] else ""
            val nextNextLine = if (i + 2 < rawLines.size) rawLines[i + 2] else ""

            // Surname & Given Name
            if (line.contains("SURNAME") && nextLine.isNotEmpty()) {
                surnameVis = nextLine.replace(Regex("[^A-Z\\s]"), "").trim()
            }
            if (line.contains("GIVEN NAME") && nextLine.isNotEmpty()) {
                givenNameVis = nextLine.replace(Regex("[^A-Z\\s]"), "").trim()
            }
            if (line.startsWith("NAME:") || line == "NAME") {
                val inlineName = line.substringAfter("NAME").replace(":", "").replace(Regex("[^A-Z\\s.]"), "").trim()
                val foundName = if (inlineName.length >= 3) inlineName else nextLine.replace(Regex("[^A-Z\\s.]"), "").trim()
                if (foundName.length >= 3 && result["nameEn"].isNullOrEmpty()) {
                    result["nameEn"] = foundName
                }
            }

            // Father's Name (পিতার নাম) - ডেমো পাসপোর্টের স্টাইল অনুযায়ী
            if (result["fatherName"].isNullOrEmpty() && (line.contains("FATHER'S NAME") || line.contains("FATHER"))) {
                val inlineVal = line.substringAfter("FATHER'S NAME").substringAfter("FATHER").replace(":", "").replace(Regex("[^A-Z\\s.]"), "").trim()
                val candidate = when {
                    inlineVal.length >= 3 -> inlineVal
                    nextLine.isNotEmpty() && !nextLine.contains("MOTHER") && !nextLine.contains("SPOUSE") -> nextLine
                    else -> ""
                }.replace(Regex("[^A-Z\\s.]"), "").trim()

                if (candidate.length >= 3 && !candidate.contains("MOTHER") && !candidate.contains("RELATIONSHIP")) {
                    result["fatherName"] = candidate
                } else if (nextNextLine.isNotEmpty() && !nextNextLine.contains("MOTHER")) {
                    val cand2 = nextNextLine.replace(Regex("[^A-Z\\s.]"), "").trim()
                    if (cand2.length >= 3) result["fatherName"] = cand2
                }
            }

            // Mother's Name (মাতার নাম)
            if (result["motherName"].isNullOrEmpty() && (line.contains("MOTHER'S NAME") || line.contains("MOTHER"))) {
                val inlineVal = line.substringAfter("MOTHER'S NAME").substringAfter("MOTHER").replace(":", "").replace(Regex("[^A-Z\\s.]"), "").trim()
                val candidate = when {
                    inlineVal.length >= 3 -> inlineVal
                    nextLine.isNotEmpty() && !nextLine.contains("SPOUSE") && !nextLine.contains("PERMANENT") -> nextLine
                    else -> ""
                }.replace(Regex("[^A-Z\\s.]"), "").trim()

                if (candidate.length >= 3 && !candidate.contains("SPOUSE")) {
                    result["motherName"] = candidate
                } else if (nextNextLine.isNotEmpty() && !nextNextLine.contains("SPOUSE")) {
                    val cand2 = nextNextLine.replace(Regex("[^A-Z\\s.]"), "").trim()
                    if (cand2.length >= 3) result["motherName"] = cand2
                }
            }

            // Birth Place (জন্মস্থান)
            if (result["birthPlace"].isNullOrEmpty() && (line.contains("PLACE OF BIRTH") || line.contains("BIRTH"))) {
                val inlineBp = line.substringAfter("PLACE OF BIRTH").substringAfter("BIRTH").replace(":", "").replace(Regex("[^A-Z\\s]"), "").trim()
                val candidate = if (inlineBp.length >= 3) inlineBp else nextLine.replace(Regex("[^A-Z\\s]"), "").trim()
                if (candidate.length >= 3 && !candidate.contains("DATE") && !candidate.contains("SEX")) {
                    result["birthPlace"] = candidate
                }
            }

            // Date of Birth (জন্মতারিখ)
            if (result["dob"].isNullOrEmpty() && (line.contains("DATE OF BIRTH") || line.contains("DOB"))) {
                val dateMatch = Regex("([0-9]{2}\\s+[A-Z]{3}\\s+[0-9]{4}|[0-9]{2}/[0-9]{2}/[0-9]{4})").find("$line $nextLine")
                if (dateMatch != null) {
                    result["dob"] = dateMatch.groupValues[1]
                }
            }

            // Issue Date (প্রদানের তারিখ)
            if (result["issueDate"].isNullOrEmpty() && (line.contains("DATE OF ISSUE") || line.contains("ISSUE"))) {
                val dateMatch = Regex("([0-9]{2}\\s+[A-Z]{3}\\s+[0-9]{4}|[0-9]{2}/[0-9]{2}/[0-9]{4})").find("$line $nextLine")
                if (dateMatch != null) {
                    result["issueDate"] = dateMatch.groupValues[1]
                }
            }

            // Expiry Date (মেয়াদ উত্তীর্ণের তারিখ)
            if (result["expiryDate"].isNullOrEmpty() && (line.contains("DATE OF EXPIRY") || line.contains("EXPIRY"))) {
                val dateMatch = Regex("([0-9]{2}\\s+[A-Z]{3}\\s+[0-9]{4}|[0-9]{2}/[0-9]{2}/[0-9]{4})").find("$line $nextLine")
                if (dateMatch != null) {
                    result["expiryDate"] = dateMatch.groupValues[1]
                }
            }

            // Permanent Address (স্থায়ী ঠিকানা)
            if (result["permanentAddress"].isNullOrEmpty() && line.contains("PERMANENT ADDRESS")) {
                val addrPart1 = line.substringAfter("PERMANENT ADDRESS").replace(":", "").trim()
                val addrPart2 = if (nextLine.isNotEmpty() && !nextLine.contains("EMERGENCY")) nextLine else ""
                val fullAddr = listOf(addrPart1, addrPart2).filter { it.isNotEmpty() }.joinToString(", ")
                if (fullAddr.length >= 5) {
                    result["permanentAddress"] = fullAddr
                }
            }

            // Telephone No / Emergency Phone (টেলিফোন নম্বর)
            if (result["emergencyPhone"].isNullOrEmpty() && (line.contains("TELEPHONE NO") || line.contains("PHONE") || line.contains("MOBILE"))) {
                val phoneMatch = Regex("(\\+?8801[3-9][0-9]{8}|01[3-9][0-9]{8})").find("$line $nextLine $nextNextLine")
                if (phoneMatch != null) {
                    result["emergencyPhone"] = phoneMatch.groupValues[1]
                }
            }
        }

        // ৫. গ্লোবাল ফলব্যাক সার্চ (যদি লেবেল সরাসরি ম্যাচ না করে)
        if (result["dob"].isNullOrEmpty()) {
            val globalDob = Regex("\\b([0-9]{2}\\s+(?:JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|OCT|NOV|DEC)\\s+[0-9]{4})\\b").find(upperText)
            if (globalDob != null) {
                result["dob"] = globalDob.groupValues[1]
            }
        }

        if (result["emergencyPhone"].isNullOrEmpty()) {
            val globalPhone = Regex("(\\+?8801[3-9][0-9]{8}|01[3-9][0-9]{8})").find(upperText)
            if (globalPhone != null) {
                result["emergencyPhone"] = globalPhone.groupValues[1]
            }
        }

        if (result["nameEn"].isNullOrEmpty()) {
            val combined = listOf(givenNameVis, surnameVis).filter { it.isNotEmpty() }.joinToString(" ").trim()
            if (combined.isNotEmpty()) {
                result["nameEn"] = combined
            }
        }

        return result
    }

    private fun fixExifOrientation(file: File) {
        try {
            val exif = ExifInterface(file.absolutePath)
            val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            val degrees = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }
            if (degrees == 0) return

            val bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return
            val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
            val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            bitmap.recycle()

            FileOutputStream(file).use {
                rotated.compress(Bitmap.CompressFormat.JPEG, 92, it)
            }
            rotated.recycle()
        } catch (_: Exception) {}
    }

    private fun optimizeAndCompress(file: File): Pair<Bitmap?, String> {
        return try {
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
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream)
            val base64 = "data:image/jpeg;base64," + Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
            Pair(bitmap, base64)
        } catch (_: Exception) {
            Pair(null, "")
        }
    }

    private fun extractPortraitFace(bitmap: Bitmap): String {
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
                    faceBmp.compress(Bitmap.CompressFormat.JPEG, 90, stream)
                    faceBmp.recycle()
                    return "data:image/jpeg;base64," + Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)
                }
            }
            ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun notifyScanningStarted() {
        activity.runOnUiThread {
            webView.evaluateJavascript("if(window.onImageScanningStarted) { window.onImageScanningStarted('passport'); }", null)
        }
    }

    private fun dispatchPassportSuccess(
        fullPageBase64: String,
        faceBase64: String,
        fields: Map<String, String>,
        rawText: String,
        callback: String?
    ) {
        val nameEn = fields["nameEn"] ?: ""
        val passportNo = fields["passportNo"] ?: ""
        val dob = fields["dob"] ?: ""

        val extraJson = JSONObject().apply {
            put("isPassportVerified", true)
            put("fatherName", fields["fatherName"] ?: "")
            put("motherName", fields["motherName"] ?: "")
            put("spouseName", fields["spouseName"] ?: "")
            put("birthPlace", fields["birthPlace"] ?: "")
            put("issueDate", fields["issueDate"] ?: "")
            put("expiryDate", fields["expiryDate"] ?: "")
            put("personalNo", fields["personalNo"] ?: "")
            put("permanentAddress", fields["permanentAddress"] ?: "")
            put("emergencyPhone", fields["emergencyPhone"] ?: "")
            put("gender", fields["gender"] ?: "")
            put("passportBase64", fullPageBase64)
            put("faceBase64", faceBase64)
            put("rawText", rawText)
        }

        activity.runOnUiThread {
            Toast.makeText(activity, "✔ পাসপোর্ট যাচাই সম্পন্ন", Toast.LENGTH_SHORT).show()

            webView.evaluateJavascript(
                "if(window.onDocumentValidated) { window.onDocumentValidated('passport', true, ${JSONObject.quote(fullPageBase64)}); }",
                null
            )

            webView.evaluateJavascript(
                "if(window.onFullPassportParsed) { window.onFullPassportParsed(" +
                    "${JSONObject.quote(nameEn)}, " +
                    "${JSONObject.quote(passportNo)}, " +
                    "${JSONObject.quote(fullPageBase64)}, " +
                    "${JSONObject.quote(dob)}, " +
                    "${JSONObject.quote(extraJson.toString())}" +
                    "); }",
                null
            )

            val resultPayload = JSONObject(extraJson.toString()).apply {
                put("nameEn", nameEn)
                put("passportNo", passportNo)
                put("dob", dob)
            }

            bridge.sendResponseToWeb(
                response = BridgeResponse(action = Actions.START_KYC, status = Status.SUCCESS, data = resultPayload),
                callbackFunction = callback
            )
        }
    }

    private fun notifyError(message: String, callback: Sring?) {
        activity.runOnUiThread {
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
            webView.evaluateJavascript("if(window.onDocumentValidated) { window.onDocumentValidated('passport', false, ''); }", null)
            webView.evaluateJavascript("if(window.onDocumentValidationFailed) { window.onDocumentValidationFailed('passport', ${JSONObject.quote(message)}); }", null)

            bridge.sendResponseToWeb(
                response = BridgeResponse(action = Actions.START_KYC, status = Status.ERROR, message = message),
                callbackFunction = callback
            )
        }
    }

    fun destroy() {
        try {
            textRecognizer.close()
            faceDetector.close()
            bgExecutor.shutdown()
        } catch (_: Exception) {}
    }
}
