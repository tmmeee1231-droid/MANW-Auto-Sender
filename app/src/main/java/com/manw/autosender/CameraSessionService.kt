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
import okhttp3.RequestBody
import okio.BufferedSink
import okio.Buffer
import okio.ForwardingSink
import okio.buffer
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
        const val EXTRA_FRONT_CAMERA = "front_camera"
        private const val CHANNEL_ID = "manw_camera_session"
        private const val NOTIFICATION_ID = 3111
    }

    @Volatile private var imageCapture: ImageCapture? = null
    @Volatile private var useFrontCamera = false
    @Volatile private var cameraControl: CameraControl? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val progressExecutor = Executors.newSingleThreadExecutor()
    private val analyzing = AtomicBoolean(false)
    @Volatile private var lastAutoFocusAt = 0L
    @Volatile private var lastTextAnalysisAt = 0L
    @Volatile private var sending = false
    @Volatile private var streamSocket: WebSocket? = null
    private val streamConnecting = AtomicBoolean(false)
    @Volatile private var lastStreamFrameAt = 0L
    private var receiverRegistered = false

    private val commandReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ImageMonitorService.ACTION_REMOTE_CAPTURE -> captureAndSend()
                ImageMonitorService.ACTION_REMOTE_ZOOM -> cameraControl?.setLinearZoom(intent.getFloatExtra("zoom", 0.0f).coerceIn(0f, 1f))
                ImageMonitorService.ACTION_REMOTE_FOCUS -> focusCenter()
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
            addAction(ImageMonitorService.ACTION_REMOTE_ZOOM)
            addAction(ImageMonitorService.ACTION_REMOTE_FOCUS)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(commandReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(commandReceiver, filter)
        receiverRegistered = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        serviceLifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
        useFrontCamera = intent?.getBooleanExtra(EXTRA_FRONT_CAMERA, useFrontCamera) ?: useFrontCamera
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
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .setJpegQuality(88)
                    .build()
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analysisExecutor) { proxy -> analyzeForTextAndFocus(proxy) }
                provider.unbindAll()
                val selector = if (useFrontCamera) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
                val camera = provider.bindToLifecycle(this, selector, capture, analysis)
                cameraControl = camera.cameraControl
                imageCapture = capture
                connectLiveStream()
                SecurePrefs.get(this).edit().putBoolean("camera_active", true).apply()
                updateNotification("جلسة ${if (useFrontCamera) "الكاميرا الأمامية" else "الكاميرا الخلفية"} نشطة بموافقتك. أرسل /capture لالتقاط صورة.")
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
        if (now - lastStreamFrameAt < 400L) return
        lastStreamFrameAt = now
        var bitmap: Bitmap? = null
        try {
            if (streamSocket == null) connectLiveStream()
            val socket = streamSocket ?: return
            bitmap = proxy.toBitmap()
            if (socket.queueSize() > 512L * 1024L) return
            val maxWidth = 640
            val scaled = if (bitmap.width > maxWidth) {
                val height = (bitmap.height * (maxWidth.toFloat() / bitmap.width)).toInt().coerceAtLeast(1)
                Bitmap.createScaledBitmap(bitmap, maxWidth, height, true)
            } else bitmap
            val output = java.io.ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 48, output)
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
        val analysisNow = System.currentTimeMillis()
        if (analysisNow - lastTextAnalysisAt < 1000L) {
            analyzing.set(false)
            proxy.close()
            return
        }
        lastTextAnalysisAt = analysisNow
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

    private fun focusCenter() {
        try {
            val factory = SurfaceOrientedMeteringPointFactory(1f, 1f)
            val point = factory.createPoint(0.5f, 0.5f)
            val action = FocusMeteringAction.Builder(point,
                FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                .setAutoCancelDuration(3, TimeUnit.SECONDS).build()
            cameraControl?.startFocusAndMetering(action)
        } catch (_: Exception) { }
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
        // Keep the text-detection autofocus already metered from the live preview.
        // Do not refocus the empty frame center here; that would undo focus on the detected text.
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            capture.takePicture(OutputFileOptions.Builder(original).build(),
            ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                    Thread {
                        val enhanced = File(cacheDir, "MANW_CLEAR_" + System.currentTimeMillis() + ".jpg")
                        val enhancedReady = try {
                            ImageClarity.createReadableCopy(original, enhanced)
                            true
                        } catch (_: Exception) { false }
                        val enhancedResult = if (enhancedReady) uploadPhoto(enhanced, "الصورة المحسّنة تلقائياً — أوضح للقراءة") else Pair(false, "تعذر إنشاء الصورة المحسّنة")
                        val prefs = SecurePrefs.get(this@CameraSessionService)
                        if (prefs.getBoolean("web_paired", false) && enhancedReady) {
                            val base = prefs.getString("web_base_url", "").orEmpty()
                            val deviceToken = prefs.getString("web_device_token", "").orEmpty()
                            try {
                                WebControlClient.upload(base, deviceToken, enhanced, "MANW_CLEAR_" + enhanced.name)
                            } catch (_: Exception) { }
                        }
                        val recognized = if (enhancedReady) try { extractReadableText(enhanced) } catch (_: Exception) { "" } else ""
                        if (recognized.isNotBlank()) sendRecognizedText(recognized)
                        if (enhancedReady && enhancedResult.first) {
                            original.delete()
                            enhanced.delete()
                            sendStatus("تم تحسين الصورة وإرسال النسخة المحسّنة فقط." +
                                if (recognized.isNotBlank()) " وتم استخراج النص." else " لم يتم العثور على نص واضح.")
                        } else {
                            sendStatus("تم التقاط الصورة لكن تعذر إرسال النسخة المحسّنة. " + enhancedResult.second)
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
        }, 150L)
    }

    private fun uploadPhoto(file: File, caption: String): Pair<Boolean, String> {
        val prefs = SecurePrefs.get(this)
        val token = prefs.getString("bot_token", "") ?: ""
        val chatId = prefs.getString("chat_id", "") ?: ""
        if (token.isBlank() || chatId.isBlank()) return Pair(false, "بيانات البوت غير مكتملة")
        val progressMessageId = sendUploadProgressMessage(token, chatId)
        return try {
            val photoBody = file.asRequestBody("image/jpeg".toMediaType())
            val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", chatId)
                .addFormDataPart("photo", file.name, photoBody)
                .addFormDataPart("caption", caption).build()
            var lastReportedPercent = -1
            var lastReportedAt = 0L
            val progressBody = object : RequestBody() {
                override fun contentType() = multipart.contentType()
                override fun contentLength() = multipart.contentLength()
                override fun writeTo(sink: BufferedSink) {
                    val total = contentLength()
                    var uploaded = 0L
                    val countingSink = object : ForwardingSink(sink) {
                        override fun write(source: Buffer, byteCount: Long) {
                            super.write(source, byteCount)
                            uploaded += byteCount
                            if (total > 0L && progressMessageId > 0L) {
                                val percent = ((uploaded * 100L) / total).toInt().coerceIn(0, 99)
                                val now = System.currentTimeMillis()
                                if (percent >= lastReportedPercent + 5 || now - lastReportedAt >= 1200L) {
                                    lastReportedPercent = percent
                                    lastReportedAt = now
                                    progressExecutor.execute { editUploadProgress(token, chatId, progressMessageId, percent) }
                                }
                            }
                        }
                    }
                    val buffered = countingSink.buffer()
                    multipart.writeTo(buffered)
                    buffered.flush()
                }
            }
            val request = Request.Builder()
                .url("https://api.telegram.org/bot" + token + "/sendPhoto")
                .post(progressBody).build()
            OkHttpClient.Builder().callTimeout(90, TimeUnit.SECONDS).build().newCall(request).execute().use { response ->
                val json = JSONObject(response.body?.string().orEmpty())
                val ok = response.isSuccessful && json.optBoolean("ok")
                if (ok) {
                    if (progressMessageId > 0L) editUploadProgress(token, chatId, progressMessageId, 100, "تم رفع الصورة 100% ووصلت إلى تيليجرام بنجاح ✅")
                    Pair(true, json.optJSONObject("result")?.optLong("message_id", -1L)?.toString() ?: "ok")
                } else {
                    if (progressMessageId > 0L) editUploadProgress(token, chatId, progressMessageId, lastReportedPercent.coerceAtLeast(0), "تعذر إكمال رفع الصورة. ${json.optString("description", "HTTP " + response.code)}")
                    Pair(false, json.optString("description", "HTTP " + response.code))
                }
            }
        } catch (e: Exception) {
            if (progressMessageId > 0L) editUploadProgress(token, chatId, progressMessageId, 0, "فشل رفع الصورة: " + (e.message ?: "خطأ اتصال"))
            Pair(false, e.message ?: "خطأ اتصال")
        }
    }

    private fun sendUploadProgressMessage(token: String, chatId: String): Long {
        return try {
            val body = FormBody.Builder().add("chat_id", chatId)
                .add("text", "📸 تم التقاط الصورة. جاري تجهيزها ورفعها إلى تيليجرام: 0%").build()
            val request = Request.Builder().url("https://api.telegram.org/bot" + token + "/sendMessage").post(body).build()
            OkHttpClient().newCall(request).execute().use { response ->
                val json = JSONObject(response.body?.string().orEmpty())
                if (response.isSuccessful && json.optBoolean("ok")) json.optJSONObject("result")?.optLong("message_id", -1L) ?: -1L else -1L
            }
        } catch (_: Exception) { -1L }
    }

    private fun editUploadProgress(token: String, chatId: String, messageId: Long, percent: Int, customText: String? = null) {
        try {
            val text = customText ?: "⬆️ جاري رفع الصورة إلى تيليجرام: $percent%"
            val body = FormBody.Builder().add("chat_id", chatId)
                .add("message_id", messageId.toString()).add("text", text).build()
            val request = Request.Builder().url("https://api.telegram.org/bot" + token + "/editMessageText").post(body).build()
            OkHttpClient().newCall(request).execute().close()
        } catch (_: Exception) { }
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
        progressExecutor.shutdown()
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
