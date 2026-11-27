package com.manw.autosender

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.net.Uri
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONObject
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.util.concurrent.TimeUnit

class CameraActivity : AppCompatActivity() {
    companion object { const val EXTRA_REMOTE_REQUEST = "remote_camera_request" }

    private lateinit var previewView: PreviewView
    private lateinit var statusLabel: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private var imageCapture: ImageCapture? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var sessionActive = false
    private var isSending = false
    private var receiverRegistered = false

    private val remoteReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                ImageMonitorService.ACTION_REMOTE_CAPTURE -> {
                    if (sessionActive) takeAndSendPhoto()
                    else sendBotStatus("لم تبدأ جلسة الكاميرا بعد. وافق على الطلب من شاشة الهاتف أولاً.")
                }
                ImageMonitorService.ACTION_REMOTE_STOP -> stopCameraSession("تم إيقاف جلسة الكاميرا بأمر من البوت.")
            }
        }
    }

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) beginCameraSession()
        else statusLabel.text = "لم تمنح إذن الكاميرا؛ لم يبدأ التصوير."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        registerRemoteReceiver()
        statusLabel.text = "طلب تصوير عن بُعد بالكاميرا الخلفية. الكاميرا متوقفة حتى تضغط موافقة."
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF10111A.toInt())
        }
        statusLabel = TextView(this).apply {
            text = "الكاميرا متوقفة حتى توافق."
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(20, 18, 20, 18)
        }
        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            visibility = View.GONE
        }
        startButton = Button(this).apply { text = "موافقة وبدء الكاميرا الخلفية" }
        stopButton = Button(this).apply {
            text = "إيقاف الكاميرا فوراً"
            visibility = View.GONE
        }
        root.addView(statusLabel)
        root.addView(previewView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        root.addView(startButton)
        root.addView(stopButton)
        setContentView(root)

        startButton.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                beginCameraSession()
            } else cameraPermission.launch(Manifest.permission.CAMERA)
        }
        stopButton.setOnClickListener { stopCameraSession("أوقفت جلسة الكاميرا من الهاتف.") }
    }

    private fun registerRemoteReceiver() {
        val filter = IntentFilter().apply {
            addAction(ImageMonitorService.ACTION_REMOTE_CAPTURE)
            addAction(ImageMonitorService.ACTION_REMOTE_STOP)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(remoteReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else @Suppress("DEPRECATION") registerReceiver(remoteReceiver, filter)
        receiverRegistered = true
    }

    private fun beginCameraSession() {
        if (sessionActive) return
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            try {
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val capture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture)
                cameraProvider = provider
                imageCapture = capture
                sessionActive = true
                previewView.visibility = View.VISIBLE
                startButton.visibility = View.GONE
                stopButton.visibility = View.VISIBLE
                statusLabel.text = "الكاميرا الخلفية تعمل بموافقتك. أرسل /capture في البوت لالتقاط صورة، أو /stopcamera للإيقاف."
                sendBotStatus("تمت الموافقة وبدأت جلسة الكاميرا الخلفية. أرسل /capture لالتقاط صورة.")
            } catch (e: Exception) {
                statusLabel.text = "تعذر تشغيل الكاميرا: " + (e.message ?: "خطأ غير معروف")
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takeAndSendPhoto() {
        if (!sessionActive || isSending) return
        val capture = imageCapture ?: return
        isSending = true
        statusLabel.text = "وصل أمر التصوير؛ جاري التقاط الصورة وإرسالها..."
        val file = File(cacheDir, "MANW_REMOTE_" + System.currentTimeMillis() + ".jpg")
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        capture.takePicture(options, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                Thread {
                    val enhanced = File(cacheDir, "MANW_CLEAR_" + System.currentTimeMillis() + ".jpg")
                    val enhancedReady = try {
                        ImageClarity.createReadableCopy(file, enhanced)
                        true
                    } catch (_: Exception) { false }
                    val originalResult = uploadPhoto(file, "الصورة الأصلية")
                    val enhancedResult = if (enhancedReady) uploadPhoto(enhanced, "نسخة محسّنة للوضوح والقراءة") else Pair(false, "تعذر إنشاء النسخة المحسّنة")
                    val recognizedText = if (enhancedReady) try { extractReadableText(enhanced) } catch (_: Exception) { "" } else ""
                    val textResult = if (recognizedText.isNotBlank()) sendRecognizedText(recognizedText) else Pair(true, "لم يتم التعرف على نص واضح")
                    val allImagesSent = originalResult.first && (!enhancedReady || enhancedResult.first)
                    runOnUiThread {
                        isSending = false
                        if (allImagesSent) {
                            statusLabel.text = "تم إرسال الصورة الأصلية والنسخة المحسّنة."
                            val textStatus = if (recognizedText.isNotBlank() && textResult.first) " وتم استخراج النص وإرساله للبوت." else " لم يتم استخراج نص واضح."
                            sendBotStatus("تم التصوير. الأصلية: تم الإرسال. النسخة المحسّنة: " +
                                (if (enhancedReady && enhancedResult.first) "تم الإرسال." else "تعذر تجهيزها أو إرسالها.") + textStatus)
                            file.delete()
                            enhanced.delete()
                        } else {
                            val error = if (!originalResult.first) originalResult.second else enhancedResult.second
                            statusLabel.text = "تعذر إرسال كل نسخ الصورة: $error"
                            sendBotStatus("نتيجة التصوير: الأصلية " + (if (originalResult.first) "وصلت" else "لم تصل") +
                                "، والنسخة المحسّنة " + (if (enhancedResult.first) "وصلت" else "لم تصل") + ". $error")
                        }
                    }
                }.start()
            }
            override fun onError(exception: ImageCaptureException) {
                isSending = false
                statusLabel.text = "فشل التصوير: " + (exception.message ?: "خطأ")
                sendBotStatus("تعذر التقاط الصورة: " + (exception.message ?: "خطأ"))
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
                .addFormDataPart("caption", caption)
                .build()
            val request = Request.Builder().url("https://api.telegram.org/bot" + token + "/sendPhoto").post(body).build()
            val client = OkHttpClient.Builder().callTimeout(90, TimeUnit.SECONDS).build()
            client.newCall(request).execute().use { response ->
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
        } finally {
            recognizer.close()
        }
    }

    private fun sendRecognizedText(text: String): Pair<Boolean, String> {
        val prefs = SecurePrefs.get(this)
        val token = prefs.getString("bot_token", "") ?: ""
        val chatId = prefs.getString("chat_id", "") ?: ""
        if (token.isBlank() || chatId.isBlank()) return Pair(false, "بيانات البوت غير مكتملة")
        return try {
            val body = okhttp3.FormBody.Builder()
                .add("chat_id", chatId)
                .add("text", "النص المستخرج آلياً من الصورة (قد يحتاج مراجعة):\n" + text.take(3500))
                .build()
            val request = Request.Builder()
                .url("https://api.telegram.org/bot" + token + "/sendMessage")
                .post(body).build()
            OkHttpClient().newCall(request).execute().use { response ->
                val json = JSONObject(response.body?.string().orEmpty())
                val ok = response.isSuccessful && json.optBoolean("ok")
                Pair(ok, if (ok) "ok" else json.optString("description", "HTTP " + response.code))
            }
        } catch (e: Exception) { Pair(false, e.message ?: "تعذر إرسال النص") }
    }

    private fun sendBotStatus(message: String) {
        val prefs = SecurePrefs.get(this)
        val token = prefs.getString("bot_token", "") ?: ""
        val chatId = prefs.getString("chat_id", "") ?: ""
        if (token.isBlank() || chatId.isBlank()) return
        Thread {
            try {
                val body = okhttp3.FormBody.Builder().add("chat_id", chatId).add("text", message).build()
                val request = Request.Builder().url("https://api.telegram.org/bot" + token + "/sendMessage").post(body).build()
                OkHttpClient().newCall(request).execute().close()
            } catch (_: Exception) { }
        }.start()
    }

    private fun stopCameraSession(message: String) {
        sessionActive = false
        cameraProvider?.unbindAll()
        cameraProvider = null
        imageCapture = null
        previewView.visibility = View.GONE
        startButton.visibility = View.VISIBLE
        stopButton.visibility = View.GONE
        statusLabel.text = message
        sendBotStatus(message)
    }

    override fun onDestroy() {
        if (sessionActive) {
            sessionActive = false
            cameraProvider?.unbindAll()
        }
        if (receiverRegistered) {
            try { unregisterReceiver(remoteReceiver) } catch (_: Exception) { }
            receiverRegistered = false
        }
        super.onDestroy()
    }
}
