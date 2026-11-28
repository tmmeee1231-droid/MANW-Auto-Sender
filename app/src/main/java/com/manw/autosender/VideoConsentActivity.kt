package com.manw.autosender

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class VideoConsentActivity : AppCompatActivity() {
    companion object { const val EXTRA_DURATION = "requested_video_duration" }
    private var duration = 30
    private lateinit var message: TextView
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startApprovedVideo() else message.text = "لم تمنح إذن الكاميرا؛ لم يبدأ التسجيل."
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        duration = intent.getIntExtra(EXTRA_DURATION, 30).coerceIn(5, 300)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 32, 28, 24) }
        message = TextView(this).apply {
            textSize = 18f
            text = "طلب تسجيل فيديو بالكاميرا الخلفية لمدة تصل إلى $duration ثانية.\n\nالتسجيل صامت (بدون صوت)، وسيظهر إشعار دائم أثناءه. لن يبدأ إلا بعد ضغطك على موافقة."
        }
        val approve = Button(this).apply { text = "موافقة وبدء التسجيل" }
        val reject = Button(this).apply { text = "رفض" }
        root.addView(message); root.addView(approve); root.addView(reject); setContentView(root)
        approve.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startApprovedVideo()
            else permission.launch(Manifest.permission.CAMERA)
        }
        reject.setOnClickListener { finish() }
    }
    private fun startApprovedVideo() {
        try {
            val i = Intent(this, VideoRecordingService::class.java)
                .setAction(VideoRecordingService.ACTION_START)
                .putExtra(VideoRecordingService.EXTRA_DURATION, duration)
            ContextCompat.startForegroundService(this, i)
            finish()
        } catch (e: Exception) { message.text = "تعذر بدء التسجيل: ${e.message}" }
    }
}
