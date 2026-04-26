package com.sysadmin.lasstore.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Tink-backed store for GitHub PATs and per-package APK signing-cert pins.
 *
 * Active storage is an AEAD-encrypted JSON file under app-private storage. A
 * legacy EncryptedSharedPreferences migration bridge is retained so existing
 * installs can carry PATs and signature pins forward without user action.
 */
class SecretStore(context: Context) {
    @Volatile var encrypted: Boolean = true
        private set

    private val backend: SecretBackend = runCatching {
        TinkFileSecretBackend(context.applicationContext).also { tink ->
            val plain = PlainPreferencesSecretBackend(context.applicationContext)
            val migrated = LegacyEncryptedPreferences.read(context.applicationContext)
                .orEmptySecret()
                .mergedWithFallback(plain.read())
            if (!migrated.isEmpty) {
                if (tink.read().isEmpty) {
                    tink.write(migrated)
                }
                LegacyEncryptedPreferences.clear(context.applicationContext)
                plain.write(SecretSnapshot())
            }
        }
    }.getOrElse {
        encrypted = false
        PlainPreferencesSecretBackend(context.applicationContext)
    }

    fun getPat(): String = backend.read().globalPat
    fun setPat(pat: String) = backend.update { it.withGlobalPat(pat) }

    fun getPat(sourceKey: String): String =
        backend.read().sourcePats[sourceKey] ?: getPat()

    fun setPat(sourceKey: String, pat: String) =
        backend.update { it.withSourcePat(sourceKey, pat) }

    fun getPin(packageName: String): String? = backend.read().pins[packageName]
    fun setPin(packageName: String, sha256Hex: String) =
        backend.update { it.withPin(packageName, sha256Hex) }

    fun clearPin(packageName: String) =
        backend.update { it.withoutPin(packageName) }
}

private interface SecretBackend {
    fun read(): SecretSnapshot
    fun write(snapshot: SecretSnapshot)
    fun update(transform: (SecretSnapshot) -> SecretSnapshot)
}

private abstract class SynchronizedSecretBackend : SecretBackend {
    @Synchronized
    override fun update(transform: (SecretSnapshot) -> SecretSnapshot) {
        write(transform(read()))
    }
}

private class TinkFileSecretBackend(context: Context) : SynchronizedSecretBackend() {
    private val json = secretJson()
    private val file = File(context.filesDir, "secrets/secrets.v1.tinkaead")
    private val aead: Aead

    init {
        AeadConfig.register()
        aead = AndroidKeysetManager.Builder()
            .withSharedPref(context, KEYSET_NAME, KEYSET_PREFS_NAME)
            .withKeyTemplate(KeyTemplates.get("AES256_GCM"))
            .withMasterKeyUri(MASTER_KEY_URI)
            .build()
            .keysetHandle
            .getPrimitive(Aead::class.java)
    }

    @Synchronized
    override fun read(): SecretSnapshot {
        if (!file.exists()) return SecretSnapshot()
        return runCatching {
            val cleartext = aead.decrypt(file.readBytes(), ASSOCIATED_DATA)
            json.decodeFromString(SecretSnapshot.serializer(), cleartext.decodeToString())
        }.getOrDefault(SecretSnapshot())
    }

    @Synchronized
    override fun write(snapshot: SecretSnapshot) {
        file.parentFile?.mkdirs()
        val cleartext = json.encodeToString(SecretSnapshot.serializer(), snapshot)
            .encodeToByteArray()
        val ciphertext = aead.encrypt(cleartext, ASSOCIATED_DATA)
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeBytes(ciphertext)
        if (!tmp.renameTo(file)) {
            tmp.copyTo(file, overwrite = true)
            tmp.delete()
        }
    }

    private companion object {
        private const val KEYSET_NAME = "lasstore_secret_keyset"
        private const val KEYSET_PREFS_NAME = "lasstore_tink_keysets"
        private const val MASTER_KEY_URI = "android-keystore://lasstore_secret_master_key"
        private val ASSOCIATED_DATA = "LocalAndroidStore SecretStore v1".encodeToByteArray()
    }
}

private class PlainPreferencesSecretBackend(context: Context) : SynchronizedSecretBackend() {
    private val prefs: SharedPreferences = context.getSharedPreferences("secrets_plain", Context.MODE_PRIVATE)

    @Synchronized
    override fun read(): SecretSnapshot {
        val all = prefs.all
        return SecretSnapshot(
            globalPat = prefs.getString(KEY_PAT, "").orEmpty(),
            sourcePats = all.stringMapWithPrefix(SOURCE_PAT_PREFIX),
            pins = all.stringMapWithPrefix(PIN_PREFIX),
        )
    }

    @Synchronized
    override fun write(snapshot: SecretSnapshot) {
        prefs.edit().clear().apply {
            if (snapshot.globalPat.isNotBlank()) putString(KEY_PAT, snapshot.globalPat)
            snapshot.sourcePats.forEach { (key, value) -> putString("$SOURCE_PAT_PREFIX$key", value) }
            snapshot.pins.forEach { (packageName, sha256) -> putString("$PIN_PREFIX$packageName", sha256) }
        }.apply()
    }
}

private object LegacyEncryptedPreferences {
    fun read(context: Context): SecretSnapshot? {
        val prefs = open(context).getOrNull() ?: return null
        val all = prefs.all
        return SecretSnapshot(
            globalPat = prefs.getString(KEY_PAT, "").orEmpty(),
            sourcePats = all.stringMapWithPrefix(SOURCE_PAT_PREFIX),
            pins = all.stringMapWithPrefix(PIN_PREFIX),
        )
    }

    fun clear(context: Context) {
        open(context).getOrNull()?.edit()?.clear()?.apply()
    }

    private fun open(context: Context): Result<SharedPreferences> = runCatching {
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
    }
}

private fun Map<String, *>.stringMapWithPrefix(prefix: String): Map<String, String> =
    entries.mapNotNull { (key, value) ->
        val secret = value as? String ?: return@mapNotNull null
        val suffix = key.removePrefix(prefix)
        if (suffix == key || secret.isBlank()) null else suffix to secret
    }.toMap()

private fun secretJson(): Json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

private fun SecretSnapshot?.orEmptySecret(): SecretSnapshot = this ?: SecretSnapshot()

private const val KEY_PAT = "github_pat"
private const val SOURCE_PAT_PREFIX = "github_pat_source_"
private const val PIN_PREFIX = "pin_"
