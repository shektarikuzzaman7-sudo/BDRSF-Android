package com.bdrsf.app.features.kyc

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.provider.MediaStore
import android.webkit.WebView
import android.widget.Toast
import com.bdrsf.app.bridge.AndroidBridge
import com.bdrsf.app.bridge.WebBridgeContract.Actions
import com.bdrsf.app.bridge.WebBridgeContract.BridgeResponse
import com.bdrsf.app.bridge.WebBridgeContract.Status
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * BDRSF NID ফ্রন্ট ও ব্যাক প্রসেসর:
 * - পুরাতন লেমিনেটেড NID, স্মার্ট NID এবং অনলাইন ডাউনলোড NID — ৩টি ফরম্যাট সাপোর্ট করে।
 * - একজনের ফ্রন্ট দিয়ে অন্যজনের ব্যাক পার্ট বা ভুয়া ব্যাক পার্ট দিলে রিজেক্ট করে।
 */
class NidDocumentProcessorFeature(
    private val activity: Activity,
    private val bridge: AndroidBridge,
    private val webView: WebView,
    private val barcodeDecoder: NidBarcodeAndQrDecoder
) {

    companion object {
        const val RC_PICK_NID_GALLERY = 6011
    }

    private val bgExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    @Volatile
    private var pendingGallerySlot: String = "nid_front"

    @Volatile
    private var pendingCallback: String? = null

    @Volatile
    private var cachedFrontNidNumber: String = ""

    @Volatile
    private var cachedFrontNameEn: String = ""

    @Volatile
    private var cachedFrontDob: String = ""

    @Volatile
    private var cachedCroppedFaceBase64: String = ""

    @Volatile
    private var cachedBackObj: JSONObject = JSONObject()

    @Volatile
    private var destroyed = false

    fun clearCache() {
        cachedFrontNidNumber = ""
        cachedFrontNameEn = ""
        cachedFrontDob = ""
        cachedCroppedFaceBase64 = ""
        cachedBackObj = JSONObject()
    }

    fun getCachedCroppedFace(): String = cachedCroppedFaceBase64

    fun openGalleryPicker(slot: String, callback: String?) {
        if (destroyed) return
        pendingGallerySlot = if (slot.lowercase(Locale.ROOT).contains("back")) "nid_back" else "nid_front"
        pendingCallback = callback

        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply {
            type = "image/*"
        }
        try {
            @Suppress("DEPRECATION")
            activity.startActivityForResult(intent, RC_PICK_NID_GALLERY)
        } catch (e: Exception) {
            pendingCallback = null
            notifyError(pendingGallerySlot, "গ্যালারি ওপেন করা যায়নি: ${e.localizedMessage ?: ""}", callback)
        }
    }

    fun handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != RC_PICK_NID_GALLERY) return false
        val slot = pendingGallerySlot
        val callback = pendingCallback
        pendingCallback = null

        if (destroyed) return true
        if (resultCode != Activity.RESULT_OK) {
            bridge.sendResponseToWeb(
                BridgeResponse(Actions.START_KYC, Status.CANCELLED, message = "NID gallery selection cancelled"),
                callback
            )
            return true
        }

        val uri = data?.data ?: run {
            notifyError(slot, "গ্যালারি থেকে ছবি পাওয়া যায়নি", callback)
            return true
        }

        try {
            val tempFile = KycImageUtils.copyUriToTempCacheFile(activity, uri, "BDRSF_${slot.uppercase(Locale.ROOT)}_")
            processNidSlot(tempFile, slot, callback)
        } catch (e: Exception) {
            notifyError(slot, "ছবি লোড করা যায়নি: ${e.localizedMessage ?: ""}", callback)
        }
        return true
    }

    fun processNidSlot(file: File, rawSlot: String, callback: String?) {
        if (destroyed) return
        val slot = if (rawSlot.lowercase(Locale.ROOT).contains("back")) "nid_back" else "nid_front"
        if (!file.exists() || file.length() <= 0L) {
            notifyError(slot, "ডকুমেন্টের ছবি পাওয়া যায়নি", callback)
            return
        }

        activity.runOnUiThread {
            webView.evaluateJavascript(
                "if(window.onImageScanningStarted) { window.onImageScanningStarted(${JSONObject.quote(slot)}); }",
                null
            )
        }

        bgExecutor.execute {
            var bitmap: Bitmap? = null
            try {
                KycImageUtils.fixExifOrientationInPlace(file)
                bitmap = KycImageUtils.decodeSampledBitmap(file, 1800)
                if (bitmap == null || bitmap.isRecycled) {
                    notifyError(slot, "এনআইডি ছবি প্রসেস করা যায়নি। পরিষ্কার ছবি দিন।", callback)
                    return@execute
                }

                val fullDocBase64 = KycImageUtils.encodeBitmapToDataUri(bitmap, 90)

                if (slot == "nid_front") {
                    processFrontBitmap(bitmap, fullDocBase64, callback)
                } else {
                    processBackBitmap(bitmap, fullDocBase64, callback)
                }
            } catch (e: Exception) {
                notifyError(slot, e.localizedMessage ?: "NID যাচাই ত্রুটি", callback)
            } finally {
                if (bitmap != null && !bitmap.isRecycled) bitmap.recycle()
                try {
                    file.delete()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun processFrontBitmap(bitmap: Bitmap, fullDocBase64: String, callback: String?) {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        val rawText = try {
            Tasks.await(recognizer.process(InputImage.fromBitmap(bitmap, 0))).text.orEmpty()
        } catch (_: Exception) {
            ""
        } finally {
            try {
                recognizer.close()
            } catch (_: Exception) {
            }
        }

        val upper = rawText.uppercase(Locale.ROOT)
        if (upper.contains("PASSPORT") && upper.contains("P<")) {
            notifyError("nid_front", "ভুল ডকুমেন্ট! পাসপোর্টের জায়গায় এনআইডি কার্ডের ফ্রন্ট পার্ট দিন।", callback)
            return
        }

        if ((upper.contains("BLOOD GROUP") || upper.contains("I<BGD")) && !upper.contains("DATE OF BIRTH")) {
            notifyError("nid_front", "এটি এনআইডি কার্ডের ব্যাক পার্ট! প্রথমে ফ্রন্ট পার্ট দিন।", callback)
            return
        }

        val extracted = parseFrontEnglishFields(rawText)
        val nidNumber = extracted["nid"].orEmpty()
        val nameEn = extracted["nameEn"].orEmpty()
        val dob = extracted["dob"].orEmpty()

        if (nidNumber.length !in listOf(10, 13, 17)) {
            notifyError(
                "nid_front",
                "এনআইডি কার্ডের ১০/১৩/১৭ ডিজিটের নম্বর স্পষ্ট পড়া যায়নি! পরিষ্কার ফ্রন্ট পার্ট দিন।",
                callback
            )
            return
        }

        val hasNidHeader = upper.contains("NATIONAL ID") ||
            upper.contains("NID") ||
            upper.contains("GOVERNMENT OF THE PEOPLE") ||
            upper.contains("DATE OF BIRTH") ||
            upper.contains("ID NO")

        if (!hasNidHeader || nameEn.length < 3) {
            notifyError(
                "nid_front",
                "ভুয়া বা অস্পষ্ট এনআইডি ফ্রন্ট পার্ট! নাম ও সকল তথ্য স্পষ্ট দেখা যায় এমন আসল কার্ড দিন।",
                callback
            )
            return
        }

        val croppedFaceCopy = KycImageUtils.cropStandalonePortraitCopy(bitmap)
        if (croppedFaceCopy.isBlank()) {
            notifyError(
                "nid_front",
                "এনআইডি কার্ডে আবেদনকারীর মুখের ছবি স্পষ্ট শনাক্ত হয়নি। পরিষ্কার ছবি দিন।",
                callback
            )
            return
        }

        cachedFrontNidNumber = nidNumber
        cachedFrontNameEn = nameEn
        cachedFrontDob = dob
        cachedCroppedFaceBase64 = croppedFaceCopy

        val metaJson = JSONObject().apply {
            put("nid", nidNumber)
            put("name", nameEn)
            put("nameEn", nameEn)
            put("dob", dob)
            put("faceBase64", cachedCroppedFaceBase64)
            put("croppedFaceBase64", cachedCroppedFaceBase64)
        }

        activity.runOnUiThread {
            if (destroyed) return@runOnUiThread
            Toast.makeText(activity, "✔ এনআইডি ফ্রন্ট পার্ট যাচাই সম্পন্ন ($nidNumber)", Toast.LENGTH_SHORT).show()

            webView.evaluateJavascript(
                "if(window.handleDocumentPreview) { window.handleDocumentPreview('nid_front', true, ${JSONObject.quote(fullDocBase64)}, ${metaJson}); }",
                null
            )
            webView.evaluateJavascript(
                "if(window.onDocumentValidated) { window.onDocumentValidated('nid_front', true, ${JSONObject.quote(fullDocBase64)}, ${metaJson}); }",
                null
            )
            webView.evaluateJavascript(
                "if(window.handleParsedNID) { window.handleParsedNID(${JSONObject.quote(nameEn)}, '', '', ${JSONObject.quote(dob)}, ${JSONObject.quote(nidNumber)}, '', ${JSONObject.quote(cachedCroppedFaceBase64)}, null); }",
                null
            )

            bridge.sendResponseToWeb(
                BridgeResponse(Actions.START_KYC, Status.SUCCESS, data = metaJson, message = "NID Front Verified"),
                callback
            )
        }
    }

    private fun processBackBitmap(bitmap: Bitmap, fullDocBase64: String, callback: String?) {
        val frontDigits = cachedFrontNidNumber.replace(Regex("\\D"), "")
        if (frontDigits.length !in listOf(10, 13, 17)) {
            notifyError("nid_back", "প্রথমে এনআইডি ফ্রন্ট পার্ট সঠিকভাবে যাচাই করুন।", callback)
            return
        }

        val backRes = barcodeDecoder.decodeFullNidBack(bitmap)
        if (!backRes.success) {
            notifyError(
                "nid_back",
                backRes.errorReason.ifBlank { "এনআইডি ব্যাক পার্টের PDF417/MRZ/QR কোড পড়া যায়নি।" },
                callback
            )
            return
        }

        val backDigits = backRes.nidNumber.replace(Regex("\\D"), "")
        if (backDigits.length !in listOf(10, 13, 17)) {
            notifyError(
                "nid_back",
                "ব্যাক পার্টের বারকোড/MRZ থেকে বৈধ NID/PIN নম্বর পাওয়া যায়নি! ভুয়া বা অস্পষ্ট ব্যাক পার্ট গ্রহণযোগ্য নয়।",
                callback
            )
            return
        }

        // ক্রস-ম্যাচ লজিক:
        // ১) একই দৈর্ঘ্যের NID হলে (যেমন স্মার্ট ১০=১০ বা পুরাতন ১৭=১৭) সরাসরি নম্বর মিলতে হবে।
        // ২) অনলাইন ডাউনলোড কপিতে ফ্রন্টে ১০ ডিজিট ও ব্যাকে ১৭ ডিজিট পিন থাকলে নাম + জন্মতারিখ ১০০% মিলতে হবে।
        val isOnlineSmartToOldPinBridge = (frontDigits.length == 10 && backDigits.length == 17) ||
            (frontDigits.length == 17 && backDigits.length == 10)

        if (!isOnlineSmartToOldPinBridge) {
            if (frontDigits != backDigits) {
                notifyError(
                    "nid_back",
                    "সতর্কতা! ফ্রন্ট পার্টের NID ($frontDigits) ও ব্যাক পার্টের NID ($backDigits) ভিন্ন ব্যক্তির। একই কার্ডের ব্যাক পার্ট দিন!",
                    callback
                )
                return
            }
        } else {
            // অনলাইন NID কপির ক্ষেত্রে নাম ও জন্মতারিখ অবশ্যই মিলতে হবে
            val nameOk = backRes.holderNameEn.length >= 3 && areNamesCompatible(cachedFrontNameEn, backRes.holderNameEn)
            val dobOk = backRes.dob.isNotBlank() && cachedFrontDob.isNotBlank() &&
                normalizeDobForCompare(cachedFrontDob) == normalizeDobForCompare(backRes.dob)

            if (!nameOk || !dobOk) {
                notifyError(
                    "nid_back",
                    "সতর্কতা! ফ্রন্ট পার্টের তথ্য (${cachedFrontNameEn}, ${cachedFrontDob}) ও ব্যাক পার্টের PDF417 তথ্য (${backRes.holderNameEn}, ${backRes.dob}) ভিন্ন ব্যক্তির!",
                    callback
                )
                return
            }
        }

        // নাম থাকলে সর্বক্ষেত্রে নাম ক্রস-চেক
        if (backRes.holderNameEn.length >= 3 && cachedFrontNameEn.length >= 3) {
            if (!areNamesCompatible(cachedFrontNameEn, backRes.holderNameEn)) {
                notifyError(
                    "nid_back",
                    "ফ্রন্ট পার্টের নাম (${cachedFrontNameEn}) ও ব্যাক পার্টের নাম (${backRes.holderNameEn}) মিলছে না!",
                    callback
                )
                return
            }
        }

        // জন্মতারিখ থাকলে সর্বক্ষেত্রে জন্মতারিখ ক্রস-চেক
        if (backRes.dob.isNotBlank() && cachedFrontDob.isNotBlank()) {
            if (normalizeDobForCompare(cachedFrontDob) != normalizeDobForCompare(backRes.dob)) {
                notifyError(
                    "nid_back",
                    "ফ্রন্ট পার্টের জন্মতারিখ (${cachedFrontDob}) ও ব্যাক পার্টের জন্মতারিখ (${backRes.dob}) মিলছে না!",
                    callback
                )
                return
            }
        }

        cachedBackObj = backRes.fullBackJson
        val effectiveName = cachedFrontNameEn.ifBlank { backRes.holderNameEn }
        val effectiveDob = cachedFrontDob.ifBlank { backRes.dob }

        val metaJson = JSONObject().apply {
            put("back_code", backRes.rawBarcodeValue)
            put("codeType", backRes.cardVariant)
            put("qrCodeData", backRes.qrEnglishData)
            put("pinNumber", backRes.pinNumber)
            put("bloodGroup", backRes.bloodGroup)
            put("issueDate", backRes.issueDate)
            put("englishText", backRes.backEnglishText)
            put("nid", frontDigits)
            put("nameEn", effectiveName)
            put("dob", effectiveDob)
        }

        activity.runOnUiThread {
            if (destroyed) return@runOnUiThread
            Toast.makeText(activity, "✔ ব্যাক পার্ট (${backRes.cardVariant}) ১০০% যাচাই সম্পন্ন!", Toast.LENGTH_SHORT).show()

            webView.evaluateJavascript(
                "if(window.handleDocumentPreview) { window.handleDocumentPreview('nid_back', true, ${JSONObject.quote(fullDocBase64)}, ${metaJson}); }",
                null
            )
            webView.evaluateJavascript(
                "if(window.onDocumentValidated) { window.onDocumentValidated('nid_back', true, ${JSONObject.quote(fullDocBase64)}, ${metaJson}); }",
                null
            )
            webView.evaluateJavascript(
                "if(window.handleParsedNID) { window.handleParsedNID(${JSONObject.quote(effectiveName)}, '', '', ${JSONObject.quote(effectiveDob)}, ${JSONObject.quote(frontDigits)}, '', ${JSONObject.quote(cachedCroppedFaceBase64)}, ${cachedBackObj}); }",
                null
            )

            bridge.sendResponseToWeb(
                BridgeResponse(Actions.START_KYC, Status.SUCCESS, data = metaJson, message = "NID Back Verified & Matched"),
                callback
            )
        }
    }

    private fun areNamesCompatible(frontName: String, backName: String): Boolean {
        val fTokens = frontName.uppercase(Locale.ROOT).replace(Regex("[^A-Z ]"), " ")
            .split(Regex("\\s+")).filter { it.length >= 3 }.toSet()
        val bTokens = backName.uppercase(Locale.ROOT).replace(Regex("[^A-Z ]"), " ")
            .split(Regex("\\s+")).filter { it.length >= 3 }.toSet()
        if (fTokens.isEmpty() || bTokens.isEmpty()) return false
        return fTokens.intersect(bTokens).size >= minOf(2, minOf(fTokens.size, bTokens.size))
    }

    private fun normalizeDobForCompare(rawDob: String): String {
        val clean = rawDob.trim().uppercase(Locale.ROOT).replace(Regex("\\s+"), " ")
        val m = Regex("(\\d{1,2})\\s+(JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|OCT|NOV|DEC)[A-Z]*\\s+(\\d{4})").find(clean)
        if (m != null) {
            val day = m.groupValues[1].padStart(2, '0')
            val mon = m.groupValues[2]
            val yr = m.groupValues[3]
            return "$day-$mon-$yr"
        }
        return clean
    }

    private fun parseFrontEnglishFields(rawText: String): Map<String, String> {
        val lines = rawText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val upperText = rawText.uppercase(Locale.ROOT)

        // স্মার্ট NID-এ স্পেসসহ ১০ ডিজিট (736 198 4136), অনলাইন NID-এ ১০ ডিজিট (3761721632), পুরাতন NID-এ ১৩/১৭ ডিজিট (19945611076000418)
        val spacedSmartNid = Regex("\\b([0-9]{3}\\s+[0-9]{3}\\s+[0-9]{4})\\b").find(rawText)?.groupValues?.getOrNull(1)
            ?.replace("\\s+".toRegex(), "")
        val plainNid = Regex("\\b([0-9]{17}|[0-9]{13}|[0-9]{10})\\b").find(rawText)?.groupValues?.getOrNull(1)
        val nid = spacedSmartNid ?: plainNid.orEmpty()

        var nameEn = ""
        for (i in lines.indices) {
            val u = lines[i].uppercase(Locale.ROOT)
            if (u.startsWith("NAME") || u.contains("NAME:")) {
                val inline = lines[i].substringAfter(":", "").trim()
                if (inline.length >= 3 && inline.any { it.isLetter() }) {
                    nameEn = inline
                    break
                }
                val next = lines.getOrNull(i + 1).orEmpty()
                if (next.length >= 3 && next.all { it.isLetter() || it == ' ' || it == '.' || it == '-' }) {
                    nameEn = next
                    break
                }
            }
        }
        if (nameEn.isBlank()) {
            nameEn = lines.firstOrNull { line ->
                val u = line.uppercase(Locale.ROOT)
                line.length in 4..45 &&
                    line == u &&
                    line.all { it.isLetter() || it == ' ' || it == '.' || it == '-' } &&
                    !u.contains("GOVERNMENT") &&
                    !u.contains("REPUBLIC") &&
                    !u.contains("BANGLADESH") &&
                    !u.contains("NATIONAL") &&
                    !u.contains("CARD") &&
                    !u.contains("DATE") &&
                    !u.contains("BIRTH")
            }.orEmpty()
        }

        val dob = Regex(
            "\\b(\\d{1,2}\\s+(?:JAN|FEB|MAR|APR|MAY|JUN|JUL|AUG|SEP|OCT|NOV|DEC)[A-Z]*\\s+\\d{4}|\\d{1,2}[/-]\\d{1,2}[/-]\\d{4})\\b"
        ).find(upperText)?.groupValues?.getOrNull(1).orEmpty()

        return mapOf(
            "nid" to nid,
            "nameEn" to nameEn,
            "dob" to dob
        )
    }

    private fun notifyError(slot: String, message: String, callback: String?) {
        if (destroyed) return
        activity.runOnUiThread {
            if (destroyed) return@runOnUiThread
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
            webView.evaluateJavascript(
                "if(window.handleDocumentValidationFailed) { window.handleDocumentValidationFailed(${JSONObject.quote(slot)}, ${JSONObject.quote(message)}); }",
                null
            )
            webView.evaluateJavascript(
                "if(window.onDocumentValidationFailed) { window.onDocumentValidationFailed(${JSONObject.quote(slot)}, ${JSONObject.quote(message)}); }",
                null
            )
            bridge.sendResponseToWeb(
                BridgeResponse(Actions.START_KYC, Status.ERROR, message = message),
                callback
            )
        }
    }

    fun destroy() {
        destroyed = true
        try {
            bgExecutor.shutdownNow()
        } catch (_: Exception) {
        }
    }
}
