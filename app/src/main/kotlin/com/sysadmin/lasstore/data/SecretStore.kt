package com.sysadmin.lasstore.data

import android.content.Context
import android.content.SharedPreferences
import com.google.crypto.tink.Aead
import com.google.crypto.tink.KeyTemplates
import com.google.crypto.tink.aead.AeadConfig
import com.google.crypto.tink.integration.android.AndroidKeysetManager
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Tink-backed store for GitHub PATs and per-package APK signing-cert pins.
 *
 * Active storage is an AEAD-encrypted JSON file under app-private storage.
 * A plaintext fallback is retained only for devices where Android Keystore
 * setup fails, and is migrated forward when Tink becomes available again.
 */
class SecretStore(context: Context) : SettingsSecretStore {
    @Volatile var encrypted: Boolean = true
        private set

    private val backend: SecretBackend = runCatching {
        TinkFileSecretBackend(context.applicationContext).also { tink ->
            val plain = PlainPreferencesSecretBackend(context.applicationContext)
            val fallback = plain.read()
            if (!fallback.isEmpty) {
                val encrypted = tink.read()
                val merged = encrypted.mergeForMigration(fallback)
                if (merged != encrypted) {
                    tink.write(merged)
                }
                check(tink.read() == merged) { "Encrypted secret migration did not verify" }
                plain.write(SecretSnapshot())
            }
        }
    }.getOrElse {
        encrypted = false
        PlainPreferencesSecretBackend(context.applicationContext)
    }

    override fun getPat(): String = backend.read().globalPat
    override fun setPat(pat: String) = backend.update { it.withGlobalPat(pat) }

    override fun getPat(sourceKey: String): String =
        backend.read().sourcePats[sourceKey] ?: getPat()

    override fun getSourcePat(sourceKey: String): String? = backend.read().sourcePats[sourceKey]

    override fun setPat(sourceKey: String, pat: String) =
        backend.update { it.withSourcePat(sourceKey, pat) }

    override fun replaceSourcePats(sourcePats: Map<String, String>, activeSourceKeys: Set<String>) =
        backend.update { it.replaceSourcePats(sourcePats, activeSourceKeys) }

    override fun beginSourcePatTransaction(
        id: String,
        targetSettingsFingerprint: String,
        targetSourcePats: Map<String, String>,
    ) = backend.update {
        it.beginSourcePatTransaction(
            id = id,
            targetSettingsFingerprint = targetSettingsFingerprint,
            targetSourcePats = targetSourcePats,
        )
    }

    override fun applySourcePatTransaction(id: String) = backend.update { snapshot ->
        snapshot.applySourcePatTransaction(id)
    }

    /** Complete a transaction if it is still pending; safe to retry after a process restart. */
    override fun completeSourcePatTransaction(id: String) = backend.update { snapshot ->
        val pending = snapshot.pendingSourcePatTransaction
        when {
            pending == null -> snapshot
            pending.id == id -> snapshot.completeSourcePatTransaction(id)
            else -> error("A different source PAT transaction is pending")
        }
    }

    /** Roll back a transaction if it is still pending; safe to retry after a process restart. */
    override fun rollbackSourcePatTransaction(id: String) = backend.update { snapshot ->
        val pending = snapshot.pendingSourcePatTransaction
        when {
            pending == null -> snapshot
            pending.id == id -> snapshot.rollbackSourcePatTransaction(id)
            else -> error("A different source PAT transaction is pending")
        }
    }

    /** Reconcile a pending transaction when its settings journal was lost or already finalized. */
    override fun reconcilePendingSourcePatTransaction(settingsFingerprint: String) =
        backend.update { snapshot ->
            val pending = snapshot.pendingSourcePatTransaction ?: return@update snapshot
            if (pending.targetSettingsFingerprint == settingsFingerprint) {
                snapshot.completeSourcePatTransaction(pending.id)
            } else {
                snapshot.rollbackSourcePatTransaction(pending.id)
            }
        }

    override fun sourcePats(): Map<String, String> = backend.read().sourcePats

    fun getPin(packageName: String): String? = backend.read().pins[packageName]
    fun setPin(packageName: String, sha256Hex: String) {
        require(packageName.isNotBlank()) { "A package name is required for signer pin enrollment" }
        val normalized = requireNotNull(normalizeSigningCertificateSha256(sha256Hex)) {
            "Signer pin must be a complete SHA-256 certificate fingerprint"
        }
        backend.update { it.withPin(packageName, normalized) }
    }

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
            pendingSourcePatTransaction = prefs
                .getString(KEY_PENDING_SOURCE_PAT_TRANSACTION, null)
                ?.let { raw ->
                    runCatching {
                        secretJson().decodeFromString(
                            PendingSourcePatTransaction.serializer(),
                            raw,
                        )
                    }.getOrNull()
                },
        )
    }

    @Synchronized
    override fun write(snapshot: SecretSnapshot) {
        check(prefs.edit().clear().apply {
            if (snapshot.globalPat.isNotBlank()) putString(KEY_PAT, snapshot.globalPat)
            snapshot.sourcePats.forEach { (key, value) -> putString("$SOURCE_PAT_PREFIX$key", value) }
            snapshot.pins.forEach { (packageName, sha256) -> putString("$PIN_PREFIX$packageName", sha256) }
            snapshot.pendingSourcePatTransaction?.let { transaction ->
                putString(
                    KEY_PENDING_SOURCE_PAT_TRANSACTION,
                    secretJson().encodeToString(
                        PendingSourcePatTransaction.serializer(),
                        transaction,
                    ),
                )
            }
        }.commit()) { "Could not persist plaintext secret fallback" }
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

private const val KEY_PAT = "github_pat"
private const val SOURCE_PAT_PREFIX = "github_pat_source_"
private const val PIN_PREFIX = "pin_"
private const val KEY_PENDING_SOURCE_PAT_TRANSACTION = "pending_source_pat_transaction"
