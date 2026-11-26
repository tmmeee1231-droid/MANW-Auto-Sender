package com.manw.autosender

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {
    private val requestCode = 41
    private lateinit var token: EditText
    private lateinit var chat: EditText
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = SecurePrefs.get(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 36, 28, 24)
            setBackgroundColor(0xFF10111A.toInt())
        }
        fun label(value: String, size: Float = 16f) = TextView(this).apply {
            text = value; textSize = size; setTextColor(0xFFF4F4F5.toInt())
            setPadding(0, 12, 0, 8)
        }
        root.addView(label("𝑀𝒜𝒩𝒲 Auto Sender", 25f))
        root.addView(label("إرسال الصور الجديدة إلى تيليجرام تلقائيًا"))
        token = EditText(this).apply {
            hint = "Bot Token الجديد"; setSingleLine(true); setTextColor(-1); setHintTextColor(0xFF9CA3AF.toInt())
            setText(prefs.getString("bot_token", ""))
        }
        root.addView(token)
        chat = EditText(this).apply {
            hint = "Chat ID"; setSingleLine(true); inputType = 2; setTextColor(-1); setHintTextColor(0xFF9CA3AF.toInt())
            setText(prefs.getString("chat_id", ""))
        }
        root.addView(chat)
        status = label("الحالة: متوقف")
        root.addView(status)
        val save = Button(this).apply { text = "حفظ الإعدادات" }
        root.addView(save)
        val start = Button(this).apply { text = "تشغيل المزامنة" }
        root.addView(start)
        val stop = Button(this).apply { text = "إيقاف المزامنة" }
        root.addView(stop)
        val test = Button(this).apply { text = "اختبار البوت وChat ID" }
        root.addView(test)
        root.addView(label("مهم: يبدأ التطبيق من الصور الجديدة بعد التفعيل فقط. الفحص الفوري عند التشغيل ثم كل 15 دقيقة تقريبًا. اسمح بالوصول إلى الصور."))
        setContentView(ScrollView(this).apply { addView(root) })

        save.setOnClickListener {
            val t = token.text.toString().trim()
            val c = chat.text.toString().trim()
            if (t.isBlank() || c.isBlank()) { toast("أدخل Bot Token و Chat ID"); return@setOnClickListener }
            prefs.edit().putString("bot_token", t).putString("chat_id", c).apply()
            toast("تم حفظ الإعدادات بشكل مشفّر")
        }
        start.setOnClickListener {
            val t = token.text.toString().trim()
            val c = chat.text.toString().trim()
            if (t.isBlank() || c.isBlank()) { toast("أدخل بيانات البوت أولًا"); return@setOnClickListener }
            prefs.edit().putString("bot_token", t).putString("chat_id", c).apply()
            if (!hasImagePermission()) {
                ActivityCompat.requestPermissions(this, arrayOf(imagePermission()), requestCode)
            } else enableSync()
        }
        stop.setOnClickListener {
            WorkManager.getInstance(this).cancelUniqueWork(ImageSyncWorker.WORK_NAME)
            WorkManager.getInstance(this).cancelUniqueWork(ImageSyncWorker.WORK_NAME + "_now")
            prefs.edit().putBoolean("sync_enabled", false).apply()
            status.text = "الحالة: متوقف"
            toast("تم إيقاف المزامنة")
        }
        test.setOnClickListener {
            val t = token.text.toString().trim()
            val c = chat.text.toString().trim()
            if (t.isBlank() || c.isBlank()) { toast("أدخل بيانات البوت أولًا"); return@setOnClickListener }
            Thread {
                val result = ImageSyncWorker.testBot(t, c)
                runOnUiThread { toast(result) }
            }.start()
        }
        if (prefs.getBoolean("sync_enabled", false)) status.text = "الحالة: المزامنة مفعّلة"
        val error = prefs.getString("last_error", null)
        if (!error.isNullOrBlank()) root.addView(label("آخر خطأ: " + error))
    }

    private fun imagePermission(): String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun hasImagePermission() =
        ContextCompat.checkSelfPermission(this, imagePermission()) == PackageManager.PERMISSION_GRANTED

    private fun enableSync() {
        val prefs = SecurePrefs.get(this)
        prefs.edit().putBoolean("sync_enabled", true)
            .putLong("scan_from", System.currentTimeMillis() / 1000L - 2L).apply()
        val wm = WorkManager.getInstance(this)
        val immediate = OneTimeWorkRequestBuilder<ImageSyncWorker>().build()
        wm.enqueueUniqueWork(ImageSyncWorker.WORK_NAME + "_now", ExistingWorkPolicy.REPLACE, immediate)
        val request = PeriodicWorkRequestBuilder<ImageSyncWorker>(15, TimeUnit.MINUTES).build()
        wm.enqueueUniquePeriodicWork(ImageSyncWorker.WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE, request)
        status.text = "الحالة: المزامنة مفعّلة"
        toast("تم التشغيل وبدأ فحص الصور الآن")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == this.requestCode && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) enableSync()
        else toast("لا يمكن المزامنة بدون إذن الصور")
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
