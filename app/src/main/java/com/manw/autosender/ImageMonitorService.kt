package com.manw.autosender

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import androidx.core.app.NotificationCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ImageMonitorService : Service() {
    companion object {
        private const val CHANNEL_ID = "manw_image_monitor"
        private const val COMMAND_CHANNEL_ID = "manw_bot_commands"
        private const val NOTIFICATION_ID = 3107
        private const val CAMERA_NOTIFICATION_ID = 3110
        private const val DEBOUNCE_MS = 800L
        private val botClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(35, TimeUnit.SECONDS)
            .build()
    }

    private val handler = Handler(Looper.getMainLooper())
    private var observer: ContentObserver? = null
    @Volatile private var polling = false
    @Volatile private var destroyed = false

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (destroyed || !SecurePrefs.get(applicationContext).getBoolean("sync_enabled", false)) return
            if (!polling) {
                polling = true
                Thread {
                    try { pollBotCommands() } finally {
                        polling = false
                        if (!destroyed && SecurePrefs.get(applicationContext).getBoolean("sync_enabled", false)) {
                            handler.postDelayed(this, 1000L)
                        }
                    }
                }.start()
            } else {
                handler.postDelayed(this, 1000L)
            }
        }
    }

    private val scanRunnable = Runnable {
        if (SecurePrefs.get(applicationContext).getBoolean("sync_enabled", false)) {
            val request = OneTimeWorkRequestBuilder<ImageSyncWorker>().build()
            WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                ImageSyncWorker.WORK_NAME + "_event",
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        createCommandNotificationChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        registerImageObserver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!SecurePrefs.get(applicationContext).getBoolean("sync_enabled", false)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (observer == null) registerImageObserver()
        handler.removeCallbacks(pollRunnable)
        handler.post(pollRunnable)
        return START_STICKY
    }

    private fun pollBotCommands() {
        val prefs = SecurePrefs.get(applicationContext)
        val token = prefs.getString("bot_token", "") ?: ""
        val allowedChat = prefs.getString("chat_id", "")?.trim().orEmpty()
        if (token.isBlank() || allowedChat.isBlank()) return
        val offset = prefs.getLong("telegram_update_offset", 0L)
        val url = "https://api.telegram.org/bot$token/getUpdates?timeout=20&offset=$offset&allowed_updates=%5B%22message%22%5D"
        val request = Request.Builder().url(url).get().build()
        try {
            botClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) return
                val json = JSONObject(body)
                if (!json.optBoolean("ok")) return
                val updates = json.optJSONArray("result") ?: return
                for (i in 0 until updates.length()) {
                    val update = updates.optJSONObject(i) ?: continue
                    val updateId = update.optLong("update_id", -1L)
                    if (updateId >= 0L) prefs.edit().putLong("telegram_update_offset", updateId + 1L).apply()
                    val message = update.optJSONObject("message") ?: continue
                    val chatId = message.optJSONObject("chat")?.opt("id")?.toString().orEmpty()
                    if (chatId != allowedChat) continue
                    val command = message.optString("text").trim().substringBefore(" ").substringBefore("@")
                    when (command) {
                        "/camera" -> {
                            showCameraRequestNotification()
                            sendBotMessage(token, allowedChat, "وصل طلب فتح الكاميرا. اضغط إشعار MANW على الهاتف لفتح كاميرا الجهاز.")
                        }
                        "/status" -> {
                            val status = prefs.getString("last_result", null)
                                ?: "المراقبة تعمل. لم تُسجل نتيجة إرسال بعد."
                            sendBotMessage(token, allowedChat, status.take(3500))
                        }
                        "/send" -> {
                            val data = androidx.work.Data.Builder()
                                .putBoolean(ImageSyncWorker.KEY_MANUAL_BATCH, true).build()
                            val work = OneTimeWorkRequestBuilder<ImageSyncWorker>().setInputData(data).build()
                            WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                                "manw_bot_manual_send", ExistingWorkPolicy.REPLACE, work
                            )
                            sendBotMessage(token, allowedChat, "بدأ فحص الصور وإعادة إرسال الصور المعلّقة. راجع إشعار الهاتف لمعرفة النتيجة المؤكدة.")
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Keep the foreground monitor alive; the next long-poll retries automatically.
        }
    }

    private fun sendBotMessage(token: String, chatId: String, message: String) {
        val request = Request.Builder()
            .url("https://api.telegram.org/bot$token/sendMessage")
            .post(FormBody.Builder().add("chat_id", chatId).add("text", message).build())
            .build()
        try { botClient.newCall(request).execute().close() } catch (_: Exception) { }
    }

    private fun showCameraRequestNotification() {
        val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        val cameraPendingIntent = PendingIntent.getActivity(
            this, 3110, cameraIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        val notification = NotificationCompat.Builder(this, COMMAND_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("MANW — طلب فتح الكاميرا")
            .setContentText("اضغط هنا لفتح كاميرا الهاتف. لن تُفتح الكاميرا دون ضغطك.")
            .setStyle(NotificationCompat.BigTextStyle().bigText("وصل أمر /camera من بوتك. اضغط الإشعار لفتح تطبيق الكاميرا العادي."))
            .setContentIntent(cameraPendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        try {
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(CAMERA_NOTIFICATION_ID, notification)
        } catch (_: SecurityException) { }
    }

    private fun registerImageObserver() {
        if (observer != null) return
        observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                if (!SecurePrefs.get(applicationContext).getBoolean("sync_enabled", false)) return
                handler.removeCallbacks(scanRunnable)
                handler.postDelayed(scanRunnable, DEBOUNCE_MS)
            }
        }
        contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer!!)
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setContentTitle("MANW Auto Sender يعمل")
            .setContentText("مراقبة الصور والرد على أوامر بوت تيليجرام")
            .setContentIntent(openApp)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "مراقبة الصور والإرسال التلقائي", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createCommandNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(COMMAND_CHANNEL_ID, "أوامر كاميرا تيليجرام", NotificationManager.IMPORTANCE_HIGH)
            channel.description = "تنبيه عند طلب فتح كاميرا الهاتف من بوتك"
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        observer?.let { contentResolver.unregisterContentObserver(it) }
        observer = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
