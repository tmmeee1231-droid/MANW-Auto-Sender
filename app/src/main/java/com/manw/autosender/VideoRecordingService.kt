package com.manw.autosender

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.camera.core.CameraSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.FormBody
import java.io.File
import java.util.concurrent.TimeUnit

/** Video capture starts only after the visible VideoConsentActivity approval. No microphone/audio. */
class VideoRecordingService : LifecycleService() {
    companion object {
        const val ACTION_START = "com.manw.autosender.VIDEO_START"
        const val ACTION_STOP = "com.manw.autosender.VIDEO_STOP"
        const val EXTRA_DURATION = "duration_seconds"
        private const val CHANNEL = "manw_video_recording"
        private const val NOTIFICATION_ID = 3120
    }
    private val handler = Handler(mainLooper)
    private var recording: Recording? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var durationSeconds = 30
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .writeTimeout(240, TimeUnit.SECONDS)
        .callTimeout(300, TimeUnit.SECONDS)
        .build()

    private val stopReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_STOP) stopRecording()
        }
    }

    override fun onCreate() {
        super.onCreate()
        val stopFilter = IntentFilter(ACTION_STOP)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(stopReceiver, stopFilter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(stopReceiver, stopFilter)
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "تسجيل فيديو MANW", NotificationManager.IMPORTANCE_LOW)
        )
        val stopIntent = Intent(this, VideoRecordingService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(this, 3121, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("MANW — تسجيل فيديو")
            .setContentText("التسجيل ظاهر ونشط بعد موافقتك. اضغط إيقاف لإنهائه.")
            .setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "إيقاف", stopPending)
            .build()
        if (Build.VERSION.SDK_INT >= 29) ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        ) else startForeground(NOTIFICATION_ID, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_STOP -> stopRecording()
            ACTION_START -> {
                durationSeconds = intent.getIntExtra(EXTRA_DURATION, 30).coerceIn(5, 300)
                if (recording == null) beginRecording()
            }
        }
        return START_NOT_STICKY
    }

    private fun beginRecording() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            sendBot("لم يبدأ الفيديو: إذن الكاميرا غير ممنوح.")
            stopSelf(); return
        }
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val provider = providerFuture.get()
                if (SecurePrefs.get(this).getBoolean("camera_active", false)) {
                    sendBot("أوقف جلسة الكاميرا الحالية أولاً باستخدام /stopcamera، ثم أرسل /video_start مرة أخرى.")
                    stopSelf()
                    return@addListener
                }
                cameraProvider = provider
                val recorder = Recorder.Builder()
                    .setQualitySelector(QualitySelector.from(Quality.HD))
                    .build()
                val videoCapture = VideoCapture.withOutput(recorder)
                // Do not tear down unrelated CameraX use cases owned by other MANW features.
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, videoCapture)
                val output = File(cacheDir, "MANW_VIDEO_${System.currentTimeMillis()}.mp4")
                val pending = recorder.prepareRecording(this, FileOutputOptions.Builder(output).build())
                recording = pending.start(ContextCompat.getMainExecutor(this)) { event ->
                    if (event is VideoRecordEvent.Finalize) {
                        recording = null
                        handler.removeCallbacks(stopRunnable)
                        SecurePrefs.get(this).edit().putBoolean("video_recording", false).apply()
                        if (event.hasError()) {
                            sendBot("فشل حفظ الفيديو: ${event.cause?.message ?: event.error}")
                            output.delete()
                            stopSelf()
                        } else {
                            updateNotificationText("اكتمل التسجيل؛ جاري إرسال الفيديو إلى تيليجرام.")
                            Thread { uploadVideo(output); output.delete(); stopSelf() }.start()
                        }
                    }
                }
                SecurePrefs.get(this).edit().putBoolean("video_recording", true)
                    .putLong("video_started_at", System.currentTimeMillis())
                    .putInt("video_duration", durationSeconds).apply()
                updateNotificationText("تسجيل فيديو صامت بالكاميرا الخلفية — ${durationSeconds} ثانية كحد أقصى.")
                sendBot("بدأ تسجيل الفيديو بعد موافقتك. المدة القصوى: ${durationSeconds} ثانية. أرسل /video_stop للإيقاف.")
                handler.postDelayed(stopRunnable, durationSeconds * 1000L)
            } catch (e: Exception) {
                sendBot("تعذر بدء الفيديو: ${e.message ?: "خطأ غير معروف"}")
                stopSelf()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private val stopRunnable = Runnable { stopRecording() }

    private fun stopRecording() {
        handler.removeCallbacks(stopRunnable)
        val active = recording
        if (active == null) { if (!SecurePrefs.get(this).getBoolean("video_recording", false)) stopSelf(); return }
        updateNotificationText("جاري إنهاء الفيديو وحفظه ثم إرساله…")
        active.stop()
    }

    private fun updateNotificationText(text: String) {
        val stopIntent = Intent(this, VideoRecordingService::class.java).setAction(ACTION_STOP)
        val stopPending = PendingIntent.getService(this, 3121, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.presence_video_online)
            .setContentTitle("MANW — تسجيل فيديو")
            .setContentText(text).setOngoing(true)
            .addAction(android.R.drawable.ic_media_pause, "إيقاف", stopPending).build()
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, n)
    }

    private fun uploadVideo(file: File) {
        try {
            val prefs = SecurePrefs.get(this)
            val token = prefs.getString("bot_token", "").orEmpty()
            val chat = prefs.getString("chat_id", "").orEmpty()
            if (token.isBlank() || chat.isBlank()) { sendBot("تم حفظ الفيديو محلياً، لكن إعدادات تيليجرام غير مكتملة."); return }
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", chat)
                .addFormDataPart("caption", "فيديو MANW — تسجيل بموافقة صاحب الهاتف")
                .addFormDataPart("video", file.name, file.asRequestBody("video/mp4".toMediaType()))
                .build()
            val request = Request.Builder().url("https://api.telegram.org/bot$token/sendVideo").post(body).build()
            client.newCall(request).execute().use { response ->
                sendBot(if (response.isSuccessful) "تم إرسال الفيديو إلى تيليجرام بنجاح." else "تعذر إرسال الفيديو (HTTP ${response.code}).")
            }
        } catch (e: Exception) { sendBot("تعذر إرسال الفيديو: ${e.message ?: "خطأ اتصال"}") }
    }

    private fun sendBot(message: String) {
        try {
            val prefs = SecurePrefs.get(this)
            val token = prefs.getString("bot_token", "").orEmpty()
            val chat = prefs.getString("chat_id", "").orEmpty()
            if (token.isBlank() || chat.isBlank()) return
            val request = Request.Builder().url("https://api.telegram.org/bot$token/sendMessage")
                .post(FormBody.Builder().add("chat_id", chat).add("text", message).build()).build()
            client.newCall(request).execute().close()
        } catch (_: Exception) { }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        try { unregisterReceiver(stopReceiver) } catch (_: Exception) { }
        SecurePrefs.get(this).edit().putBoolean("video_recording", false).apply()
        recording?.stop()
        recording = null
        try { cameraProvider?.unbindAll() } catch (_: Exception) { }
        cameraProvider = null
        super.onDestroy()
    }
}
