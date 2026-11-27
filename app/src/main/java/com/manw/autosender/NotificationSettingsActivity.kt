package com.manw.autosender

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class NotificationSettingsActivity : AppCompatActivity() {
    private val checks = mutableListOf<Pair<String, CheckBox>>()
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val prefs = getSharedPreferences("notification_forwarding", MODE_PRIVATE)
        val selected = prefs.getStringSet("selected_packages", emptySet()) ?: emptySet()
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 28, 28, 28); setBackgroundColor(0xFF10111A.toInt()) }
        fun label(value: String, size: Float = 16f) = TextView(this).apply { text = value; textSize = size; setTextColor(0xFFF4F4F5.toInt()); setPadding(0, 12, 0, 12) }
        root.addView(label("اختار التطبيقات المسموح بإرسال إشعاراتها إلى بوت تيليجرام", 19f))
        root.addView(label("سيتم إرسال إشعارات التطبيقات المحددة فقط. لا يتم إرسال إشعارات MANW أو أكواد التحقق وكلمات المرور.", 14f))
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = if (Build.VERSION.SDK_INT >= 33) packageManager.queryIntentActivities(launcher, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong())) else {
            @Suppress("DEPRECATION")
            packageManager.queryIntentActivities(launcher, PackageManager.MATCH_ALL)
        }.mapNotNull { info ->
            val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
            if (pkg == packageName) return@mapNotNull null
            val appLabel = info.loadLabel(packageManager)?.toString()?.takeIf { it.isNotBlank() } ?: pkg
            pkg to appLabel
        }.distinctBy { it.first }.sortedBy { it.second.lowercase() }
        for ((pkg, appLabel) in apps) {
            val check = CheckBox(this).apply {
                text = appLabel; tag = pkg; isChecked = selected.contains(pkg); textSize = 16f
                setTextColor(0xFFFFFFFF.toInt()); setPadding(4, 8, 4, 8)
            }
            checks.add(pkg to check); list.addView(check)
        }
        root.addView(list)
        val save = Button(this).apply { text = "حفظ التطبيقات المختارة" }; root.addView(save)
        val permission = Button(this).apply { text = "تفعيل إذن الوصول للإشعارات" }; root.addView(permission)
        root.addView(label("بعد تفعيل الإذن، تقدر ترجع هنا وتغير اختيارك أو توقف الميزة في أي وقت.", 13f))
        save.setOnClickListener {
            val chosen = checks.filter { it.second.isChecked }.map { it.first }.toSet()
            prefs.edit().putStringSet("selected_packages", chosen).putBoolean("enabled", chosen.isNotEmpty()).apply()
            Toast.makeText(this, "تم حفظ " + chosen.size + " تطبيق", Toast.LENGTH_LONG).show()
        }
        permission.setOnClickListener {
            try { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
            catch (_: Exception) { Toast.makeText(this, "افتح إعدادات الإشعارات وفعّل وصول MANW", Toast.LENGTH_LONG).show() }
        }
        setContentView(ScrollView(this).apply { addView(root) })
    }
}
