package com.manw.autosender

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ScreenShareService : Service() {
    companion object {
        const val ACTION_START = "com.manw.autosender.SCREEN_START"
        const val ACTION_STOP = "com.manw.autosender.SCREEN_STOP"
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_RESULT_DATA = "result_data"
        const val EXTRA_SCREENSHOT_ONLY = "screenshot_only"
        private const val CHANNEL = "manw_screen_share"
        private const val NOTIFICATION_ID = 3180
    }
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var socket: WebSocket? = null
    private var socketConnecting = false
    private var worker: HandlerThread? = null
    private var handler: Handler? = null
    private val sending = AtomicBoolean(false)
    private var lastFrame = 0L
    @Volatile private var stopped = false
    @Volatile private var screenshotOnly = false
    private val client = OkHttpClient.Builder().pingInterval(20, TimeUnit.SECONDS).build()

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
            NotificationChannel(CHANNEL, "مشاركة شاشة الهاتف", NotificationManager.IMPORTANCE_LOW)
        )
        val notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("MANW — مشاركة الشاشة نشطة")
            .setContentText("شاشة الهاتف مرئية للموقع المقترن. اضغط لإيقاف المشاركة.")
            .setOngoing(true).setCategory(NotificationCompat.CATEGORY_SERVICE).build()
        if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        else startForeground(NOTIFICATION_ID, notification)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopCapture(); stopSelf(); return START_NOT_STICKY }
        if (intent?.action != ACTION_START) { stopSelf(); return START_NOT_STICKY }
        val prefs = SecurePrefs.get(this)
        screenshotOnly = intent.getBooleanExtra(EXTRA_SCREENSHOT_ONLY, false)
        if (!screenshotOnly && !prefs.getBoolean("web_paired", false)) { stopSelf(); return START_NOT_STICKY }
        val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, Activity.RESULT_CANCELED)
        val data = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                   else @Suppress("DEPRECATION") intent.getParcelableExtra(EXTRA_RESULT_DATA)
        if (resultCode != Activity.RESULT_OK || data == null) { stopSelf(); return START_NOT_STICKY }
        try {
            stopped = false
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            projection = manager.getMediaProjection(resultCode, data)
            projection?.registerCallback(object : MediaProjection.Callback() {
                override fun onStop() { stopCapture(); stopSelf() }
            }, Handler(mainLooper))
            startCapture()
        } catch (_: Exception) { stopSelf() }
        return START_NOT_STICKY
    }

    private fun startCapture() {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        val width = metrics.widthPixels.coerceAtMost(900)
        val height = (metrics.heightPixels.toFloat() * width / metrics.widthPixels).toInt().coerceAtLeast(1)
        val density = metrics.densityDpi
        worker = HandlerThread("MANW-ScreenFrames").also { it.start() }
        handler = Handler(worker!!.looper)
        reader = ImageReader.newInstance(width, height, android.graphics.PixelFormat.RGBA_8888, 2)
        reader?.setOnImageAvailableListener({ source ->
            val now = System.currentTimeMillis()
            if (now - lastFrame < 350L || !sending.compareAndSet(false, true)) {
                source.acquireLatestImage()?.close()
                return@setOnImageAvailableListener
            }
            lastFrame = now
            val image = source.acquireLatestImage()
            if (image == null) { sending.set(false); return@setOnImageAvailableListener }
            try {
                val plane = image.planes[0]
                val paddedWidth = plane.rowStride / plane.pixelStride
                val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
                padded.copyPixelsFromBuffer(plane.buffer)
                val bitmap = Bitmap.createBitmap(padded, 0, 0, width, height)
                padded.recycle()
                val out = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 55, out)
                bitmap.recycle()
                val jpeg = out.toByteArray()
                if (screenshotOnly) uploadScreenshot(jpeg) else sendFrame(jpeg)
            } catch (_: Exception) {
                sending.set(false)
            } finally { image.close() }
        }, handler)
        display = projection?.createVirtualDisplay(
            "MANW-Visible-Screen-Share", width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader?.surface, null, handler
        )
        if (!screenshotOnly) connectSocket()
    }

    private fun uploadScreenshot(bytes: ByteArray) {
        if (stopped) return
        try {
            val prefs = SecurePrefs.get(this)
            val token = prefs.getString("bot_token", "").orEmpty()
            val chat = prefs.getString("chat_id", "").orEmpty()
            if (token.isBlank() || chat.isBlank()) {
                sendScreenshotStatus("تعذر إرسال لقطة الشاشة: إعدادات تيليجرام غير مكتملة.")
            } else {
                val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                    .addFormDataPart("chat_id", chat)
                    .addFormDataPart("caption", "لقطة شاشة MANW — بعد موافقة صاحب الهاتف")
                    .addFormDataPart("photo", "screenshot.jpg", bytes.toRequestBody("image/jpeg".toMediaType()))
                    .build()
                val request = Request.Builder().url("https://api.telegram.org/bot$token/sendPhoto").post(body).build()
                client.newCall(request).execute().use { response ->
                    sendScreenshotStatus(if (response.isSuccessful) "تم إرسال لقطة الشاشة إلى تيليجرام بعد موافقتك." else "تعذر إرسال لقطة الشاشة (HTTP ${response.code}).")
                }
            }
        } catch (e: Exception) {
            sendScreenshotStatus("تعذر إرسال لقطة الشاشة: ${e.message ?: "خطأ اتصال"}")
        } finally {
            stopCapture()
            stopSelf()
        }
    }

    private fun sendScreenshotStatus(message: String) {
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

    private fun connectSocket() {
        if (socket != null || socketConnecting) return
        val prefs = SecurePrefs.get(this)
        val base = prefs.getString("web_base_url", "").orEmpty()
        val token = prefs.getString("web_device_token", "").orEmpty()
        if (base.isBlank() || token.isBlank()) return
        socketConnecting = true
        val wsBase = base.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://")
        val request = Request.Builder().url("$wsBase/ws/device?device=" + java.net.URLEncoder.encode(token, "UTF-8")).build()
        socket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                socketConnecting = false
                webSocket.send(JSONObject().put("type", "status").put("screenActive", true).toString())
            }
            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val message = JSONObject(text)
                    if (message.optString("type") == "remote_input") {
                        val action = message.optJSONObject("payload") ?: return
                        val name = action.optString("action")
                        val accepted = RemoteControlAccessibilityService.handle(action)
                        webSocket.send(JSONObject()
                            .put("type", "remote_input_result")
                            .put("accepted", accepted)
                            .put("action", name)
                            .put("reason", if (accepted) "" else "خدمة إمكانية الوصول غير مفعّلة أو تعذّر تنفيذ الأمر")
                            .toString())
                    }
                } catch (_: Exception) { }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (socket === webSocket) socket = null
                socketConnecting = false
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                if (socket === webSocket) socket = null
                socketConnecting = false
            }
        })
    }

    private fun sendFrame(bytes: ByteArray) {
        if (socket == null) connectSocket()
        val current = socket
        if (current == null || current.queueSize() > 700_000L) { sending.set(false); return }
        val encoded = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        if (!current.send(JSONObject().put("type", "screen_frame").put("jpeg", encoded).toString())) {
            socket = null
        }
        sending.set(false)
    }

    private fun stopCapture() {
        if (stopped) return
        stopped = true
        try { display?.release() } catch (_: Exception) {}
        display = null
        try { reader?.close() } catch (_: Exception) {}
        reader = null
        try { projection?.stop() } catch (_: Exception) {}
        projection = null
        socket?.send(JSONObject().put("type", "status").put("screenActive", false).toString())
        socket?.close(1000, "screen sharing stopped")
        socket = null
        worker?.quitSafely()
        worker = null
        handler = null
        stopForeground(STOP_FOREGROUND_REMOVE)
    }
    override fun onDestroy() { stopCapture(); super.onDestroy() }
    override fun onBind(intent: Intent?): IBinder? = null
}
