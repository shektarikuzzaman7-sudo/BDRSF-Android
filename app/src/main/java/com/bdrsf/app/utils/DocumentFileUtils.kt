// File: app/src/main/java/com/bdrsf/app/utils/DocumentFileUtils.kt
package com.bdrsf.app.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.util.Base64
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/**
 * BDRSF সার্বজনীন ফাইল ও ইমেজ প্রসেসিং ইউটিলিটি।
 * ক্যামেরা ও গ্যালারির ছবির EXIF সংশোধন, সেন্টার ক্রপ, কম্প্রেশন এবং Base64 কনভার্সন সম্পাদন করে।
 */
object DocumentFileUtils {

    /**
     * ক্যাশ ডিরেক্টরিতে টাইমস্ট্যাম্প যুক্ত ইউনিক টেম্পোরারি ফাইল তৈরি করে
     */
    @Throws(IOException::class)
    fun createTempImageFile(context: Context, prefix: String = "DOC"): File {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val fileName = "${prefix}_${timeStamp}_"
        return File.createTempFile(fileName, ".jpg", context.cacheDir)
    }

    /**
     * ছবির EXIF ওরিয়েন্টেশন সোজা করে এবং কার্ড বা পাসপোর্টের রেশিও অনুযায়ী সেন্টার বক্স ক্রপ করে
     */
    fun fixOrientationAndCrop(file: File, isCard: Boolean = true) {
        if (!file.exists() || file.length() == 0L) return

        try {
            val exif = ExifInterface(file.absolutePath)
            val orientation = exif.getAttributeInt(
                ExifInterface.TAG_ORIENTATION,
                ExifInterface.ORIENTATION_NORMAL
            )

            var bitmap = BitmapFactory.decodeFile(file.absolutePath) ?: return

            val rotationDegrees = when (orientation) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> 0
            }

            if (rotationDegrees != 0) {
                val matrix = Matrix().apply { postRotate(rotationDegrees.toFloat()) }
                val rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
                bitmap.recycle()
                bitmap = rotatedBitmap
            }

            val cropWidth = (bitmap.width * 0.90f).toInt()
            val ratio = if (isCard) 1.58f else 1.41f
            val cropHeight = (cropWidth / ratio).toInt().coerceAtMost(bitmap.height)

            val x = ((bitmap.width - cropWidth) / 2).coerceAtLeast(0)
            val y = ((bitmap.height - cropHeight) / 2).coerceAtLeast(0)
            val safeWidth = cropWidth.coerceAtMost(bitmap.width - x)
            val safeHeight = cropHeight.coerceAtMost(bitmap.height - y)

            val croppedBitmap = Bitmap.createBitmap(bitmap, x, y, safeWidth, safeHeight)
            bitmap.recycle()

            FileOutputStream(file).use { outStream ->
                croppedBitmap.compress(Bitmap.CompressFormat.JPEG, 90, outStream)
            }
            croppedBitmap.recycle()
        } catch (_: Exception) {
        }
    }

    /**
     * আউট-অব-মেমোরি (OOM) ক্র্যাশ রোধে নিরাপদ ডাউনস্যাম্পলিং করে Base64 ডাটা স্ট্রিং প্রস্তুত করে
     */
    fun optimizeAndCompressToBase64(
        file: File,
        maxDimension: Int = 1500,
        quality: Int = 85
    ): Pair<Bitmap?, String> {
        try {
            if (!file.exists() || file.length() == 0L) return Pair(null, "")

            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, options)

            val currentMaxDim = max(options.outWidth, options.outHeight)
            var sampleSize = 1
            while (currentMaxDim / sampleSize > maxDimension) {
                sampleSize *= 2
            }

            val decodeOptions = BitmapFactory.Options().apply { inSampleSize = sampleSize }
            val bitmap = BitmapFactory.decodeFile(file.absolutePath, decodeOptions) ?: return Pair(null, "")

            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
            val bytes = outputStream.toByteArray()
            val base64String = "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)

            return Pair(bitmap, base64String)
        } catch (_: Exception) {
            return Pair(null, "")
        }
    }

    /**
     * সরাসরি Bitmap অবজেক্টকে মেমোরি-সেফ উপায়ে Base64 স্ট্রিংয়ে রূপান্তর করে
     */
    fun bitmapToBase64(bitmap: Bitmap, quality: Int = 90): String {
        return try {
            val outputStream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG, quality, outputStream)
            val bytes = outputStream.toByteArray()
            "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (_: Exception) {
            ""
        }
    }
}
