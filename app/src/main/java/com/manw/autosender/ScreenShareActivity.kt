package com.manw.autosender

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class ScreenShareActivity : AppCompatActivity() {
    companion object { const val EXTRA_SCREENSHOT_ONLY = "screenshot_only" }
    private val screenshotOnly by lazy { intent.getBooleanExtra(EXTRA_SCREENSHOT_ONLY, false) }
    private val request = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            val start = Intent(this, ScreenShareService::class.java)
                .setAction(ScreenShareService.ACTION_START)
                .putExtra(ScreenShareService.EXTRA_RESULT_CODE, result.resultCode)
                .putExtra(ScreenShareService.EXTRA_RESULT_DATA, result.data)
                .putExtra(ScreenShareService.EXTRA_SCREENSHOT_ONLY, screenshotOnly)
            ContextCompat.startForegroundService(this, start)
            finish()
        } else {
            status.text = "تم رفض مشاركة الشاشة. لم يتم بث أي محتوى."
        }
    }
    private lateinit var status: TextView
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 32, 28, 24)
            setBackgroundColor(0xFF10111A.toInt())
        }
        status = TextView(this).apply {
            text = if (screenshotOnly) "طلب أخذ لقطة واحدة من شاشة الهاتف. ستظهر موافقة أندرويد، ولن يتم إرسال أي صورة إذا رفضت." else "طلب الموقع عرض شاشة الهاتف وواتساب. سيظهر إشعار دائم أثناء المشاركة، ويمكنك إيقافها في أي وقت."
            textSize = 17f
            setTextColor(-1)
            setPadding(8, 18, 8, 24)
        }
        root.addView(status)
        root.addView(Button(this).apply {
            text = if (screenshotOnly) "موافقة وأخذ لقطة الشاشة" else "موافقة ومشاركة الشاشة"
            setOnClickListener {
                val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
                request.launch(manager.createScreenCaptureIntent())
            }
        })
        root.addView(Button(this).apply {
            text = "رفض الطلب"
            setOnClickListener { finish() }
        })
        setContentView(root)
    }
}
