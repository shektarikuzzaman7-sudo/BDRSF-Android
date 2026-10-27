package com.bdrsf.app.features.download

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.webkit.CookieManager
import android.webkit.URLUtil
import android.widget.Toast
import com.bdrsf.app.bridge.AndroidBridge
import com.bdrsf.app.bridge.WebBridgeContract.Actions
import com.bdrsf.app.bridge.WebBridgeContract.BridgeResponse
import com.bdrsf.app.bridge.WebBridgeContract.Status
import org.json.JSONObject
import java.io.File

/**
 * ওয়েবভিউ ও ব্রিজ থেকে ফাইল ডাউনলোড পরিচালনার জন্য স্বয়ংসম্পূর্ণ ফিচার ক্লাস।
 */
class DownloadFeature(
    private val activity: Activity
) {

    private val downloadManager: DownloadManager by lazy {
        activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
    }

    /**
     * WebView এর setDownloadListener থেকে কল করা হয়
     */
    fun handleWebViewDownload(
        url: String?,
        userAgent: String?,
        contentDisposition: String?,
        mimetype: String?
    ) {
        if (url.isNullOrBlank()) {
            Toast.makeText(activity, "ডাউনলোড লিঙ্ক সঠিক নয়", Toast.LENGTH_SHORT).show()
            return
        }

        try {
            val fileName = URLUtil.guessFileName(url, contentDisposition, mimetype)
            enqueueDownload(url, fileName, userAgent, mimetype)
            Toast.makeText(activity, "ডাউনলোড শুরু হয়েছে: $fileName", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(activity, "ডাউনলোড শুরু করা যায়নি", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * AndroidBridge এর executeFeature("DOWNLOAD_FILE") থেকে কল করা হয়
     */
    fun handleBridgeDownload(
        payload: JSONObject,
        callback: String?,
        bridge: AndroidBridge
    ) {
        val url = payload.optString("url", "")
        val customName = payload.optString("fileName", "")

        if (url.isBlank()) {
            bridge.sendResponseToWeb(
                response = BridgeResponse(
                    action = Actions.DOWNLOAD_FILE,
                    status = Status.ERROR,
                    message = "URL parameter is missing"
                ),
                callbackFunction = callback
            )
            return
        }

        try {
            val fileName = if (customName.isNotBlank()) customName else URLUtil.guessFileName(url, null, null)
            val downloadId = enqueueDownload(url, fileName, null, null)

            val data = JSONObject().apply {
                put("downloadId", downloadId)
                put("fileName", fileName)
            }

            bridge.sendResponseToWeb(
                response = BridgeResponse(
                    action = Actions.DOWNLOAD_FILE,
                    status = Status.SUCCESS,
                    data = data,
                    message = "Download queued successfully"
                ),
                callbackFunction = callback
            )
            Toast.makeText(activity, "ডাউনলোড শুরু হয়েছে: $fileName", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            bridge.sendResponseToWeb(
                response = BridgeResponse(
                    action = Actions.DOWNLOAD_FILE,
                    status = Status.ERROR,
                    message = e.localizedMessage ?: "Download failed"
                ),
                callbackFunction = callback
            )
        }
    }

    private fun enqueueDownload(
        url: String,
        fileName: String,
        userAgent: String?,
        mimeType: String?
    ): Long {
        val request = DownloadManager.Request(Uri.parse(url)).apply {
            setTitle(fileName)
            setDescription("BDRSF ফাইল ডাউনলোড হচ্ছে...")
            setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, fileName)
            setAllowedOverMetered(true)
            setAllowedOverRoaming(true)

            if (!mimeType.isNullOrBlank()) {
                setMimeType(mimeType)
            }

            val cookies = CookieManager.getInstance().getCookie(url)
            if (!cookies.isNullOrEmpty()) {
                addRequestHeader("Cookie", cookies)
            }

            if (!userAgent.isNullOrBlank()) {
                addRequestHeader("User-Agent", userAgent)
            }
        }

        return downloadManager.enqueue(request)
    }
}
