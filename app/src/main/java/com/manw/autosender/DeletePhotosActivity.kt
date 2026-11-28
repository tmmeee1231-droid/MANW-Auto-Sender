package com.manw.autosender

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat

class DeletePhotosActivity : AppCompatActivity() {
    private lateinit var status: TextView
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) requestAndroidDeleteConfirmation() else status.text = "لم تمنح إذن الصور؛ لم يتم حذف أي ملف."
    }
    private val deleteResult = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
        status.text = if (it.resultCode == RESULT_OK) "انتهى تأكيد أندرويد. راجع المعرض لمعرفة العناصر التي حُذفت." else "تم إلغاء الحذف؛ لم يتم حذف الصور."
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28, 32, 28, 24) }
        status = TextView(this).apply {
            textSize = 18f
            text = "طلب حذف الصور من الهاتف. لن يُحذف شيء قبل موافقتك على نافذة تأكيد أندرويد. سيطبق الحذف على الصور التي يسمح النظام للتطبيق بالوصول إليها."
        }
        val proceed = Button(this).apply { text = "متابعة إلى تأكيد أندرويد" }
        val cancel = Button(this).apply { text = "إلغاء" }
        root.addView(status); root.addView(proceed); root.addView(cancel); setContentView(root)
        proceed.setOnClickListener {
            if (Build.VERSION.SDK_INT < 30) {
                status.text = "الحذف الجماعي المؤكد من النظام يحتاج Android 11 أو أحدث."
            } else {
                val permissionName = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE
                if (ContextCompat.checkSelfPermission(this, permissionName) == PackageManager.PERMISSION_GRANTED) requestAndroidDeleteConfirmation()
                else permission.launch(permissionName)
            }
        }
        cancel.setOnClickListener { finish() }
    }
    private fun requestAndroidDeleteConfirmation() {
        try {
            val uris = mutableListOf<Uri>()
            contentResolver.query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Images.Media._ID), null, null, null)?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    uris.add(Uri.withAppendedPath(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id.toString()))
                }
            }
            if (uris.isEmpty()) { status.text = "لم يتم العثور على صور يمكن للتطبيق الوصول إليها."; return }
            status.text = "تم العثور على ${uris.size} صورة. افتح نافذة أندرويد التالية وراجع العناصر قبل الموافقة."
            val request: PendingIntent = MediaStore.createDeleteRequest(contentResolver, uris)
            deleteResult.launch(IntentSenderRequest.Builder(request.intentSender).build())
        } catch (e: Exception) { status.text = "تعذر طلب تأكيد الحذف: ${e.message}" }
    }
}
