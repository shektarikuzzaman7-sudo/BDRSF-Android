// File: app/src/main/java/com/bdrsf/app/features/download/DownloadFeature.kt
package com.bdrsf.app.features.download

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.widget.Toast
import com.bdrsf.app.bridge.AndroidBridge
import com.bdrsf.app.bridge.WebBridgeContract.Actions
import com.bdrsf.app.bridge.WebBridgeContract.BridgeResponse
import com.bdrsf.app.bridge.WebBridgeContract.Status
import org.json.JSONObject

/**
 * BDRSF সার্বজনীন ডাউনলোড ফিচার।
 * ওয়েবভিউ ডিরেক্ট ডাউনলোড এবং bdrsf-bridge.js এর DOWNLOAD_FILE হ্যান্ডল করে।
 */
class DownloadFeature(private val context: Context) {

    fun handleBridgeDownload(payload: JSONObject, callback: String?, bridge: AndroidBridge) {
        val url = payload.optString("url", "").trim()
        val fileName = payload.optString("fileName", "download").trim()
        val mimeType = payload.optString("mimeType", "application/octet-stream").trim()

        if (url.isBlank()) {
            bridge.sendResponseToWeb(
                BridgeResponse(action = Actions.DOWNLOAD_FILE, status = Status.ERROR, message = "ডাউনলোড ইউআরএল পাওয়া যায়নি"),
                callback
            )
            return
        }

        val success = executeDownload(url, null, null, mimeType, fileName)
        if (success) {
            bridge.sendResponseToWeb(
                BridgeResponse(action = Actions.DOWNLOAD_FILE, status = Status.SUCCESS, message = "ডাউনলোড শুরু হয়েছে"),
                callback
            )
        } else {
            bridge.sendResponseToWeb(
                BridgeResponse(action = Actions.DOWNLOAD_FILE, status = Status.ERROR, message = "ডাউনলোড শুরু করা সম্ভব হয়নি"),
                callback
            )
        }
    }

    fun handleWebViewDownload(
        url: String?,
        userAgent: String?,
        contentDisposition: String?,
        mimetype: String?
    ) {
        if (url.isNullOrBlank()) return
        executeDownload(url, userAgent, contentDisposition, mimetype, null)
    }

    private fun executeDownload(
        url: String,
        userAgent: String?,
        contentDisposition: String?,
        mimetype: String?,
        customFileName: String?
    ): Boolean {
        return try {
            val resolvedFileName = if (!customFileName.isNullOrBlank()) {
                if (!customFileName.contains(".")) {
                    val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(mimetype) ?: "pdf"
                    "$customFileName.$ext"
                } else customFileName
            } else {
                URLUtil.guessFileName(url, contentDisposition, mimetype)
            }

            val request = DownloadManager.Request(Uri.parse(url)).apply {
                setMimeType(mimetype ?: "application/octet-stream")
                val cookies = CookieManager.getInstance().getCookie(url)
                if (!cookies.isNullOrBlank()) {
                    addRequestHeader("cookie", cookies)
                }
                if (!userAgent.isNullOrBlank()) {
                    addRequestHeader("User-Agent", userAgent)
                }
                setDescription("ফাইল ডাউনলোড হচ্ছে...")
                setTitle(resolvedFileName)
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, resolvedFileName)
            }

            val downloadManager = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            downloadManager.enqueue(request)
            Toast.makeText(context, "ডাউনলোড শুরু হয়েছে: $resolvedFileName", Toast.LENGTH_SHORT).show()
            true
        } catch (e: Exception) {
            Toast.makeText(context, "ডাউনলোড ব্যর্থ হয়েছে", Toast.LENGTH_SHORT).show()
            false
        }
    }
}
