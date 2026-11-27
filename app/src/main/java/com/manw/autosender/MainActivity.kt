package com.manw.autosender

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.provider.ContactsContract
import android.database.Cursor
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
    private val contactRequestCode = 42
    private val volumeRequestCode = 43
    private lateinit var token: EditText
    private lateinit var chat: EditText
    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!AppPassword.isSessionValid(this)) {
            showPasswordGate()
            return
        }
        val prefs = SecurePrefs.get(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 36, 28, 24)
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF07070B.toInt(), 0xFF1B080D.toInt(), 0xFF090A10.toInt())
            )
        }
        fun label(value: String, size: Float = 16f) = TextView(this).apply {
            text = value; textSize = size; setTextColor(0xFFF4F4F5.toInt())
            setPadding(0, 12, 0, 8)
        }
        root.addView(label("☠ 𝓜𝓐𝓝𝓦𓄿𝓐𝓗𝓜𝓔𝓓  ☠", 25f).apply {
            gravity = android.view.Gravity.CENTER
            setTextColor(0xFFFFDADA.toInt())
            setShadowLayer(14f, 0f, 0f, 0xFFB91C1C.toInt())
        })
        root.addView(label("إرسال الصور الجديدة إلى تيليجرام"))
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
        val test = Button(this).apply { text = "اختبار اتصال البوت فقط" }
        root.addView(test)
        root.addView(label("ربط الهاتف بموقع MANW للتحكم عن بُعد", 18f))
        val webUrl = EditText(this).apply {
            hint = "رابط الموقع https://..."
            setSingleLine(true); setTextColor(-1); setHintTextColor(0xFF9CA3AF.toInt())
            setText(prefs.getString("web_base_url", "https://manw-control-center-production.up.railway.app"))
        }
        root.addView(webUrl)
        val webCode = EditText(this).apply {
            hint = "رمز الاقتران الذي يظهر في الموقع"; setSingleLine(true); setTextColor(-1); setHintTextColor(0xFF9CA3AF.toInt())
        }
        root.addView(webCode)
        val pairWeb = Button(this).apply { text = "ربط الهاتف بالموقع" }
        root.addView(pairWeb)
        val webStatus = label(if (prefs.getBoolean("web_paired", false)) "الموقع: الهاتف مقترن" else "الموقع: غير مربوط")
        root.addView(webStatus)
        val unpairWeb = Button(this).apply { text = "فصل الهاتف عن الموقع" }
        root.addView(unpairWeb)
        val enableVolumeSend = Button(this).apply { text = "تفعيل الإرسال بزر خفض الصوت مع الكاميرا العادية" }
        root.addView(enableVolumeSend)
        val disableVolumeSend = Button(this).apply { text = "إيقاف الإرسال بزر خفض الصوت" }
        root.addView(disableVolumeSend)
        val notificationSettings = Button(this).apply { text = "اختيار التطبيقات لإرسال إشعاراتها إلى البوت" }
        root.addView(notificationSettings)
        val changePassword = Button(this).apply { text = "تغيير كلمة سر التطبيق" }
        root.addView(changePassword)
        root.addView(label("بعد التفعيل اسمح لخدمة إمكانية الوصول الخاصة بالتطبيق. افتح كاميرا الهاتف العادية وصوّر؛ الضغط المطوّل على زر خفض الصوت يرسل الصور الجديدة منذ التفعيل إلى تيليجرام. أثناء تفعيل الوضع سيُستخدم زر خفض الصوت للإرسال بدل خفض مستوى الصوت."))
        root.addView(label("بعد تشغيل المزامنة، يراقب التطبيق الصور الجديدة تلقائيًا بخدمة خلفية وإشعار دائم، ويرسلها عند اكتشافها دون الضغط على زر. يوجد فحص احتياطي كل 15 دقيقة. اسمح بالوصول إلى كل الصور؛ الوصول المحدود للصور المختارة لن يتيح مراقبة بقية المعرض. قد يوقف أندرويد الخدمة عند فرض إيقاف التطبيق أو تقييد البطارية."))
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
            if (!hasImagePermission()) ActivityCompat.requestPermissions(this, permissionsToRequest(), requestCode)
            else enableSync()
        }
        stop.setOnClickListener {
            WorkManager.getInstance(this).cancelUniqueWork(ImageSyncWorker.WORK_NAME)
            WorkManager.getInstance(this).cancelUniqueWork(ImageSyncWorker.WORK_NAME + "_now")
            WorkManager.getInstance(this).cancelUniqueWork(ImageSyncWorker.WORK_NAME + "_event")
            prefs.edit().putBoolean("sync_enabled", false).putBoolean("manual_send_enabled", false).apply()
            stopService(android.content.Intent(this, ImageMonitorService::class.java))
            status.text = "الحالة: متوقف"
            toast("تم إيقاف المزامنة")
        }
        enableVolumeSend.setOnClickListener {
            val t = token.text.toString().trim()
            val c = chat.text.toString().trim()
            if (t.isBlank() || c.isBlank()) { toast("أدخل Bot Token و Chat ID أولاً"); return@setOnClickListener }
            val missingPermissions = volumeModePermissions().filter {
                ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
            }
            if (missingPermissions.isNotEmpty()) {
                toast("اسمح بالصور والإشعارات حتى يصلك تنبيه فتح الكاميرا وحالة الإرسال.")
                ActivityCompat.requestPermissions(this, missingPermissions.toTypedArray(), volumeRequestCode)
                return@setOnClickListener
            }
            activateVolumeSendMode()
        }
        disableVolumeSend.setOnClickListener {
            prefs.edit().putBoolean("manual_send_enabled", false).apply()
            toast("تم إيقاف الإرسال بزر خفض الصوت.")
        }
        notificationSettings.setOnClickListener {
            startActivity(Intent(this, NotificationSettingsActivity::class.java))
        }
        changePassword.setOnClickListener { showChangePasswordDialog() }
        test.setOnClickListener {
            val t = token.text.toString().trim()
            val c = chat.text.toString().trim()
            if (t.isBlank() || c.isBlank()) { toast("أدخل بيانات البوت أولًا"); return@setOnClickListener }
            Thread {
                val result = ImageSyncWorker.testBot(t, c)
                runOnUiThread { toast(result) }
            }.start()
        }
        pairWeb.setOnClickListener {
            val base = webUrl.text.toString().trim()
            val code = webCode.text.toString().trim()
            if (base.isBlank() || code.isBlank()) { toast("اكتب رابط الموقع ورمز الاقتران أولاً"); return@setOnClickListener }
            pairWeb.isEnabled = false
            Thread {
                try {
                    val normalized = WebControlClient.normalizeBase(base)
                    val deviceToken = WebControlClient.pair(normalized, code)
                    prefs.edit().putString("web_base_url", normalized).putString("web_device_token", deviceToken)
                        .putBoolean("web_paired", true).apply()
                    runOnUiThread {
                        pairWeb.isEnabled = true
                        webStatus.text = "الموقع: الهاتف مقترن ومتصل بالخدمة"
                        webCode.text.clear()
                        toast("تم ربط الهاتف بالموقع. أوامر الكاميرا تتطلب موافقتك من الهاتف.")
                        startMonitorService()
                    }
                } catch (e: Exception) {
                    runOnUiThread { pairWeb.isEnabled = true; toast("فشل الربط: " + (e.message ?: "تحقق من الرمز والرابط")) }
                }
            }.start()
        }
        unpairWeb.setOnClickListener {
            prefs.edit().remove("web_device_token").putBoolean("web_paired", false).apply()
            webStatus.text = "الموقع: غير مربوط"
            toast("تم فصل الهاتف عن الموقع")
        }
        if (prefs.getBoolean("sync_enabled", false) || prefs.getBoolean("web_paired", false)) {
            status.text = "الحالة: المزامنة مفعّلة"
            if (hasImagePermission()) startMonitorService()
        }
        val error = prefs.getString("last_error", null)
        val last = prefs.getString("last_result", null)
        if (!error.isNullOrBlank()) root.addView(label("آخر خطأ: " + error))
        if (!last.isNullOrBlank()) root.addView(label("آخر نتيجة: " + last))
    }


    private fun showPasswordGate() {
        val prefs = SecurePrefs.get(this)
        val firstSetup = !AppPassword.isConfigured(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setPadding(32, 36, 32, 36)
            background = android.graphics.drawable.GradientDrawable(
                android.graphics.drawable.GradientDrawable.Orientation.TL_BR,
                intArrayOf(0xFF050509.toInt(), 0xFF26080D.toInt(), 0xFF08080D.toInt())
            ).apply { cornerRadius = 0f }
        }
        val emblem = TextView(this).apply {
            text = "☠"
            textSize = 76f
            gravity = android.view.Gravity.CENTER
            setTextColor(0xFFB91C1C.toInt())
            setShadowLayer(22f, 0f, 0f, 0xFFFF0000.toInt())
        }
        val title = TextView(this).apply {
            text = "𝓜𝓐𝓝𝓦𓄿𝓐𝓗𝓜𝓔𝓓"
            textSize = 27f
            gravity = android.view.Gravity.CENTER
            setTextColor(0xFFF8FAFC.toInt())
            setShadowLayer(12f, 0f, 0f, 0xFF991B1B.toInt())
        }
        val subtitle = TextView(this).apply {
            text = if (firstSetup) "أنشئ كلمة سر لحماية التطبيق" else "منطقة محمية • أدخل كلمة السر"
            textSize = 16f
            gravity = android.view.Gravity.CENTER
            setTextColor(0xFFFCA5A5.toInt())
            setPadding(0, 16, 0, 22)
        }
        val password = EditText(this).apply {
            hint = if (firstSetup) "كلمة سر جديدة (6 أحرف على الأقل)" else "كلمة السر"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            setTextColor(-1); setHintTextColor(0xFFBCAAAA.toInt())
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF991B1B.toInt())
        }
        val confirm = EditText(this).apply {
            hint = "أكد كلمة السر"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            setTextColor(-1); setHintTextColor(0xFFBCAAAA.toInt())
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF991B1B.toInt())
            visibility = if (firstSetup) android.view.View.VISIBLE else android.view.View.GONE
        }
        val error = TextView(this).apply {
            text = ""
            gravity = android.view.Gravity.CENTER
            setTextColor(0xFFFCA5A5.toInt())
            setPadding(0, 12, 0, 8)
        }
        val enter = Button(this).apply {
            text = if (firstSetup) "إنشاء كلمة السر والدخول" else "فتح التطبيق"
            setTextColor(-1)
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF991B1B.toInt())
        }
        root.addView(emblem)
        root.addView(title)
        root.addView(subtitle)
        root.addView(password)
        root.addView(confirm)
        root.addView(error)
        root.addView(enter)
        setContentView(root)
        enter.setOnClickListener {
            val pass = password.text.toString()
            if (firstSetup) {
                if (pass.length < 6) { error.text = "كلمة السر لازم تكون 6 أحرف على الأقل"; return@setOnClickListener }
                if (pass != confirm.text.toString()) { error.text = "كلمتا السر غير متطابقتين"; return@setOnClickListener }
                AppPassword.create(this, pass)
                AppPassword.markUnlocked(this)
                recreate()
            } else {
                if (AppPassword.verify(this, pass)) {
                    AppPassword.markUnlocked(this)
                    recreate()
                } else {
                    error.text = "كلمة السر غير صحيحة"
                    password.text.clear()
                }
            }
        }
    }

    private fun showChangePasswordDialog() {
        val current = EditText(this).apply {
            hint = "كلمة السر الحالية"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val next = EditText(this).apply {
            hint = "كلمة السر الجديدة (6 أحرف على الأقل)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val confirm = EditText(this).apply {
            hint = "تأكيد كلمة السر الجديدة"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 8, 24, 0)
            addView(current); addView(next); addView(confirm)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("تغيير كلمة سر MANW")
            .setView(box)
            .setNegativeButton("إلغاء", null)
            .setPositiveButton("حفظ") { _, _ ->
                val old = current.text.toString()
                val fresh = next.text.toString()
                if (!AppPassword.verify(this, old)) {
                    toast("كلمة السر الحالية غير صحيحة")
                } else if (fresh.length < 6 || fresh != confirm.text.toString()) {
                    toast("تأكد من كلمة السر الجديدة (6 أحرف على الأقل)")
                } else {
                    AppPassword.create(this, fresh)
                    AppPassword.markUnlocked(this)
                    toast("تم تغيير كلمة السر")
                }
            }.show()
    }

    @Deprecated("Use Activity Result APIs")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == contactRequestCode && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            var name = "جهة اتصال واتساب"
            var number = ""
            contentResolver.query(uri, arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    name = cursor.getString(0) ?: name
                    number = cursor.getString(1) ?: ""
                }
            }
            if (number.isBlank()) { toast("تعذر قراءة رقم جهة الاتصال"); return }
            SecurePrefs.get(this).edit().putString("wa_name", name).putString("wa_phone", number).apply()
            startActivity(Intent(this, CameraActivity::class.java))
        }
    }

    private fun activateVolumeSendMode() {
        val prefs = SecurePrefs.get(this)
        val t = token.text.toString().trim()
        val c = chat.text.toString().trim()
        val now = System.currentTimeMillis() / 1000L
        prefs.edit().putString("bot_token", t).putString("chat_id", c)
            .putBoolean("sync_enabled", true)
            .putBoolean("manual_send_enabled", true)
            .putLong("scan_from", now - 2L)
            .putLong("manual_batch_since", now)
            .apply()
        val wm = WorkManager.getInstance(this)
        wm.cancelUniqueWork(ImageSyncWorker.WORK_NAME)
        wm.cancelUniqueWork(ImageSyncWorker.WORK_NAME + "_now")
        wm.cancelUniqueWork(ImageSyncWorker.WORK_NAME + "_event")
        wm.enqueueUniquePeriodicWork(ImageSyncWorker.WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<ImageSyncWorker>(15, TimeUnit.MINUTES).build())
        startMonitorService()
        toast("المراقبة التلقائية وزر خفض الصوت مفعّلان. فعّل خدمة إمكانية الوصول مرة واحدة ثم افتح الكاميرا العادية.")
        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    private fun permissionsToRequest(): Array<String> {
        val permissions = mutableListOf(imagePermission())
        if (Build.VERSION.SDK_INT >= 34) permissions.add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        if (Build.VERSION.SDK_INT >= 33) permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        return permissions.toTypedArray()
    }

    private fun volumeModePermissions(): Array<String> {
        val permissions = mutableListOf(imagePermission())
        if (Build.VERSION.SDK_INT >= 33) permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        return permissions.toTypedArray()
    }

    private fun imagePermission(): String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE

    private fun hasImagePermission(): Boolean {
        val full = ContextCompat.checkSelfPermission(this, imagePermission()) == PackageManager.PERMISSION_GRANTED
        val selected = Build.VERSION.SDK_INT >= 34 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED
        return full || selected
    }

    private fun startMonitorService() {
        val intent = android.content.Intent(this, ImageMonitorService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun enableSync() {
        val prefs = SecurePrefs.get(this)
        prefs.edit().putBoolean("sync_enabled", true).putBoolean("manual_send_enabled", false)
            .putLong("scan_from", System.currentTimeMillis() / 1000L - 2L).apply()
        val wm = WorkManager.getInstance(this)
        wm.enqueueUniqueWork(ImageSyncWorker.WORK_NAME + "_now", ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<ImageSyncWorker>().build())
        wm.enqueueUniquePeriodicWork(ImageSyncWorker.WORK_NAME, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<ImageSyncWorker>(15, TimeUnit.MINUTES).build())
        startMonitorService()
        status.text = "الحالة: المراقبة الفورية تعمل في الخلفية"
        toast("تم تشغيل المراقبة الفورية في الخلفية.")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == volumeRequestCode) {
            val permissionsGranted = volumeModePermissions().all {
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            }
            if (permissionsGranted) activateVolumeSendMode()
            else toast("لازم تسمح بالصور والإشعارات لتفعيل أوامر البوت وتنبيهات الكاميرا.")
        } else if (requestCode == this.requestCode && hasImagePermission()) enableSync()
        else if (requestCode == this.requestCode) toast("لا يمكن المزامنة بدون إذن الصور")
    }

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()
}
