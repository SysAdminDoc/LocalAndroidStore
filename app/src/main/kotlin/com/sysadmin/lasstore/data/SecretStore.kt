package com.sysadmin.lasstore.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * EncryptedSharedPreferences-backed store for the GitHub PAT and the per-package
 * APK signing-cert SHA-256 fingerprints (signature pin).
 *
 * Falls back to a regular plaintext SharedPreferences only if the AndroidKeystore-backed
 * MasterKey cannot be created on the device (extremely rare). On that failure we mark
 * the store as insecure so the UI can warn the user.
 */
class SecretStore(context: Context) {
    @Volatile var encrypted: Boolean = true
        private set

    private val prefs: SharedPreferences = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "secrets",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    } catch (t: Throwable) {
        encrypted = false
        context.getSharedPreferences("secrets_plain", Context.MODE_PRIVATE)
    }

    fun getPat(): String = prefs.getString(KEY_PAT, "") ?: ""
    fun setPat(pat: String) {
        prefs.edit().putString(KEY_PAT, pat).apply()
    }

    fun getPat(sourceKey: String): String =
        prefs.getString(sourcePatKey(sourceKey), null) ?: getPat()

    fun setPat(sourceKey: String, pat: String) {
        val key = sourcePatKey(sourceKey)
        prefs.edit().apply {
            if (pat.isBlank()) {
                remove(key)
            } else {
                putString(key, pat.trim())
            }
        }.apply()
    }

    fun getPin(packageName: String): String? = prefs.getString("pin_$packageName", null)
    fun setPin(packageName: String, sha256Hex: String) {
        prefs.edit().putString("pin_$packageName", sha256Hex).apply()
    }
    fun clearPin(packageName: String) {
        prefs.edit().remove("pin_$packageName").apply()
    }

    private fun sourcePatKey(sourceKey: String): String = "github_pat_source_$sourceKey"

    companion object { private const val KEY_PAT = "github_pat" }
}
