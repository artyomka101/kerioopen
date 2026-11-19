package com.openkerio.vpn.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class ConnectionConfig(
    val displayName: String = "",
    val server: String = "",
    val username: String = "",
    val password: String = "",
    val savePassword: Boolean = true,
    val persistent: Boolean = false,
    val trustedFingerprintSha256: String = "",
)

data class SavedProfile(
    val id: String,
    val displayName: String,
    val server: String,
    val username: String,
    val password: String,
    val savePassword: Boolean,
    val persistent: Boolean,
    val trustedFingerprintSha256: String,
    val lastUsed: Long,
) {
    fun label(): String {
        if (displayName.isNotBlank()) return displayName
        return if (username.isBlank()) server else "$username@$server"
    }

    fun toConfig(): ConnectionConfig = ConnectionConfig(
        displayName = displayName,
        server = server,
        username = username,
        password = password,
        savePassword = savePassword,
        persistent = persistent,
        trustedFingerprintSha256 = trustedFingerprintSha256,
    )
}

/**
 * Preferences with password encrypted via Android Keystore AES-GCM.
 * Also stores a list of previously used connection profiles.
 */
class ConnectionPrefs(context: Context) {
    private val appContext = context.applicationContext ?: context
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun load(): ConnectionConfig {
        val savePassword = prefs.getBoolean(KEY_SAVE_PASSWORD, true)
        return ConnectionConfig(
            displayName = prefs.getString(KEY_NAME, "") ?: "",
            server = prefs.getString(KEY_SERVER, "") ?: "",
            username = prefs.getString(KEY_USERNAME, "") ?: "",
            password = if (savePassword) decryptPassword(prefs.getString(KEY_PASSWORD, "") ?: "") else "",
            savePassword = savePassword,
            persistent = prefs.getBoolean(KEY_PERSISTENT, false),
            trustedFingerprintSha256 = prefs.getString(KEY_FINGERPRINT, "") ?: "",
        )
    }

    fun save(config: ConnectionConfig) {
        val editor = prefs.edit()
            .putString(KEY_NAME, config.displayName.trim())
            .putString(KEY_SERVER, config.server.trim())
            .putString(KEY_USERNAME, config.username.trim())
            .putBoolean(KEY_SAVE_PASSWORD, config.savePassword)
            .putBoolean(KEY_PERSISTENT, config.persistent)
            .putString(KEY_FINGERPRINT, config.trustedFingerprintSha256.trim().lowercase())
        if (config.savePassword) {
            val encrypted = encryptPassword(config.password)
            if (encrypted.isNotEmpty() || config.password.isEmpty()) {
                editor.putString(KEY_PASSWORD, encrypted)
            }
        } else {
            editor.remove(KEY_PASSWORD)
        }
        editor.commit()
    }

    fun saveTrustedFingerprint(fingerprintSha256: String) {
        prefs.edit()
            .putString(KEY_FINGERPRINT, fingerprintSha256.trim().lowercase())
            .commit()
    }

    fun listProfiles(): List<SavedProfile> {
        val raw = prefs.getString(KEY_PROFILES, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                val savePassword = o.optBoolean("savePassword", true)
                val enc = o.optString("password", "")
                SavedProfile(
                    id = o.optString("id"),
                    displayName = o.optString("name", ""),
                    server = o.optString("server"),
                    username = o.optString("username"),
                    password = if (savePassword) decryptPassword(enc) else "",
                    savePassword = savePassword,
                    persistent = o.optBoolean("persistent", false),
                    trustedFingerprintSha256 = o.optString("fingerprint", ""),
                    lastUsed = o.optLong("lastUsed", 0L),
                ).takeIf { it.id.isNotBlank() && it.server.isNotBlank() }
            }.sortedByDescending { it.lastUsed }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun upsertProfile(config: ConnectionConfig) {
        if (config.server.isBlank() || config.username.isBlank()) return
        val id = profileId(config.server, config.username)
        val existing = listProfiles().filterNot { it.id == id }.toMutableList()
        existing.add(
            0,
            SavedProfile(
                id = id,
                displayName = config.displayName.trim(),
                server = config.server.trim(),
                username = config.username.trim(),
                password = if (config.savePassword) config.password else "",
                savePassword = config.savePassword,
                persistent = config.persistent,
                trustedFingerprintSha256 = config.trustedFingerprintSha256.trim().lowercase(),
                lastUsed = System.currentTimeMillis(),
            ),
        )
        persistProfiles(existing.take(20))
    }

    fun deleteProfile(id: String) {
        persistProfiles(listProfiles().filterNot { it.id == id })
    }

    private fun persistProfiles(profiles: List<SavedProfile>) {
        val arr = JSONArray()
        profiles.forEach { p ->
            arr.put(
                JSONObject()
                    .put("id", p.id)
                    .put("name", p.displayName)
                    .put("server", p.server)
                    .put("username", p.username)
                    .put("password", if (p.savePassword) encryptPassword(p.password) else "")
                    .put("savePassword", p.savePassword)
                    .put("persistent", p.persistent)
                    .put("fingerprint", p.trustedFingerprintSha256)
                    .put("lastUsed", p.lastUsed),
            )
        }
        prefs.edit().putString(KEY_PROFILES, arr.toString()).commit()
    }

    private fun profileId(server: String, username: String): String =
        "${server.trim().lowercase()}|${username.trim().lowercase()}"

    private fun encryptPassword(password: String): String {
        if (password.isEmpty()) return ""
        return try {
            val key = getOrCreateKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val encrypted = cipher.doFinal(password.toByteArray(Charsets.UTF_8))
            Base64.encodeToString(iv + encrypted, Base64.NO_WRAP)
        } catch (_: Exception) {
            ""
        }
    }

    private fun decryptPassword(payload: String): String {
        if (payload.isEmpty()) return ""
        return try {
            val raw = Base64.decode(payload, Base64.NO_WRAP)
            if (raw.size <= 12) return ""
            val iv = raw.copyOfRange(0, 12)
            val encrypted = raw.copyOfRange(12, raw.size)
            val key = getOrCreateKey()
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existing = keyStore.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generator.generateKey()
    }

    companion object {
        private const val PREFS = "openkerio_prefs"
        private const val KEY_NAME = "display_name"
        private const val KEY_SERVER = "server"
        private const val KEY_USERNAME = "username"
        private const val KEY_PASSWORD = "password"
        private const val KEY_SAVE_PASSWORD = "save_password"
        private const val KEY_PERSISTENT = "persistent"
        private const val KEY_FINGERPRINT = "trusted_fingerprint_sha256"
        private const val KEY_PROFILES = "profiles_json"
        private const val ANDROID_KEYSTORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "openkerio_password_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
