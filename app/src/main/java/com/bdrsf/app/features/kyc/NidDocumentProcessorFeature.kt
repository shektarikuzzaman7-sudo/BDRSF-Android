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
 * - ডিজিটাল/স্মার্ট NID-এর স্পেসযুক্ত ১০ ডিজিট নম্বর (যেমন: 736 198 4136) এবং পুরাতন ১৩/১৭ ডিজিট নম্বর সঠিকভাবে বের করে।
 * - ব্যাক পার্টের বারকোড/MRZ ডিকোড হলে ফ্রন্ট পার্টের সাথে ক্রস-চেক করে মিলিয়ে নেয় (ভিন্ন কার্ড হলে ব্লক করে)।
 * - পুরাতন ল্যামিনেটেড কার্ডের ব্যাক পার্টে গ্লেয়ার থাকলে Gemini AI দ্বারা ফাইনাল ক্রস-চেক করে।
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
                backRes.errorReason.ifBlank { "এনআইডি ব্যাক পার্ট শনাক্ত করা যায়নি। পরিষ্কার ছবি দিন।" },
                callback
            )
            return
        }

        val backDigits = backRes.nidNumber.replace(Regex("\\D"), "")

        // ব্যাক পার্ট থেকে NID/PIN বা নাম পাওয়া গেলে ফ্রন্ট পার্টের সাথে ক্রস-চেক করে মিলিয়ে নেওয়া
        if (backRes.hasDecodedBarcodeOrMrz && backDigits.length in listOf(10, 13, 17)) {
            val isOnlineSmartToOldPinBridge = (frontDigits.length == 10 && backDigits.length == 17) ||
                (frontDigits.length == 17 && backDigits.length == 10)

            if (!isOnlineSmartToOldPinBridge) {
                if (frontDigits != backDigits) {
                    notifyError(
                        "nid_back",
                        "ভুল কার্ড! ফ্রন্ট পার্টের NID ($frontDigits) ও ব্যাক পার্টের NID ($backDigits) ভিন্ন ব্যক্তির।",
                        callback
                    )
                    return
                }
            } else if (backRes.holderNameEn.length >= 4 && cachedFrontNameEn.length >= 4) {
                if (!areNamesCompatible(cachedFrontNameEn, backRes.holderNameEn)) {
                    notifyError(
                        "nid_back",
                        "ভুল কার্ড! ফ্রন্ট পার্টের নাম (${cachedFrontNameEn}) ও ব্যাক পার্টের নাম (${backRes.holderNameEn}) মিলছে না!",
                        callback
                    )
                    return
                }
            }
        }

        if (backRes.holderNameEn.length >= 4 && cachedFrontNameEn.length >= 4) {
            if (!areNamesCompatible(cachedFrontNameEn, backRes.holderNameEn)) {
                notifyError(
                    "nid_back",
                    "ফ্রন্ট পার্টের নাম (${cachedFrontNameEn}) ও ব্যাক পার্টের নাম (${backRes.holderNameEn}) ভিন্ন ব্যক্তির!",
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
            put("bloodGroup", backRes.bloodGroup)
            put("issueDate", backRes.issueDate)
            put("englishText", backRes.backEnglishText)
            put("nid", frontDigits)
            put("nameEn", effectiveName)
            put("dob", effectiveDob)
        }

        activity.runOnUiThread {
            if (destroyed) return@runOnUiThread
            Toast.makeText(activity, "✔ এনআইডি ব্যাক পার্ট যাচাই সম্পন্ন", Toast.LENGTH_SHORT).show()

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
                BridgeResponse(Actions.START_KYC, Status.SUCCESS, data = metaJson, message = "NID Back Verified"),
                callback
            )
        }
    }

    private fun areNamesCompatible(frontName: String, backName: String): Boolean {
        val ignored = setOf("MD", "MST", "MOSTA", "SHEK", "SHEIKH", "BEGUM", "KHATUN", "AKTER", "MIA", "MIYA", "UDDIN", "ISLAM", "HOSSAIN")
        val fAll = frontName.uppercase(Locale.ROOT).replace(Regex("[^A-Z ]"), " ")
            .split(Regex("\\s+")).filter { it.length >= 3 }.toSet()
        val bAll = backName.uppercase(Locale.ROOT).replace(Regex("[^A-Z ]"), " ")
            .split(Regex("\\s+")).filter { it.length >= 3 }.toSet()
        if (fAll.isEmpty() || bAll.isEmpty()) return true
        if (fAll.intersect(bAll).isNotEmpty()) return true

        val fCore = fAll - ignored
        val bCore = bAll - ignored
        if (fCore.isEmpty() || bCore.isEmpty()) return true
        return fCore.intersect(bCore).isNotEmpty()
    }

    /**
     * ডিজিটাল/স্মার্ট NID-এর স্পেসযুক্ত ১০ ডিজিট (যেমন: 736 198 4136 বা 376 172 1632)
     * এবং পুরাতন NID-এর ১৩/১৭ ডিজিট নম্বর নির্ভুলভাবে এক্সট্রাক্ট করে।
     */
    private fun parseFrontEnglishFields(rawText: String): Map<String, String> {
        val lines = rawText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val upperText = rawText.uppercase(Locale.ROOT)

        // ১) স্পেসসহ ১০ ডিজিট স্মার্ট/ডিজিটাল NID (যেমন: 736 198 4136 বা 376 172 1632)
        val spacedSmartNid = Regex("\\b([0-9]{3}\\s+[0-9]{3}\\s+[0-9]{4})\\b")
            .find(rawText)?.groupValues?.getOrNull(1)?.replace("\\s+".toRegex(), "")

        // ২) NID No / ID NO লেবেলের পাশের যেকোনো স্পেসযুক্ত বা স্পেসবিহীন ১০/১৩/১৭ ডিজিট
        val labelNid = Regex("(?:NID\\s*NO|ID\\s*NO)[^0-9]{0,6}([0-9][0-9\\s]{8,20}[0-9])", RegexOption.IGNORE_CASE)
            .find(rawText)?.groupValues?.getOrNull(1)?.replace("\\s+".toRegex(), "")
            ?.takeIf { it.length in listOf(10, 13, 17) }

        // ৩) স্পেসবিহীন ১০, ১৩ বা ১৭ ডিজিট
        val plainNid = Regex("\\b([0-9]{17}|[0-9]{13}|[0-9]{10})\\b")
            .find(rawText)?.groupValues?.getOrNull(1)

        val nid = spacedSmartNid ?: labelNid ?: plainNid.orEmpty()

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
