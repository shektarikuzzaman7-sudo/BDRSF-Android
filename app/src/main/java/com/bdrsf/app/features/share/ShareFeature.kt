package com.bdrsf.app.features.share

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.core.content.FileProvider
import com.bdrsf.app.bridge.AndroidBridge
import com.bdrsf.app.bridge.WebBridgeContract.Actions
import com.bdrsf.app.bridge.WebBridgeContract.BridgeResponse
import com.bdrsf.app.bridge.WebBridgeContract.Status
import org.json.JSONObject
import java.io.File

/**
 * নেটিভ সোশ্যাল শেয়ারিং (টেক্সট, ইনভয়েস, ইউআরএল বা ফাইল) পরিচালনার জন্য ফিচার ক্লাস।
 */
class ShareFeature(
    private val activity: Activity
) {

    /**
     * AndroidBridge থেকে কল করা হয়
     */
    fun handleBridgeShare(
        payload: JSONObject,
        callback: String?,
        bridge: AndroidBridge
    ) {
        val text = payload.optString("text", "")
        val url = payload.optString("url", "")
        val filePath = payload.optString("filePath", "")
        val chooserTitle = payload.optString("title", "শেয়ার করুন")

        val combinedText = buildString {
            if (text.isNotBlank()) append(text)
            if (url.isNotBlank()) {
                if (isNotEmpty()) append("\n")
                append(url)
            }
        }

        try {
            if (filePath.isNotBlank()) {
                val file = File(filePath)
                if (file.exists()) {
                    shareFileWithText(file, combinedText, chooserTitle)
                } else {
                    sharePlainText(combinedText, chooserTitle)
                }
            } else if (combinedText.isNotBlank()) {
                sharePlainText(combinedText, chooserTitle)
            } else {
                bridge.sendResponseToWeb(
                    response = BridgeResponse(
                        action = Actions.SHARE_CONTENT,
                        status = Status.ERROR,
                        message = "No content provided to share"
                    ),
                    callbackFunction = callback
                )
                return
            }

            bridge.sendResponseToWeb(
                response = BridgeResponse(
                    action = Actions.SHARE_CONTENT,
                    status = Status.SUCCESS,
                    message = "Share dialog opened"
                ),
                callbackFunction = callback
            )
        } catch (e: Exception) {
            bridge.sendResponseToWeb(
                response = BridgeResponse(
                    action = Actions.SHARE_CONTENT,
                    status = Status.ERROR,
                    message = e.localizedMessage ?: "Failed to open share dialog"
                ),
                callbackFunction = callback
            )
        }
    }

    fun sharePlainText(text: String, title: String = "শেয়ার করুন") {
        if (text.isBlank()) return
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        val chooser = Intent.createChooser(intent, title)
        activity.startActivity(chooser)
    }

    fun shareFileWithText(file: File, text: String?, title: String = "ফাইল শেয়ার করুন") {
        val fileUri: Uri = try {
            FileProvider.getUriForFile(
                activity,
                "${activity.packageName}.fileprovider",
                file
            )
        } catch (e: Exception) {
            Toast.makeText(activity, "ফাইল অ্যাক্সেস করা সম্ভব হয়নি", Toast.LENGTH_SHORT).show()
            return
        }

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = activity.contentResolver.getType(fileUri) ?: "*/*"
            putExtra(Intent.EXTRA_STREAM, fileUri)
            if (!text.isNullOrBlank()) {
                putExtra(Intent.EXTRA_TEXT, text)
            }
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        val chooser = Intent.createChooser(intent, title)
        activity.startActivity(chooser)
    }
}
