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
 * BDRSF ধাপ ৪ লোকাল ডকুমেন্ট প্রসেসর:
 * - বিদ্যুৎ বিল (UTILITY_BILL)
 * - ব্যাংক স্টেটমেন্ট (BANK_STATEMENT)
 * - TIN সার্টিফিকেট (TIN_CERTIFICATE)
 * - ভ্যাট/BIN সার্টিফিকেট (BIN_VAT_CERTIFICATE)
 */
class LocalSupportingDocFeature(
    private val activity: Activity,
    private val bridge: AndroidBridge,
    private val webView: WebView
) {

    companion object {
        const val RC_PICK_LOCAL_DOC_GALLERY = 6014
    }

    private val bgExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    @Volatile
    private var pendingDocType: String = "UTILITY_BILL"

    @Volatile
    private var pendingCallback: String? = null

    @Volatile
    private var destroyed = false

    fun openGalleryPicker(docType: String, callback: String?) {
        if (destroyed) return
        pendingDocType = docType.uppercase(Locale.ROOT).ifBlank { "UTILITY_BILL" }
        pendingCallback = callback
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI).apply {
            type = "image/*"
        }
        try {
            @Suppress("DEPRECATION")
            activity.startActivityForResult(intent, RC_PICK_LOCAL_DOC_GALLERY)
        } catch (e: Exception) {
            pendingCallback = null
            notifyError("গ্যালারি ওপেন করা যায়নি: ${e.localizedMessage ?: ""}", callback)
        }
    }

    fun handleActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean {
        if (requestCode != RC_PICK_LOCAL_DOC_GALLERY) return false
        val docType = pendingDocType
        val callback = pendingCallback
        pendingCallback = null

        if (destroyed) return true
        if (resultCode != Activity.RESULT_OK) {
            bridge.sendResponseToWeb(
                BridgeResponse(Actions.START_KYC, Status.CANCELLED, message = "Local doc cancelled"),
                callback
            )
            return true
        }

        val uri = data?.data ?: run {
            notifyError("লোকাল ডকুমেন্টের ছবি পাওয়া যায়নি", callback)
            return true
        }

        try {
            val tempFile = KycImageUtils.copyUriToTempCacheFile(activity, uri, "BDRSF_LOCAL_DOC_")
            processLocalDocumentFile(tempFile, docType, callback)
        } catch (e: Exception) {
            notifyError("ছবি লোড করা যায়নি: ${e.localizedMessage ?: ""}", callback)
        }
        return true
    }

    fun processLocalDocumentFile(file: File, docType: String, callback: String?) {
        if (destroyed) return
        bgExecutor.execute {
            var bitmap: Bitmap? = null
            try {
                KycImageUtils.fixExifOrientationInPlace(file)
                bitmap = KycImageUtils.decodeSampledBitmap(file, 1600)
                if (bitmap == null || bitmap.isRecycled) {
                    notifyError("লোকাল ডকুমেন্ট প্রসেস করা যায়নি।", callback)
                    return@execute
                }

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
                val category = docType.uppercase(Locale.ROOT)

                val valid = when {
                    category.contains("BANK") || category.contains("STATEMENT") ->
                        upper.contains("BANK") || upper.contains("STATEMENT") || upper.contains("ACCOUNT") || upper.contains("BALANCE")
                    category.contains("TIN") ->
                        upper.contains("TIN") || upper.contains("TAXPAYER") || upper.contains("NATIONAL BOARD OF REVENUE") || upper.contains("NBR")
                    category.contains("BIN") || category.contains("VAT") ->
                        upper.contains("BIN") || upper.contains("VAT") || upper.contains("MUSHAK")
                    else ->
                        upper.contains("DESCO") || upper.contains("DPDC") || upper.contains("NESCO") ||
                            upper.contains("WZPDCL") || upper.contains("BREB") || upper.contains("BPDB") ||
                            upper.contains("BILL") || upper.contains("METER") || upper.contains("KWH") || rawText.length >= 15
                }

                if (!valid) {
                    notifyError("নির্বাচিত লোকাল ডকুমেন্টের সাথে ছবিটি মিলছে না। সঠিক ডকুমেন্ট দিন।", callback)
                    return@execute
                }

                val refNo = Regex("\\b[0-9A-Z\\-]{6,20}\\b").find(upper)?.value.orEmpty()
                val base64 = KycImageUtils.encodeBitmapToDataUri(bitmap, 88)

                val result = JSONObject().apply {
                    put("docVerified", true)
                    put("docCategory", category)
                    put("extractedRefNo", refNo)
                    put("rawText", rawText)
                    put("imageBase64", base64)
                }

                activity.runOnUiThread {
                    if (destroyed) return@runOnUiThread
                    Toast.makeText(activity, "✔ লোকাল ডকুমেন্ট যাচাই সম্পন্ন", Toast.LENGTH_SHORT).show()
                    webView.evaluateJavascript(
                        "if(window.onGenericOcrCompleted) { window.onGenericOcrCompleted(${JSONObject.quote(result.toString())}); }",
                        null
                    )
                    bridge.sendResponseToWeb(
                        BridgeResponse(Actions.START_KYC, Status.SUCCESS, data = result, message = "Local document verified"),
                        callback
                    )
                }
            } catch (e: Exception) {
                notifyError(e.localizedMessage ?: "লোকাল ডকুমেন্ট ত্রুটি", callback)
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
                "if(window.onDocumentValidationFailed) { window.onDocumentValidationFailed('local_doc', ${JSONObject.quote(message)}); }",
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
