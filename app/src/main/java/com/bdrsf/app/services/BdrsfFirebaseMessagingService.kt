package com.bdrsf.app.services

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat
import com.bdrsf.app.MainActivity
import com.bdrsf.app.R
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import me.leolin.shortcutbadger.ShortcutBadger
import org.json.JSONObject

class BdrsfFirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        const val CHANNEL_ID = "bdrsf_live_alerts"
        private const val PREFS_NAME = "bdrsf_fcm_prefs"
        private const val KEY_FCM_TOKEN = "fcm_token"
        private const val KEY_UNREAD_COUNT = "unread_notif_count"

        fun getSavedToken(context: Context): String? {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getString(KEY_FCM_TOKEN, null)?.takeIf { it.isNotBlank() }
        }

        fun saveTokenLocally(context: Context, token: String) {
            if (token.isBlank()) return
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_FCM_TOKEN, token).apply()
        }

        fun applyLauncherBadge(context: Context, count: Int) {
            val safeCount = count.coerceAtLeast(0)
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putInt(KEY_UNREAD_COUNT, safeCount).apply()

            try {
                if (safeCount > 0) {
                    ShortcutBadger.applyCount(context.applicationContext, safeCount)
                } else {
                    ShortcutBadger.removeCount(context.applicationContext)
                }
            } catch (_: Exception) {}
        }

        fun getUnreadCount(context: Context): Int {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            return prefs.getInt(KEY_UNREAD_COUNT, 0)
        }

        fun isAppInForeground(context: Context): Boolean {
            return try {
                val activityManager =
                    context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
                        ?: return false
                val processes = activityManager.runningAppProcesses ?: return false
                processes.any {
                    it.processName == context.packageName &&
                        it.importance == ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND
                }
            } catch (_: Exception) {
                false
            }
        }
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        if (token.isBlank()) return

        saveTokenLocally(applicationContext, token)

        val broadcastIntent = Intent("com.bdrsf.app.ACTION_NEW_FCM_TOKEN").apply {
            setPackage(applicationContext.packageName)
            putExtra("fcm_token", token)
        }
        applicationContext.sendBroadcast(broadcastIntent)
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        val dataPayload = remoteMessage.data
        val title = remoteMessage.notification?.title
            ?: dataPayload["title"]
            ?: "BDRSF Alert"

        val body = remoteMessage.notification?.body
            ?: dataPayload["message"]
            ?: dataPayload["body"]
            ?: ""

        val pushType = dataPayload["type"] ?: "info"
        val targetUrl = dataPayload["url"]
            ?: dataPayload["link_url"]
            ?: ""

        val serverUnread = dataPayload["unread_count"]?.toIntOrNull()
        val updatedUnread = if (serverUnread != null) {
            serverUnread.coerceAtLeast(0)
        } else {
            (getUnreadCount(applicationContext) + 1)
        }
        applyLauncherBadge(applicationContext, updatedUnread)

        if (isAppInForeground(applicationContext)) {
            val liveIntent = Intent("com.bdrsf.app.ACTION_LIVE_PUSH_EVENT").apply {
                setPackage(applicationContext.packageName)
                putExtra("title", title)
                putExtra("message", body)
                putExtra("type", pushType)
                putExtra("unread_count", updatedUnread)
            }
            applicationContext.sendBroadcast(liveIntent)
            return
        }

        showSystemNotification(title, body, targetUrl, updatedUnread)
    }

    private fun showSystemNotification(title: String, message: String, targetUrl: String, badgeCount: Int) {
        val notificationManager =
            getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "BDRSF Live Alerts",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Transaction, balance update & system notifications"
                enableVibration(true)
                setShowBadge(true)
            }
            notificationManager.createNotificationChannel(channel)
        }

        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (targetUrl.isNotBlank()) {
                putExtra("fcm_target_url", targetUrl)
            }
        } ?: Intent(this, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addCategory(Intent.CATEGORY_LAUNCHER)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or
                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (targetUrl.isNotBlank()) {
                putExtra("fcm_target_url", targetUrl)
            }
        }

        val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val requestCode = (System.currentTimeMillis() % Int.MAX_VALUE).toInt()
        val pendingIntent = PendingIntent.getActivity(
            this,
            requestCode,
            launchIntent,
            pendingIntentFlags
        )

        val defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        val safeBadge = badgeCount.coerceAtLeast(1)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setAutoCancel(true)
            .setNumber(safeBadge)
            .setBadgeIconType(NotificationCompat.BADGE_ICON_SMALL)
            .setSound(defaultSoundUri)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setContentIntent(pendingIntent)

        notificationManager.notify(requestCode, builder.build())
        applyLauncherBadge(applicationContext, safeBadge)
    }

    fun buildWebAlertPayload(title: String, message: String, type: String): String {
        return JSONObject().apply {
            put("title", title)
            put("message", message)
            put("type", type)
        }.toString()
    }
}
