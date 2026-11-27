package com.manw.autosender

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.KeyEvent
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
import java.io.File
import java.util.concurrent.TimeUnit

class CameraActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private lateinit var statusLabel: TextView
    private var imageCapture: ImageCapture? = null
    private val capturedPhotos = mutableListOf<File>()
    private var isSending = false
    private var longPressHandled = false

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else {
            Toast.makeText(this, "لازم تسمح باستخدام الكاميرا", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = SecurePrefs.get(this)
        val chatId = prefs.getString("chat_id", "") ?: ""
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF10111A.toInt())
        }
        statusLabel = TextView(this).apply {
            text = "إرسال تيليجرام إلى: ${if (chatId.isBlank()) "لم يتم ضبط Chat ID" else chatId}\nزر رفع الصوت: تصوير صورة\nاضغط مطولاً على زر خفض الصوت: إرسال كل الصور"
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(20, 16, 20, 16)
        }
        previewView = PreviewView(this).apply {
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        root.addView(statusLabel)
        root.addView(previewView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        val capture = Button(this).apply { text = "التقاط صورة (زر رفع الصوت)" }
        root.addView(capture)
        capture.setOnClickListener { takePhoto() }
        val send = Button(this).apply { text = "إرسال الصور (ضغط مطوّل على خفض الصوت)" }
        root.addView(send)
        send.setOnClickListener { sendCapturedPhotos() }
        setContentView(root)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else permission.launch(Manifest.permission.CAMERA)
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            val captureUseCase = imageCapture ?: return@addListener
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, captureUseCase)
            } catch (e: Exception) {
                Toast.makeText(this, "تعذر تشغيل الكاميرا الخلفية: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        if (isSending) return
        val capture = imageCapture ?: return
        val file = File(cacheDir, "MANW_${System.currentTimeMillis()}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        capture.takePicture(options, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                capturedPhotos.add(file)
                statusLabel.text = "تم تصوير ${capturedPhotos.size} صورة.\nزر رفع الصوت: صورة جديدة\nاضغط مطولاً على زر خفض الصوت: إرسال الكل إلى تيليجرام"
                Toast.makeText(this@CameraActivity, "تم التقاط الصورة رقم ${capturedPhotos.size}", Toast.LENGTH_SHORT).show()
            }

            override fun onError(exception: ImageCaptureException) {
                Toast.makeText(this@CameraActivity, "فشل التقاط الصورة: ${exception.message}", Toast.LENGTH_LONG).show()
            }
        })
    }

    private fun sendCapturedPhotos() {
        if (isSending) return
        val photos = capturedPhotos.toList()
        if (photos.isEmpty()) {
            Toast.makeText(this, "مفيش صور لإرسالها. صوّر الأول بزر رفع الصوت.", Toast.LENGTH_LONG).show()
            return
        }
        val prefs = SecurePrefs.get(this)
        val token = prefs.getString("bot_token", "") ?: ""
        val chatId = prefs.getString("chat_id", "") ?: ""
        if (token.isBlank() || chatId.isBlank()) {
            Toast.makeText(this, "اضبط Bot Token وChat ID من إعدادات التطبيق أولاً.", Toast.LENGTH_LONG).show()
            return
        }

        isSending = true
        statusLabel.text = "جاري إرسال ${photos.size} صورة إلى تيليجرام..."
        Thread {
            var sent = 0
            var error: String? = null
            for (photo in photos) {
                val result = uploadPhoto(photo, token, chatId)
                if (!result.first) {
                    error = result.second
                    break
                }
                sent++
            }
            runOnUiThread {
                isSending = false
                if (error == null) {
                    capturedPhotos.removeAll(photos.toSet())
                    photos.forEach { it.delete() }
                    statusLabel.text = "تم إرسال ${sent} صورة بنجاح إلى تيليجرام.\nيمكنك التصوير من جديد بزر رفع الصوت."
                    Toast.makeText(this, "تيليجرام أكد استلام كل الصور: $sent", Toast.LENGTH_LONG).show()
                } else {
                    statusLabel.text = "تم إرسال $sent من ${photos.size} صورة. فشل الباقي: $error\nحاول الضغط مطولاً على زر خفض الصوت مرة أخرى."
                    Toast.makeText(this, "تعذر إكمال الإرسال: $error", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun uploadPhoto(file: File, token: String, chatId: String): Pair<Boolean, String> {
        return try {
            val body = MultipartBody.Builder().setType(MultipartBody.FORM)
                .addFormDataPart("chat_id", chatId)
                .addFormDataPart("photo", file.name, file.asRequestBody("image/jpeg".toMediaType()))
                .build()
            val request = Request.Builder()
                .url("https://api.telegram.org/bot$token/sendPhoto")
                .post(body)
                .build()
            val client = OkHttpClient.Builder().callTimeout(90, TimeUnit.SECONDS).build()
            client.newCall(request).execute().use { response ->
                val json = JSONObject(response.body?.string().orEmpty())
                val ok = response.isSuccessful && json.optBoolean("ok")
                Pair(ok, if (ok) "ok" else json.optString("description", "HTTP ${response.code}"))
            }
        } catch (e: Exception) {
            Pair(false, e.message ?: "خطأ اتصال")
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (event?.repeatCount == 0) takePhoto()
                return true
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (event?.repeatCount == 0) {
                    longPressHandled = false
                    event.startTracking()
                }
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (!longPressHandled) {
                longPressHandled = true
                sendCapturedPhotos()
            }
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) return true
        return super.onKeyUp(keyCode, event)
    }
}
