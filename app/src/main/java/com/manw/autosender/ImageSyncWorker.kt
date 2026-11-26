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
import java.io.IOException
import java.util.concurrent.TimeUnit

class ImageSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    companion object {
        const val WORK_NAME = "manw_new_image_sync"
        private val client = OkHttpClient.Builder().callTimeout(90, TimeUnit.SECONDS).build()

        fun testBot(token: String, chatId: String): String = try {
            val request = Request.Builder().url("https://api.telegram.org/bot$token/getMe").get().build()
            client.newCall(request).execute().use { response ->
                if (response.isSuccessful) "الاتصال بالبوت ناجح" else "فشل الاتصال: راجع التوكن"
            }
        } catch (_: Exception) { "تعذر الاتصال بالإنترنت" }
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val prefs = SecurePrefs.get(applicationContext)
        if (!prefs.getBoolean("sync_enabled", false)) return@withContext Result.success()
        val token = prefs.getString("bot_token", "") ?: ""
        val chatId = prefs.getString("chat_id", "") ?: ""
        if (token.isBlank() || chatId.isBlank()) return@withContext Result.failure()

        val since = prefs.getLong("scan_from", System.currentTimeMillis() / 1000L)
        val collection = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(MediaStore.Images.Media._ID, MediaStore.Images.Media.DATE_ADDED)
        val selection = "${MediaStore.Images.Media.DATE_ADDED} >= ?"
        val sent = prefs.getStringSet("sent_ids", emptySet())?.toMutableSet() ?: mutableSetOf()
        var newest = since
        try {
            applicationContext.contentResolver.query(
                collection, projection, selection, arrayOf(since.toString()),
                "${MediaStore.Images.Media.DATE_ADDED} ASC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val dateCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idCol)
                    val date = cursor.getLong(dateCol)
                    newest = maxOf(newest, date)
                    if (id.toString() in sent) continue
                    val uri = ContentUris.withAppendedId(collection, id)
                    if (!sendImage(uri, token, chatId)) return@withContext Result.retry()
                    sent.add(id.toString())
                }
            }
            prefs.edit().putLong("scan_from", maxOf(newest, since))
                .putStringSet("sent_ids", sent).apply()
            Result.success()
        } catch (_: SecurityException) { Result.failure() }
        catch (_: Exception) { Result.retry() }
    }

    private fun sendImage(uri: android.net.Uri, token: String, chatId: String): Boolean {
        val resolver = applicationContext.contentResolver
        val mime = resolver.getType(uri) ?: "image/jpeg"
        val body = object : RequestBody() {
            override fun contentType() = mime.toMediaType()
            override fun writeTo(sink: BufferedSink) {
                resolver.openInputStream(uri)?.use { input -> input.copyTo(sink.outputStream()) }
                    ?: throw IOException("Cannot open image")
            }
        }
        val multipart = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("chat_id", chatId)
            .addFormDataPart("photo", "image.jpg", body)
            .build()
        val request = Request.Builder()
            .url("https://api.telegram.org/bot$token/sendPhoto")
            .post(multipart).build()
        return client.newCall(request).execute().use { it.isSuccessful }
    }
}
