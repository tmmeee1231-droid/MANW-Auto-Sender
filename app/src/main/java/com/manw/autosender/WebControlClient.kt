package com.manw.autosender

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

object WebControlClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    fun normalizeBase(raw: String): String {
        val value = raw.trim().trimEnd('/')
        require(value.startsWith("https://")) { "استخدم رابط الموقع الآمن الذي يبدأ بـ https://" }
        return value
    }

    fun pair(base: String, code: String): String {
        val json = JSONObject().put("code", code.trim().uppercase()).toString()
            .toRequestBody("application/json".toMediaType())
        val request = Request.Builder().url(normalizeBase(base) + "/api/device/pair")
            .post(json).build()
        client.newCall(request).execute().use { response ->
            val body = JSONObject(response.body?.string().orEmpty())
            if (!response.isSuccessful) throw IllegalStateException(body.optString("error", "فشل الاقتران"))
            return body.getString("deviceToken")
        }
    }

    private fun request(base: String, token: String, path: String, body: String? = null): Request {
        val builder = Request.Builder().url(normalizeBase(base) + path)
            .header("X-MANW-Device", token)
        if (body == null) builder.get() else builder.post(body.toRequestBody("application/json".toMediaType()))
        return builder.build()
    }

    fun heartbeat(base: String, token: String, battery: Int, cameraActive: Boolean) {
        val body = JSONObject().put("battery", battery).put("cameraActive", cameraActive).toString()
        client.newCall(request(base, token, "/api/device/heartbeat", body)).execute().close()
    }

    fun pollCommands(base: String, token: String): List<String> {
        client.newCall(request(base, token, "/api/device/commands")).execute().use { response ->
            if (!response.isSuccessful) return emptyList()
            val json = JSONObject(response.body?.string().orEmpty())
            val commands = json.optJSONArray("commands") ?: return emptyList()
            return (0 until commands.length()).mapNotNull { i ->
                commands.optJSONObject(i)?.optString("command")?.takeIf { it.isNotBlank() }
            }
        }
    }

    fun upload(base: String, token: String, file: File, displayName: String = file.name): Boolean {
        if (!file.exists() || file.length() <= 0L) return false
        val mime = "image/jpeg"
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", displayName, file.asRequestBody(mime.toMediaType()))
            .build()
        val request = Request.Builder().url(normalizeBase(base) + "/api/device/media")
            .header("X-MANW-Device", token).post(body).build()
        client.newCall(request).execute().use { return it.isSuccessful }
    }
}
