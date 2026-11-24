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
 * - NID Front: মূল ছবি অক্ষত রেখে ML Kit দিয়ে মুখের ছবি আলাদা কপি করে কাটে,
 *   এবং ইংরেজি নাম, জন্ম তারিখ ও ১০/১৩/১৭ ডিজিটের NID নম্বর বের করে।
 * - NID Back: NidBarcodeAndQrDecoder ব্যবহার করে PDF417, MicroPDF417, Digital NID QR
 *   এবং ব্যাক পার্টের FZ নম্বর ও সকল ইংরেজি তথ্য বের করে ফ্রন্ট NID নম্বরের সাথে ক্রস-চেক করে।
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

        val uri = data?.data
        if (uri == null) {
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
                bitmap = KycImageUtils.decodeSampledBitmap(file, 1600)
                if (bitmap == null || bitmap.isRecycled) {
                    notifyError(slot, "এনআইডি ছবি প্রসেস করা যায়নি। পরিষ্কার ছবি দিন।", callback)
                    return@execute
                }

                // মূল ডকুমেন্টের অক্ষত Full Base64
                val fullDocBase64 = KycImageUtils.encodeBitmapToDataUri(bitmap, 88)

                if (slot == "nid_front") {
                    processFrontBitmap(bitmap, fullDocBase64, callback)
                } else {
                    processBackBitmap(bitmap, fullDocBase64, callback)
                }
            } catch (e: Exception) {
                notifyError(slot, e.localizedMessage ?: "NID যাচাই ত্রুটি", callback)
            } finally {
                if (bitmap != null && !bitmap.isRecycled) {
                    bitmap.recycle()
                }
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

        val isNidFront = upper.contains("NATIONAL ID") ||
            upper.contains("NID") ||
            upper.contains("GOVERNMENT OF THE PEOPLE") ||
            upper.contains("DATE OF BIRTH") ||
            Regex("\\b[0-9]{3}\\s*[0-9]{3}\\s*[0-9]{4}\\b").containsMatchIn(rawText) ||
            Regex("\\b([0-9]{10}|[0-9]{13}|[0-9]{17})\\b").containsMatchIn(rawText)

        if (!isNidFront) {
            notifyError("nid_front", "সঠিক এনআইডি কার্ডের ফ্রন্ট পার্ট শনাক্ত করা যায়নি। স্পষ্ট ছবি দিন।", callback)
            return
        }

        // ১. মূল কার্ড পরিবর্তন না করে ML Kit দিয়ে কার্ডের ছবিটির আলাদা কপি কেটে নেওয়া
        val croppedFaceCopy = KycImageUtils.cropStandalonePortraitCopy(bitmap)
        if (croppedFaceCopy.isNotBlank()) {
            cachedCroppedFaceBase64 = croppedFaceCopy
        }

        // ২. ইংরেজি নাম, জন্ম তারিখ ও ১০/১৩/১৭ ডিজিটের NID নম্বর এক্সট্রাক্ট করা
        val extracted = parseFrontEnglishFields(rawText)
        val nidNumber = extracted["nid"].orEmpty()
        val nameEn = extracted["nameEn"].orEmpty()
        val dob = extracted["dob"].orEmpty()

        cachedFrontNidNumber = nidNumber
        cachedFrontNameEn = nameEn
        cachedFrontDob = dob

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
            Toast.makeText(activity, "✔ এনআইডি ফ্রন্ট পার্ট যাচাই সম্পন্ন", Toast.LENGTH_SHORT).show()

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
        val backRes = barcodeDecoder.decodeFullNidBack(bitmap)
        if (!backRes.success) {
            notifyError("nid_back", "এনআইডি ব্যাক পার্টের PDF417/QR বারকোড বা তথ্য পড়া যায়নি। পরিষ্কার ছবি দিন।", callback)
            return
        }

        // ফ্রন্ট পার্টের NID নম্বরের সাথে ব্যাক পার্টের PDF417/QR NID নম্বর ক্রস-ভেরিফিকেশন
        val frontDigits = cachedFrontNidNumber.replace(Regex("\\D"), "")
        val backDigits = backRes.nidNumber.replace(Regex("\\D"), "")
        if (frontDigits.length >= 10 && backDigits.length >= 10) {
            val matched = backDigits.endsWith(frontDigits.takeLast(10)) ||
                frontDigits.endsWith(backDigits.takeLast(10))
            if (!matched) {
                notifyError(
                    "nid_back",
                    "ফ্রন্ট পার্টের NID নম্বর ($frontDigits) ও ব্যাক পার্টের বারকোড NID নম্বর ($backDigits) মিলছে না!",
                    callback
                )
                return
            }
        }

        cachedBackObj = backRes.fullBackJson
        val effectiveNid = frontDigits.ifBlank { backDigits }
        val effectiveName = cachedFrontNameEn.ifBlank { backRes.holderNameEn }
        val effectiveDob = cachedFrontDob.ifBlank { backRes.dob }

        val metaJson = JSONObject().apply {
            put("back_code", backRes.rawBarcodeValue.ifBlank { backRes.fzNumber.ifBlank { effectiveNid } })
            put("codeType", backRes.codeType)
            put("qrCodeData", backRes.qrEnglishData)
            put("fzNumber", backRes.fzNumber)
            put("englishText", backRes.backEnglishText)
            put("nid", effectiveNid)
            put("nameEn", effectiveName)
            put("dob", effectiveDob)
        }

        activity.runOnUiThread {
            if (destroyed) return@runOnUiThread
            Toast.makeText(activity, "✔ এনআইডি ব্যাক পার্ট (${backRes.codeType}) যাচাই সম্পন্ন", Toast.LENGTH_SHORT).show()

            webView.evaluateJavascript(
                "if(window.handleDocumentPreview) { window.handleDocumentPreview('nid_back', true, ${JSONObject.quote(fullDocBase64)}, ${metaJson}); }",
                null
            )
            webView.evaluateJavascript(
                "if(window.onDocumentValidated) { window.onDocumentValidated('nid_back', true, ${JSONObject.quote(fullDocBase64)}, ${metaJson}); }",
                null
            )
            webView.evaluateJavascript(
                "if(window.handleParsedNID) { window.handleParsedNID(${JSONObject.quote(effectiveName)}, '', '', ${JSONObject.quote(effectiveDob)}, ${JSONObject.quote(effectiveNid)}, '', ${JSONObject.quote(cachedCroppedFaceBase64)}, ${cachedBackObj}); }",
                null
            )

            bridge.sendResponseToWeb(
                BridgeResponse(Actions.START_KYC, Status.SUCCESS, data = metaJson, message = "NID Back Verified"),
                callback
            )
        }
    }

    private fun parseFrontEnglishFields(rawText: String): Map<String, String> {
        val lines = rawText.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val upperText = rawText.uppercase(Locale.ROOT)

        // Smart NID-এ স্পেসসহ ১০ ডিজিট (যেমন: 123 456 7890) এবং পুরনো NID-এ ১৩/১৭ ডিজিট
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
