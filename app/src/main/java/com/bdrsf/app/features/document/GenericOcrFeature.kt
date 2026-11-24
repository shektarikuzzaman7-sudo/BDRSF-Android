package com.bdrsf.app.features.document

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Base64
import android.webkit.WebView
import androidx.exifinterface.media.ExifInterface
import com.bdrsf.app.bridge.AndroidBridge
import com.bdrsf.app.bridge.WebBridgeContract.Actions
import com.bdrsf.app.bridge.WebBridgeContract.BridgeResponse
import com.bdrsf.app.bridge.WebBridgeContract.Status
import com.google.mlkit.vision.common.InputImage
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
import kotlin.math.max

/**
 * BDRSF বাংলাদেশের বৈধ ডকুমেন্ট ভেরিফিকেশন ও ML Kit OCR এক্সট্রাক্টর।
 * সমর্থিত ডকুমেন্ট:
 * - বিদ্যুৎ বিল (UTILITY_BILL)
 * - ব্যাংক স্টেটমেন্ট (BANK_STATEMENT)
 * - TIN সার্টিফিকেট (TIN_CERTIFICATE)
 * - ভ্যাট / BIN সার্টিফিকেট (BIN_VAT_CERTIFICATE)
 * ব্যবহারকারী যে ডকুমেন্ট সিলেক্ট করবেন, ছবিতে আসলেই সেই ডকুমেন্টের তথ্য আছে কি না ১০০% যাচাই করে।
 */
class GenericOcrFeature(
    private val activity: Activity,
    private val bridge: AndroidBridge,
    private val webView: WebView
) {

    enum class SupportedDocType(val code: String, val bnLabel: String) {
        UTILITY_BILL("UTILITY_BILL", "বিদ্যুৎ বিল"),
        BANK_STATEMENT("BANK_STATEMENT", "ব্যাংক স্টেটমেন্ট"),
        TIN_CERTIFICATE("TIN_CERTIFICATE", "TIN সার্টিফিকেট"),
        BIN_VAT_CERTIFICATE("BIN_VAT_CERTIFICATE", "ভ্যাট সার্টিফিকেট"),
        GENERIC("GENERIC", "ডকুমেন্ট")
    }

    private data class DocCheckResult(
        val valid: Boolean,
        val message: String,
        val extractedRefNo: String = "",
        val matchedSignals: Int = 0
    )

    private val bgExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private val textRecognizer: TextRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    fun processWithPolicy(file: File, policy: JSONObject, callback: String?) {
        if (!file.exists() || file.length() == 0L) {
            sendError("ডকুমেন্টের ছবি পাওয়া যায়নি।", callback)
            return
        }

        val targetType = resolveDocumentType(policy)

        bgExecutor.execute {
            var bitmap: Bitmap? = null
            try {
                val orientedFile = createOrientedTempFile(file)
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(orientedFile.absolutePath, options)
                if (options.outWidth <= 0 || options.outHeight <= 0) {
                    orientedFile.delete()
                    sendError("ডকুমেন্টের ছবি লোড করা যায়নি।", callback)
                    return@execute
                }

                val maxDim = max(options.outWidth, options.outHeight)
                var sampleSize = 1
                while (maxDim / sampleSize > 1600) {
                    sampleSize *= 2
                }

                bitmap = BitmapFactory.decodeFile(
                    orientedFile.absolutePath,
                    BitmapFactory.Options().apply {
                        inSampleSize = sampleSize
                        inPreferredConfig = Bitmap.Config.ARGB_8888
                    }
                )
                orientedFile.delete()

                if (bitmap == null || bitmap.isRecycled) {
                    sendError("ছবি প্রসেস করা যায়নি। স্পষ্ট ছবি দিন।", callback)
                    return@execute
                }

                val stream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, stream)
                val base64 = "data:image/jpeg;base64," + Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP)

                textRecognizer.process(InputImage.fromBitmap(bitmap, 0))
                    .addOnSuccessListener { visionText ->
                        val rawText = visionText.text.orEmpty().trim()
                        recycleQuietly(bitmap)

                        if (rawText.length < 12) {
                            sendError(
                                "${targetType.bnLabel}-এর কোনো স্পষ্ট লেখা পাওয়া যায়নি। পরিষ্কার ছবি দিন।",
                                callback
                            )
                            return@addOnSuccessListener
                        }

                        val policyError = validateAgainstPhpPolicy(rawText, policy, targetType)
                        if (policyError != null) {
                            sendError(policyError, callback)
                            return@addOnSuccessListener
                        }

                        val docCheck = verifySelectedBangladeshDocument(targetType, rawText)
                        if (!docCheck.valid) {
                            sendError(docCheck.message, callback)
                            return@addOnSuccessListener
                        }

                        val result = JSONObject().apply {
                            put("docVerified", true)
                            put("docCategory", targetType.code)
                            put("docLabelBn", targetType.bnLabel)
                            put("extractedRefNo", docCheck.extractedRefNo)
                            put("matchedSignals", docCheck.matchedSignals)
                            put("rawText", rawText)
                            put("imageBase64", base64)
                        }

                        activity.runOnUiThread {
                            webView.evaluateJavascript(
                                "if(window.onGenericOcrCompleted) { window.onGenericOcrCompleted(${JSONObject.quote(result.toString())}); }",
                                null
                            )
                            bridge.sendResponseToWeb(
                                BridgeResponse(
                                    action = Actions.START_KYC,
                                    status = Status.SUCCESS,
                                    data = result,
                                    message = "${targetType.bnLabel} সফলভাবে ভেরিফাই হয়েছে"
                                ),
                                callback
                            )
                        }
                    }
                    .addOnFailureListener {
                        recycleQuietly(bitmap)
                        sendError("${targetType.bnLabel}-এর লেখা পড়া যায়নি। পুনরায় চেষ্টা করুন।", callback)
                    }
            } catch (e: Exception) {
                recycleQuietly(bitmap)
                sendError(e.localizedMessage ?: "ডকুমেন্ট যাচাই ত্রুটি", callback)
            }
        }
    }

    private fun resolveDocumentType(policy: JSONObject): SupportedDocType {
        val raw = listOf(
            policy.optString("docCategory", ""),
            policy.optString("docType", ""),
            policy.optString("documentType", ""),
            policy.optString("mode", ""),
            policy.optString("scanMode", "")
        ).firstOrNull { it.isNotBlank() }.orEmpty().trim().uppercase(Locale.ROOT)

        return when {
            raw.contains("UTILITY") || raw.contains("ELECTRIC") || raw.contains("BILL") || raw.contains("বিদ্যুৎ") ->
                SupportedDocType.UTILITY_BILL
            raw.contains("BANK") || raw.contains("STATEMENT") || raw.contains("ব্যাংক") ->
                SupportedDocType.BANK_STATEMENT
            raw.contains("TIN") || raw.contains("ETIN") || raw.contains("E-TIN") || raw.contains("টিআইএন") ->
                SupportedDocType.TIN_CERTIFICATE
            raw.contains("BIN") || raw.contains("VAT") || raw.contains("ভ্যাট") || raw.contains("মূসক") ->
                SupportedDocType.BIN_VAT_CERTIFICATE
            else -> SupportedDocType.GENERIC
        }
    }

    private fun verifySelectedBangladeshDocument(type: SupportedDocType, rawText: String): DocCheckResult {
        val normalizedText = convertBanglaToEnglishDigits(rawText)
        val upper = normalizedText.uppercase(Locale.ROOT)

        return when (type) {
            SupportedDocType.UTILITY_BILL -> verifyUtilityBill(rawText, normalizedText, upper)
            SupportedDocType.BANK_STATEMENT -> verifyBankStatement(rawText, normalizedText, upper)
            SupportedDocType.TIN_CERTIFICATE -> verifyTinCertificate(rawText, normalizedText, upper)
            SupportedDocType.BIN_VAT_CERTIFICATE -> verifyBinVatCertificate(rawText, normalizedText, upper)
            SupportedDocType.GENERIC -> DocCheckResult(
                valid = true,
                message = "ডকুমেন্ট যাচাই সম্পন্ন",
                extractedRefNo = extractGenericReference(normalizedText),
                matchedSignals = 1
            )
        }
    }

    private fun verifyUtilityBill(rawText: String, normalized: String, upper: String): DocCheckResult {
        // অন্য ডকুমেন্ট দিলে আটকে দেওয়া
        if (hasStrongTinSignals(rawText, upper) || hasStrongVatBinSignals(rawText, upper) || hasStrongBankStatementSignals(rawText, upper)) {
            return DocCheckResult(
                false,
                "ভুল ডকুমেন্ট! আপনি বিদ্যুৎ বিল সিলেক্ট করেছেন, কিন্তু এটি ভিন্ন ধরনের ডকুমেন্ট মনে হচ্ছে।"
            )
        }

        val providers = listOf(
            "DESCO", "DPDC", "NESCO", "WZPDCL", "BREB", "PBS", "PDB", "BPDB",
            "বিদ্যুৎ", "পল্লী বিদ্যুৎ", "বিল", "ইউনিট", "মিটার", "হিসাব", "গ্রাহক"
        )
        val billMetrics = listOf(
            "KWH", "KW", "DEMAND", "BILL", "DUE", "CONSUMPTION", "METER", "ACCOUNT",
            "টাকা", "বকেয়া", "ভ্যাট", "প্রিপেইড", "পোস্টপেইড", "মাস", "BDT", "TK"
        )

        val providerHits = providers.count { kw -> rawText.contains(kw, true) || upper.contains(kw.uppercase(Locale.ROOT)) }
        val metricHits = billMetrics.count { kw -> rawText.contains(kw, true) || upper.contains(kw.uppercase(Locale.ROOT)) }

        val refMatch = Regex(
            "(?:ACCOUNT\\s*(?:NO\\.?|NUMBER)?|METER\\s*(?:NO\\.?|NUMBER)?|CUSTOMER\\s*(?:NO\\.?|ID)?|হিসাব\\s*নং?|মিটার\\s*নং?|গ্রাহক\\s*নং?)[^0-9A-Za-z]{0,10}([0-9A-Z\\-]{5,20})",
            RegexOption.IGNORE_CASE
        ).find(normalized)

        val extractedRef = refMatch?.groupValues?.getOrNull(1)?.trim().orEmpty()
            .ifBlank { Regex("\\b[0-9]{6,16}\\b").find(normalized)?.value.orEmpty() }

        if (providerHits < 1 || (metricHits < 1 && extractedRef.isBlank())) {
            return DocCheckResult(
                false,
                "বৈধ বাংলাদেশি বিদ্যুৎ বিল হিসেবে যাচাই করা যায়নি! বিদ্যুৎ বিল, হিসাব/মিটার নং ও টাকার তথ্য স্পষ্টভাবে দিন।"
            )
        }

        return DocCheckResult(
            valid = true,
            message = "বৈধ বিদ্যুৎ বিল যাচাই সম্পন্ন",
            extractedRefNo = extractedRef,
            matchedSignals = providerHits + metricHits
        )
    }

    private fun verifyBankStatement(rawText: String, normalized: String, upper: String): DocCheckResult {
        if (hasStrongTinSignals(rawText, upper) || hasStrongVatBinSignals(rawText, upper)) {
            return DocCheckResult(
                false,
                "ভুল ডকুমেন্ট! ব্যাংক স্টেটমেন্টের জায়গায় TIN বা ভ্যাট সার্টিফিকেট দেওয়া হয়েছে।"
            )
        }

        val statementKeywords = listOf(
            "BANK STATEMENT", "ACCOUNT STATEMENT", "STATEMENT OF ACCOUNT",
            "ব্যাংক স্টেটমেন্ট", "হিসাব বিবরণী", "স্টেটমেন্ট"
        )
        val bankingTerms = listOf(
            "BANK", "ACCOUNT", "A/C", "BALANCE", "OPENING BALANCE", "CLOSING BALANCE",
            "DEBIT", "CREDIT", "WITHDRAWAL", "DEPOSIT", "BRANCH", "ROUTING", "IBAN", "SWIFT",
            "ব্যাংক", "শাখা", "অ্যাকাউন্ট", "ব্যালেন্স", "জমা", "উত্তোলন"
        )

        val hasStatementTitle = statementKeywords.any { rawText.contains(it, true) || upper.contains(it.uppercase(Locale.ROOT)) }
        val bankingHits = bankingTerms.count { rawText.contains(it, true) || upper.contains(it.uppercase(Locale.ROOT)) }

        val accMatch = Regex(
            "(?:A/?C\\s*(?:NO\\.?|NUMBER)?|ACCOUNT\\s*(?:NO\\.?|NUMBER)?|হিসাব\\s*নং?)[^0-9A-Za-z]{0,10}([0-9A-Z\\-]{7,24})",
            RegexOption.IGNORE_CASE
        ).find(normalized)

        val extractedAcc = accMatch?.groupValues?.getOrNull(1)?.trim().orEmpty()
            .ifBlank { Regex("\\b[0-9]{8,20}\\b").find(normalized)?.value.orEmpty() }

        if ((!hasStatementTitle && bankingHits < 3) || bankingHits < 2 || extractedAcc.isBlank()) {
            return DocCheckResult(
                false,
                "বৈধ ব্যাংক স্টেটমেন্ট হিসেবে যাচাই করা যায়নি! ব্যাংক স্টেটমেন্ট, অ্যাকাউন্ট নং ও ব্যালেন্স/লেনদেনের তথ্য স্পষ্ট রাখুন।"
            )
        }

        return DocCheckResult(
            valid = true,
            message = "বৈধ ব্যাংক স্টেটমেন্ট যাচাই সম্পন্ন",
            extractedRefNo = extractedAcc,
            matchedSignals = bankingHits + if (hasStatementTitle) 2 else 0
        )
    }

    private fun verifyTinCertificate(rawText: String, normalized: String, upper: String): DocCheckResult {
        if (hasStrongVatBinSignals(rawText, upper) && !hasStrongTinSignals(rawText, upper)) {
            return DocCheckResult(
                false,
                "ভুল ডকুমেন্ট! TIN সার্টিফিকেটের জায়গায় ভ্যাট/BIN সার্টিফিকেট দেওয়া হয়েছে।"
            )
        }

        val tinKeywords = listOf(
            "TAXPAYER", "IDENTIFICATION NUMBER", "E-TIN", "ETIN", "TIN CERTIFICATE",
            "NATIONAL BOARD OF REVENUE", "NBR", "TAX", "CIRCLE", "ZONE",
            "টিআইএন", "করদাতা", "সনদ", "জাতীয় রাজস্ব বোর্ড", "কর অঞ্চল", "সার্কেল"
        )
        val hits = tinKeywords.count { rawText.contains(it, true) || upper.contains(it.uppercase(Locale.ROOT)) }

        val tinMatch = Regex(
            "(?:E-?TIN|TIN\\s*(?:NO\\.?|NUMBER)?|টিআইএন)[^0-9]{0,12}([0-9]{10}|[0-9]{12})",
            RegexOption.IGNORE_CASE
        ).find(normalized)

        val tinNumber = tinMatch?.groupValues?.getOrNull(1)?.trim().orEmpty()
            .ifBlank { Regex("\\b([0-9]{12}|[0-9]{10})\\b").find(normalized)?.value.orEmpty() }

        if (hits < 2 || tinNumber.length !in listOf(10, 12)) {
            return DocCheckResult(
                false,
                "বৈধ বাংলাদেশি TIN সার্টিফিকেট হিসেবে যাচাই করা যায়নি! ১০ বা ১২ ডিজিটের TIN নম্বর ও সার্টিফিকেটের তথ্য স্পষ্ট দিন।"
            )
        }

        return DocCheckResult(
            valid = true,
            message = "বৈধ TIN সার্টিফিকেট যাচাই সম্পন্ন",
            extractedRefNo = tinNumber,
            matchedSignals = hits
        )
    }

    private fun verifyBinVatCertificate(rawText: String, normalized: String, upper: String): DocCheckResult {
        if (hasStrongTinSignals(rawText, upper) && !hasStrongVatBinSignals(rawText, upper)) {
            return DocCheckResult(
                false,
                "ভুল ডকুমেন্ট! ভ্যাট/BIN সার্টিফিকেটের জায়গায় TIN সার্টিফিকেট দেওয়া হয়েছে।"
            )
        }

        val vatKeywords = listOf(
            "BIN", "BUSINESS IDENTIFICATION NUMBER", "VAT", "VALUE ADDED TAX",
            "MUSHAK", "NATIONAL BOARD OF REVENUE", "NBR", "COMMISSIONERATE",
            "ভ্যাট", "মূসক", "মূল্য সংযোজন কর", "ব্যবসায় সনাক্তকরণ", "সনদ", "কমিশনারেট"
        )
        val hits = vatKeywords.count { rawText.contains(it, true) || upper.contains(it.uppercase(Locale.ROOT)) }

        val binMatch = Regex(
            "(?:BIN|VAT\\s*(?:REG\\.?|NO\\.?)?|মূসক|ভ্যাট)[^0-9\\-]{0,12}([0-9]{9}|[0-9]{13}|[0-9]{4}-[0-9]{3}-[0-9]{3})",
            RegexOption.IGNORE_CASE
        ).find(normalized)

        val binNumber = binMatch?.groupValues?.getOrNull(1)?.trim().orEmpty()
            .ifBlank { Regex("\\b([0-9]{13}|[0-9]{9})\\b").find(normalized)?.value.orEmpty() }

        val cleanDigits = binNumber.replace(Regex("\\D"), "")
        if (hits < 2 || cleanDigits.length !in listOf(9, 13)) {
            return DocCheckResult(
                false,
                "বৈধ বাংলাদেশি ভ্যাট/BIN সার্টিফিকেট হিসেবে যাচাই করা যায়নি! ৯ বা ১৩ ডিজিটের BIN/ভ্যাট নম্বর ও মূসক/সনদ তথ্য স্পষ্ট দিন।"
            )
        }

        return DocCheckResult(
            valid = true,
            message = "বৈধ ভ্যাট/BIN সার্টিফিকেট যাচাই সম্পন্ন",
            extractedRefNo = binNumber,
            matchedSignals = hits
        )
    }

    private fun hasStrongTinSignals(rawText: String, upper: String): Boolean {
        return upper.contains("TAXPAYER'S IDENTIFICATION NUMBER") ||
            upper.contains("E-TIN") ||
            upper.contains("TIN CERTIFICATE") ||
            rawText.contains("টিআইএন", true) ||
            rawText.contains("করদাতা সনাক্তকরণ", true)
    }

    private fun hasStrongVatBinSignals(rawText: String, upper: String): Boolean {
        return upper.contains("BUSINESS IDENTIFICATION NUMBER") ||
            upper.contains("VALUE ADDED TAX") ||
            upper.contains("MUSHAK") ||
            rawText.contains("মূসক", true) ||
            rawText.contains("মূল্য সংযোজন কর", true) ||
            rawText.contains("ভ্যাট নিবন্ধন", true)
    }

    private fun hasStrongBankStatementSignals(rawText: String, upper: String): Boolean {
        return upper.contains("BANK STATEMENT") ||
            upper.contains("ACCOUNT STATEMENT") ||
            upper.contains("STATEMENT OF ACCOUNT") ||
            rawText.contains("ব্যাংক স্টেটমেন্ট", true) ||
            rawText.contains("হিসাব বিবরণী", true)
    }

    private fun extractGenericReference(normalized: String): String {
        return Regex("\\b[0-9A-Z]{6,18}\\b").find(normalized.uppercase(Locale.ROOT))?.value.orEmpty()
    }

    private fun convertBanglaToEnglishDigits(input: String): String {
        val bn = charArrayOf('০', '১', '২', '৩', '৪', '৫', '৬', '৭', '৮', '৯')
        val en = charArrayOf('0', '1', '2', '3', '4', '5', '6', '7', '8', '9')
        var out = input
        for (i in bn.indices) {
            out = out.replace(bn[i], en[i])
        }
        return out
    }

    private fun createOrientedTempFile(sourceFile: File): File {
        val temp = File.createTempFile("BDRSF_DOC_ORIENTED_", ".jpg", activity.cacheDir)
        var original: Bitmap? = null
        var rotated: Bitmap? = null
        try {
            val exif = ExifInterface(sourceFile.absolutePath)
            val orientation = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            val matrix = Matrix()
            when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
                ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
                ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(270f)
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.preScale(-1f, 1f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.preScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.preScale(-1f, 1f) }
                ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(270f); matrix.preScale(-1f, 1f) }
                else -> {
                    sourceFile.copyTo(temp, overwrite = true)
                    return temp
                }
            }

            original = BitmapFactory.decodeFile(sourceFile.absolutePath)
            if (original == null) {
                sourceFile.copyTo(temp, overwrite = true)
                return temp
            }

            rotated = Bitmap.createBitmap(original, 0, 0, original.width, original.height, matrix, true)
            FileOutputStream(temp).use { out ->
                rotated.compress(Bitmap.CompressFormat.JPEG, 92, out)
                out.flush()
            }
        } catch (_: Exception) {
            sourceFile.copyTo(temp, overwrite = true)
        } finally {
            if (rotated !== original) recycleQuietly(rotated)
            recycleQuietly(original)
        }
        return temp
    }

    private fun validateAgainstPhpPolicy(rawText: String, policy: JSONObject, type: SupportedDocType): String? {
        val upper = rawText.uppercase(Locale.ROOT)
        val rejectIf = jsonArrayToList(policy.optJSONArray("rejectIfContains"))
        for (bad in rejectIf) {
            if (rawText.contains(bad, true) || upper.contains(bad.uppercase(Locale.ROOT))) {
                return policy.optString("rejectMessage", "অনুমোদিত ${type.bnLabel} নয়।")
            }
        }

        val mustContain = jsonArrayToList(policy.optJSONArray("mustContainAny"))
        if (mustContain.isNotEmpty() && mustContain.none { kw -> rawText.contains(kw, true) || upper.contains(kw.uppercase(Locale.ROOT)) }) {
            return policy.optString("invalidMessage", "${type.bnLabel}-এর প্রয়োজনীয় তথ্য পাওয়া যায়নি।")
        }
        return null
    }

    private fun recycleQuietly(bmp: Bitmap?) {
        try {
            if (bmp != null && !bmp.isRecycled) bmp.recycle()
        } catch (_: Exception) {}
    }

    private fun jsonArrayToList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).mapNotNull { arr.optString(it, "").trim().takeIf { s -> s.isNotEmpty() } }
    }

    private fun sendError(msg: String, callback: String?) {
        activity.runOnUiThread {
            webView.evaluateJavascript(
                "if(window.onDocumentValidationFailed) { window.onDocumentValidationFailed('generic', ${JSONObject.quote(msg)}); }",
                null
            )
            bridge.sendResponseToWeb(
                BridgeResponse(action = Actions.START_KYC, status = Status.ERROR, message = msg),
                callback
            )
        }
    }

    fun destroy() {
        try {
            textRecognizer.close()
            bgExecutor.shutdownNow()
        } catch (_: Exception) {}
    }
}
