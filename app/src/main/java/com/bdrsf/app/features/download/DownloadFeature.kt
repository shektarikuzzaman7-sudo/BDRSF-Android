// File: app/src/main/java/com/bdrsf/app/features/download/DownloadFeature.kt
package com.bdrsf.app.features.download

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Base64
import android.webkit.CookieManager
import android.webkit.MimeTypeMap
import android.webkit.URLUtil
import android.widget.Toast
import com.bdrsf.app.bridge.AndroidBridge
import com.bdrsf.app.bridge.WebBridgeContract.Actions
import com.bdrsf.app.bridge.WebBridgeContract.BridgeResponse
import com.bdrsf.app.bridge.WebBridgeContract.Status
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream

/**
 * BDRSF সার্বজনীন ডাউনলোড ফিচার।
 * ওয়েবভিউ ডিরেক্ট ডাউনলোড, HTTP ফাইল এবং Base64 ইমেজ (QR কার্ড) গ্যালারি/ডাউনলোডে সেভ করে।
 */
class DownloadFeature(private val context: Context) {

    fun handleBridgeDownload(payload: JSONObject, callback: String?, bridge: AndroidBridge) {
        val url = payload.optString("url", "").trim()
        val base64Input = payload.optString("base64", "").trim()
        val rawSource = url.ifBlank { base64Input }
        val fileName = payload.optString("fileName", "download").trim()
        val mimeType = payload.optString("mimeType", "application/octet-stream").trim()

        if (rawSource.isBlank()) {
            bridge.sendResponseToWeb(
                BridgeResponse(action = Actions.DOWNLOAD_FILE, status = Status.ERROR, message = "ডাউনলোড ডাটা পাওয়া যায়নি"),
                callback
            )
            return
        }

        val success = if (rawSource.startsWith("data:", ignoreCase = true) || base64Input.isNotBlank()) {
            saveBase64ToGalleryAndDownloads(rawSource, fileName, mimeType)
        } else {
            executeDownload(rawSource, null, null, mimeType, fileName)
        }

        if (success) {
            bridge.sendResponseToWeb(
                BridgeResponse(action = Actions.DOWNLOAD_FILE, status = Status.SUCCESS, message = "ফাইল সফলভাবে সেভ হয়েছে"),
                callback
            )
        } else {
            bridge.sendResponseToWeb(
                BridgeResponse(action = Actions.DOWNLOAD_FILE, status = Status.ERROR, message = "ফাইল সেভ করা সম্ভব হয়নি"),
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
        if (url.startsWith("data:", ignoreCase = true)) {
            saveBase64ToGalleryAndDownloads(url, "BDRSF_Card_${System.currentTimeMillis()}.png", mimetype ?: "image/png")
            return
        }
        executeDownload(url, userAgent, contentDisposition, mimetype, null)
    }

    private fun saveBase64ToGalleryAndDownloads(
        dataUrl: String,
        customFileName: String,
        mimeType: String
    ): Boolean {
        return try {
            val resolvedMime = when {
                dataUrl.startsWith("data:", ignoreCase = true) && dataUrl.contains(";") ->
                    dataUrl.substringAfter("data:").substringBefore(";").ifBlank { "image/png" }
                mimeType.isNotBlank() && mimeType != "application/octet-stream" -> mimeType
                else -> "image/png"
            }

            val cleanBase64 = if (dataUrl.contains(",")) {
                dataUrl.substringAfter(",")
            } else {
                dataUrl
            }.replace("\\s".toRegex(), "")

            val decodedBytes = Base64.decode(cleanBase64, Base64.DEFAULT)
            if (decodedBytes.isEmpty()) return false

            val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(resolvedMime) ?: "png"
            val safeName = if (customFileName.contains(".")) {
                customFileName
            } else {
                "${customFileName.ifBlank { "BDRSF_QR_${System.currentTimeMillis()}" }}.$ext"
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, safeName)
                    put(MediaStore.MediaColumns.MIME_TYPE, resolvedMime)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/BDRSF")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }

                val resolver = context.contentResolver
                val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                val itemUri = resolver.insert(collection, values) ?: return false

                resolver.openOutputStream(itemUri)?.use { output ->
                    output.write(decodedBytes)
                    output.flush()
                } ?: return false

                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                resolver.update(itemUri, values, null, null)
            } else {
                @Suppress("DEPRECATION")
                val targetDir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "BDRSF"
                )
                if (!targetDir.exists()) {
                    targetDir.mkdirs()
                }
                val outFile = File(targetDir, safeName)
                FileOutputStream(outFile).use { fos ->
                    fos.write(decodedBytes)
                    fos.flush()
                }
                MediaScannerConnection.scanFile(
                    context,
                    arrayOf(outFile.absolutePath),
                    arrayOf(resolvedMime),
                    null
                )
            }

            Toast.makeText(context, "গ্যালারিতে সেভ হয়েছে: $safeName", Toast.LENGTH_SHORT).show()
            true
        } catch (e: Exception) {
            Toast.makeText(context, "ইমেজ সেভ ব্যর্থ হয়েছে", Toast.LENGTH_SHORT).show()
            false
        }
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
