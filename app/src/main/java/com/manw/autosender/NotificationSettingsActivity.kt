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
        try {
        val prefs = getSharedPreferences("notification_forwarding", MODE_PRIVATE)
        val selected = try { prefs.getStringSet("selected_packages", emptySet())?.toSet() ?: emptySet() } catch (_: Exception) { emptySet() }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(28, 28, 28, 28)
            setBackgroundColor(0xFF10111A.toInt())
        }
        fun label(value: String, size: Float = 16f) = TextView(this).apply {
            text = value
            textSize = size
            setTextColor(0xFFF4F4F5.toInt())
            setPadding(0, 12, 0, 12)
        }

        root.addView(label("اختار التطبيقات المسموح بإرسال إشعاراتها إلى بوت تيليجرام", 19f))
        root.addView(label("سيتم إرسال إشعارات التطبيقات المحددة فقط. لا يتم إرسال إشعارات MANW أو أكواد التحقق وكلمات المرور.", 14f))
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(list)
        // Display the screen immediately; package enumeration can be slow on some devices.
        setContentView(ScrollView(this).apply { addView(root) })

        // Use the regular launcher query and isolate package-manager failures so
        // a device-specific package entry cannot crash the whole settings screen.
        val apps = try {
            val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val activities = if (Build.VERSION.SDK_INT >= 33) {
                packageManager.queryIntentActivities(launcher, PackageManager.ResolveInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                packageManager.queryIntentActivities(launcher, 0)
            }
            activities.mapNotNull { info ->
                try {
                    val pkg = info.activityInfo?.packageName ?: return@mapNotNull null
                    if (pkg == packageName) return@mapNotNull null
                    val appLabel = info.loadLabel(packageManager)?.toString()?.takeIf { it.isNotBlank() } ?: pkg
                    pkg to appLabel
                } catch (_: Exception) {
                    null
                }
            }.distinctBy { it.first }.sortedBy { it.second.lowercase() }
        } catch (_: Exception) {
            emptyList()
        }

        if (apps.isEmpty()) {
            list.addView(label("تعذر تحميل قائمة التطبيقات على هذا الجهاز. جرّب إعادة فتح القسم، ويمكنك تفعيل إذن الوصول للإشعارات من الزر بالأسفل.", 14f))
        } else {
            for ((pkg, appLabel) in apps) {
                val check = CheckBox(this).apply {
                    text = appLabel
                    tag = pkg
                    isChecked = selected.contains(pkg)
                    textSize = 16f
                    setTextColor(0xFFFFFFFF.toInt())
                    setPadding(4, 8, 4, 8)
                }
                checks.add(pkg to check)
                list.addView(check)
            }
        }

        val save = Button(this).apply { text = "حفظ التطبيقات المختارة"; isEnabled = apps.isNotEmpty() }
        root.addView(save)
        val permission = Button(this).apply { text = "تفعيل إذن الوصول للإشعارات" }
        root.addView(permission)
        root.addView(label("بعد تفعيل الإذن، تقدر ترجع هنا وتغير اختيارك أو توقف الميزة في أي وقت.", 13f))

        save.setOnClickListener {
            val chosen = checks.filter { it.second.isChecked }.map { it.first }.toSet()
            prefs.edit().putStringSet("selected_packages", chosen).putBoolean("enabled", chosen.isNotEmpty()).apply()
            Toast.makeText(this, "تم حفظ " + chosen.size + " تطبيق", Toast.LENGTH_LONG).show()
        }
        permission.setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            } catch (_: Exception) {
                Toast.makeText(this, "افتح إعدادات الإشعارات وفعّل وصول MANW", Toast.LENGTH_LONG).show()
            }
        }
        } catch (e: Exception) {
            val fallback = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(28, 32, 28, 32)
                setBackgroundColor(0xFF10111A.toInt())
            }
            fallback.addView(TextView(this).apply {
                text = "تعذر فتح إعدادات إشعارات التطبيقات. جرّب إعادة المحاولة أو افتح إعدادات إذن الإشعارات مباشرة.\n\nالتفاصيل: ${e.localizedMessage ?: e.javaClass.simpleName}"
                textSize = 16f
                setTextColor(0xFFFFFFFF.toInt())
            })
            fallback.addView(Button(this).apply {
                text = "فتح إعدادات الوصول للإشعارات"
                setOnClickListener {
                    try { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }
                    catch (_: Exception) { Toast.makeText(this@NotificationSettingsActivity, "افتح إعدادات الإشعارات يدويًا", Toast.LENGTH_LONG).show() }
                }
            })
            setContentView(fallback)
        }
    }
}
