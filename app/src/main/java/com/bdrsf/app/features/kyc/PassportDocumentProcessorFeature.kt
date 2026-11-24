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
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * BDRSF পাসপোর্ট ডকুমেন্ট প্রসেসর:
 * - পাসপোর্টের মূল ছবি অক্ষত রেখে ML Kit দিয়ে পাসপোর্টে থাকা মুখের ছবি আলাদা কপি করে কাটে।
 * - PassportQrAndMrzDecoder ব্যবহার করে QR কোড, MRZ এবং ইংরেজি তথ্য বের করে।
 */
class PassportDocumentProcessorFeature(
    private val activity: Activity,
    private val bridge: AndroidBridge,
    private val webView: WebView,
    private val qrAndMrzDecoder: PassportQrAndMrzDecoder
) {

    companion object {
        const val RC_PICK_PASSPORT_GALLERY = 6012
    }

    private val bgExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    @Volatile
    private var pendingCallback: String? = null

    @Volatile
    private var cachedCroppedFaceBase64: String = ""

    @Volatile
    private var destroyed = false

    fun clearCache() {
        cachedCroppedFaceBase64 = ""
    }

    fun getCachedCroppedFace(): String = cachedCroppedFaceBase64

    fun openGalleryPicker(callback: String?) {
        if (destroyed) return
        pendingCallback = callback
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply {
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

    fun handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != RC_PICK_PASSPORT_GALLERY) return false
        val callback = pendingCallback
        pendingCallback = null

        if (destroyed) return true
        if (resultCode != Activity.RESULT_OK) {
            bridge.sendResponseToWeb(
                BridgeResponse(Actions.START_KYC, Status.CANCELLED, message = "Passport gallery selection cancelled"),
                callback
            )
            return true
        }

        val uri = data?.data
        if (uri == null) {
            notifyError("গ্যালারি থেকে পাসপোর্টের ছবি পাওয়া যায়নি", callback)
            return true
        }

        try {
            val tempFile = KycImageUtils.copyUriToTempCacheFile(activity, uri, "BDRSF_PASSPORT_")
            processPassportFile(tempFile, callback)
        } catch (e: Exception) {
            notifyError("পাসপোর্ট ছবি লোড করা যায়নি: ${e.localizedMessage ?: ""}", callback)
        }
        return true
    }

    fun processPassportFile(file: File, callback: String?) {
        if (destroyed) return
        if (!file.exists() || file.length() <= 0L) {
            notifyError("পাসপোর্টের ছবি পাওয়া যায়নি", callback)
            return
        }

        activity.runOnUiThread {
            webView.evaluateJavascript(
                "if(window.onImageScanningStarted) { window.onImageScanningStarted('passport'); }",
                null
            )
        }

        bgExecutor.execute {
            var bitmap: Bitmap? = null
            try {
                KycImageUtils.fixExifOrientationInPlace(file)
                bitmap = KycImageUtils.decodeSampledBitmap(file, 1600)
                if (bitmap == null || bitmap.isRecycled) {
                    notifyError("পাসপোর্টের ছবি প্রসেস করা যায়নি।", callback)
                    return@execute
                }

                val decoded = qrAndMrzDecoder.decodePassport(bitmap)
                val upper = decoded.rawText.uppercase(Locale.ROOT)
                if (upper.contains("NATIONAL ID") || upper.contains("NID NO")) {
                    notifyError("ভুল ডকুমেন্ট! পাসপোর্টের জায়গায় এনআইডি কার্ড দেওয়া হয়েছে।", callback)
                    return@execute
                }
                if (!decoded.isPassport) {
                    notifyError("পাসপোর্টের তথ্যের পাতা স্পষ্টভাবে শনাক্ত করা যায়নি। পরিষ্কার ছবি দিন।", callback)
                    return@execute
                }

                // মূল পাসপোর্ট ছবি অপরিবর্তিত রাখা
                val fullPassportBase64 = KycImageUtils.encodeBitmapToDataUri(bitmap, 88)

                // পাসপোর্ট থেকে মুখের ছবিটির আলাদা কপি কাটা
                val croppedFaceCopy = KycImageUtils.cropStandalonePortraitCopy(bitmap)
                if (croppedFaceCopy.isNotBlank()) {
                    cachedCroppedFaceBase64 = croppedFaceCopy
                }

                val f = decoded.fields
                val passportNo = f["passportNo"].orEmpty()
                val nameEn = f["nameEn"].orEmpty()
                val dob = f["dob"].orEmpty()

                val metaJson = JSONObject().apply {
                    put("passportNo", passportNo)
                    put("nameEn", nameEn)
                    put("dob", dob)
                    put("qrCodeData", decoded.qrCodeData)
                    put("englishText", decoded.rawText)
                    put("fatherName", f["fatherName"].orEmpty())
                    put("motherName", f["motherName"].orEmpty())
                    put("permanentAddress", f["permanentAddress"].orEmpty())
                    put("issueDate", f["issueDate"].orEmpty())
                    put("expiryDate", f["expiryDate"].orEmpty())
                    put("nationality", f["nationality"].orEmpty())
                    put("countryCode", f["countryCode"].orEmpty())
                    put("faceBase64", cachedCroppedFaceBase64)
                    put("croppedFaceBase64", cachedCroppedFaceBase64)
                }

                activity.runOnUiThread {
                    if (destroyed) return@runOnUiThread
                    Toast.makeText(activity, "✔ পাসপোর্ট যাচাই ও ছবি কপি সম্পন্ন", Toast.LENGTH_SHORT).show()

                    webView.evaluateJavascript(
                        "if(window.handleDocumentPreview) { window.handleDocumentPreview('passport', true, ${JSONObject.quote(fullPassportBase64)}, ${metaJson}); }",
                        null
                    )
                    webView.evaluateJavascript(
                        "if(window.onDocumentValidated) { window.onDocumentValidated('passport', true, ${JSONObject.quote(fullPassportBase64)}, ${metaJson}); }",
                        null
                    )
                    webView.evaluateJavascript(
                        "if(window.handleParsedPassport) { window.handleParsedPassport(${JSONObject.quote(nameEn)}, ${JSONObject.quote(passportNo)}, ${JSONObject.quote(cachedCroppedFaceBase64)}, ${JSONObject.quote(dob)}, ${metaJson}); }",
                        null
                    )

                    bridge.sendResponseToWeb(
                        BridgeResponse(Actions.START_KYC, Status.SUCCESS, data = metaJson, message = "Passport Verified"),
                        callback
                    )
                }
            } catch (e: Exception) {
                notifyError(e.localizedMessage ?: "পাসপোর্ট প্রসেসিং ত্রুটি", callback)
            } finally {
                if (bitmap != null && !bitmap.isRecycled) bitmap.recycle()
                try {
                    file.delete()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun notifyError(message: String, callback: String?) {
        if (destroyed) return
        activity.runOnUiThread {
            if (destroyed) return@runOnUiThread
            Toast.makeText(activity, message, Toast.LENGTH_LONG).show()
            webView.evaluateJavascript(
                "if(window.handleDocumentValidationFailed) { window.handleDocumentValidationFailed('passport', ${JSONObject.quote(message)}); }",
                null
            )
            webView.evaluateJavascript(
                "if(window.onDocumentValidationFailed) { window.onDocumentValidationFailed('passport', ${JSONObject.quote(message)}); }",
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
