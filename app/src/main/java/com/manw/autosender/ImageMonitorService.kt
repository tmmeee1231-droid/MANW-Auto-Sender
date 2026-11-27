package com.manw.autosender

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.ComponentName
import android.content.pm.ServiceInfo
import android.database.ContentObserver
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class ImageMonitorService : Service() {
    companion object {
        const val ACTION_REMOTE_CAPTURE = "com.manw.autosender.REMOTE_CAPTURE"
        const val ACTION_REMOTE_STOP = "com.manw.autosender.REMOTE_STOP"
        const val ACTION_REMOTE_ZOOM = "com.manw.autosender.REMOTE_ZOOM"
        const val ACTION_REMOTE_FOCUS = "com.manw.autosender.REMOTE_FOCUS"
        const val ACTION_SCREEN_STOP = "com.manw.autosender.SCREEN_STOP"
        private const val CHANNEL_ID = "manw_image_monitor"
        private const val COMMAND_CHANNEL_ID = "manw_bot_commands"
        private const val NOTIFICATION_ID = 3107
        private const val CAMERA_NOTIFICATION_ID = 3110
        private const val DEBOUNCE_MS = 800L
        private val botClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(35, TimeUnit.SECONDS)
            .build()
    }

    private val handler = Handler(Looper.getMainLooper())
    private var observer: ContentObserver? = null
    @Volatile private var polling = false
    @Volatile private var destroyed = false

    private val pollRunnable = object : Runnable {
        override fun run() {
            if (destroyed || (!SecurePrefs.get(applicationContext).getBoolean("sync_enabled", false) && !SecurePrefs.get(applicationContext).getBoolean("web_paired", false))) return
            if (!polling) {
                polling = true
                Thread {
                    try { pollBotCommands() } finally {
                        polling = false
                        if (!destroyed && (SecurePrefs.get(applicationContext).getBoolean("sync_enabled", false) || SecurePrefs.get(applicationContext).getBoolean("web_paired", false))) {
                            handler.postDelayed(this, 1000L)
                        }
                    }
                }.start()
            } else {
                handler.postDelayed(this, 1000L)
            }
        }
    }

    private val scanRunnable = Runnable {
        if (SecurePrefs.get(applicationContext).getBoolean("sync_enabled", false)) {
            val request = OneTimeWorkRequestBuilder<ImageSyncWorker>().build()
            WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                ImageSyncWorker.WORK_NAME + "_event",
                ExistingWorkPolicy.APPEND_OR_REPLACE,
                request
            )
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        createCommandNotificationChannel()
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
        registerImageObserver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!SecurePrefs.get(applicationContext).getBoolean("sync_enabled", false) && !SecurePrefs.get(applicationContext).getBoolean("web_paired", false)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (observer == null) registerImageObserver()
        handler.removeCallbacks(pollRunnable)
        handler.post(pollRunnable)
        return START_STICKY
    }

    private fun pollBotCommands() {
        val prefs = SecurePrefs.get(applicationContext)
        pollWebsiteControl(prefs)
        val token = prefs.getString("bot_token", "") ?: ""
        val allowedChat = prefs.getString("chat_id", "")?.trim().orEmpty()
        if (token.isBlank() || allowedChat.isBlank()) return
        val offset = prefs.getLong("telegram_update_offset", 0L)
        val url = "https://api.telegram.org/bot$token/getUpdates?timeout=5&offset=$offset&allowed_updates=%5B%22message%22%5D"
        val request = Request.Builder().url(url).get().build()
        try {
            botClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) return
                val json = JSONObject(body)
                if (!json.optBoolean("ok")) return
                val updates = json.optJSONArray("result") ?: return
                for (i in 0 until updates.length()) {
                    val update = updates.optJSONObject(i) ?: continue
                    val updateId = update.optLong("update_id", -1L)
                    if (updateId >= 0L) prefs.edit().putLong("telegram_update_offset", updateId + 1L).apply()
                    val message = update.optJSONObject("message") ?: continue
                    val chatId = message.optJSONObject("chat")?.opt("id")?.toString().orEmpty()
                    if (chatId != allowedChat) continue
                    val command = message.optString("text").trim().substringBefore(" ").substringBefore("@")
                    when (command) {
                        "/start" -> {
                            sendBotMessage(token, allowedChat, "أهلاً بك في MANW. استخدم /help لعرض الأوامر المتاحة.")
                        }
                        "/help" -> {
                            sendBotMessage(token, allowedChat, "أوامر MANW المتاحة:\n/start — بدء البوت\n/help — المساعدة\n/status — حالة المزامنة\n/battery — البطارية\n/device — معلومات الجهاز\n/storage — مساحة التخزين\n/network — حالة الإنترنت\n/flash — تشغيل/إيقاف الفلاش\n/vibrate — اهتزاز الهاتف\n/ring — تنبيه صوتي\n/volume — مستوى الصوت\n/brightness — سطوع الشاشة\n/apps — التطبيقات الظاهرة في المشغّل\n/gallery — عدد الصور والفيديوهات\n/notifications — حالة إذن الإشعارات\n/screen — طلب مشاركة الشاشة بموافقة الهاتف\n/record — حالة ميزة التسجيل\n\nأوامر الكاميرا كما هي: /camera /capture /stopcamera /send")
                        }
                        "/battery" -> {
                            val batteryManager = getSystemService(BATTERY_SERVICE) as android.os.BatteryManager
                            val level = batteryManager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
                            val charging = if (batteryManager.isCharging) "قيد الشحن" else "غير موصل بالشاحن"
                            val safeLevel = if (level in 0..100) "$level%" else "غير متاح"
                            sendBotMessage(token, allowedChat, "البطارية: $safeLevel\nالحالة: $charging")
                        }
                        "/device" -> {
                            val model = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
                            val androidVersion = Build.VERSION.RELEASE ?: "غير معروف"
                            val appVersion = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (_: Exception) { "غير معروف" }
                            sendBotMessage(token, allowedChat, "الجهاز: $model\nAndroid: $androidVersion\nإصدار MANW: ${appVersion ?: "غير معروف"}")
                        }
                        "/storage" -> sendBotMessage(token, allowedChat, storageSummary())
                        "/network" -> sendBotMessage(token, allowedChat, networkSummary())
                        "/flash" -> sendBotMessage(token, allowedChat, toggleTorch())
                        "/vibrate" -> sendBotMessage(token, allowedChat, vibratePhone())
                        "/ring" -> sendBotMessage(token, allowedChat, ringPhone())
                        "/volume" -> sendBotMessage(token, allowedChat, volumeSummary())
                        "/brightness" -> sendBotMessage(token, allowedChat, brightnessSummary())
                        "/apps" -> sendBotMessage(token, allowedChat, appsSummary())
                        "/gallery" -> sendBotMessage(token, allowedChat, gallerySummary())
                        "/notifications" -> sendBotMessage(token, allowedChat, notificationPermissionSummary())
                        "/screen" -> {
                            if (prefs.getBoolean("web_paired", false)) {
                                showScreenShareRequestNotification()
                                sendBotMessage(token, allowedChat, "وصل طلب مشاركة الشاشة. افتح إشعار MANW ووافق بنفسك من شاشة أندرويد؛ لن تبدأ المشاركة قبل موافقتك.")
                            } else sendBotMessage(token, allowedChat, "مشاركة الشاشة تحتاج ربط الهاتف بلوحة MANW أولاً، ثم الموافقة من الهاتف.")
                        }
                        "/record" -> sendBotMessage(token, allowedChat, "تسجيل الشاشة كملف غير متاح في الإصدار الحالي. استخدم /screen لطلب مشاركة مباشرة بعد موافقة أندرويد.")
                        "/camera" -> {
                            showCameraRequestNotification()
                            sendBotMessage(token, allowedChat, "وصل طلب الكاميرا الخلفية. افتح إشعار MANW واضغط «موافقة وبدء الكاميرا». بعد الموافقة أرسل /capture لالتقاط صورة أو /stopcamera للإيقاف.")
                        }
                        "/capture" -> {
                            if (prefs.getBoolean("camera_active", false)) {
                                sendBroadcast(Intent(ACTION_REMOTE_CAPTURE).setPackage(packageName))
                                sendBotMessage(token, allowedChat, "وصل أمر التصوير إلى جلسة الكاميرا النشطة. هبعتلك تحديث تجهيز الصورة ونسبة رفعها.")
                            } else {
                                showCameraRequestNotification()
                                sendBotMessage(token, allowedChat, "وصل أمر التصوير، لكن جلسة الكاميرا مش شغالة. افتح إشعار MANW واضغط «موافقة وبدء الكاميرا»، وبعدها ابعت /capture مرة تانية.")
                            }
                        }
                        "/stopcamera" -> {
                            sendBroadcast(Intent(ACTION_REMOTE_STOP).setPackage(packageName))
                            sendBotMessage(token, allowedChat, "تم إرسال طلب إيقاف جلسة الكاميرا إلى الهاتف.")
                        }
                        "/status" -> {
                            val status = prefs.getString("last_result", null)
                                ?: "المراقبة تعمل. لم تُسجل نتيجة إرسال بعد."
                            sendBotMessage(token, allowedChat, status.take(3500))
                        }
                        "/send" -> {
                            val data = androidx.work.Data.Builder()
                                .putBoolean(ImageSyncWorker.KEY_MANUAL_BATCH, true).build()
                            val work = OneTimeWorkRequestBuilder<ImageSyncWorker>().setInputData(data).build()
                            WorkManager.getInstance(applicationContext).enqueueUniqueWork(
                                "manw_bot_manual_send", ExistingWorkPolicy.REPLACE, work
                            )
                            sendBotMessage(token, allowedChat, "بدأ فحص الصور وإعادة إرسال الصور المعلّقة. راجع إشعار الهاتف لمعرفة النتيجة المؤكدة.")
                        }
                    }
                }
            }
        } catch (_: Exception) {
            // Keep the foreground monitor alive; the next long-poll retries automatically.
        }
    }

    private fun storageSummary(): String {
        return try {
            val stat = android.os.StatFs(android.os.Environment.getDataDirectory().path)
            val total = stat.totalBytes / (1024L * 1024L * 1024L)
            val free = stat.availableBytes / (1024L * 1024L * 1024L)
            "مساحة التخزين الداخلية: إجمالي ${total} GB، متاح ${free} GB"
        } catch (_: Exception) { "تعذر قراءة مساحة التخزين." }
    }

    private fun networkSummary(): String {
        return try {
            val cm = getSystemService(CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
            val network = cm.activeNetwork ?: return "لا يوجد اتصال إنترنت نشط."
            val caps = cm.getNetworkCapabilities(network) ?: return "حالة الشبكة غير متاحة."
            val kind = when {
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "بيانات الهاتف"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                else -> "اتصال آخر"
            }
            "الاتصال: $kind\nالإنترنت: ${if (caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)) "متاح" else "غير متاح"}"
        } catch (_: Exception) { "تعذر قراءة حالة الشبكة." }
    }

    private fun toggleTorch(): String {
        if (Build.VERSION.SDK_INT < 23) return "الفلاش يحتاج Android 6 أو أحدث."
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED)
            return "إذن الكاميرا غير ممنوح. افتح التطبيق وامنح الإذن أولاً."
        return try {
            val manager = getSystemService(android.hardware.camera2.CameraManager::class.java)
            val cameraId = manager.cameraIdList.firstOrNull { id ->
                manager.getCameraCharacteristics(id).get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            } ?: return "لا يوجد فلاش متاح."
            val prefs = SecurePrefs.get(this)
            val enabled = !prefs.getBoolean("bot_torch_on", false)
            manager.setTorchMode(cameraId, enabled)
            prefs.edit().putBoolean("bot_torch_on", enabled).apply()
            if (enabled) "تم تشغيل الفلاش." else "تم إيقاف الفلاش."
        } catch (_: Exception) { "تعذر تغيير حالة الفلاش. تأكد أن الكاميرا غير مستخدمة." }
    }

    private fun vibratePhone(): String {
        return try {
            val vibrator = if (Build.VERSION.SDK_INT >= 31) getSystemService(android.os.VibratorManager::class.java).defaultVibrator
                else @Suppress("DEPRECATION") (getSystemService(VIBRATOR_SERVICE) as android.os.Vibrator)
            if (!vibrator.hasVibrator()) return "الهاتف لا يدعم الاهتزاز."
            if (Build.VERSION.SDK_INT >= 26) vibrator.vibrate(android.os.VibrationEffect.createOneShot(500, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
            else @Suppress("DEPRECATION") vibrator.vibrate(500)
            "تم إرسال اهتزاز تنبيهي قصير للهاتف."
        } catch (_: Exception) { "تعذر تشغيل الاهتزاز." }
    }

    private fun ringPhone(): String {
        return try {
            val uri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
                ?: return "لا يوجد صوت تنبيه افتراضي."
            val ringtone = android.media.RingtoneManager.getRingtone(applicationContext, uri)
                ?: return "تعذر تحميل صوت التنبيه."
            ringtone.play()
            handler.postDelayed({ try { if (ringtone.isPlaying) ringtone.stop() } catch (_: Exception) { } }, 4000L)
            "تم تشغيل نغمة تنبيه لمدة قصيرة."
        } catch (_: Exception) { "تعذر تشغيل صوت التنبيه." }
    }

    private fun volumeSummary(): String {
        val audio = getSystemService(AUDIO_SERVICE) as android.media.AudioManager
        val current = audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC)
        val max = audio.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
        return "مستوى صوت الوسائط: $current من $max"
    }

    private fun brightnessSummary(): String {
        return try {
            val value = Settings.System.getInt(contentResolver, Settings.System.SCREEN_BRIGHTNESS)
            "سطوع الشاشة: ${(value * 100 / 255)}%"
        } catch (_: Exception) { "تعذر قراءة سطوع الشاشة." }
    }

    private fun appsSummary(): String {
        return try {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val apps = if (Build.VERSION.SDK_INT >= 33)
                packageManager.queryIntentActivities(intent, android.content.pm.PackageManager.ResolveInfoFlags.of(0))
            else @Suppress("DEPRECATION") packageManager.queryIntentActivities(intent, 0)
            val names = apps.mapNotNull { it.loadLabel(packageManager)?.toString() }.distinct().sorted()
            if (names.isEmpty()) "لم يتم العثور على تطبيقات ظاهرة في المشغّل."
            else "التطبيقات الظاهرة في المشغّل (${names.size}):\n" + names.take(45).joinToString("\n") +
                if (names.size > 45) "\n... والقائمة مختصرة." else ""
        } catch (_: Exception) { "تعذر قراءة قائمة التطبيقات." }
    }

    private fun gallerySummary(): String {
        if (androidx.core.content.ContextCompat.checkSelfPermission(this,
                if (Build.VERSION.SDK_INT >= 33) android.Manifest.permission.READ_MEDIA_IMAGES else android.Manifest.permission.READ_EXTERNAL_STORAGE
            ) != android.content.pm.PackageManager.PERMISSION_GRANTED) return "إذن الصور غير ممنوح؛ افتح التطبيق وامنح إذن المعرض."
        return try {
            fun count(uri: Uri): Int = contentResolver.query(uri, arrayOf(android.provider.MediaStore.MediaColumns._ID), null, null, null)?.use { it.count } ?: 0
            val images = count(MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
            val videos = if (Build.VERSION.SDK_INT >= 33 && androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_MEDIA_VIDEO) != android.content.pm.PackageManager.PERMISSION_GRANTED) -1
                else count(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            "المعرض: $images صورة، " + if (videos >= 0) "$videos فيديو." else "عدد الفيديوهات غير متاح بدون إذن الفيديو."
        } catch (_: Exception) { "تعذر قراءة عدد ملفات المعرض." }
    }

    private fun notificationPermissionSummary(): String {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners").orEmpty()
        val component = ComponentName(this, AppNotificationListenerService::class.java).flattenToString()
        val granted = enabled.split(':').any { it.equals(component, ignoreCase = true) }
        val prefs = getSharedPreferences("notification_forwarding", MODE_PRIVATE)
        val selected = prefs.getStringSet("selected_packages", emptySet())?.size ?: 0
        val forwarding = prefs.getBoolean("enabled", false)
        return "إذن قراءة الإشعارات: ${if (granted) "مفعّل" else "غير مفعّل"}\nالتطبيقات المختارة: $selected\nإعادة التوجيه: ${if (forwarding && granted) "مفعّلة" else "غير مفعّلة"}"
    }

    private fun pollWebsiteControl(prefs: android.content.SharedPreferences) {
        if (!prefs.getBoolean("web_paired", false)) return
        val base = prefs.getString("web_base_url", "").orEmpty()
        val deviceToken = prefs.getString("web_device_token", "").orEmpty()
        if (base.isBlank() || deviceToken.isBlank()) return
        try {
            val batteryManager = getSystemService(BATTERY_SERVICE) as android.os.BatteryManager
            val battery = batteryManager.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY).coerceIn(0, 100)
            WebControlClient.heartbeat(base, deviceToken, battery, prefs.getBoolean("camera_active", false), isRemoteAccessibilityEnabled())
            WebControlClient.pollCommands(base, deviceToken).forEach { remote ->
                when (remote.command) {
                    "camera_start" -> showCameraRequestNotification()
                    "screen_start" -> showScreenShareRequestNotification()
                    "accessibility_settings" -> showAccessibilitySettingsNotification()
                    "screen_stop" -> stopService(Intent(this, ScreenShareService::class.java))
                    "capture" -> sendBroadcast(Intent(ACTION_REMOTE_CAPTURE).setPackage(packageName))
                    "camera_stop" -> sendBroadcast(Intent(ACTION_REMOTE_STOP).setPackage(packageName))
                    "focus_center" -> sendBroadcast(Intent(ACTION_REMOTE_FOCUS).setPackage(packageName))
                    "whatsapp_open" -> showWhatsAppOpenNotification(remote.phone, remote.message)
                    // Recording is not exposed until a visible, consent-based recorder is implemented.
                    "record_start", "record_stop" -> Unit
                    else -> if (remote.command.startsWith("zoom:")) {
                        val zoom = remote.command.substringAfter(":").toFloatOrNull()?.coerceIn(0f, 1f)
                        if (zoom != null) sendBroadcast(Intent(ACTION_REMOTE_ZOOM).setPackage(packageName).putExtra("zoom", zoom))
                    }
                }
            }
        } catch (_: Exception) {
            // Retry on the next monitor cycle; never stop the local image monitor for a website outage.
        }
    }

    private fun isRemoteAccessibilityEnabled(): Boolean {
        val expected = ComponentName(this, RemoteControlAccessibilityService::class.java).flattenToString()
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
    }

    private fun showAccessibilitySettingsNotification() {
        val settingsIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(this, 3182, settingsIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0))
        val notification = NotificationCompat.Builder(this, COMMAND_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle("MANW — تفعيل التحكم باللمس")
            .setContentText("اضغط لفتح إعدادات إمكانية الوصول وتفعيل خدمة MANW بنفسك.")
            .setStyle(NotificationCompat.BigTextStyle().bigText("طلبت لوحة التحكم تفعيل اللمس عن بُعد. افتح إعدادات إمكانية الوصول، واختر خدمة MANW وفعّلها بنفسك. لن يتم تفعيلها تلقائيًا، ويمكنك إيقافها من إعدادات أندرويد في أي وقت."))
            .setContentIntent(pending).setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH).build()
        try { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(3182, notification) }
        catch (_: SecurityException) { }
    }

    private fun showWhatsAppOpenNotification(phone: String, message: String) {
        if (phone.length !in 8..15 || !phone.all { it.isDigit() }) return
        val uri = android.net.Uri.parse("https://wa.me/" + phone).buildUpon().apply {
            if (message.isNotBlank()) appendQueryParameter("text", message)
        }.build()
        val openIntent = Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pending = PendingIntent.getActivity(
            this, phone.hashCode(), openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        val notification = NotificationCompat.Builder(this, COMMAND_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setContentTitle("MANW — طلب فتح واتساب")
            .setContentText("اضغط لفتح محادثة الرقم المحدد على هذا الهاتف.")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                if (message.isBlank()) "اضغط لفتح محادثة واتساب مع الرقم $phone."
                else "اضغط لفتح محادثة واتساب مع الرقم $phone ورسالة جاهزة للمراجعة. لن تُرسل الرسالة تلقائيًا."
            ))
            .setContentIntent(pending).setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH).build()
        try { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(phone.hashCode(), notification) }
        catch (_: SecurityException) { }
    }

    private fun sendBotMessage(token: String, chatId: String, message: String) {
        // Present bot replies as a compact Termux-style terminal panel.
        val safeMessage = message.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        val terminalMessage = "<pre>┌─[ MANW // TERMUX ]────────────\n│ STATUS: ONLINE\n├───────────────────────────────\n$safeMessage\n└─[ END OF TRANSMISSION ]</pre>"
        val request = Request.Builder()
            .url("https://api.telegram.org/bot$token/sendMessage")
            .post(FormBody.Builder()
                .add("chat_id", chatId)
                .add("text", terminalMessage)
                .add("parse_mode", "HTML")
                .build())
            .build()
        try { botClient.newCall(request).execute().close() } catch (_: Exception) { }
    }

    private fun showCameraRequestNotification() {
        val cameraIntent = Intent(this, CameraActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            putExtra(CameraActivity.EXTRA_REMOTE_REQUEST, true)
        }
        val cameraPendingIntent = PendingIntent.getActivity(
            this, CAMERA_NOTIFICATION_ID, cameraIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        val notification = NotificationCompat.Builder(this, COMMAND_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("MANW — طلب استخدام الكاميرا الخلفية")
            .setContentText("اضغط لمراجعة الطلب. الكاميرا لن تعمل إلا بعد موافقتك.")
            .setStyle(NotificationCompat.BigTextStyle().bigText("وصل طلب تشغيل كاميرا من لوحة التحكم أو بوت تيليجرام. افتح الإشعار ثم اضغط «موافقة وبدء الكاميرا» لبدء جلسة التصوير عن بُعد."))
            .setContentIntent(cameraPendingIntent).setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH).build()
        try { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(CAMERA_NOTIFICATION_ID, notification) }
        catch (_: SecurityException) { }
    }

    private fun showScreenShareRequestNotification() {
        val shareIntent = Intent(this, ScreenShareActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val pending = PendingIntent.getActivity(this, 3181, shareIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0))
        val notification = NotificationCompat.Builder(this, COMMAND_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setContentTitle("MANW — طلب مشاركة شاشة الهاتف")
            .setContentText("اضغط لمراجعة الطلب والموافقة على عرض الشاشة وواتساب.")
            .setStyle(NotificationCompat.BigTextStyle().bigText("لن تبدأ مشاركة الشاشة إلا بعد موافقتك من أندرويد. سيظل إشعار المشاركة ظاهرًا ويمكنك إيقافها في أي وقت."))
            .setContentIntent(pending).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_HIGH).build()
        try { (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(3181, notification) }
        catch (_: SecurityException) { }
    }

    private fun registerImageObserver() {
        if (observer != null) return
        observer = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                if (!SecurePrefs.get(applicationContext).getBoolean("sync_enabled", false)) return
                handler.removeCallbacks(scanRunnable)
                handler.postDelayed(scanRunnable, DEBOUNCE_MS)
            }
        }
        contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, observer!!)
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0)
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .setContentTitle("MANW Auto Sender يعمل")
            .setContentText("مراقبة الصور والرد على أوامر بوت تيليجرام")
            .setContentIntent(openApp)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "مراقبة الصور والإرسال التلقائي", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    private fun createCommandNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(COMMAND_CHANNEL_ID, "أوامر كاميرا تيليجرام", NotificationManager.IMPORTANCE_HIGH)
            channel.description = "تنبيه عند طلب فتح كاميرا الهاتف من بوتك"
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        destroyed = true
        handler.removeCallbacksAndMessages(null)
        observer?.let { contentResolver.unregisterContentObserver(it) }
        observer = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
