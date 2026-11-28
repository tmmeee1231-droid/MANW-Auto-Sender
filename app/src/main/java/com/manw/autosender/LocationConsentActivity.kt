package com.manw.autosender

import android.Manifest
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class LocationConsentActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private val client = OkHttpClient.Builder().callTimeout(20, TimeUnit.SECONDS).build()
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) getOneLocation() else status.text = "لم تمنح إذن الموقع؛ لم يتم إرسال أي موقع."
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 32, 28, 24) }
        status = TextView(this).apply {
            textSize = 18f
            text = "وصل طلب مشاركة موقع الهاتف. سيتم تحديد الموقع مرة واحدة فقط وإرساله إلى محادثة تيليجرام المضبوطة. لن يعمل تتبع مستمر."
        }
        val approve = Button(this).apply { text = "موافقة وإرسال موقعي مرة واحدة" }
        val reject = Button(this).apply { text = "رفض" }
        root.addView(status); root.addView(approve); root.addView(reject); setContentView(root)
        approve.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) getOneLocation()
            else permission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        reject.setOnClickListener { finish() }
    }
    private fun getOneLocation() {
        status.text = "جاري تحديد موقعك مرة واحدة…"
        try {
            val manager = getSystemService(LOCATION_SERVICE) as LocationManager
            if (Build.VERSION.SDK_INT >= 30) {
                manager.getCurrentLocation(LocationManager.GPS_PROVIDER, CancellationSignal(), mainExecutor) { location ->
                    if (location != null) sendLocation(location) else {
                        val fallback = manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                        if (fallback != null) sendLocation(fallback) else status.text = "لم يتم الحصول على موقع. تحقق من تشغيل GPS وحاول مرة أخرى."
                    }
                }
            } else {
                @Suppress("DEPRECATION")
                val last = manager.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    ?: manager.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                if (last != null) sendLocation(last) else status.text = "لم يتوفر موقع محفوظ على هذا الجهاز. جرّب بعد تشغيل GPS."
            }
        } catch (e: SecurityException) { status.text = "إذن الموقع غير متاح." }
        catch (e: Exception) { status.text = "تعذر تحديد الموقع: ${e.message}" }
    }
    private fun sendLocation(location: Location) {
        status.text = "تم تحديد الموقع؛ جاري إرساله…"
        Thread {
            try {
                val prefs = SecurePrefs.get(this)
                val token = prefs.getString("bot_token", "").orEmpty()
                val chat = prefs.getString("chat_id", "").orEmpty()
                if (token.isBlank() || chat.isBlank()) throw IllegalStateException("إعدادات تيليجرام غير مكتملة")
                val maps = "https://maps.google.com/?q=${location.latitude},${location.longitude}"
                val age = ((System.currentTimeMillis() - location.time).coerceAtLeast(0) / 1000)
                val text = "موقع الهاتف بموافقة صاحبه:\n$maps\nالدقة التقريبية: ${location.accuracy.toInt()} متر\nعمر قراءة الموقع: $age ثانية"
                val req = Request.Builder().url("https://api.telegram.org/bot$token/sendMessage")
                    .post(FormBody.Builder().add("chat_id", chat).add("text", text).build()).build()
                client.newCall(req).execute().use { response ->
                    runOnUiThread { status.text = if (response.isSuccessful) "تم إرسال الموقع مرة واحدة." else "فشل إرسال الموقع (HTTP ${response.code})." }
                }
            } catch (e: Exception) { runOnUiThread { status.text = "تعذر إرسال الموقع: ${e.message}" } }
        }.start()
    }
}
