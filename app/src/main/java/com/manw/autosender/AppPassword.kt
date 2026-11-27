package com.manw.autosender

import android.content.Context
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

object AppPassword {
    private const val PREF_HASH = "app_password_hash"
    private const val PREF_SALT = "app_password_salt"
    private const val PREF_UNLOCKED_AT = "app_password_unlocked_at"
    private const val SESSION_MS = 3L * 24L * 60L * 60L * 1000L
    private const val ITERATIONS = 150_000
    private const val KEY_BITS = 256

    fun isConfigured(context: Context): Boolean {
        val p = SecurePrefs.get(context)
        return !p.getString(PREF_HASH, null).isNullOrBlank() &&
            !p.getString(PREF_SALT, null).isNullOrBlank()
    }

    fun isSessionValid(context: Context): Boolean {
        if (!isConfigured(context)) return false
        val unlockedAt = SecurePrefs.get(context).getLong(PREF_UNLOCKED_AT, 0L)
        val now = System.currentTimeMillis()
        return unlockedAt > 0L && now >= unlockedAt && now - unlockedAt < SESSION_MS
    }

    fun markUnlocked(context: Context) {
        SecurePrefs.get(context).edit()
            .putLong(PREF_UNLOCKED_AT, System.currentTimeMillis())
            .apply()
    }

    fun create(context: Context, password: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        val hash = derive(password, salt)
        SecurePrefs.get(context).edit()
            .putString(PREF_SALT, Base64.encodeToString(salt, Base64.NO_WRAP))
            .putString(PREF_HASH, Base64.encodeToString(hash, Base64.NO_WRAP))
            .putLong(PREF_UNLOCKED_AT, 0L)
            .apply()
    }

    fun verify(context: Context, password: String): Boolean {
        val p = SecurePrefs.get(context)
        val saltText = p.getString(PREF_SALT, null) ?: return false
        val hashText = p.getString(PREF_HASH, null) ?: return false
        return try {
            val salt = Base64.decode(saltText, Base64.NO_WRAP)
            val expected = Base64.decode(hashText, Base64.NO_WRAP)
            MessageDigest.isEqual(expected, derive(password, salt))
        } catch (_: Exception) {
            false
        }
    }

    private fun derive(password: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(password.toCharArray(), salt, ITERATIONS, KEY_BITS)
        return try {
            SecretKeyFactory.getInstance("PBKDF2WithHmacSHA1").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }
}
