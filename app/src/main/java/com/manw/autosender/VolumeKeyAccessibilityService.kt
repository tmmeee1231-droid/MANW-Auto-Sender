package com.manw.autosender

import android.accessibilityservice.AccessibilityService
import android.view.KeyEvent
import android.widget.Toast
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager

class VolumeKeyAccessibilityService : AccessibilityService() {
    private var sendTriggeredForCurrentPress = false

    override fun onAccessibilityEvent(event: android.view.accessibility.AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val prefs = SecurePrefs.get(this)
        if (!prefs.getBoolean("manual_send_enabled", false)) return false
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN) return false

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount == 0) sendTriggeredForCurrentPress = false
                if (event.repeatCount >= 2 && !sendTriggeredForCurrentPress) {
                    sendTriggeredForCurrentPress = true
                    val request = OneTimeWorkRequestBuilder<ImageSyncWorker>()
                        .setInputData(Data.Builder().putBoolean(ImageSyncWorker.KEY_MANUAL_BATCH, true).build())
                        .build()
                    WorkManager.getInstance(this).enqueueUniqueWork(
                        "manw_volume_key_photo_send",
                        ExistingWorkPolicy.REPLACE,
                        request
                    )
                    Toast.makeText(this, "جاري إرسال الصور الجديدة إلى تيليجرام", Toast.LENGTH_SHORT).show()
                }
                return true
            }
            KeyEvent.ACTION_UP -> {
                sendTriggeredForCurrentPress = false
                return true
            }
        }
        return true
    }
}
