package com.manw.autosender

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

/**
 * Consent screen only. The camera itself belongs to CameraSessionService so closing this
 * activity does not end an already approved camera session.
 */
class CameraActivity : AppCompatActivity() {
    companion object { const val EXTRA_REMOTE_REQUEST = "remote_camera_request" }
    private lateinit var statusLabel: TextView
    private lateinit var startButton: Button

    private val cameraPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startApprovedSession()
        else statusLabel.text = "لم تمنح إذن الكاميرا؛ لم يبدأ التصوير."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 28, 24, 24)
            setBackgroundColor(0xFF10111A.toInt())
        }
        statusLabel = TextView(this).apply {
            text = "وصلك طلب تشغيل الكاميرا. لن تبدأ الكاميرا إلا بعد موافقتك."
            textSize = 17f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(8, 18, 8, 24)
        }
        startButton = Button(this).apply { text = "موافقة وبدء جلسة الكاميرا في الخلفية" }
        val cancelButton = Button(this).apply { text = "رفض الطلب" }
        root.addView(statusLabel)
        root.addView(startButton)
        root.addView(cancelButton)
        setContentView(root)

        startButton.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                startApprovedSession()
            } else cameraPermission.launch(Manifest.permission.CAMERA)
        }
        cancelButton.setOnClickListener {
            Toast.makeText(this, "تم رفض طلب الكاميرا.", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun startApprovedSession() {
        try {
            val intent = Intent(this, CameraSessionService::class.java).setAction(CameraSessionService.ACTION_START)
            ContextCompat.startForegroundService(this, intent)
            Toast.makeText(this, "بدأت جلسة الكاميرا. يمكنك إغلاق هذه الشاشة؛ سيظل إشعار الكاميرا ظاهرًا.", Toast.LENGTH_LONG).show()
            finish()
        } catch (e: Exception) {
            statusLabel.text = "تعذر بدء جلسة الكاميرا: " + (e.message ?: "خطأ غير معروف")
        }
    }
}
