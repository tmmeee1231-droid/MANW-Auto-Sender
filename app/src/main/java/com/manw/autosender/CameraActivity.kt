package com.manw.autosender

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
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
import java.io.File

class CameraActivity : AppCompatActivity() {
    private lateinit var previewView: PreviewView
    private var imageCapture: ImageCapture? = null
    private lateinit var recipientLabel: TextView
    private var lastPhoto: File? = null

    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else {
            Toast.makeText(this, "لازم تسمح باستخدام الكاميرا", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = SecurePrefs.get(this)
        val phone = prefs.getString("wa_phone", "") ?: ""
        val name = prefs.getString("wa_name", "جهة الاتصال المختارة") ?: "جهة الاتصال المختارة"
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF10111A.toInt())
        }
        recipientLabel = TextView(this).apply {
            text = "المستلم المحدد: $name ($phone)\nزر الصوت يلتقط صورة بالكاميرا الخلفية"
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(20, 16, 20, 16)
        }
        previewView = PreviewView(this).apply { implementationMode = PreviewView.ImplementationMode.COMPATIBLE }
        root.addView(recipientLabel)
        root.addView(previewView, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        val capture = Button(this).apply { text = "التقاط صورة (أو زر الصوت)" }
        root.addView(capture)
        capture.setOnClickListener { takePhoto() }
        val share = Button(this).apply {
            text = "إرسال آخر صورة عبر واتساب"
            isEnabled = false
        }
        root.addView(share)
        share.setOnClickListener { lastPhoto?.let { sharePhoto(it) } }
        setContentView(root)
        captureButton = capture
        shareButton = share

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else permission.launch(Manifest.permission.CAMERA)
    }

    private lateinit var captureButton: Button
    private lateinit var shareButton: Button

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            imageCapture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            try {
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
            } catch (e: Exception) {
                Toast.makeText(this, "تعذر تشغيل الكاميرا الخلفية: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun takePhoto() {
        val capture = imageCapture ?: return
        val file = File(cacheDir, "MANW_${System.currentTimeMillis()}.jpg")
        val options = ImageCapture.OutputFileOptions.Builder(file).build()
        capture.takePicture(options, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                lastPhoto = file
                shareButton.isEnabled = true
                Toast.makeText(this@CameraActivity, "تم التقاط الصورة. اضغط إرسال لفتح واتساب ومراجعة المستلم قبل الإرسال.", Toast.LENGTH_LONG).show()
                sharePhoto(file)
            }
            override fun onError(exception: ImageCaptureException) {
                Toast.makeText(this@CameraActivity, "فشل التقاط الصورة: ${exception.message}", Toast.LENGTH_LONG).show()
            }
        })
    }

    private fun sharePhoto(file: File) {
        val uri = androidx.core.content.FileProvider.getUriForFile(
            this, "${packageName}.fileprovider", file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "image/jpeg"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            setPackage("com.whatsapp")
        }
        try {
            startActivity(intent)
        } catch (_: Exception) {
            val fallback = Intent(Intent.ACTION_SEND).apply {
                type = "image/jpeg"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(fallback, "إرسال الصورة"))
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            takePhoto()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
