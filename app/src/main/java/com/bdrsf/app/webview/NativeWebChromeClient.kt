// File: app/src/main/java/com/bdrsf/app/webview/NativeWebChromeClient.kt
package com.bdrsf.app.webview

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.widget.ProgressBar
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * BDRSF নেটিভ ওয়েব-ক্রোম ক্লায়েন্ট।
 * ওয়েব পেজের ফাইল আপলোড, সিস্টেম ক্যামেরা/গ্যালারি ইনটেন্ট, পারমিশন রিকোয়েস্ট এবং পেজ প্রোগ্রেস পরিচালনা করে।
 */
class NativeWebChromeClient(
    private val activity: Activity,
    private val progressBar: ProgressBar? = null,
    private val onProgressUpdate: ((progress: Int) -> Unit)? = null
) : WebChromeClient() {

    companion object {
        const val FILE_CHOOSER_REQUEST_CODE = 5001
        private const val TAG = "NativeWebChromeClient"
    }

    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraPhotoPath: String? = null

    override fun onProgressChanged(view: WebView?, newProgress: Int) {
        super.onProgressChanged(view, newProgress)

        progressBar?.let {
            if (newProgress < 100) {
                it.visibility = View.VISIBLE
                it.progress = newProgress
            } else {
                it.visibility = View.GONE
            }
        }

        onProgressUpdate?.invoke(newProgress)
    }

    override fun onPermissionRequest(request: PermissionRequest?) {
        if (request == null) return
        activity.runOnUiThread {
            try {
                // ওয়েবপেজের জন্য ক্যামেরা ও মাইক্রোফোন পারমিশন গ্রান্ট
                request.grant(request.resources)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to grant web permissions", e)
            }
        }
    }

    override fun onGeolocationPermissionsShowPrompt(
        origin: String?,
        callback: GeolocationPermissions.Callback?
    ) {
        val hasPermission = ContextCompat.checkSelfPermission(
            activity,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED

        callback?.invoke(origin, hasPermission, false)
    }

    override fun onShowFileChooser(
        webView: WebView?,
        filePathCallback: ValueCallback<Array<Uri>>?,
        fileChooserParams: FileChooserParams?
    ): Boolean {
        // পূর্বের কোনো পেন্ডিং কলব্যাক থাকলে তা বাতিল নিশ্চিত করা
        this.filePathCallback?.onReceiveValue(null)
        this.filePathCallback = filePathCallback

        val intentList = mutableListOf<Intent>()

        // ১. ক্যামেরা ক্যাপচার ইনটেন্ট প্রস্তুতি (যদি ক্যামেরা পারমিশন থাকে)
        if (hasCameraPermission()) {
            val takePictureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
            val photoFile: File? = try {
                createImageFile()
            } catch (ex: IOException) {
                Log.e(TAG, "Unable to create image file for camera capture", ex)
                null
            }

            if (photoFile != null) {
                cameraPhotoPath = photoFile.absolutePath
                val photoUri: Uri = FileProvider.getUriForFile(
                    activity,
                    "${activity.packageName}.fileprovider",
                    photoFile
                )
                takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoUri)
                takePictureIntent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
                intentList.add(takePictureIntent)
            }
        }

        // ২. সিস্টেম ফাইল ও গ্যালারি চুজ ইনটেন্ট প্রস্তুতি
        val contentSelectionIntent = fileChooserParams?.createIntent() ?: Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }

        // ৩. ইউনিভার্সাল চোজার ডায়ালগ
        val chooserIntent = Intent(Intent.ACTION_CHOOSER).apply {
            putExtra(Intent.EXTRA_INTENT, contentSelectionIntent)
            putExtra(Intent.EXTRA_TITLE, "ফাইল বা ছবি সিলেক্ট করুন")
            if (intentList.isNotEmpty()) {
                putExtra(Intent.EXTRA_INITIAL_INTENTS, intentList.toTypedArray())
            }
        }

        return try {
            activity.startActivityForResult(chooserIntent, FILE_CHOOSER_REQUEST_CODE)
            true
        } catch (e: ActivityNotFoundException) {
            this.filePathCallback?.onReceiveValue(null)
            this.filePathCallback = null
            Toast.makeText(activity, "ফাইল ম্যানেজার ওপেন করা সম্ভব হয়নি", Toast.LENGTH_SHORT).show()
            false
        } catch (e: Exception) {
            this.filePathCallback?.onReceiveValue(null)
            this.filePathCallback = null
            false
        }
    }

    /**
     * MainActivity-এর onActivityResult থেকে কল করে ফাইল চুজারের ফলাফল ডেলিভার করা হয়।
     */
    fun handleFileChooserResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode != FILE_CHOOSER_REQUEST_CODE) return

        if (filePathCallback == null) return

        var results: Array<Uri>? = null

        if (resultCode == Activity.RESULT_OK) {
            // ১. ক্যামেরা থেকে ছবি এসেছে কি না চেক
            val photoPath = cameraPhotoPath
            if (photoPath != null) {
                val file = File(photoPath)
                if (file.exists() && file.length() > 0) {
                    results = arrayOf(Uri.fromFile(file))
                }
            }

            // ২. গ্যালারি বা ফাইল থেকে ডাটা এসেছে কি না চেক
            if (results == null && data != null) {
                val dataString = data.dataString
                val clipData = data.clipData

                if (clipData != null) {
                    val count = clipData.itemCount
                    val uriList = ArrayList<Uri>()
                    for (i in 0 until count) {
                        uriList.add(clipData.getItemAt(i).uri)
                    }
                    results = uriList.toTypedArray()
                } else if (dataString != null) {
                    results = arrayOf(Uri.parse(dataString))
                }
            }
        }

        // রেজাল্ট ওয়েবভিউতে পাঠিয়ে কলব্যাক মুক্ত করা
        filePathCallback?.onReceiveValue(results)
        filePathCallback = null
        cameraPhotoPath = null
    }

    @Throws(IOException::class)
    private fun createImageFile(): File {
        val timeStamp: String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val storageDir: File = activity.cacheDir
        return File.createTempFile(
            "UPLOAD_${timeStamp}_",
            ".jpg",
            storageDir
        )
    }

    private fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            activity,
            Manifest.permission.CAMERA
        ) == PackageManager.PERMISSION_GRANTED
    }
}
