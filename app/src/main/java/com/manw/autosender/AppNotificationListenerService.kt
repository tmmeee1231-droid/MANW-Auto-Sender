package com.manw.autosender

import android.app.Notification
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

class AppNotificationListenerService : NotificationListenerService() {
    companion object {
        private val client = OkHttpClient.Builder().connectTimeout(10, TimeUnit.SECONDS).writeTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS).build()
        private val sensitivePattern = Regex("(?i)(otp|one.?time|verification|verify|password|passcode|security code|رمز|كود|تحقق|كلمة المرور|كلمه المرور)")
    }
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val prefs = getSharedPreferences("notification_forwarding", MODE_PRIVATE)
        if (!prefs.getBoolean("enabled", false)) return
        val allowed = prefs.getStringSet("selected_packages", emptySet()) ?: emptySet()
        if (!allowed.contains(sbn.packageName)) return
        val n = sbn.notification ?: return
        if ((n.flags and Notification.FLAG_ONGOING_EVENT) != 0) return
        val extras: Bundle = n.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        val body = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim()
            ?: extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim().orEmpty()
        if (title.isBlank() && body.isBlank()) return
        val combined = "$title\n$body"
        if (sensitivePattern.containsMatchIn(combined)) return
        if (Regex("(^|\\D)\\d{4,8}(\\D|$)").containsMatchIn(combined) &&
            Regex("(?i)(code|رمز|كود|verify|تحقق)").containsMatchIn(combined)) return
        val appName = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString() } catch (_: Exception) { sbn.packageName }
        val secure = SecurePrefs.get(this)
        val token = secure.getString("bot_token", "") ?: ""
        val chatId = secure.getString("chat_id", "") ?: ""
        if (token.isBlank() || chatId.isBlank()) return
        val message = "إشعار جديد من $appName\n" + (if (title.isNotBlank()) "العنوان: $title\n" else "") + (if (body.isNotBlank()) "الرسالة: $body" else "")
        Thread {
            try {
                val request = Request.Builder().url("https://api.telegram.org/bot$token/sendMessage")
                    .post(FormBody.Builder().add("chat_id", chatId).add("text", message.take(3900)).build()).build()
                client.newCall(request).execute().use { }
            } catch (_: Exception) { }
        }.start()
    }
}
