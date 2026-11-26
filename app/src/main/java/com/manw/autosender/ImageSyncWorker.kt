package com.manw.autosender

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okio.BufferedSink
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class ImageSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object {
        const val WORK_NAME = "manw_new_image_sync"
        const val KEY_IMAGE_ID = "manual_image_id"
        private val client = OkHttpClient.Builder().callTimeout(90, TimeUnit.SECONDS).build()

        fun testBot(token: String, chatId: String): String = try {
            val request = Request.Builder()
                .url("https://api.telegram.org/bot$token/sendChatAction")
                .post(okhttp3.FormBody.Builder().add("chat_id", chatId).add("action", "upload_photo").build())
                .build()
            client.newCall(request).execute().use { response ->
                val json = JSONObject(response.body?.string().orEmpty())
                if (response.isSuccessful && json.optBoolean("ok")) "الاتصال ناجح وChat ID صحيح"
                else "تيليجرام رفض البيانات: " + json.optString("description", "راجع التوكن وChat ID وافتح البوت واضغط Start")
            }
        } catch (_: Exception) { "تعذر الاتصال. تأكد من الإنترنت وصحة التوكن" }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = SecurePrefs.get(applicationContext)
        if (!prefs.getBoolean("sync_enabled", false)) return@withContext Result.success()
        val token = prefs.getString("bot_token", "") ?: ""
        val chatId = prefs.getString("chat_id", "") ?: ""
        if (token.isBlank() || chatId.isBlank()) return@withContext Result.failure()

        val manualId = inputData.getLong(KEY_IMAGE_ID, -1L)
        val since = (prefs.getLong("scan_from", System.currentTimeMillis() / 1000L) - 120L).coerceAtLeast(0L)
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED)
        val selection = if (manualId >= 0L) "${MediaStore.Images.Media._ID} = ?" else "${MediaStore.Images.Media.DATE_ADDED} >= ?"
        val selectionArg = if (manualId >= 0L) manualId.toString() else since.toString()
        val sent = prefs.getStringSet("sent_ids", emptySet())?.toMutableSet() ?: mutableSetOf()
        var checked = 0
        var delivered = 0
        try {
            val cursor = applicationContext.contentResolver.query(
                collection, projection, selection, arrayOf(selectionArg),
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            ) ?: throw IOException("تعذر قراءة الصور من المعرض")
            cursor.use {
                val idCol = it.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                while (it.moveToNext()) {
                    checked++
                    val id = it.getLong(idCol)
                    if (manualId < 0L && id.toString() in sent) continue
                    val uri = ContentUris.withAppendedId(collection, id)
                    val result = sendImage(uri, token, chatId)
                    if (!result.first) {
                        prefs.edit().putString("last_error", result.second).putString("last_result", "فشل إرسال الصورة رقم $id").apply()
                        return@withContext Result.retry()
                    }
                    sent.add(id.toString())
                    delivered++
                    prefs.edit().putStringSet("sent_ids", sent.toSet())
                        .putString("last_result", "تأكيد تيليجرام: تم استلام الصورة بنجاح. message_id=${result.second}")
                        .remove("last_error").apply()
                }
            }
            val message = when {
                manualId >= 0L && delivered > 0 -> "تم إرسال الصورة الآن وتأكيد استلامها من تيليجرام."
                manualId >= 0L -> "لم يتم العثور على الصورة المطلوبة في المعرض."
                checked == 0 -> "الفحص اكتمل، لكن لم يعثر على صور جديدة ضمن صلاحيات التطبيق."
                delivered == 0 -> "الفحص اكتمل؛ الصور التي عثر عليها سبق إرسالها."
                else -> "تم إرسال $delivered صورة وتأكيد استلامها من تيليجرام."
            }
            prefs.edit().putString("last_result", message).remove("last_error").apply()
            Result.success()
        } catch (e: SecurityException) {
            prefs.edit().putString("last_error", "إذن الصور غير متاح أو الوصول جزئي. اسمح بالوصول لكل الصور من إعدادات التطبيق.").apply()
            Result.failure()
        } catch (e: Exception) {
            prefs.edit().putString("last_error", "خطأ أثناء الفحص/الإرسال: " + (e.message ?: "غير معروف")).apply()
            Result.retry()
        }
    }

    private fun sendImage(uri: android.net.Uri, token: String, chatId: String): Pair<Boolean, String> {
        val resolver = applicationContext.contentResolver
        val mime = resolver.getType(uri) ?: "image/jpeg"
        val body = object : RequestBody() {
            override fun contentType() = mime.toMediaType()
            override fun writeTo(sink: BufferedSink) {
                resolver.openInputStream(uri)?.use { input -> input.copyTo(sink.outputStream()) }
                    ?: throw IOException("تعذر فتح ملف الصورة")
            }
        }
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", chatId)
            .addFormDataPart("photo", "image.jpg", body)
            .build()
        val request = Request.Builder().url("https://api.telegram.org/bot$token/sendPhoto").post(multipart).build()
        return try {
            client.newCall(request).execute().use { response ->
                val json = JSONObject(response.body?.string().orEmpty())
                val ok = response.isSuccessful && json.optBoolean("ok")
                val messageId = json.optJSONObject("result")?.optLong("message_id", -1L) ?: -1L
                Pair(ok, if (ok) messageId.toString() else json.optString("description", "HTTP ${response.code}"))
            }
        } catch (e: Exception) {
            Pair(false, e.message ?: "خطأ اتصال")
        }
    }
}
