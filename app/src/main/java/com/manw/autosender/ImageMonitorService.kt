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

class ImageMonitorService : Service() {
    companion object {
        private const val CHANNEL_ID = "manw_image_monitor"
        private const val NOTIFICATION_ID = 3107
        private const val DEBOUNCE_MS = 800L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var observer: ContentObserver? = null
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
        return START_STICKY
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
            .setContentText("مراقبة الصور الجديدة وإرسالها تلقائيًا إلى تيليجرام")
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

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        observer?.let { contentResolver.unregisterContentObserver(it) }
        observer = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}