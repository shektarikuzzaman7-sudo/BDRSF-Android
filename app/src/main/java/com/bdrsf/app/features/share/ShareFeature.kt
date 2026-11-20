// File: app/src/main/java/com/bdrsf/app/features/share/ShareFeature.kt
package com.bdrsf.app.features.share

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.util.Base64
import android.widget.Toast
import androidx.core.content.FileProvider
import com.bdrsf.app.bridge.AndroidBridge
import com.bdrsf.app.bridge.WebBridgeContract.Actions
import com.bdrsf.app.bridge.WebBridgeContract.BridgeResponse
import com.bdrsf.app.bridge.WebBridgeContract.Status
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * BDRSF সার্বজনীন শেয়ার ফিচার।
 * টেক্সট লিঙ্ক এবং Base64 ইমেজ (QR কার্ড) FileProvider-এর মাধ্যমে সরাসরি শেয়ার করে।
 */
class ShareFeature(private val activity: Activity) {

    fun handleBridgeShare(payload: JSONObject, callback: String?, bridge: AndroidBridge) {
        val title = payload.optString("title", "BDRSF").trim()
        val text = payload.optString("text", "").trim()
        val url = payload.optString("url", "").trim()
        val imageBase64 = payload.optString("imageBase64", payload.optString("base64", "")).trim()
        val fileName = payload.optString("fileName", "BDRSF_QR_${System.currentTimeMillis()}.png").trim()
        val mimeType = payload.optString("mimeType", "image/png").trim()

        if (imageBase64.isNotBlank() || url.startsWith("data:image", ignoreCase = true)) {
            val rawBase64 = imageBase64.ifBlank { url }
            shareBase64Image(title, text, rawBase64, fileName, mimeType, callback, bridge)
            return
        }

        val shareBody = buildString {
            if (text.isNotBlank()) append(text)
            if (url.isNotBlank()) {
                if (isNotEmpty()) append("\n")
                append(url)
            }
        }

        if (shareBody.isBlank()) {
            bridge.sendResponseToWeb(
                BridgeResponse(action = Actions.SHARE_CONTENT, status = Status.ERROR, message = "শেয়ার করার মতো তথ্য নেই"),
                callback
            )
            return
        }

        try {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, title)
                putExtra(Intent.EXTRA_TEXT, shareBody)
            }
            activity.startActivity(Intent.createChooser(shareIntent, title))

            bridge.sendResponseToWeb(
                BridgeResponse(action = Actions.SHARE_CONTENT, status = Status.SUCCESS, message = "শেয়ার ডায়ালগ খোলা হয়েছে"),
                callback
            )
        } catch (e: Exception) {
            Toast.makeText(activity, "শেয়ার করা সম্ভব হয়নি", Toast.LENGTH_SHORT).show()
            bridge.sendResponseToWeb(
                BridgeResponse(action = Actions.SHARE_CONTENT, status = Status.ERROR, message = e.localizedMessage ?: "শেয়ার ত্রুটি"),
                callback
            )
        }
    }

    private fun shareBase64Image(
        title: String,
        text: String,
        dataUrl: String,
        fileName: String,
        mimeType: String,
        callback: String?,
        bridge: AndroidBridge
    ) {
        try {
            val cleanBase64 = if (dataUrl.contains(",")) {
                dataUrl.substringAfter(",")
            } else {
                dataUrl
            }.replace("\\s".toRegex(), "")

            val decodedBytes = Base64.decode(cleanBase64, Base64.DEFAULT)
            if (decodedBytes.isEmpty()) {
                bridge.sendResponseToWeb(
                    BridgeResponse(action = Actions.SHARE_CONTENT, status = Status.ERROR, message = "ইমেজ ডাটা খালি"),
                    callback
                )
                return
            }

            val shareCacheDir = File(activity.cacheDir, "shared_qr_cards").apply {
                if (!exists()) mkdirs()
            }
            // পুরনো টেম্প ফাইল পরিষ্কার রাখা
            shareCacheDir.listFiles()?.forEach { oldFile ->
                if (System.currentTimeMillis() - oldFile.lastModified() > 300_000L) {
                    oldFile.delete()
                }
            }

            val safeFileName = if (fileName.endsWith(".png", ignoreCase = true) || fileName.endsWith(".jpg", ignoreCase = true)) {
                fileName
            } else {
                "$fileName.png"
            }

            val tempImageFile = File(shareCacheDir, safeFileName)
            FileOutputStream(tempImageFile).use { fos ->
                fos.write(decodedBytes)
                fos.flush()
            }

            val contentUri: Uri = FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.fileprovider",
                tempImageFile
            )

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = mimeType.ifBlank { "image/png" }
                putExtra(Intent.EXTRA_STREAM, contentUri)
                putExtra(Intent.EXTRA_SUBJECT, title)
                if (text.isNotBlank()) {
                    putExtra(Intent.EXTRA_TEXT, text)
                }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }

            val chooser = Intent.createChooser(shareIntent, title)
            activity.startActivity(chooser)

            bridge.sendResponseToWeb(
                BridgeResponse(action = Actions.SHARE_CONTENT, status = Status.SUCCESS, message = "শেয়ার ডায়ালগ খোলা হয়েছে"),
                callback
            )
        } catch (e: Exception) {
            Toast.makeText(activity, "ইমেজ শেয়ার করা সম্ভব হয়নি", Toast.LENGTH_SHORT).show()
            bridge.sendResponseToWeb(
                BridgeResponse(action = Actions.SHARE_CONTENT, status = Status.ERROR, message = e.localizedMessage ?: "ইমেজ শেয়ার ত্রুটি"),
                callback
            )
        }
    }
}
